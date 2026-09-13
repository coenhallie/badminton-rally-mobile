package com.badmintontracker.shared.testing

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.repo.VideosRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
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

    override suspend fun startProcessing(videoId: String): Result<Unit> {
        startCalls += videoId
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
        return nextMetadataResult
    }

    override suspend fun deleteMatch(videoId: String): Result<Unit> {
        deleteMatchCalls += videoId
        return nextDeleteMatchResult
    }
}
