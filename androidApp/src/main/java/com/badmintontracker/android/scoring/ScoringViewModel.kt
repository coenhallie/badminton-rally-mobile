package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchScorer
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.PointTag
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.UndoneRally
import com.badmintontracker.shared.scoring.isPlayable
import com.badmintontracker.shared.scoring.undoneNotice
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
    /** True until the first point: the window in which the board can hand the opening serve across. */
    val canSwitchFirstServer: Boolean = false,
    /**
     * Whether the account has any labels at all, board-scoped or not. Lets the
     * board tell "you have not made any labels" apart from "none of yours are
     * on the board", which are different problems with different fixes.
     */
    val hasAnyLabels: Boolean = false,
    /**
     * "Point to Home undone" while the last undo can still be put back, null
     * otherwise. Undo asks no question, so a mis-tap on it is answered here.
     */
    val undoneNotice: String? = null,
    /** The undo [undoneNotice] is about. A new one restarts the notice's timer even when the words match. */
    val undone: UndoneRally? = null,
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

    /** The last undo, until it is redone or dismissed. Only offered while [MatchScorer.canRedo]. */
    private val lastUndo = MutableStateFlow<UndoneRally?>(null)

    /**
     * Eager rather than [SharingStarted.WhileSubscribed], unlike the list screens.
     * A tap has to be recorded whether or not the composition happens to be
     * collecting at that instant, and the store this folds is already in memory, so
     * there is nothing to defer.
     *
     * The initial value is built from the store rather than left blank for the same
     * reason: a blank one would say the match is not on this phone, and the surface
     * would flash that at the coach for a frame on the way in.
     */
    val state = combine(
        scoreLogs.logs, labels.scoreboardLabels, labels.labels, pending, lastUndo, ::build,
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        build(scoreLogs.logs.value, labels.scoreboardLabels.value, labels.labels.value, pending.value, lastUndo.value),
    )

    private fun build(
        logs: List<ScoreLog>,
        boardLabels: List<AnnotationLabel>,
        allLabels: List<AnnotationLabel>,
        pendingOrdinal: Int?,
        undone: UndoneRally?,
    ): ScoringUiState {
        val log = logs.firstOrNull { it.id == scoreLogId }
        val match = log?.state()
        // Read against the log rather than cleared by each caller, so a change
        // from anywhere - a rally, a tag, a sync - retires the redo with it.
        val live = undone?.takeIf { log != null && log.events == it.after }
        return ScoringUiState(
            log = log,
            match = match,
            labels = boardLabels,
            // A row still pointing at a rally that undo took back would put the next
            // label on a point that no longer exists, so the clamp lives here rather
            // than in every caller.
            pendingTagOrdinal = pendingOrdinal?.takeIf { match != null && it < match.pointCount },
            canScore = match?.isOver == false,
            canUndo = (match?.pointCount ?: 0) > 0,
            canSwitchFirstServer = log != null && log.isPlayable() && log.events.isEmpty(),
            hasAnyLabels = allLabels.isNotEmpty(),
            undoneNotice = live?.let { log?.undoneNotice(it) },
            undone = live,
        )
    }

    /** One tap. The tag row follows to the rally just played. */
    fun score(side: Side) {
        scorer.score(side)
        lastUndo.value = null
        pending.value = lastOrdinal()
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

    fun updateDetails(title: String, format: MatchFormat?, homePlayers: List<String>, awayPlayers: List<String>) {
        scorer.updateDetails(title, format, homePlayers, awayPlayers)
    }

    fun switchFirstServer() {
        scorer.switchFirstServer()
    }

    /**
     * Takes back the last rally. The tag row goes back to the rally before it, so
     * the board looks exactly as it did the moment that rally was scored.
     */
    fun undo() {
        lastUndo.value = scorer.undo() ?: return
        pending.value = lastOrdinal()
    }

    /** Puts back what the last undo took, as long as nothing has happened since. */
    fun redo() {
        val undone = lastUndo.value ?: return
        lastUndo.value = null
        if (!scorer.canRedo(undone)) return
        scorer.redo(undone)
        pending.value = lastOrdinal()
    }

    /** The notice timed out. The undo stands. */
    fun dismissUndoNotice() {
        lastUndo.value = null
    }

    fun resetCurrentGame() {
        scorer.resetCurrentGame()
        lastUndo.value = null
        pending.value = null
    }

    fun finish() {
        scorer.finish()
        lastUndo.value = null
        pending.value = null
    }

    /**
     * Read straight through the scorer rather than off [state], because a tap and
     * the tag that follows it can both land before the state flow has emitted once.
     */
    private fun lastOrdinal(): Int? = scorer.state()?.pointCount?.minus(1)?.takeIf { it >= 0 }

    private fun pointAt(ordinal: Int) =
        scorer.state()?.points?.getOrNull(ordinal)
}
