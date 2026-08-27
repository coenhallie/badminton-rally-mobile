package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.scoring.MatchScorer
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.PointTag
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the courtside surface draws.
 *
 * [pendingTagOrdinal] is the point the tag row is currently pointing at, and it is
 * state rather than a dialog: that is what lets one tap score and a second tap tag
 * without a modal ever appearing between the coach and the next rally.
 */
data class ScoringUiState(
    /** Null when the match is not on this device - deleted elsewhere, or a cold start. */
    val log: ScoreLog? = null,
    val match: MatchState? = null,
    val labels: List<AnnotationLabel> = emptyList(),
    val pendingTagOrdinal: Int? = null,
    val canScore: Boolean = false,
    val canUndo: Boolean = false,
)

/**
 * Drives the courtside surface. Every mutation goes through [MatchScorer] rather
 * than touching [ScoreLogsRepository] directly, so this screen and its iOS twin
 * cannot come to disagree about what "undo" or "reset game" means.
 */
class ScoringViewModel(
    scoreLogs: ScoreLogsRepository,
    labels: AnnotationLabelsRepository,
    private val scoreLogId: String,
) : ViewModel() {

    private val scorer = MatchScorer(scoreLogs, scoreLogId)

    private val pending = MutableStateFlow<Int?>(null)

    /**
     * Eager rather than [SharingStarted.WhileSubscribed], unlike the list screens.
     * A tap has to be recorded whether or not the composition happens to be
     * collecting at that instant, and the store this folds is already in memory, so
     * there is nothing to defer.
     */
    val state = combine(scoreLogs.logs, labels.labels, pending) { logs, palette, pendingOrdinal ->
        val log = logs.firstOrNull { it.id == scoreLogId }
        val match = log?.state()
        ScoringUiState(
            log = log,
            match = match,
            labels = palette,
            // A row still pointing at a rally that undo took back would put the next
            // label on a point that no longer exists, so the clamp lives here rather
            // than in every caller.
            pendingTagOrdinal = pendingOrdinal?.takeIf { match != null && it < match.pointCount },
            canScore = match?.isOver == false,
            canUndo = log?.events?.isNotEmpty() == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScoringUiState())

    /** One tap. The tag row follows to the rally just played. */
    fun score(side: Side) {
        scorer.score(side)
        pending.value = scorer.state()?.pointCount?.minus(1)?.takeIf { it >= 0 }
    }

    /**
     * Adds [label] to the rally, or takes it off again if it is already there. The
     * row stays open: a rally can be a good shot and a forced error at once, and
     * re-opening the row to say so would cost the coach the next point.
     */
    fun toggleTag(ordinal: Int, label: AnnotationLabel) {
        val point = pointAt(ordinal) ?: return
        val tag = PointTag(labelName = label.name, labelColor = label.colorKey)
        val next =
            if (point.tags.any { it.labelName == tag.labelName }) {
                point.tags.filterNot { it.labelName == tag.labelName }
            } else {
                point.tags + tag
            }
        scorer.tagPoint(ordinal, next, point.comment)
    }

    /** The longer thought, written between rallies or at the interval. */
    fun setComment(ordinal: Int, text: String) {
        val point = pointAt(ordinal) ?: return
        scorer.tagPoint(ordinal, point.tags, text.trim().ifEmpty { null })
    }

    fun undo() {
        scorer.undo()
    }

    fun resetCurrentGame() {
        scorer.resetCurrentGame()
        pending.value = null
    }

    fun finish() {
        scorer.finish()
        pending.value = null
    }

    fun dismissTagRow() {
        pending.value = null
    }

    /**
     * Read straight through the scorer rather than off [state], because a tap and
     * the tag that follows it can both land before the state flow has emitted once.
     */
    private fun pointAt(ordinal: Int) =
        scorer.state()?.points?.getOrNull(ordinal)
}
