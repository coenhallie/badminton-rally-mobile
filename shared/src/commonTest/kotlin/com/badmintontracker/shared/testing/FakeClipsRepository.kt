package com.badmintontracker.shared.testing

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeClipsRepository : ClipsRepository {
    val clips = MutableStateFlow<List<RallyClip>>(emptyList())
    var refreshError: Throwable? = null
    val refreshCalls = mutableListOf<Unit>()

    /** Scripted results for [countClipsForVideo]; once drained, counts from [clips]. */
    val countResults = ArrayDeque<Result<Int>>()
    val countCalls = mutableListOf<String>()

    override suspend fun listClips(): List<RallyClip> = clips.value
    override fun observeClips() = clips
    override suspend fun refresh() {
        refreshCalls += Unit
        refreshError?.let { throw it }
    }
    override suspend fun updateTitle(clipId: String, title: String?) = Result.success(Unit)
    override suspend fun countClipsForVideo(videoId: String): Result<Int> {
        countCalls += videoId
        return countResults.removeFirstOrNull()
            ?: Result.success(clips.value.count { it.videoId == videoId })
    }
    override fun pruneVideo(videoId: String) {
        clips.value = clips.value.filterNot { it.videoId == videoId }
    }
}
