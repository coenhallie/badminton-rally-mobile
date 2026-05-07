package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

data class MatchSummary(
    val videoId: String,
    val rallyCount: Int,
    val latestCreatedAt: Instant,
    val coverClip: RallyClip,
    val isOwned: Boolean,
)

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

private fun List<RallyClip>.toMatches(currentUserId: String?): List<MatchSummary> =
    groupBy { it.videoId }
        .map { (videoId, list) ->
            val cover = list.minByOrNull { it.rallyIndex } ?: list.first()
            MatchSummary(
                videoId = videoId,
                rallyCount = list.size,
                latestCreatedAt = list.maxOf { it.createdAt },
                coverClip = cover,
                isOwned = currentUserId != null && cover.ownerId == currentUserId,
            )
        }
        .sortedByDescending { it.latestCreatedAt }

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val errors     = MutableStateFlow<String?>(null)

    val state = combine(clips.observeClips(), refreshing, errors) { list, r, e ->
        val matches = list.toMatches(auth.currentUserId())
        val (owned, shared) = matches.partition { it.isOwned }
        ClipListState(
            clips = list,
            ownedMatches = owned,
            sharedMatches = shared,
            isRefreshing = r,
            error = e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            runCatching { clips.refresh() }.onFailure { errors.value = it.message }
            refreshing.value = false
        }
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }
}
