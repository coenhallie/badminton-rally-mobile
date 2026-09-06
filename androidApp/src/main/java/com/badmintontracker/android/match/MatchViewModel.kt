package com.badmintontracker.android.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.scoring.AttachStatus
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.buildScoreTagSummary
import com.badmintontracker.shared.scoring.isPlayable
import com.badmintontracker.shared.scoring.removeMatchVideoOrMessage
import com.badmintontracker.shared.scoring.scoreLogAttachStatus
import com.badmintontracker.shared.scoring.scoreLogCanRemoveVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One match, folded. Everything is derived from the store's flow rather than
 * fetched once, so a point scored elsewhere or a video finishing its pipeline
 * redraws this page without a refresh.
 */
data class MatchPageState(
    /** Null for a video-first or shared match, and for one deleted while open. */
    val log: ScoreLog? = null,
    val match: MatchState? = null,
    val card: ScoreMatchCard? = null,
    val tally: ScoreTagSummary = ScoreTagSummary.EMPTY,
    /** A finished match with no video, and no video already picked for it. The
     *  one condition "Add video" is offered on. */
    val canAddVideo: Boolean = false,
    /** What this match's video is doing right now, or null when there is
     *  nothing to say - same derivation and same precedence as the match list's
     *  own row, see [scoreLogAttachStatus]. */
    val attach: AttachStatus? = null,
    /** This match has a video and nothing is in flight for it: the one condition
     *  "Change video" and "Remove video" are offered on, see
     *  [scoreLogCanRemoveVideo]. Mutually exclusive with [canAddVideo]. */
    val canRemoveVideo: Boolean = false,
    /** Whether a videos row exists, which decides what the confirm says is about
     *  to be lost - an entry that never reached CREATE_ROW has no rallies and no
     *  notes to lose. */
    val hasServerVideo: Boolean = false,
    /** A failed removal, for the page's snackbar. Nothing else on this page can
     *  fail: everything else it shows is derived from stores it only reads. */
    val error: String? = null,
)

class MatchViewModel(
    private val scoreLogs: ScoreLogsRepository,
    private val localVideos: LocalVideoRepository,
    coordinator: AnalyzeCoordinator,
    private val clips: ClipsRepository,
    private val videos: VideosRepository,
    private val localAnnotations: LocalAnnotationsRepository,
    private val scoreLogId: String?,
) : ViewModel() {

    private val errors = MutableStateFlow<String?>(null)

    val state = combine(
        scoreLogs.logs,
        localVideos.entries,
        coordinator.progress,
        clips.observeClips(),
        errors,
    ) { logs, entries, progress, allClips, error ->
        build(logs, entries, progress, allClips, error)
    }
        // Eagerly, not WhileSubscribed: state.value is read directly in tests and
        // by the navigation callbacks, both without a collector attached. The
        // initial value is built from each store's current value rather than left
        // blank, for the same reason ScoringViewModel's is: a blank one would say
        // the match is not on this phone for the one frame before the first
        // collection lands, and a test reading state.value synchronously right
        // after construction would see that blank frame instead of the real one.
        // clips has no synchronous snapshot to read (ClipsRepository.observeClips()
        // is a bare Flow), so the initial build assumes none yet - correct for a
        // freshly attached match, and self-corrects the moment the combine's first
        // emission lands.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            build(
                scoreLogs.logs.value, localVideos.entries.value, coordinator.progress.value,
                emptyList(), errors.value,
            ),
        )

    /**
     * Takes the video off this match and leaves the match: the ordering, and the
     * message on failure, are [removeMatchVideoOrMessage]'s.
     *
     * [onRemoved] is what makes "Change video" one gesture rather than two. Once
     * removal lands, this match has no video in either sense, so `canAddVideo`
     * flips true on its own and the caller can go straight to the picker the
     * page already owns - no second attach path. See the 2026-08-29 design, §4.
     */
    fun removeVideo(onRemoved: () -> Unit = {}) {
        val id = scoreLogId ?: return
        viewModelScope.launch {
            val message = removeMatchVideoOrMessage(
                scoreLogId = id,
                scoreLogs = scoreLogs,
                videos = videos,
                clips = clips,
                localVideos = localVideos,
                localAnnotations = localAnnotations,
            )
            if (message == null) onRemoved() else errors.value = message
        }
    }

    fun dismissError() { errors.value = null }

    private fun build(
        logs: List<ScoreLog>,
        entries: List<LocalVideoEntry>,
        progress: Map<String, AnalyzeProgress>,
        allClips: List<RallyClip>,
        error: String?,
    ): MatchPageState {
        val log = logs.firstOrNull { it.id == scoreLogId } ?: return MatchPageState(error = error)
        val match = log.state()
        // An entry can exist for a while before the pipeline gives the match a
        // videoId (LOCAL, UPLOADING, PROCESSING); gating on videoId alone would
        // let "Add video" reopen the picker mid-attach.
        val entry = entries.firstOrNull { it.scoreLogId == scoreLogId }
        return MatchPageState(
            log = log,
            match = match,
            card = buildScoreMatchCard(log),
            tally = buildScoreTagSummary(match),
            // isPlayable, not `status != LIVE`: the board's Done button leaves a
            // won match at LIVE on purpose, so undo stays reachable.
            canAddVideo = !log.isPlayable() && log.videoId == null && entry == null,
            attach = scoreLogAttachStatus(
                log = log,
                entries = entries,
                progress = progress,
                clipCount = allClips.count { it.videoId == log.videoId },
            ),
            canRemoveVideo = scoreLogCanRemoveVideo(log, entries),
            hasServerVideo = log.videoId != null,
            error = error,
        )
    }
}
