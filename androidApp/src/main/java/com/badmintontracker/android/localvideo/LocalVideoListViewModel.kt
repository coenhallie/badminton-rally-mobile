package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.normalizeDescription
import com.badmintontracker.shared.localvideo.normalizeTitle
import com.badmintontracker.shared.localvideo.setDetails

/**
 * What the match list does to a video on this phone. The
 * rows themselves come from the shared match list (`ClipListState.matchList`);
 * this class keeps only the actions, which need the coordinator and the stores.
 */
class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
    private val localAnnotations: LocalAnnotationsRepository,
) : ViewModel() {

    /**
     * The list's "Remove video": the coach wants the space back, not the
     * match gone. `freeVideo` keeps the tracks, so a match with a cloud copy
     * keeps its heatmap and base position - see
     * [com.badmintontracker.shared.localvideo.LocalVideoRemoval] and
     * `MatchListCopy.removeFromPhoneDetail`, which says what goes per row.
     */
    fun remove(id: String) {
        localVideos.freeVideo(id)
        localAnnotations.removeAllFor(id)
    }

    fun retry(id: String) = coordinator.retry(id)

    /** Marks a failure's result dialog as shown so it isn't re-displayed. */
    fun acknowledgeResult(id: String) = localVideos.update(id) { it.copy(resultSeen = true) }

    /** Stores the match name and description typed into the details sheet. */
    fun setDetails(id: String, title: String, description: String) =
        localVideos.setDetails(id, normalizeTitle(title), normalizeDescription(description))
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
