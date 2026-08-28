package com.badmintontracker.android.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.buildScoreTagSummary
import com.badmintontracker.shared.scoring.isPlayable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
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
    /** A finished match with no video. The one condition "Add video" is offered on. */
    val canAddVideo: Boolean = false,
)

class MatchViewModel(
    scoreLogs: ScoreLogsRepository,
    private val scoreLogId: String?,
) : ViewModel() {

    val state = scoreLogs.logs
        .map(::build)
        // Eagerly, not WhileSubscribed: state.value is read directly in tests and
        // by the navigation callbacks, both without a collector attached. The
        // initial value is built from the store's current value rather than left
        // blank, for the same reason ScoringViewModel's is: a blank one would say
        // the match is not on this phone for the one frame before the first
        // collection lands, and a test reading state.value synchronously right
        // after construction would see that blank frame instead of the real one.
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(scoreLogs.logs.value))

    private fun build(logs: List<ScoreLog>): MatchPageState {
        val log = logs.firstOrNull { it.id == scoreLogId } ?: return MatchPageState()
        val match = log.state()
        return MatchPageState(
            log = log,
            match = match,
            card = buildScoreMatchCard(log),
            tally = buildScoreTagSummary(match),
            // isPlayable, not `status != LIVE`: the board's Done button leaves a
            // won match at LIVE on purpose, so undo stays reachable.
            canAddVideo = !log.isPlayable() && log.videoId == null,
        )
    }
}
