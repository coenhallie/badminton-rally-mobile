package com.badmintontracker.shared.players

import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.MONTHS
import com.badmintontracker.shared.matchlist.formatMatchDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The matches that belong to [playerId]: the coach's own matches whose record
 * names the player on either side, in the list's order. A shared match never
 * belongs to one of this coach's players, whatever its ids say.
 */
fun MatchList.forPlayer(playerId: String): List<MatchListItem> =
    mine.filter { it.homePlayerId == playerId || it.awayPlayerId == playerId }

/**
 * What a player's row and profile tiles say about their matches. Computed from
 * [forPlayer], the same list the Matches tab shows, so the three cannot disagree.
 */
data class PlayerSummary(
    val matchCount: Int,
    val rallyCount: Int,
    /** When the newest match was played (the list's own sort time), or null with no matches. */
    val lastMatchAtEpochMs: Long?,
)

fun playerSummary(list: MatchList, playerId: String): PlayerSummary {
    val matches = list.forPlayer(playerId)
    return PlayerSummary(
        matchCount = matches.size,
        rallyCount = matches.sumOf { it.rallyCount },
        lastMatchAtEpochMs = matches.maxOfOrNull { it.sortAtEpochMs },
    )
}

/**
 * The player row's detail: "12 matches · last Oct 28" for a date in the current
 * year, "12 matches · last Oct 28, 2025" for an earlier one, or "No matches yet".
 * "Now" is read here; the overload below takes it, for tests.
 */
fun playerRowDetail(summary: PlayerSummary): String =
    playerRowDetail(summary, Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault())

fun playerRowDetail(summary: PlayerSummary, nowEpochMs: Long, timeZone: TimeZone): String =
    PlayersCopy.rowDetail(
        summary.matchCount,
        summary.lastMatchAtEpochMs?.let { at ->
            if (sameYear(at, nowEpochMs, timeZone)) dayAndMonth(at, timeZone) else formatMatchDate(at, timeZone)
        },
    )

/**
 * The "Last match" tile's value: "Oct 28" for a date in the current year,
 * "Oct '25" for an earlier one, or "None yet". Short in both cases, so it
 * keeps to one line in a tile a third of a phone wide. "Now" is read here;
 * the overload below takes it, for tests.
 */
fun lastMatchTile(summary: PlayerSummary): String =
    lastMatchTile(summary, Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault())

fun lastMatchTile(summary: PlayerSummary, nowEpochMs: Long, timeZone: TimeZone): String {
    val at = summary.lastMatchAtEpochMs ?: return PlayersCopy.LAST_MATCH_NONE
    if (sameYear(at, nowEpochMs, timeZone)) return dayAndMonth(at, timeZone)
    val d = localDate(at, timeZone)
    return "${MONTHS[d.month.number - 1]} '${(d.year % 100).toString().padStart(2, '0')}"
}

private fun localDate(epochMs: Long, timeZone: TimeZone) =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date

private fun sameYear(epochMs: Long, nowEpochMs: Long, timeZone: TimeZone): Boolean =
    localDate(epochMs, timeZone).year == localDate(nowEpochMs, timeZone).year

/** "Oct 28": the list's own date without its year. */
private fun dayAndMonth(epochMs: Long, timeZone: TimeZone): String {
    val d = localDate(epochMs, timeZone)
    return "${MONTHS[d.month.number - 1]} ${d.day}"
}
