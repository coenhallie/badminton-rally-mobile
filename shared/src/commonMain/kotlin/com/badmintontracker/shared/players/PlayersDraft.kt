package com.badmintontracker.shared.players

import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.submittedPlayers

/**
 * What the two editors hold while the coach is choosing, and how it becomes a
 * [MatchPlayers]. Pure values and pure functions, so the Android and iOS screens
 * only render a draft and hand taps back; every rule about which end keeps whom
 * lives here once.
 */

/** How many players the coach is following in a match born as a video. */
enum class Tracked { ONE, BOTH }

/**
 * The "Who's playing" section, as the camera sees it: [nearPlayerId] and
 * [farPlayerId] are the two ends.
 *
 * In [Tracked.ONE] the coach's player is at the near end when [playerAtNear] and at
 * the far end otherwise, and the profile sits in that end's field (the other field
 * is null); [opponentName] is typed for the other end. In [Tracked.BOTH] both ends
 * are profiles and [opponentName] is ignored (it is kept so toggling back to One does
 * not lose what was typed).
 */
data class EndsDraft(
    val tracked: Tracked,
    val nearPlayerId: String?,
    val farPlayerId: String?,
    val opponentName: String,
    val playerAtNear: Boolean,
)

/**
 * Reads an existing assignment back into a draft. [nearSide] is the side the camera
 * saw nearest ([Side.HOME] when nobody said, the convention a video-born match is
 * saved with). Both sides profiles is [Tracked.BOTH]. Otherwise it is [Tracked.ONE]
 * with the profile at its end and the other end's typed name as the opponent; a match
 * with no profile at all keeps a typed name, at whichever end it was on. A blank match
 * starts with [prefillPlayerId] at the near end; a match that already says who is
 * playing ignores the prefill.
 */
fun endsDraft(players: MatchPlayers, nearSide: Side?, prefillPlayerId: String?): EndsDraft {
    val near = nearSide ?: Side.HOME
    val nearId = if (near == Side.HOME) players.homePlayerId else players.awayPlayerId
    val farId = if (near == Side.HOME) players.awayPlayerId else players.homePlayerId
    val nearNames = if (near == Side.HOME) players.homePlayers else players.awayPlayers
    val farNames = if (near == Side.HOME) players.awayPlayers else players.homePlayers

    if (nearId != null && farId != null) return EndsDraft(Tracked.BOTH, nearId, farId, "", playerAtNear = true)
    if (nearId == null && farId == null && nearNames.isEmpty() && farNames.isEmpty()) {
        return EndsDraft(Tracked.ONE, prefillPlayerId, null, "", playerAtNear = true)
    }
    // One player: the opponent is whatever the profile's opposite end carries. With no
    // profile, a name on the far end is the opponent, else the near end's name is, so
    // a typed name survives the round trip.
    val playerAtNear = when {
        nearId != null -> true
        farId != null -> false
        else -> farNames.isNotEmpty() || nearNames.isEmpty()
    }
    val opponentNames = if (playerAtNear) farNames else nearNames
    return EndsDraft(Tracked.ONE, nearId, farId, opponentNames.joinToString(" / "), playerAtNear)
}

/** The ends exchanged. In One mode the player and the opponent trade ends too. */
fun EndsDraft.swapped(): EndsDraft = copy(
    nearPlayerId = farPlayerId,
    farPlayerId = nearPlayerId,
    playerAtNear = if (tracked == Tracked.ONE) !playerAtNear else playerAtNear,
)

/**
 * Switches the toggle. To Both the near player stays and far is left empty to be
 * picked; a One-mode player standing at far is not dropped, since near has nobody to
 * keep. To One whichever end holds a profile stays (near first), and the other end's
 * profile is dropped. Either way the typed opponent is kept.
 */
fun EndsDraft.withTracked(t: Tracked): EndsDraft {
    if (t == tracked) return this
    return when (t) {
        Tracked.BOTH -> copy(tracked = t, farPlayerId = if (nearPlayerId == null) farPlayerId else null)
        Tracked.ONE -> when {
            nearPlayerId != null -> copy(tracked = t, farPlayerId = null, playerAtNear = true)
            farPlayerId != null -> copy(tracked = t, playerAtNear = false)
            else -> copy(tracked = t)
        }
    }
}

/**
 * What to save: home is the near end, away the far end, and the near side is home. A
 * draft with no profile and no name saves nothing ([MatchPlayers.NONE] and a null
 * near side), so a skipped section leaves the match as it was.
 */
fun EndsDraft.toAssignment(): Pair<MatchPlayers, Side?> {
    val opponent = if (tracked == Tracked.ONE) submittedPlayers(listOf(opponentName), null) else emptyList()
    val assignment = MatchPlayers(
        homePlayerId = nearPlayerId,
        awayPlayerId = farPlayerId,
        // The opponent stands at the end opposite the player.
        homePlayers = if (tracked == Tracked.ONE && !playerAtNear) opponent else emptyList(),
        awayPlayers = if (tracked == Tracked.ONE && playerAtNear) opponent else emptyList(),
    )
    return if (assignment == MatchPlayers.NONE) MatchPlayers.NONE to null else assignment to Side.HOME
}

/**
 * Whether to print "Analysis on this phone follows only the near player": a player at
 * the far end is one the phone's analysis does not follow (section 2.3 of the design).
 */
fun EndsDraft.showsDeviceLine(): Boolean = farPlayerId != null

/**
 * The details editor of a score log: each side is a profile or typed names, and
 * [nearSide] is the end Home stood at when a video is attached (null: not set).
 * The names are what is typed, unfiltered; [toMatchPlayers] is where they are cleaned.
 */
data class SidesDraft(
    val homePlayerId: String?,
    val awayPlayerId: String?,
    val homeNames: List<String>,
    val awayNames: List<String>,
    val nearSide: Side?,
)

/** The editor's starting point: the log's own assignment and the end it was filmed from. */
fun sidesDraft(log: ScoreLog, nearSide: Side?): SidesDraft =
    SidesDraft(log.homePlayerId, log.awayPlayerId, log.homePlayers, log.awayPlayers, nearSide)

/**
 * The assignment to save under [format]: typed names are trimmed and cut to the
 * format's players per side, and a side that holds a profile saves none, whatever is
 * still typed in its hidden field.
 */
fun SidesDraft.toMatchPlayers(format: MatchFormat?): MatchPlayers = MatchPlayers(
    homePlayerId = homePlayerId,
    awayPlayerId = awayPlayerId,
    homePlayers = if (homePlayerId != null) emptyList() else submittedPlayers(homeNames, format),
    awayPlayers = if (awayPlayerId != null) emptyList() else submittedPlayers(awayNames, format),
)

/** Whether attaching a video to [log] is followed by "Which end was Home on?": only a profile uses the answer. */
fun asksForHomeEnd(log: ScoreLog): Boolean = log.homePlayerId != null || log.awayPlayerId != null

/**
 * "Which end was {home} on?", with {home} the Home side's display name: the profile's
 * name or the typed names joined with " / ", and "Home" when it has none (or the
 * profile is not in [players]).
 */
fun homeEndTitle(log: ScoreLog, players: List<Player>): String {
    val home = log.matchPlayers.names(Side.HOME, players)
    return PlayersCopy.attachTitle(if (home.isEmpty()) Side.HOME.label else home.joinToString(" / "))
}
