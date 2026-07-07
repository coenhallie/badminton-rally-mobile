package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Snapshot of the cloud pipeline's state for one video (from the videos row). */
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
        }

    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> =
        runCatching {
            client.postgrest.from("videos")
                .update(KeypointsPatch(keypoints)) {
                    filter { eq("id", videoId) }
                }
            Unit
        }

    override suspend fun startProcessing(videoId: String): Result<Unit> = runCatching {
        val response = client.functions(
            function = "process-video",
            body = ProcessVideoBody(videoId),
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            },
        )
        check(response.status.isSuccess()) { response.bodyAsText() }
    }

    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> = flow {
        while (true) {
            val row = client.postgrest.from("videos")
                .select(Columns.list("status", "progress", "error")) {
                    filter { eq("id", videoId) }
                }
                .decodeSingle<StatusRow>()
            val update = ProcessingUpdate(row.status, row.progress, row.error)
            emit(update)
            if (update.isTerminal) break
            delay(pollIntervalMs)
        }
    }

    // channelFlow (not flow{}): progress is emitted from a child coroutine while
    // startOrResumeUploading() suspends, which plain flow{} forbids.
    override fun uploadVideo(
        videoId: String,
        sizeBytes: Long,
        channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState> = channelFlow {
        val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
        val upload = client.storage.from("videos").resumable.createOrContinueUpload(
            channel = channelProvider,
            source = videoId,          // stable key so retries resume the TUS session
            size = sizeBytes,
            path = storagePath(uid, videoId),
        )
        val progressJob = launch {
            upload.stateFlow.collect { send(toUploadState(it.progress, it.isDone)) }
        }
        upload.startOrResumeUploading()   // suspends until finished
        progressJob.cancel()
        send(UploadState.Done)
    }.distinctUntilChanged()
        .catch { emit(UploadState.Failed(it.message ?: "Upload failed")) }

    companion object {
        fun storagePath(uid: String, videoId: String) = "$uid/$videoId.mp4"
    }
}
