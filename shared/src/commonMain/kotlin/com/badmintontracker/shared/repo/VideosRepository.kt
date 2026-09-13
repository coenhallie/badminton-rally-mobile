package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.MatchMetadata
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The statuses the videos row holds, as the pipeline writes them.
 *
 * Named rather than spelled out at each comparison: three of these are
 * compared in more than one place, and a typo in a string literal reads as
 * "this video is not finished" rather than as an error.
 */
object VideoStatus {
    const val UPLOADED = "uploaded"
    const val PROCESSING_PHASE1 = "processing_phase1"
    const val PHASE1_COMPLETE = "phase1_complete"
    const val PROCESSING_PHASE2 = "processing_phase2"
    const val COMPLETED = "completed"
}

/**
 * Snapshot of the cloud pipeline's state for one video (from the videos row).
 * [progress] is normalized to 0f..1f (the DB stores it as a 0..100 percentage).
 *
 * There are two kinds of "done" here and they are not interchangeable. Phase 1
 * ends with watchable rally clips and the pipeline then STOPS: Phase 2 runs
 * only when something calls start-analytics. Phase 2 ends with the pose
 * artifact the heatmap and skeleton are drawn from. A caller waiting for clips
 * wants [isTerminal]; a caller waiting for the artifact wants
 * [isAnalyticsTerminal].
 */
data class ProcessingUpdate(val status: String, val progress: Float?, val error: String?) {
    /** Phase 1 is done: the rally clips exist. Phase 2 merges into its results, so it keeps them. */
    val hasClips: Boolean get() = status == VideoStatus.PHASE1_COMPLETE || status == VideoStatus.COMPLETED

    /**
     * Phase 2 is done.
     *
     * Not the same as "a pose artifact exists": the upload is non-fatal on the
     * worker, so a completed video can still have a null poses_artifact_path.
     * That is the field to gate a fetch on, not this.
     */
    val hasAnalytics: Boolean get() = status == VideoStatus.COMPLETED

    val isSuccess: Boolean get() = hasClips
    val isFailure: Boolean get() = status.startsWith("failed")
    val isTerminal: Boolean get() = isSuccess || isFailure

    /** A wait for the pose artifact. phase1_complete does NOT end it: Phase 2 is still to come. */
    val isAnalyticsTerminal: Boolean get() = hasAnalytics || isFailure
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
            // e.error, not e.message: the message is a multi-line
            // Code/Hint/URL/Headers dump that ends up in user-facing dialogs.
            if (e is RestException) IllegalStateException("HTTP ${e.statusCode} — ${e.error}", e) else e,
        )
    },
)

interface VideosRepository {
    /**
     * Insert the videos row. Call AFTER the upload succeeds (same order as web).
     * [title] and [description] ride along here because the database grants no
     * UPDATE on either column — this insert is the only chance to set them.
     */
    suspend fun createVideo(
        videoId: String,
        filename: String,
        sizeBytes: Long,
        title: String?,
        description: String?,
    ): Result<Unit>
    suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit>
    /** Invoke the process-video Edge Function (requires row + keypoints in place). */
    suspend fun startProcessing(videoId: String): Result<Unit>

    /**
     * Invoke the start-analytics Edge Function, which runs Phase 2: the full
     * pose pass whose artifact the heatmap and skeleton are drawn from.
     *
     * Only valid from `phase1_complete` or `failed_phase2`; the function
     * answers 409 otherwise, and flips the status to `processing_phase2`
     * BEFORE calling Modal, so a double tap cannot start two workers.
     */
    suspend fun startAnalytics(videoId: String): Result<Unit>

    /**
     * Poll the videos row until a terminal status, emitting every change.
     *
     * @param awaitAnalytics keep polling past `phase1_complete` until Phase 2
     *   finishes. False for the ordinary upload-and-clips wait, because
     *   nothing advances a video past `phase1_complete` on its own and such a
     *   wait would never end.
     */
    fun observeProcessing(
        videoId: String,
        pollIntervalMs: Long = 5_000,
        awaitAnalytics: Boolean = false,
    ): Flow<ProcessingUpdate>
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
     * Title and description for every match the user can see, owned or shared,
     * from the list_match_metadata RPC. An RPC rather than a select on videos:
     * that table's SELECT policy is owner-only, and widening it would also hand
     * share recipients storage_path, results_meta and player_labels.
     */
    suspend fun listMatchMetadata(): Result<List<MatchMetadata>>

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
        val title: String? = null,
        val description: String? = null,
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

    override suspend fun createVideo(
        videoId: String,
        filename: String,
        sizeBytes: Long,
        title: String?,
        description: String?,
    ): Result<Unit> =
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
                    title = title,
                    description = description,
                )
            )
            Unit
        }.annotateHttpStatus()

    override suspend fun listMatchMetadata(): Result<List<MatchMetadata>> = runCatching {
        client.postgrest.rpc("list_match_metadata").decodeList<MatchMetadata>()
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
        // Clear any previous run's terminal failed_* status BEFORE re-triggering;
        // the processing poll loop would otherwise instantly re-read the stale
        // failure. A no-op for fresh videos (inserted as "uploaded").
        client.postgrest.from("videos")
            .update(
                buildJsonObject {
                    put("status", "uploaded")
                    put("error", JsonNull)
                    put("progress", JsonNull)
                }
            ) {
                filter { eq("id", videoId) }
            }
        val response = client.functions(
            function = "process-video",
            body = ProcessVideoBody(videoId),
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
        )
        check(response.status.isSuccess()) { response.bodyAsText() }
    }.annotateHttpStatus()

    /**
     * Deliberately NOT a copy of [startProcessing]'s opening move.
     *
     * That one clears the row to "uploaded" before invoking, because Phase 1
     * restarts from scratch and a stale failed_phase1 would be re-read by the
     * poll loop. Phase 2 is the opposite case: start-analytics accepts only
     * `phase1_complete` or `failed_phase2` and answers 409 to anything else,
     * so resetting the status first would reject every call. The edge function
     * flips the status itself, before it calls Modal.
     */
    override suspend fun startAnalytics(videoId: String): Result<Unit> = runCatching {
        val response = client.functions(
            function = "start-analytics",
            body = ProcessVideoBody(videoId),
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
        )
        check(response.status.isSuccess()) { response.bodyAsText() }
    }.annotateHttpStatus()

    override fun observeProcessing(
        videoId: String,
        pollIntervalMs: Long,
        awaitAnalytics: Boolean,
    ): Flow<ProcessingUpdate> = flow {
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
                    val message = e.userFacingMessage("Lost connection while checking progress")
                    emit(
                        ProcessingUpdate(
                            status = "failed_connection",
                            progress = null,
                            error = message,
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
            if (if (awaitAnalytics) update.isAnalyticsTerminal else update.isTerminal) break
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
            try {
                createOrContinue()
            } catch (e: BadContentTypeFormatException) {
                // Entry poisoned by an older build (contentType "null"). The library
                // drops the expired entry before throwing, so one retry starts clean.
                createOrContinue()
            }
        } catch (e: IllegalStateException) {
            // Crash window: the app died after the last chunk uploaded but before
            // the TUS cache entry was removed. The file IS in storage, so report
            // success instead of failing every retry until the entry expires.
            if (e.message?.contains("File already uploaded") == true) {
                send(UploadState.Done)
                return@channelFlow
            }
            throw e
        }
        // startOrResumeUploading() is fire-and-forget (launches in its own scope and
        // returns at once); real completion is awaited via the state flow below.
        upload.startOrResumeUploading()
        awaitUploadStates(upload.stateFlow.map { it.progress to it.isDone })
            .collect { send(it) }
    }.distinctUntilChanged()
        .catch { e ->
            emit(UploadState.Failed(e.userFacingMessage("Upload failed — check your connection")))
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
        // Tolerate ~3 minutes of consecutive failures (at the 5s default poll
        // interval): processing runs server-side for minutes, and phones drop
        // the network far longer than 15s during WiFi<->cellular handoffs.
        private const val MAX_POLL_ERRORS = 36
    }
}
