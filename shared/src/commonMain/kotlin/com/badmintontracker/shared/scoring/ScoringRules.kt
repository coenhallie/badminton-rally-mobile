package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a match is scored.
 *
 * A parameter rather than an enum, because the coach said "15 or 21" and clubs
 * disagree about what a 15 point game is - win by two or a straight race, capped
 * or not, interval or none. A wrong guess about one club's variant then costs a
 * preset, not a redesign.
 *
 * Serializable because the rules travel with the match. The same log folded under
 * different rules is a different score, so the rules are stored beside the log
 * rather than re-derived at read time from whatever the app's current default is.
 */
@Serializable
data class ScoringRules(
    @SerialName("points_to_win")  val pointsToWin: Int,
    /** Lead needed to take a game. 2 for BWF, 1 for a straight race to the target. */
    @SerialName("win_by")         val winBy: Int,
    /** Score at which the lead requirement is dropped and the next point takes the game. Null means no cap. */
                                  val cap: Int? = null,
    /** Score at which a game pauses for the interval. Null means no interval. */
    @SerialName("interval_at")    val intervalAt: Int? = null,
    /** Games one side must win to take the match. 2 is best of three. */
    @SerialName("games_to_win")   val gamesToWin: Int,
    /** Score at which ends change in the deciding game. Null means no mid-game change. */
    @SerialName("change_ends_at") val changeEndsAt: Int? = null,
) {
    init {
        scoringRulesProblem(pointsToWin, winBy, cap, intervalAt, gamesToWin, changeEndsAt)
            ?.let { throw IllegalArgumentException(it) }
    }

    companion object {
        /** BWF: 21, win by two, capped at 30, interval at 11, best of three, ends change at 11 in the third. */
        val BWF_21 = ScoringRules(
            pointsToWin = 21, winBy = 2, cap = 30, intervalAt = 11, gamesToWin = 2, changeEndsAt = 11,
        )

        /** Club 15: win by two, capped at 21, interval at 8. */
        val CLUB_15 = ScoringRules(
            pointsToWin = 15, winBy = 2, cap = 21, intervalAt = 8, gamesToWin = 2, changeEndsAt = 8,
        )

        /** Club 15: first to 15, no setting. */
        val STRAIGHT_15 = ScoringRules(
            pointsToWin = 15, winBy = 1, cap = null, intervalAt = 8, gamesToWin = 2, changeEndsAt = 8,
        )

        /** Declaration order, which is also the order a rules picker renders. */
        val PRESETS: List<ScoringRules> = listOf(BWF_21, CLUB_15, STRAIGHT_15)
    }
}

/**
 * The one place a rule set's own constraints are spelled out, so a settings screen
 * and the constructor above cannot drift into disagreeing about what is playable.
 * Returns a ready to display sentence, or null when the combination is fine.
 *
 * Separate from the constructor because a Kotlin exception crossing the ObjC bridge
 * aborts the iOS app rather than surfacing as an error: Swift calls this first and
 * only constructs once it has returned null.
 */
fun scoringRulesProblem(
    pointsToWin: Int,
    winBy: Int,
    cap: Int?,
    intervalAt: Int?,
    gamesToWin: Int,
    changeEndsAt: Int?,
): String? = when {
    pointsToWin < 1 -> "A game needs at least one point."
    winBy < 1 -> "A game has to be won by at least one point."
    gamesToWin < 1 -> "A match needs at least one game."
    cap != null && cap < pointsToWin -> "The cap cannot be below the target score."
    intervalAt != null && (intervalAt < 1 || intervalAt >= pointsToWin) ->
        "The interval has to fall inside the game."
    changeEndsAt != null && (changeEndsAt < 1 || changeEndsAt >= pointsToWin) ->
        "The change of ends has to fall inside the game."
    else -> null
}
