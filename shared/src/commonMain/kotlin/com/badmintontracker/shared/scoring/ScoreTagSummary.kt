package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.model.LabelCount
import com.badmintontracker.shared.model.LabelRef
import com.badmintontracker.shared.model.rollUpLabels

/**
 * How a match was tagged courtside.
 *
 * [taggedPointCount] counts points, [labels] counts labels, and the two differ:
 * one rally can be both a good shot and a forced error. Both numbers are useful and
 * neither is a rounding of the other.
 */
data class ScoreTagSummary(
    val taggedPointCount: Int,
    val labels: List<LabelCount>,
) {
    val isEmpty: Boolean get() = taggedPointCount == 0

    companion object { val EMPTY = ScoreTagSummary(taggedPointCount = 0, labels = emptyList()) }
}

/**
 * Rolls a match's courtside tags up. Shares [rollUpLabels] with the rally page's
 * summary rather than restating its rules, so the same match cannot report one
 * share here and another there.
 */
fun buildScoreTagSummary(state: MatchState): ScoreTagSummary {
    val refs = state.points.flatMap { point ->
        point.tags.mapNotNull { tag ->
            val name = tag.labelName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Ordinals are unique across a match, so the tie break is never consulted.
            LabelRef(name = name, colorKey = tag.labelColor, recency = point.ordinal.toLong(), tieBreak = "")
        }
    }
    if (refs.isEmpty()) return ScoreTagSummary.EMPTY
    return ScoreTagSummary(
        taggedPointCount = state.points.count { point ->
            point.tags.any { it.labelName.isNotBlank() }
        },
        labels = rollUpLabels(refs),
    )
}

/**
 * The match as plain text, for pasting into a message the same evening. Point
 * numbers are one-based here and only here: this is the one output a person reads
 * as a list rather than an index, and "point 0" is not a thing anyone says.
 */
fun exportMatchText(log: ScoreLog): String {
    val state = log.state()
    val header = listOf(
        log.title,
        "${sideLabel(log.homePlayers)} vs ${sideLabel(log.awayPlayers)}",
        scoreLine(state),
    )
    if (state.points.isEmpty()) return header.joinToString("\n")

    val rows = state.points.map { point ->
        val winner = if (point.wonBy == Side.HOME) sideLabel(log.homePlayers) else sideLabel(log.awayPlayers)
        val score = "${point.scoreAfter.home}-${point.scoreAfter.away}"
        val tags = point.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
        val comment = point.comment?.trim()?.takeIf { it.isNotEmpty() }
        buildString {
            append("${point.ordinal + 1}. $score $winner")
            if (tags != null) append(" - $tags")
            if (comment != null) append(" ($comment)")
        }
    }
    return (header + "" + rows).joinToString("\n")
}
