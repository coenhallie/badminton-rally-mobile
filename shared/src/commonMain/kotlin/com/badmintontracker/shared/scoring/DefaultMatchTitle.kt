package com.badmintontracker.shared.scoring

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * The name a new match starts with: when it is being played, and where if the phone
 * could say. "Sep 20, 14:30 - Amsterdam", or "Sep 20, 14:30" without a place.
 *
 * Built here rather than per platform so the two forms prefill the same string. The
 * year is left out because the match list prints the date beside every title, and
 * the 24 hour clock is deliberate: it is a name that gets synced and read on other
 * devices, not a time shown in this device's locale.
 *
 * [place] is cut rather than the whole name rejected when it would not fit: the
 * title is capped at [MAX_MATCH_TITLE], by the form and by the database, and a long
 * place name is no reason to open the form with an error already showing.
 */
fun defaultMatchTitle(now: Instant, zone: TimeZone, place: String?): String {
    val local = now.toLocalDateTime(zone)
    val minute = local.minute.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val time = "${MONTHS[local.monthNumber - 1]} ${local.dayOfMonth}, $hour:$minute"
    val named = place?.trim()?.takeIf { it.isNotEmpty() } ?: return time
    val separator = " - "
    return time + separator + named.take(MAX_MATCH_TITLE - time.length - separator.length).trim()
}

/**
 * The same name from a plain timestamp in the phone's own zone. For Swift, where a
 * Date is one call from epoch milliseconds and a kotlinx TimeZone is not something
 * the form should have to build.
 */
fun defaultMatchTitle(epochMs: Long, place: String?): String =
    defaultMatchTitle(Instant.fromEpochMilliseconds(epochMs), TimeZone.currentSystemDefault(), place)
