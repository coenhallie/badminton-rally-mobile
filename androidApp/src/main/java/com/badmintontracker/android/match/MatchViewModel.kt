package com.badmintontracker.android.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.scoring.AttachStatus
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.buildScoreTagSummary
import com.badmintontracker.shared.scoring.isPlayable
import com.badmintontracker.shared.scoring.scoreLogAttachStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

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
)

class MatchViewModel(
    private val scoreLogs: ScoreLogsRepository,
    private val localVideos: LocalVideoRepository,
    coordinator: AnalyzeCoordinator,
    clips: ClipsRepository,
    private val scoreLogId: String?,
) : ViewModel() {

    val state = combine(
        scoreLogs.logs,
        localVideos.entries,
        coordinator.progress,
        clips.observeClips(),
    ) { logs, entries, progress, allClips ->
        build(logs, entries, progress, allClips)
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
            build(scoreLogs.logs.value, localVideos.entries.value, coordinator.progress.value, emptyList()),
        )

    private fun build(
        logs: List<ScoreLog>,
        entries: List<LocalVideoEntry>,
        progress: Map<String, AnalyzeProgress>,
        allClips: List<RallyClip>,
    ): MatchPageState {
        val log = logs.firstOrNull { it.id == scoreLogId } ?: return MatchPageState()
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
        )
    }
}
