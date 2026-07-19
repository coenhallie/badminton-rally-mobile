package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Snapshot of the cloud pipeline's state for one video (from the videos row).
 * [progress] is normalized to 0f..1f (the DB stores it as a 0..100 percentage).
 */
data class ProcessingUpdate(val status: String, val progress: Float?, val error: String?) {
    // Phase 1 produces the rally clips; phase 2 analytics is desktop-only.
    val isSuccess: Boolean get() = status == "phase1_complete" || status == "completed"
    val isFailure: Boolean get() = status.startsWith("failed")
    val isTerminal: Boolean get() = isSuccess || isFailure
}

sealed interface UploadState {
    data class InProgress(val progress: Float) : UploadState
    data object Done : UploadState
    data class Failed(val message: String) : UploadState
}

internal fun toUploadState(progress: Float, isDone: Boolean): UploadState =
    if (isDone) UploadState.Done else UploadState.InProgress(progress)

/**
 * Emit an [UploadState] for each (progress, isDone) pair and complete ONLY after a
 * done state. supabase-kt's startOrResumeUploading() launches the transfer in its
 * own scope and returns immediately, so completion is observable solely through the
 * upload's state flow — reporting Done any earlier triggers processing on a file
 * that isn't in storage yet. Throws if the source ends without ever being done.
 */
internal fun awaitUploadStates(states: Flow<Pair<Float, Boolean>>): Flow<UploadState> = flow {
    states.first { (progress, isDone) ->
        emit(toUploadState(progress, isDone))
        isDone
    }
}

/**
 * Prefix Supabase errors with their HTTP status. Unparseable bodies (e.g. an
 * HTML page from the edge) otherwise surface as a bare "Unknown error", which
 * hides the one fact that identifies the culprit.
 */
internal fun <T> Result<T>.annotateHttpStatus(): Result<T> = fold(
    onSuccess = { this },
    onFailure = { e ->
        Result.failure(
            if (e is RestException) IllegalStateException("HTTP ${e.statusCode} — ${e.message}", e) else e,
        )
    },
)

interface VideosRepository {
    /** Insert the videos row. Call AFTER the upload succeeds (same order as web). */
    suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit>
    suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit>
    /** Invoke the process-video Edge Function (requires row + keypoints in place). */
    suspend fun startProcessing(videoId: String): Result<Unit>
    /** Poll the videos row until a terminal status, emitting every change of state. */
    fun observeProcessing(videoId: String, pollIntervalMs: Long = 5_000): Flow<ProcessingUpdate>
    /**
     * Resumable (TUS) upload to videos/{uid}/{videoId}.mp4. [channelProvider] must
     * return a channel positioned at the requested byte offset so interrupted
     * uploads resume instead of restarting. Terminates with [UploadState.Done]
     * or [UploadState.Failed].
     */
    fun uploadVideo(
        videoId: String,
        sizeBytes: Long,
        channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState>

    /**
     * Permanently delete an owned match: best-effort storage cleanup (clips,
     * thumbnails, original video) FIRST — the storage delete policies check the
     * DB rows — then the delete_match RPC removes all rows transactionally.
     * A storage failure never blocks the delete; orphaned files beat a match
     * the user can't remove.
     */
    suspend fun deleteMatch(videoId: String): Result<Unit>
}

class VideosRepositoryImpl(private val client: SupabaseClient) : VideosRepository {

    @Serializable
    private data class NewVideoRow(
        val id: String,
        @SerialName("owner_id")     val ownerId: String,
        val filename: String,
        val size: Long,
        @SerialName("storage_path") val storagePath: String,
        val status: String,
    )

    @Serializable
    private data class KeypointsPatch(
        @SerialName("manual_court_keypoints") val keypoints: CourtKeypoints,
    )

    @Serializable
    private data class ProcessVideoBody(@SerialName("video_id") val videoId: String)

    @Serializable
    private data class StatusRow(
        val status: String,
        val progress: Float? = null,
        val error: String? = null,
    )

    @Serializable
    private data class ClipPathsRow(
        @SerialName("clip_storage_path")      val clipPath: String,
        @SerialName("thumbnail_storage_path") val thumbnailPath: String? = null,
    )

    @Serializable
    private data class VideoPathRow(@SerialName("storage_path") val storagePath: String)

    @Serializable
    private data class DeleteMatchArgs(@SerialName("p_video_id") val videoId: String)

    override suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit> =
        runCatching {
            val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
            client.postgrest.from("videos").insert(
                NewVideoRow(
                    id = videoId,
                    ownerId = uid,
                    filename = filename,
                    size = sizeBytes,
                    storagePath = storagePath(uid, videoId),
                    status = "uploaded",
                )
            )
            Unit
        }.annotateHttpStatus()

    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> =
        runCatching {
            client.postgrest.from("videos")
                .update(KeypointsPatch(keypoints)) {
                    filter { eq("id", videoId) }
                }
            Unit
        }.annotateHttpStatus()

    override suspend fun startProcessing(videoId: String): Result<Unit> = runCatching {
        val response = client.functions(
            function = "process-video",
            body = ProcessVideoBody(videoId),
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
        )
        check(response.status.isSuccess()) { response.bodyAsText() }
    }.annotateHttpStatus()

    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> = flow {
        // Collectors run in an app scope where an uncaught throw is fatal, and
        // processing spans minutes — a poll error must never escape this flow.
        // Tolerate transient errors; only persistent ones become a terminal update.
        var consecutiveErrors = 0
        while (true) {
            val row = try {
                client.postgrest.from("videos")
                    .select(Columns.list("status", "progress", "error")) {
                        filter { eq("id", videoId) }
                    }
                    .decodeSingle<StatusRow>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++consecutiveErrors >= MAX_POLL_ERRORS) {
                    val message = if (e is RestException) "HTTP ${e.statusCode} — ${e.message}" else e.message
                    emit(
                        ProcessingUpdate(
                            status = "failed_connection",
                            progress = null,
                            error = message ?: "Lost connection while checking progress",
                        )
                    )
                    break
                }
                delay(pollIntervalMs)
                continue
            }
            consecutiveErrors = 0
            // DB progress is a 0..100 percentage; normalize to 0..1 for the UI.
            val update = ProcessingUpdate(row.status, row.progress?.let { (it / 100f).coerceIn(0f, 1f) }, row.error)
            emit(update)
            if (update.isTerminal) break
            delay(pollIntervalMs)
        }
    }

    override fun uploadVideo(
        videoId: String,
        sizeBytes: Long,
        channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState> = channelFlow {
        val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
        suspend fun createOrContinue() = client.storage.from("videos").resumable.createOrContinueUpload(
            channel = channelProvider,
            source = videoId,          // stable key so retries resume the TUS session
            size = sizeBytes,
            path = storagePath(uid, videoId),
        ) {
            // Re-analyze re-uploads to the same path; without upsert the TUS
            // create call 409s once a previous upload completed.
            upsert = true
            // Must be set explicitly: supabase-kt caches contentType.toString()
            // in the TUS cache entry and feeds it back through ContentType.parse()
            // when resuming an expired entry — unset it stores the literal "null"
            // and the resume throws "Bad Content-Type format: null".
            contentType = ContentType.Video.MP4
        }
        val upload = try {
            createOrContinue()
        } catch (e: BadContentTypeFormatException) {
            // Entry poisoned by an older build (contentType "null"). The library
            // drops the expired entry before throwing, so one retry starts clean.
            createOrContinue()
        }
        // startOrResumeUploading() is fire-and-forget (launches in its own scope and
        // returns at once); real completion is awaited via the state flow below.
        upload.startOrResumeUploading()
        awaitUploadStates(upload.stateFlow.map { it.progress to it.isDone })
            .collect { send(it) }
    }.distinctUntilChanged()
        .catch { e ->
            val message = if (e is RestException) "HTTP ${e.statusCode} — ${e.message}" else e.message
            emit(UploadState.Failed(message ?: "Upload failed"))
        }

    override suspend fun deleteMatch(videoId: String): Result<Unit> = runCatching {
        val clipRows = runCatching {
            client.postgrest.from("rally_clips")
                .select(Columns.list("clip_storage_path", "thumbnail_storage_path")) {
                    filter { eq("video_id", videoId) }
                }
                .decodeList<ClipPathsRow>()
        }.getOrElse { emptyList() }
        val videoPath = runCatching {
            client.postgrest.from("videos")
                .select(Columns.list("storage_path")) {
                    filter { eq("id", videoId) }
                }
                .decodeList<VideoPathRow>()
                .firstOrNull()?.storagePath
        }.getOrNull()

        val clipPaths = clipRows.map { it.clipPath }
        if (clipPaths.isNotEmpty()) runCatching { client.storage.from("clips").delete(clipPaths) }
        val thumbnailPaths = clipRows.mapNotNull { it.thumbnailPath }
        if (thumbnailPaths.isNotEmpty()) runCatching { client.storage.from("thumbnails").delete(thumbnailPaths) }
        if (videoPath != null) runCatching { client.storage.from("videos").delete(listOf(videoPath)) }

        client.postgrest.rpc("delete_match", DeleteMatchArgs(videoId))
        Unit
    }.annotateHttpStatus()

    companion object {
        fun storagePath(uid: String, videoId: String) = "$uid/$videoId.mp4"
        private const val MAX_POLL_ERRORS = 3
    }
}
