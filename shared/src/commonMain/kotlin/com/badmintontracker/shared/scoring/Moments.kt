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
