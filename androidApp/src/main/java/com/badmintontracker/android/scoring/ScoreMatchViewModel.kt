package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One scored match, folded. Everything is derived from the store's flow rather
 * than fetched once, so a rename or a point scored elsewhere redraws this page
 * without a refresh.
 */
data class ScoreMatchState(
    /** Null when the match is not on this device - deleted from the list, or a cold start. */
    val log: ScoreLog? = null,
    val match: MatchState? = null,
    val card: ScoreMatchCard? = null,
)

class ScoreMatchViewModel(
    scoreLogs: ScoreLogsRepository,
    private val scoreLogId: String,
) : ViewModel() {

    val state = scoreLogs.logs
        .map { logs ->
            val log = logs.firstOrNull { it.id == scoreLogId }
            if (log == null) ScoreMatchState()
            else ScoreMatchState(log = log, match = log.state(), card = buildScoreMatchCard(log))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScoreMatchState())
}
