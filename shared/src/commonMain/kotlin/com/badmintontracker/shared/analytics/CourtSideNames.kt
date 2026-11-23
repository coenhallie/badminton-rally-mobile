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
 * hand set; [nearPlayerId] and [farPlayerId] carry the matched profile ID on
 * each end so match-suggested drill sessions can credit that player's log.
 */
data class CourtSideNames(
    val near: String,
    val far: String,
    val nearArm: RacketArm?,
    val farArm: RacketArm?,
    val nearPlayerId: String? = null,
    val farPlayerId: String? = null,
) {
    /** Four-argument convenience constructor for Swift and tests without player IDs. */
    constructor(
        near: String,
        far: String,
        nearArm: RacketArm?,
        farArm: RacketArm?,
    ) : this(near, far, nearArm, farArm, null, null)
}

/**
 * The names of the two ends of [item]'s court, or null when the match does not
 * say which end Home was at, or names nobody on either side (the toggle then
 * reads "Near" / "Far", never "Opponent" twice). A side with no name next to one
 * with a name is "Opponent". [players] is the coach's profile list, for the
 * hand and matched profile id: the item's names are already resolved, its ids
 * are validated against loaded profiles.
 */
fun courtSideNames(item: MatchListItem, players: List<Player>): CourtSideNames? {
    val homeAtNear = when (item.nearSide) {
        Side.HOME -> true
        Side.AWAY -> false
        null -> return null
    }
    fun matchedProfile(playerId: String?): Player? =
        playerId?.let { id -> players.firstOrNull { it.id == id } }
    fun named(names: List<String>): String = names.filter { it.isNotBlank() }.joinToString(" / ")

    val homeNamed = named(item.homeNames)
    val awayNamed = named(item.awayNames)
    if (homeNamed.isEmpty() && awayNamed.isEmpty()) return null
    val home = homeNamed.ifEmpty { PlayersCopy.OPPONENT }
    val away = awayNamed.ifEmpty { PlayersCopy.OPPONENT }
    val homeProfile = matchedProfile(item.homePlayerId)
    val awayProfile = matchedProfile(item.awayPlayerId)
    return if (homeAtNear) {
        CourtSideNames(home, away, homeProfile?.racketArm, awayProfile?.racketArm, homeProfile?.id, awayProfile?.id)
    } else {
        CourtSideNames(away, home, awayProfile?.racketArm, homeProfile?.racketArm, awayProfile?.id, homeProfile?.id)
    }
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

/** The profile id of whoever is at [side], when the ends are known and linked to a loaded profile. */
fun profilePlayerId(side: CourtSide, names: CourtSideNames?): String? = when (side) {
    CourtSide.NEAR -> names?.nearPlayerId
    CourtSide.FAR -> names?.farPlayerId
}
