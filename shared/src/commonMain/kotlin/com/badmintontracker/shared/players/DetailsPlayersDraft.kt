package com.badmintontracker.shared.players

import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.playersPerSide

/**
 * Who played, as the match details editor holds it, for either kind of record, so
 * the Android dialog and the iOS sheet only render it and hand taps back.
 *
 * A score log ([byEnds] false) is [sides]: Home and Away, each a profile or typed
 * names, and the end Home was at. A match born as a video ([byEnds] true) is the Who's
 * playing section, [ends], while it is singles; while it is doubles or mixed it is
 * [sides] with typed names only, since a profile is singles only and the section is
 * never built for doubles. [withFormat] carries the typed names across that switch.
 */
data class DetailsPlayersDraft(
    val byEnds: Boolean,
    val sides: SidesDraft,
    val ends: EndsDraft?,
)

/** A score log's editor: its own sides, and [nearSide], the end its attached video says Home was at. */
fun scoreLogPlayersDraft(log: ScoreLog, nearSide: Side?): DetailsPlayersDraft =
    DetailsPlayersDraft(byEnds = false, sides = sidesDraft(log, nearSide), ends = null)

/**
 * A video-born match's editor, from what it says now: the section in singles (or
 * format not set), the typed names by side otherwise.
 */
fun videoPlayersDraft(players: MatchPlayers, nearSide: Side?, format: MatchFormat?): DetailsPlayersDraft =
    DetailsPlayersDraft(
        byEnds = true,
        sides = SidesDraft(null, null, players.homePlayers, players.awayPlayers, nearSide),
        ends = if (playersPerSide(format) == 1) endsDraft(players, nearSide, null) else null,
    )

/**
 * The draft once the format becomes [format]. A score log keeps its sides as they are
 * (its typed partners stay on screen, and only the format's players are saved). A
 * video-born match moving into doubles carries each end's typed name to its side,
 * keeping any partner typed before, and drops the section; moving back to singles
 * builds the section from each side's first name, at the end already said. Moving into
 * doubles is only offered with no profile held ([offersPairs]); asked anyway, the draft
 * is left as it is rather than losing a profile.
 */
fun DetailsPlayersDraft.withFormat(format: MatchFormat?): DetailsPlayersDraft {
    if (!byEnds) return this
    val pairs = playersPerSide(format) == 2
    val current = ends
    return when {
        pairs && current != null -> {
            val (typed, _) = current.toAssignment()
            if (typed.homePlayerId != null || typed.awayPlayerId != null) return this
            copy(
                sides = sides.copy(
                    homeNames = listOf(typed.homePlayers.firstOrNull().orEmpty(), sides.homeNames.getOrElse(1) { "" }),
                    awayNames = listOf(typed.awayPlayers.firstOrNull().orEmpty(), sides.awayNames.getOrElse(1) { "" }),
                    nearSide = current.nearSide,
                ),
                ends = null,
            )
        }
        !pairs && current == null -> copy(
            ends = endsDraft(
                MatchPlayers(null, null, sides.homeNames.take(1), sides.awayNames.take(1)), sides.nearSide, null,
            ),
        )
        else -> this
    }
}

/**
 * What the editor saves under [format]: who played and the side nearest the camera.
 * The section's assignment while it is shown, else the sides, cut to [format]'s
 * players (a video-born match's sides carry no profile).
 */
fun DetailsPlayersDraft.toAssignment(format: MatchFormat?): Pair<MatchPlayers, Side?> {
    val current = ends
    return if (current != null && playersPerSide(format) == 1) {
        current.toAssignment()
    } else {
        sides.toMatchPlayers(format) to sides.nearSide
    }
}

/**
 * Whether Doubles and Mixed are offered: only while nobody on the match is a profile,
 * since a profile is singles only. While they are not, the editor says
 * [PlayersCopy.DOUBLES_NOTE].
 */
fun DetailsPlayersDraft.offersPairs(format: MatchFormat?): Boolean {
    val (players, _) = toAssignment(format)
    return players.homePlayerId == null && players.awayPlayerId == null
}
