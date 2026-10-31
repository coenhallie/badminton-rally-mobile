package com.badmintontracker.shared.players

import com.badmintontracker.analysis.player.CourtSide
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
 * [farPlayerId] are the two ends' profiles, [nearName] and [farName] the name typed
 * for an end ("" for none). An end holding a profile saves no name, but its typed
 * name stays in the draft so removing the profile brings it back. An end without a
 * profile saves its typed name, in either mode, and always shows it ([field]), so
 * nothing the draft saves or keeps is out of sight.
 *
 * In [Tracked.ONE] the coach's player is at the near end when [playerAtNear] and at
 * the far end otherwise; the other end is the typed opponent. In [Tracked.BOTH] both
 * ends are pickers. A picker end is a profile, or "Someone else" with a typed name:
 * [nearSomeoneElse] and [farSomeoneElse] say it was chosen, so its field stays open
 * while the name is cleared to be retyped. [nearSide] is the side the match already
 * said was nearest the camera, or null while nobody has said.
 *
 * Write the ends through [withPlayer], [withSomeoneElse] and [withName], not `copy`,
 * so the rule about which end holds the profile lives here once. The section is
 * singles only: callers must not build one for a doubles or mixed match, whose editor
 * keeps typed side names.
 */
data class EndsDraft(
    val tracked: Tracked,
    val nearPlayerId: String?,
    val farPlayerId: String?,
    val nearName: String,
    val farName: String,
    val playerAtNear: Boolean,
    val nearSide: Side?,
    val nearSomeoneElse: Boolean = false,
    val farSomeoneElse: Boolean = false,
)

/** What one end of the section draws: see [EndsDraft.field]. */
enum class EndField {
    /** A player picker, showing the end's profile or "Choose a player". */
    PLAYER,

    /** A player picker reading "Someone else", with the end's typed name under it. */
    SOMEONE_ELSE,

    /** One mode's other end: the typed opponent's name, no picker. */
    OPPONENT,
}

/**
 * What [end] draws. In One mode the end away from the player is the typed opponent.
 * Every other end is a picker: its profile when it holds one; "Someone else" and the
 * typed name when it was chosen or the end has a name and no profile, so a typed name
 * is never hidden behind "Choose a player"; and the bare picker otherwise.
 */
fun EndsDraft.field(end: CourtSide): EndField {
    val near = end == CourtSide.NEAR
    if (tracked == Tracked.ONE && playerAtNear != near) return EndField.OPPONENT
    val id = if (near) nearPlayerId else farPlayerId
    val chosen = if (near) nearSomeoneElse else farSomeoneElse
    val name = if (near) nearName else farName
    return when {
        id != null -> EndField.PLAYER
        chosen || name.isNotBlank() -> EndField.SOMEONE_ELSE
        else -> EndField.PLAYER
    }
}

/**
 * Reads an existing assignment back into a draft. [nearSide] is the side the camera
 * saw nearest, null when nobody said; a null is read as home being near for the
 * layout but stays null in the draft, so a save-unchanged does not invent it. A stored
 * [Side.AWAY] reads back with home at the far end and saves back that way. Both sides
 * profiles is [Tracked.BOTH]; anything else is [Tracked.ONE], with the profile (if
 * any) at its end and each end's typed name kept at that end. A blank match starts
 * with [prefillPlayerId] at the near end; a match that already says who is playing
 * ignores the prefill.
 *
 * Singles only: only the first typed name of a side is carried, so callers must not
 * build a draft for a doubles or mixed match.
 */
fun endsDraft(players: MatchPlayers, nearSide: Side?, prefillPlayerId: String?): EndsDraft {
    val near = nearSide ?: Side.HOME
    val nearId = if (near == Side.HOME) players.homePlayerId else players.awayPlayerId
    val farId = if (near == Side.HOME) players.awayPlayerId else players.homePlayerId
    val nearName = (if (near == Side.HOME) players.homePlayers else players.awayPlayers).firstOrNull() ?: ""
    val farName = (if (near == Side.HOME) players.awayPlayers else players.homePlayers).firstOrNull() ?: ""

    if (nearId != null && farId != null) {
        return EndsDraft(Tracked.BOTH, nearId, farId, nearName, farName, playerAtNear = true, nearSide)
    }
    if (nearId == null && farId == null && nearName.isEmpty() && farName.isEmpty()) {
        return EndsDraft(Tracked.ONE, prefillPlayerId, null, "", "", playerAtNear = true, nearSide)
    }
    return EndsDraft(Tracked.ONE, nearId, farId, nearName, farName, playerAtNear = farId == null, nearSide)
}

/** The ends exchanged. In One mode the player and the opponent trade ends too. */
fun EndsDraft.swapped(): EndsDraft = copy(
    nearPlayerId = farPlayerId,
    farPlayerId = nearPlayerId,
    nearName = farName,
    farName = nearName,
    playerAtNear = if (tracked == Tracked.ONE) !playerAtNear else playerAtNear,
    nearSomeoneElse = farSomeoneElse,
    farSomeoneElse = nearSomeoneElse,
)

/**
 * Puts [playerId] (null clears) on [end], which stops being "Someone else". In One
 * mode there is one player: choosing one moves the player to [end] and takes the
 * profile off the other end; clearing leaves where the player stands as it was. In
 * Both mode only [end] changes. A cleared end that still has a typed name shows it
 * again ([field]).
 */
fun EndsDraft.withPlayer(end: CourtSide, playerId: String?): EndsDraft {
    val placed = when {
        tracked == Tracked.BOTH ->
            if (end == CourtSide.NEAR) copy(nearPlayerId = playerId) else copy(farPlayerId = playerId)
        playerId == null ->
            if (end == CourtSide.NEAR) copy(nearPlayerId = null) else copy(farPlayerId = null)
        else -> copy(
            nearPlayerId = if (end == CourtSide.NEAR) playerId else null,
            farPlayerId = if (end == CourtSide.FAR) playerId else null,
            playerAtNear = end == CourtSide.NEAR,
        )
    }
    return if (end == CourtSide.NEAR) placed.copy(nearSomeoneElse = false) else placed.copy(farSomeoneElse = false)
}

/**
 * "Someone else" at [end]: its profile comes off (as [withPlayer] with null, so in One
 * mode the player keeps their end) and its typed name is what it saves.
 */
fun EndsDraft.withSomeoneElse(end: CourtSide): EndsDraft {
    val cleared = withPlayer(end, null)
    return if (end == CourtSide.NEAR) cleared.copy(nearSomeoneElse = true) else cleared.copy(farSomeoneElse = true)
}

/**
 * Sets the name typed for [end]. Typing chooses "Someone else" there, so clearing the
 * field to retype never takes it away.
 */
fun EndsDraft.withName(end: CourtSide, name: String): EndsDraft =
    if (end == CourtSide.NEAR) copy(nearName = name, nearSomeoneElse = true) else copy(farName = name, farSomeoneElse = true)

/**
 * Switches the toggle. To Both the near player stays and far is left empty to be
 * picked; a One-mode player standing at far is not dropped, since near has nobody to
 * keep. To One whichever end holds a profile stays, near first (so a Both draft that
 * gained a near profile after a far one keeps the near one), and the other end's
 * profile is dropped. Typed names are kept either way.
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
 * What to save. An end with a profile saves no name; an end without one saves its
 * typed name, in either mode (it is on screen, see [field]). The side nearest the camera is [EndsDraft.nearSide]
 * when the match had one, else home when the draft holds a profile (a new video's
 * convention), else null: names alone say nothing about which end is which. Home is
 * the near end unless that side is [Side.AWAY]. A draft with no profile and no name
 * saves nothing ([MatchPlayers.NONE] and a null near side), so a skipped section
 * leaves the match as it was.
 */
fun EndsDraft.toAssignment(): Pair<MatchPlayers, Side?> {
    val nearNames = if (nearPlayerId == null) submittedPlayers(listOf(nearName), null) else emptyList()
    val farNames = if (farPlayerId == null) submittedPlayers(listOf(farName), null) else emptyList()
    val side = nearSide ?: if (nearPlayerId != null || farPlayerId != null) Side.HOME else null
    val homeIsNear = side != Side.AWAY
    val assignment = MatchPlayers(
        homePlayerId = if (homeIsNear) nearPlayerId else farPlayerId,
        awayPlayerId = if (homeIsNear) farPlayerId else nearPlayerId,
        homePlayers = if (homeIsNear) nearNames else farNames,
        awayPlayers = if (homeIsNear) farNames else nearNames,
    )
    return if (assignment == MatchPlayers.NONE) MatchPlayers.NONE to null else assignment to side
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
 * [homeSomeoneElse] and [awaySomeoneElse] say "Someone else" was chosen for a side, so
 * its name field stays open while cleared; see [showsName].
 *
 * Write the sides through [withPlayer], [withSomeoneElse] and [withName].
 */
data class SidesDraft(
    val homePlayerId: String?,
    val awayPlayerId: String?,
    val homeNames: List<String>,
    val awayNames: List<String>,
    val nearSide: Side?,
    val homeSomeoneElse: Boolean = false,
    val awaySomeoneElse: Boolean = false,
)

/**
 * Whether [side]'s singles picker reads "Someone else" with the typed name under it:
 * no profile, and it was chosen or the side has a typed name, so a typed name is
 * never hidden behind "Choose a player".
 */
fun SidesDraft.showsName(side: Side): Boolean = if (side == Side.HOME) {
    homePlayerId == null && (homeSomeoneElse || homeNames.any { it.isNotBlank() })
} else {
    awayPlayerId == null && (awaySomeoneElse || awayNames.any { it.isNotBlank() })
}

/** [playerId] on [side], which stops being "Someone else"; its typed names stay, unsaved, for if it is taken off. */
fun SidesDraft.withPlayer(side: Side, playerId: String): SidesDraft =
    if (side == Side.HOME) copy(homePlayerId = playerId, homeSomeoneElse = false)
    else copy(awayPlayerId = playerId, awaySomeoneElse = false)

/** "Someone else" on [side]: no profile, and its typed names are what it saves. */
fun SidesDraft.withSomeoneElse(side: Side): SidesDraft =
    if (side == Side.HOME) copy(homePlayerId = null, homeSomeoneElse = true)
    else copy(awayPlayerId = null, awaySomeoneElse = true)

/**
 * The [index]th name typed for [side] (0 the player, 1 the partner). Typing chooses
 * "Someone else" there, so clearing the field to retype never takes it away.
 */
fun SidesDraft.withName(side: Side, index: Int, name: String): SidesDraft {
    fun List<String>.with(name: String): List<String> =
        List(maxOf(size, index + 1)) { i -> if (i == index) name else getOrElse(i) { "" } }
    return if (side == Side.HOME) copy(homeNames = homeNames.with(name), homeSomeoneElse = true)
    else copy(awayNames = awayNames.with(name), awaySomeoneElse = true)
}

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
    return PlayersCopy.attachTitle(if (home.isEmpty()) PlayersCopy.SIDE_HOME else home.joinToString(" / "))
}
