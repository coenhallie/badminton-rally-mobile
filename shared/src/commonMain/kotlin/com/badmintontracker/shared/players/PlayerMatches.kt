package com.badmintontracker.shared.players

import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.formatMatchDate
import kotlinx.datetime.TimeZone

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

/** The player row's detail: "12 matches · last Oct 28, 2026", or "No matches yet". */
fun playerRowDetail(summary: PlayerSummary): String = playerRowDetail(summary, TimeZone.currentSystemDefault())

internal fun playerRowDetail(summary: PlayerSummary, timeZone: TimeZone): String =
    PlayersCopy.rowDetail(summary.matchCount, summary.lastMatchAtEpochMs?.let { formatMatchDate(it, timeZone) })

/** The "Last match" tile's value: the date, or "None yet". */
fun lastMatchTile(summary: PlayerSummary): String = lastMatchTile(summary, TimeZone.currentSystemDefault())

internal fun lastMatchTile(summary: PlayerSummary, timeZone: TimeZone): String =
    summary.lastMatchAtEpochMs?.let { formatMatchDate(it, timeZone) } ?: PlayersCopy.LAST_MATCH_NONE
