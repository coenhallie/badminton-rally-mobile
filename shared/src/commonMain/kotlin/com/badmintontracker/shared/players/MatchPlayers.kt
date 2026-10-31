package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.PLAYER_NAME_TOO_LONG
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.Side

/**
 * Who is on each side of a match: a profile ([homePlayerId], [awayPlayerId]) or the
 * names typed in ([homePlayers], [awayPlayers]). A score log, a local video entry
 * and a videos row all carry the same four fields, so this is the one value the
 * assignment travels as. It is a plain value: the database refuses a side that is
 * both, and a profile outside singles, and the editor that builds one is where
 * those rules are kept.
 */
data class MatchPlayers(
    val homePlayerId: String?,
    val awayPlayerId: String?,
    val homePlayers: List<String>,
    val awayPlayers: List<String>,
) {
    companion object {
        /** Nobody assigned, nobody typed. */
        val NONE = MatchPlayers(null, null, emptyList(), emptyList())
    }
}

val ScoreLog.matchPlayers: MatchPlayers
    get() = MatchPlayers(homePlayerId, awayPlayerId, homePlayers, awayPlayers)

val LocalVideoEntry.matchPlayers: MatchPlayers
    get() = MatchPlayers(homePlayerId, awayPlayerId, homePlayers, awayPlayers)

/** The typed names, not [MatchMetadata.homeNames]: those are the RPC's resolved display names. */
val MatchMetadata.matchPlayers: MatchPlayers
    get() = MatchPlayers(homePlayerId, awayPlayerId, homePlayers, awayPlayers)

/**
 * What a deleted player leaves behind: the side that held [playerId] keeps [name]
 * as a typed name and loses the id, so the match still reads as it did.
 */
fun MatchPlayers.forgetting(playerId: String, name: String): MatchPlayers = copy(
    homePlayerId = if (homePlayerId == playerId) null else homePlayerId,
    homePlayers = if (homePlayerId == playerId) listOf(name) else homePlayers,
    awayPlayerId = if (awayPlayerId == playerId) null else awayPlayerId,
    awayPlayers = if (awayPlayerId == playerId) listOf(name) else awayPlayers,
)

/** Whether either side holds [playerId]. */
fun MatchPlayers.holds(playerId: String): Boolean =
    homePlayerId == playerId || awayPlayerId == playerId

/**
 * The names [side] is shown with: the profile's name when the side holds one, and
 * the typed names otherwise. A profile that is not in [players] has no name to show
 * (it is gone, or this phone has not loaded it), so it yields an empty list rather
 * than a stale typed name.
 */
fun MatchPlayers.names(side: Side, players: List<Player>): List<String> {
    val id = if (side == Side.HOME) homePlayerId else awayPlayerId
    if (id == null) return if (side == Side.HOME) homePlayers else awayPlayers
    val player = players.firstOrNull { it.id == id } ?: return emptyList()
    return listOf(player.name)
}

/**
 * Why [players] cannot be saved under [format], as a ready to display sentence, or
 * null when it can. The database refuses the same shapes with a CHECK; this is the
 * sentence the editor shows first. In order: a profile in doubles or mixed (singles
 * only, and "nobody said" counts as singles), the same profile on both sides, a side
 * holding a profile and typed names, then a typed name past [MAX_PLAYER_NAME].
 */
fun matchPlayersProblem(players: MatchPlayers, format: MatchFormat?): String? {
    val hasProfile = players.homePlayerId != null || players.awayPlayerId != null
    return when {
        hasProfile && format != null && format != MatchFormat.SINGLES -> PlayersCopy.DOUBLES_NOTE
        players.homePlayerId != null && players.homePlayerId == players.awayPlayerId ->
            PlayersCopy.SAME_PLAYER_BOTH_SIDES
        (players.homePlayerId != null && players.homePlayers.isNotEmpty()) ||
            (players.awayPlayerId != null && players.awayPlayers.isNotEmpty()) -> PlayersCopy.PROFILE_AND_NAMES
        (players.homePlayers + players.awayPlayers).any { it.trim().length > MAX_PLAYER_NAME } ->
            PLAYER_NAME_TOO_LONG
        else -> null
    }
}
