package com.badmintontracker.shared.testing

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideosRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    val createCalls = mutableListOf<Triple<String, String, Long>>()
    val keypointsCalls = mutableListOf<Pair<String, CourtKeypoints>>()
    val startCalls = mutableListOf<String>()
    val uploadCalls = mutableListOf<String>()
    val deleteMatchCalls = mutableListOf<String>()

    override suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit> {
        createCalls += Triple(videoId, filename, sizeBytes)
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

    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> =
        flow { processingUpdates.forEach { emit(it) } }

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

    override suspend fun deleteMatch(videoId: String): Result<Unit> {
        deleteMatchCalls += videoId
        return nextDeleteMatchResult
    }
}
