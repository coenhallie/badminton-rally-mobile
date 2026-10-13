package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.model.LabelCount
import com.badmintontracker.shared.model.LabelRef
import com.badmintontracker.shared.model.rollUpLabels
import kotlinx.datetime.TimeZone

/**
 * How a match was tagged courtside.
 *
 * [taggedPointCount] counts points, [labels] counts labels, and the two differ:
 * one rally can be both a good shot and a forced error. Both numbers are useful and
 * neither is a rounding of the other.
 */
data class ScoreTagSummary(
    val taggedPointCount: Int,
    /** Moments logged, labelled or not. */
    val momentCount: Int,
    val labels: List<LabelCount>,
) {
    val isEmpty: Boolean get() = taggedPointCount == 0 && momentCount == 0

    /** "3 rallies tagged · 6 moments", naming only what there is. */
    val headline: String
        get() = listOfNotNull(
            taggedPointCount.takeIf { it > 0 }?.let { if (it == 1) "1 rally tagged" else "$it rallies tagged" },
            momentCount.takeIf { it > 0 }?.let { if (it == 1) "1 moment" else "$it moments" },
        ).joinToString(" · ")

    companion object { val EMPTY = ScoreTagSummary(taggedPointCount = 0, momentCount = 0, labels = emptyList()) }
}

/**
 * Rolls a match's courtside tags up. Shares [rollUpLabels] with the rally page's
 * summary rather than restating its rules, so the same match cannot report one
 * share here and another there.
 */
fun buildScoreTagSummary(state: MatchState): ScoreTagSummary {
    // Recency is the position in the timeline, so a rally's tags and a moment's
    // share one scale.
    val refs = matchTimeline(state).flatMapIndexed { index, entry ->
        val tags = when (entry) {
            is TimelineEntry.Rally -> entry.point.tags
            is TimelineEntry.Moment -> entry.moment.tags
        }
        tags.mapNotNull { tag ->
            val name = tag.labelName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LabelRef(name = name, colorKey = tag.labelColor, recency = index.toLong(), tieBreak = "")
        }
    }
    val tagged = state.points.count { point -> point.tags.any { it.labelName.isNotBlank() } }
    if (tagged == 0 && state.moments.isEmpty()) return ScoreTagSummary.EMPTY
    return ScoreTagSummary(taggedPointCount = tagged, momentCount = state.moments.size, labels = rollUpLabels(refs))
}

/**
 * The match as plain text, for pasting into a message the same evening. Point
 * numbers are one-based here and only here: this is the one output a person reads
 * as a list rather than an index, and "point 0" is not a thing anyone says.
 */
fun exportMatchText(log: ScoreLog): String = exportMatchText(log, TimeZone.currentSystemDefault())

/** [exportMatchText] with an explicit zone for a moment's clock time. */
fun exportMatchText(log: ScoreLog, zone: TimeZone): String {
    val state = log.state()
    val header = listOfNotNull(log.title, log.playersLine(), scoreLine(state))
    val timeline = matchTimeline(state)
    if (timeline.isEmpty()) return header.joinToString("\n")

    val rows = timeline.map { entry ->
        when (entry) {
            is TimelineEntry.Rally -> {
                val point = entry.point
                val score = "${point.scoreAfter.home}-${point.scoreAfter.away}"
                val tags = point.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
                val comment = point.comment?.trim()?.takeIf { it.isNotEmpty() }
                buildString {
                    append("${point.ordinal + 1}. $score ${log.sideName(point.wonBy)}")
                    if (tags != null) append(" - $tags")
                    if (comment != null) append(" ($comment)")
                }
            }
            is TimelineEntry.Moment -> {
                val m = entry.moment
                val tags = m.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
                val comment = m.comment?.trim()?.takeIf { it.isNotEmpty() }
                val body = when {
                    tags != null && comment != null -> "$tags ($comment)"
                    tags != null -> tags
                    else -> comment.orEmpty()
                }
                "At ${clockTime(m.loggedAt, zone)}: $body"
            }
        }
    }
    return (header + "" + rows).joinToString("\n")
}
