package com.badmintontracker.shared.testing

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.scoring.MatchFormat
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

data class CreateVideoCall(
    val videoId: String,
    val filename: String,
    val sizeBytes: Long,
    val title: String?,
    val description: String?,
)

class FakeVideosRepository : VideosRepository {
    var nextCreateResult: Result<Unit> = Result.success(Unit)
    var nextKeypointsResult: Result<Unit> = Result.success(Unit)
    var nextStartResult: Result<Unit> = Result.success(Unit)
    var uploadStates: List<UploadState> = listOf(UploadState.InProgress(0.5f), UploadState.Done)
    var processingUpdates: List<ProcessingUpdate> = listOf(
        ProcessingUpdate("processing_phase1", 0.5f, null),
        ProcessingUpdate("phase1_complete", 1f, null),
    )
    var nextDeleteMatchResult: Result<Unit> = Result.success(Unit)
    var nextMetadataResult: Result<List<MatchMetadata>> = Result.success(emptyList())

    val createCalls = mutableListOf<CreateVideoCall>()
    val keypointsCalls = mutableListOf<Pair<String, CourtKeypoints>>()
    val startCalls = mutableListOf<String>()
    val uploadCalls = mutableListOf<String>()
    val deleteMatchCalls = mutableListOf<String>()
    var metadataCalls = 0

    override suspend fun createVideo(
        videoId: String,
        filename: String,
        sizeBytes: Long,
        title: String?,
        description: String?,
    ): Result<Unit> {
        createCalls += CreateVideoCall(videoId, filename, sizeBytes, title, description)
        return nextCreateResult
    }

    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> {
        keypointsCalls += videoId to keypoints
        return nextKeypointsResult
    }

    /**
     * What a successful trigger does to the row, for a test that needs the fake to
     * behave like the backend across a retry: a failed row STAYS failed until
     * something re-triggers it, and only then starts reporting progress again.
     */
    var onStartProcessing: () -> Unit = {}

    override suspend fun startProcessing(videoId: String): Result<Unit> {
        startCalls += videoId
        if (nextStartResult.isSuccess) onStartProcessing()
        return nextStartResult
    }

    /** Recorded separately from [startCalls]: the two triggers are different phases. */
    val startAnalyticsCalls = mutableListOf<String>()
    var nextStartAnalyticsResult: Result<Unit> = Result.success(Unit)

    override suspend fun startAnalytics(videoId: String): Result<Unit> {
        startAnalyticsCalls += videoId
        return nextStartAnalyticsResult
    }

    /** Recorded so a test can assert which kind of wait a caller asked for. */
    val awaitAnalyticsCalls = mutableListOf<Boolean>()

    override fun observeProcessing(
        videoId: String,
        pollIntervalMs: Long,
        awaitAnalytics: Boolean,
    ): Flow<ProcessingUpdate> = flow {
        awaitAnalyticsCalls += awaitAnalytics
        processingUpdates.forEach { emit(it) }
    }

    /** Optional per-video gate: the upload flow suspends until it completes. */
    val uploadGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override fun uploadVideo(
        videoId: String,
        sizeBytes: Long,
        channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState> {
        uploadCalls += videoId
        return flow {
            uploadGates[videoId]?.await()
            uploadStates.forEach { emit(it) }
        }
    }

    override suspend fun listMatchMetadata(): Result<List<MatchMetadata>> {
        metadataCalls++
        nextMetadataResult.onSuccess { rows -> metadata.value = rows.associateBy { it.videoId } }
        return nextMetadataResult
    }

    /** The cache the real repository keeps; a test seeds it directly or through [listMatchMetadata]. */
    val metadata = MutableStateFlow<Map<String, MatchMetadata>>(emptyMap())

    override fun observeMatchMetadata(): Flow<Map<String, MatchMetadata>> = metadata

    var nextUpdateDetailsResult: Result<Unit> = Result.success(Unit)
    val updateDetailsCalls = mutableListOf<MatchMetadata>()

    override suspend fun updateMatchDetails(
        videoId: String,
        title: String?,
        description: String?,
        format: MatchFormat?,
        homePlayers: List<String>,
        awayPlayers: List<String>,
    ): Result<Unit> {
        val row = MatchMetadata(videoId, title, description, homePlayers, awayPlayers, format)
        updateDetailsCalls += row
        nextUpdateDetailsResult.onSuccess { metadata.value = metadata.value + (videoId to row) }
        return nextUpdateDetailsResult
    }

    override suspend fun deleteMatch(videoId: String): Result<Unit> {
        deleteMatchCalls += videoId
        return nextDeleteMatchResult
    }
}
