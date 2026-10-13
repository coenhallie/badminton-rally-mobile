package com.badmintontracker.shared.scoring

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The event that would write [this] back, for putting a removed moment back. */
internal fun LoggedMoment.toEvent(): ScoreEvent.Moment =
    ScoreEvent.Moment(id = id, afterPoints = afterPoints, loggedAt = loggedAt, tags = tags, comment = comment)

/**
 * "19:42". The 24 hour clock for the same reason [defaultMatchTitle] uses it: a
 * moment's time is read beside rally scores, not as a date in the phone's locale.
 */
fun clockTime(at: Instant, zone: TimeZone): String {
    val local = at.toLocalDateTime(zone)
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

/** [clockTime] in the phone's own zone. An overload, not a default: Swift sees no defaults. */
fun clockTime(at: Instant): String = clockTime(at, TimeZone.currentSystemDefault())

/**
 * The composer's moment while Note is open. Held by the board, not the log:
 * nothing is written until it is committed, so typing appends nothing.
 *
 * [openedAt] and [afterPoints] are fixed when it opens, because that is when the
 * thing being written about was seen.
 */
data class MomentDraft(
    val tags: List<PointTag>,
    val text: String,
    val openedAt: Instant,
    val afterPoints: Int,
) {
    val isEmpty: Boolean get() = tags.isEmpty() && text.isBlank()

    /** Adds [tag], or takes it off if it is already on. By name, as a rally's tags are. */
    fun toggle(tag: PointTag): MomentDraft =
        if (tags.any { it.labelName == tag.labelName }) copy(tags = tags.filterNot { it.labelName == tag.labelName })
        else copy(tags = tags + tag)

    fun withText(text: String): MomentDraft = copy(text = text)
}
