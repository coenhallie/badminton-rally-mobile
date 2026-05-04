package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeClipsRepository : ClipsRepository {
    val clips = MutableStateFlow<List<RallyClip>>(emptyList())
    var refreshError: Throwable? = null
    val refreshCalls = mutableListOf<Unit>()

    override suspend fun listClips(): List<RallyClip> = clips.value
    override fun observeClips() = clips
    override suspend fun refresh() {
        refreshCalls += Unit
        refreshError?.let { throw it }
    }
    override suspend fun updateTitle(clipId: String, title: String?) = Result.success(Unit)
}
