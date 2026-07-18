package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ReceivedShare
import com.badmintontracker.shared.repo.SharesRepository

class FakeSharesRepository : SharesRepository {
    val shareCalls   = mutableListOf<Pair<String, String>>()
    val unshareCalls = mutableListOf<Pair<String, String>>()
    val leaveShareCalls = mutableListOf<String>()
    var nextShareResult:   Result<Unit> = Result.success(Unit)
    var nextUnshareResult: Result<Unit> = Result.success(Unit)
    var nextLeaveShareResult: Result<Unit> = Result.success(Unit)
    var sharesByVideo: Map<String, List<MatchShare>> = emptyMap()

    var receivedShares: List<ReceivedShare> = emptyList()
    var listReceivedError: Throwable? = null
    var listReceivedCalls: Int = 0

    override suspend fun share(videoId: String, email: String): Result<Unit> {
        shareCalls += videoId to email
        return nextShareResult
    }

    override suspend fun unshare(videoId: String, userId: String): Result<Unit> {
        unshareCalls += videoId to userId
        return nextUnshareResult
    }

    override suspend fun listShares(videoId: String): Result<List<MatchShare>> =
        Result.success(sharesByVideo[videoId].orEmpty())

    override suspend fun listReceived(): List<ReceivedShare> {
        listReceivedCalls += 1
        listReceivedError?.let { throw it }
        return receivedShares
    }

    override suspend fun leaveShare(videoId: String): Result<Unit> {
        leaveShareCalls += videoId
        return nextLeaveShareResult
    }
}
