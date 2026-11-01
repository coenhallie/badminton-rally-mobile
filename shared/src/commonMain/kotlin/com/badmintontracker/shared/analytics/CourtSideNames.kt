package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.Side

/**
 * Who stands at each end of the court, for the Analysis panels' near/far toggle.
 * [near] and [far] are display names; [nearArm] and [farArm] are the playing
 * hand of the profile on that end, null for typed names or a profile with no
 * hand set.
 */
data class CourtSideNames(
    val near: String,
    val far: String,
    val nearArm: RacketArm?,
    val farArm: RacketArm?,
)

/**
 * The names of the two ends of [item]'s court, or null when the match does not
 * say which end Home was at, or names nobody on either side (the toggle then
 * reads "Near" / "Far", never "Opponent" twice). A side with no name next to one
 * with a name is "Opponent". [players] is the coach's profile list, for the
 * hand: the item's names are already resolved, its ids are not. A side with no
 * name has no hand either (a profile this phone cannot name is one it has not
 * loaded), so nothing is lost by the null.
 */
fun courtSideNames(item: MatchListItem, players: List<Player>): CourtSideNames? {
    val homeAtNear = when (item.nearSide) {
        Side.HOME -> true
        Side.AWAY -> false
        null -> return null
    }
    fun arm(playerId: String?): RacketArm? =
        playerId?.let { id -> players.firstOrNull { it.id == id }?.racketArm }
    fun named(names: List<String>): String = names.filter { it.isNotBlank() }.joinToString(" / ")

    val homeNamed = named(item.homeNames)
    val awayNamed = named(item.awayNames)
    if (homeNamed.isEmpty() && awayNamed.isEmpty()) return null
    val home = homeNamed.ifEmpty { PlayersCopy.OPPONENT }
    val away = awayNamed.ifEmpty { PlayersCopy.OPPONENT }
    val homeArm = arm(item.homePlayerId)
    val awayArm = arm(item.awayPlayerId)
    return if (homeAtNear) CourtSideNames(home, away, homeArm, awayArm)
    else CourtSideNames(away, home, awayArm, homeArm)
}

/** What the near/far toggle calls [side]: the player's name, or "Near" / "Far" while the ends are unknown. */
fun sideToggleLabel(side: CourtSide, names: CourtSideNames?): String = when (side) {
    CourtSide.NEAR -> names?.near ?: PlayersCopy.NEAR
    CourtSide.FAR -> names?.far ?: PlayersCopy.FAR
}

/** The playing hand of whoever is at [side], when the ends are known and a profile there has one set. */
fun profileArm(side: CourtSide, names: CourtSideNames?): RacketArm? = when (side) {
    CourtSide.NEAR -> names?.nearArm
    CourtSide.FAR -> names?.farArm
}
