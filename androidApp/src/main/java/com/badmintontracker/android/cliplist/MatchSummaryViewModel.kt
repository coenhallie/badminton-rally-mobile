package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.TopRally
import com.badmintontracker.shared.model.buildMatchLabelSummary
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The label rollup for one match. Observes the clip cache itself rather than
 * taking state from [ClipListViewModel], so the two screens' view models stay
 * independent and this one can be tested with two fakes.
 *
 * A null summary means "nothing to show yet"; an empty one means "nothing to
 * show". Both hide the strip, which is why there is no loading flag: a skeleton
 * that resolves to nothing is worse than a row that was never there.
 */
class MatchSummaryViewModel(
    private val clips: ClipsRepository,
    private val annotations: AnnotationsRepository,
    private val videoId: String,
) : ViewModel() {

    private val _summary = MutableStateFlow<MatchLabelSummary?>(null)
    val summary: StateFlow<MatchLabelSummary?> = _summary.asStateFlow()

    private var clipsForVideo: List<RallyClip> = emptyList()
    private var lastFetchedIds: List<String>? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            clips.observeClips().collect { all ->
                clipsForVideo = all.filter { it.videoId == videoId }
                val ids = clipsForVideo.map { it.id }
                if (ids != lastFetchedIds) {
                    lastFetchedIds = ids
                    load()
                }
            }
        }
    }

    /** Re-reads the notes without waiting for the clip set to change. */
    fun refresh() {
        // The init collector already fetches on first composition, and the
        // screen's resume effect fires on that same entry. Without this guard
        // every entry would cancel a healthy in-flight fetch and start it over,
        // delaying the strip for no gain.
        if (loadJob?.isActive == true) return
        load()
    }

    private fun load() {
        // Cancelling the previous job keeps a slow earlier response from landing
        // on top of a newer one.
        loadJob?.cancel()
        val target = clipsForVideo
        if (target.isEmpty()) {
            // The match's clips were pruned, by a delete or a leave-share. Drop
            // the summary rather than leave a rollup of clips that are gone.
            _summary.value = null
            return
        }
        loadJob = viewModelScope.launch {
            annotations.listForClips(target.map { it.id })
                .onSuccess { _summary.value = buildMatchLabelSummary(target, it) }
            // Soft failure: keep whatever is on screen and say nothing. Same
            // contract as the metadata and shares lookups in ClipListViewModel.
            // The rally list underneath is fully usable without a summary.
        }
    }
}

/**
 * Name for the most-labelled rally in the summary sheet. Goes through the same
 * [clipRowTitle] the list row uses, so the sheet and the row can never name one
 * clip two ways. Falls back to the rally number when the clip has left the
 * list, which a prune racing an in-flight fetch can produce.
 */
internal fun topRallyName(top: TopRally, clips: List<RallyClip>, matchTitle: String?): String =
    clips.firstOrNull { it.id == top.clipId }
        ?.let { clipRowTitle(it, matchTitle) }
        ?: "Rally #${top.rallyIndex}"
