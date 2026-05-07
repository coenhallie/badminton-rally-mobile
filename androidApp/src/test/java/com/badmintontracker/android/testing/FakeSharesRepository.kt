package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.SharesRepository

class FakeSharesRepository : SharesRepository {
    val shareCalls   = mutableListOf<Pair<String, String>>()
    val unshareCalls = mutableListOf<Pair<String, String>>()
    var nextShareResult:   Result<Unit> = Result.success(Unit)
    var nextUnshareResult: Result<Unit> = Result.success(Unit)
    var sharesByVideo: Map<String, List<MatchShare>> = emptyMap()

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
}
