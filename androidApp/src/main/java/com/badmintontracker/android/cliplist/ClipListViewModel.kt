package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.VideosRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val sharerEmail: String? = null,
)

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

private fun List<RallyClip>.toMatches(
    currentUserId: String?,
    sharerByVideoId: Map<String, String>,
): List<MatchSummary> =
    groupBy { it.videoId }
        .map { (videoId, list) ->
            val cover = list.minByOrNull { it.rallyIndex } ?: list.first()
            val owned = currentUserId != null && cover.ownerId == currentUserId
            MatchSummary(
                videoId = videoId,
                rallyCount = list.size,
                latestCreatedAt = list.maxOf { it.createdAt },
                coverClip = cover,
                isOwned = owned,
                sharerEmail = if (owned) null else sharerByVideoId[videoId],
            )
        }
        .sortedByDescending { it.latestCreatedAt }

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
    private val shares: SharesRepository,
    private val videos: VideosRepository,
) : ViewModel() {
    private val refreshing      = MutableStateFlow(true)
    private val errors          = MutableStateFlow<String?>(null)
    private val sharerByVideoId = MutableStateFlow<Map<String, String>>(emptyMap())

    val state = combine(
        clips.observeClips(),
        sharerByVideoId,
        refreshing,
        errors,
    ) { list, sharerMap, r, e ->
        val matches = list.toMatches(auth.currentUserId(), sharerMap)
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
            coroutineScope {
                val clipsJob = async {
                    runCatching { clips.refresh() }
                        .onFailure { errors.value = "Couldn't refresh matches. Pull to try again." }
                }
                val sharesJob = async {
                    runCatching { shares.listReceived() }
                        .onSuccess { received ->
                            sharerByVideoId.value =
                                received.mapNotNull { r -> r.sharerEmail?.let { r.videoId to it } }.toMap()
                        }
                        // Soft failure: leave sharerByVideoId untouched, no user-facing error.
                }
                clipsJob.await()
                sharesJob.await()
            }
            refreshing.value = false
        }
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }

    fun deleteMatch(videoId: String) {
        viewModelScope.launch {
            videos.deleteMatch(videoId)
                .onSuccess { clips.pruneVideo(videoId); refresh() }
                .onFailure { errors.value = "Couldn't delete the match. Please try again." }
        }
    }

    fun leaveShare(videoId: String) {
        viewModelScope.launch {
            shares.leaveShare(videoId)
                .onSuccess { clips.pruneVideo(videoId); refresh() }
                .onFailure { errors.value = "Couldn't remove the shared match. Please try again." }
        }
    }
}
