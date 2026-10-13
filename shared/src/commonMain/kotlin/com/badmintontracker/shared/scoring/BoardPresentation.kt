package com.badmintontracker.shared.scoring

/**
 * What a chip tap on the board does. One rule for both platforms: the row tags the
 * last rally whenever there is one to tag, and logs moments when there is not.
 */
sealed class BoardTarget {
    data class Rally(val ordinal: Int) : BoardTarget()
    data object Moments : BoardTarget()
}

/** [pendingOrdinal] if it still names a rally, otherwise moments. */
fun boardTarget(match: MatchState, pendingOrdinal: Int?): BoardTarget =
    pendingOrdinal?.takeIf { it in 0 until match.pointCount }?.let { BoardTarget.Rally(it) } ?: BoardTarget.Moments

/**
 * What the row is pointing at, so a tap on a chip is never a guess. Was written
 * once per platform (`tagRowCaption`, `ScoringView.caption`); one copy now.
 */
fun boardCaption(match: MatchState, target: BoardTarget, hasBoardLabels: Boolean, draft: MomentDraft?): String {
    if (draft != null) {
        return if (draft.tags.isEmpty()) "New moment"
        else "New moment: " + draft.tags.joinToString(", ") { it.labelName }
    }
    val moments = match.moments.size
    val logged = if (moments == 1) "1 moment logged" else "$moments moments logged"
    return when (target) {
        is BoardTarget.Rally -> if (match.isOver) "Match over" else match.points[target.ordinal].let {
            "Point ${it.ordinal + 1}: ${it.scoreAfter.home}-${it.scoreAfter.away}"
        }
        BoardTarget.Moments -> when {
            match.isOver -> if (moments == 0) "Match over" else "Match over · $logged"
            moments > 0 -> logged
            // With no chips there is nothing to log a moment with, so the hint
            // stays the one it was.
            !hasBoardLabels -> if (match.points.isEmpty()) "Tap a side to score, or SERVE to switch server" else "Tap a side to score"
            else -> "Tap a side to score, or a label to log a moment"
        }
    }
}

/**
 * What the board's strip says for a few seconds after something it cannot show on
 * its own, and the one action that reverses it.
 */
sealed class BoardNotice {
    data class RallyUndone(val undone: UndoneRally) : BoardNotice()
    data class MomentAdded(val moment: LoggedMoment) : BoardNotice()
    data class MomentRemoved(val moment: LoggedMoment) : BoardNotice()
}

fun ScoreLog.noticeMessage(notice: BoardNotice): String = when (notice) {
    is BoardNotice.RallyUndone -> undoneNotice(notice.undone)
    is BoardNotice.MomentAdded -> "Moment added" + notice.moment.labelSuffix()
    is BoardNotice.MomentRemoved -> "Moment removed" + notice.moment.labelSuffix()
}

val BoardNotice.actionLabel: String
    get() = if (this is BoardNotice.RallyUndone) "Redo" else "Undo"

private fun LoggedMoment.labelSuffix(): String =
    if (tags.isEmpty()) "" else tags.joinToString(", ", prefix = ": ") { it.labelName }
