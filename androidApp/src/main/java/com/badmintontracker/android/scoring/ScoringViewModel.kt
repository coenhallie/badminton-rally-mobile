package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.scoring.BoardNotice
import com.badmintontracker.shared.scoring.BoardTarget
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchScorer
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.MomentDraft
import com.badmintontracker.shared.scoring.PointTag
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.actionLabel
import com.badmintontracker.shared.scoring.boardCaption
import com.badmintontracker.shared.scoring.boardTarget
import com.badmintontracker.shared.scoring.noticeMessage
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
    /** Whether the board offers the score editor. See [MatchScorer.canEditScore]. */
    val canEditScore: Boolean = false,
    /** True until the first point: the window in which the board can hand the opening serve across. */
    val canSwitchFirstServer: Boolean = false,
    /**
     * Whether the account has any labels at all, board-scoped or not. Lets the
     * board tell "you have not made any labels" apart from "none of yours are
     * on the board", which are different problems with different fixes.
     */
    val hasAnyLabels: Boolean = false,
    /** What a chip tap does: tag [pendingTagOrdinal]'s rally, or log a moment. Null with no match. */
    val target: BoardTarget? = null,
    /** The open moment composer. Only ever open while [target] is [BoardTarget.Moments]. */
    val momentDraft: MomentDraft? = null,
    /** The line beside Edit score. See [boardCaption]. */
    val caption: String = "",
    /** Whether the menu offers "Remove last moment". */
    val canRemoveMoment: Boolean = false,
    /**
     * The strip's notice while its action can still do what it says, for example
     * "Moment added: Good shot" with Undo. A new one restarts the timer even when
     * the words match.
     */
    val notice: BoardNotice? = null,
    val noticeMessage: String? = null,
    val noticeAction: String? = null,
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

    // Opens on the last rally, so a board reopened mid-match tags it, as the
    // board would have the moment it was scored.
    private val pending = MutableStateFlow(lastOrdinal())
    private val draft = MutableStateFlow<MomentDraft?>(null)
    private val lastNotice = MutableStateFlow<BoardNotice?>(null)

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
        combine(scoreLogs.logs, labels.scoreboardLabels, labels.labels, ::Triple),
        pending, draft, lastNotice,
    ) { (logs, board, all), p, d, n -> build(logs, board, all, p, d, n) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            build(scoreLogs.logs.value, labels.scoreboardLabels.value, labels.labels.value, pending.value, draft.value, lastNotice.value),
        )

    private fun build(
        logs: List<ScoreLog>,
        boardLabels: List<AnnotationLabel>,
        allLabels: List<AnnotationLabel>,
        pendingOrdinal: Int?,
        openDraft: MomentDraft?,
        notice: BoardNotice?,
    ): ScoringUiState {
        val log = logs.firstOrNull { it.id == scoreLogId }
        val match = log?.state()
        val target = match?.let { boardTarget(it, pendingOrdinal) }
        val liveDraft = openDraft?.takeIf { target == BoardTarget.Moments }
        // Read against the log rather than cleared by each caller, so a change
        // from anywhere - a rally, a tag, a sync - retires the notice with it.
        val liveNotice = notice?.takeIf { log != null && scorer.isLive(it) }
        return ScoringUiState(
            log = log,
            match = match,
            labels = boardLabels,
            pendingTagOrdinal = (target as? BoardTarget.Rally)?.ordinal,
            target = target,
            momentDraft = liveDraft,
            caption = if (match != null && target != null) boardCaption(match, target, boardLabels.isNotEmpty(), liveDraft) else "",
            canScore = match?.isOver == false,
            canUndo = (match?.pointCount ?: 0) > 0,
            canEditScore = log != null && scorer.canEditScore(),
            canSwitchFirstServer = log != null && scorer.canSwitchFirstServer(),
            hasAnyLabels = allLabels.isNotEmpty(),
            canRemoveMoment = match?.moments?.isNotEmpty() == true,
            notice = liveNotice,
            noticeMessage = liveNotice?.let { log?.noticeMessage(it) },
            noticeAction = liveNotice?.actionLabel,
        )
    }

    /** One tap. The tag row follows to the rally just played. */
    fun score(side: Side) {
        commitMomentDraft()
        scorer.score(side)
        lastNotice.value = null
        pending.value = lastOrdinal()
    }

    /**
     * A chip tap while the row targets moments: a check on the composer's moment
     * while it is open, otherwise a moment of its own.
     */
    fun tapMomentLabel(label: AnnotationLabel) {
        val tag = PointTag(labelName = label.name, labelColor = label.colorKey)
        draft.value?.let { draft.value = it.toggle(tag); return }
        lastNotice.value = BoardNotice.MomentAdded(scorer.logMoment(listOf(tag)) ?: return)
    }

    /** Only while the row targets moments, so a draft never waits behind a rally to resurface later. */
    fun openMomentDraft() {
        val match = scorer.state() ?: return
        if (boardTarget(match, pending.value) != BoardTarget.Moments) return
        if (draft.value == null) draft.value = scorer.openMomentDraft()
    }

    /** Kept here so the draft can be saved by a score tap; the field reads its own copy. */
    fun setMomentDraftText(text: String) {
        draft.value = draft.value?.withText(text)
    }

    fun commitMomentDraft() {
        val open = draft.value ?: return
        draft.value = null
        scorer.commitMomentDraft(open)?.let { lastNotice.value = BoardNotice.MomentAdded(it) }
    }

    fun removeLastMoment() {
        val last = scorer.lastMoment() ?: return
        lastNotice.value = BoardNotice.MomentRemoved(scorer.removeMoment(last.id) ?: return)
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
        commitMomentDraft()
        lastNotice.value = BoardNotice.RallyUndone(scorer.undo() ?: return)
        pending.value = lastOrdinal()
    }

    /** The strip's action: Redo after an undo, Undo after a moment was added or removed. */
    fun reverseNotice() {
        val notice = lastNotice.value ?: return
        lastNotice.value = null
        // Reversed before the composer is saved: saving changes the log, which
        // would retire a Redo that was still waiting.
        scorer.reverse(notice)
        commitMomentDraft()
        if (notice is BoardNotice.RallyUndone) pending.value = lastOrdinal()
    }

    /** The notice timed out. What it reported stands. */
    fun dismissNotice() {
        lastNotice.value = null
    }

    /**
     * The score editor's save. The tag row follows to the last rally, as it does
     * after a tap, so a rally just added can be tagged straight away.
     */
    fun editScore(home: Int, away: Int, nextServer: Side?) {
        commitMomentDraft()
        if (!scorer.editScore(home, away, nextServer)) return
        lastNotice.value = null
        pending.value = lastOrdinal()
    }

    fun resetCurrentGame() {
        commitMomentDraft()
        scorer.resetCurrentGame()
        lastNotice.value = null
        pending.value = null
    }

    fun finish() {
        commitMomentDraft()
        scorer.finish()
        lastNotice.value = null
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
