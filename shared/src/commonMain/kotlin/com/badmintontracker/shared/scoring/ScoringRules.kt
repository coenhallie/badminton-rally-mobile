package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a match is scored.
 *
 * A parameter rather than an enum, because clubs disagree about what a game is -
 * its length, win by two or a straight race, capped or not, interval or none. The
 * form offers the few combinations of [GamePoints] and [MatchSets] people actually
 * play, and a variant it does not offer costs an option there, not a redesign here.
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
        val BWF_21 = of(GamePoints.DEFAULT, MatchSets.DEFAULT)

        /**
         * The rules a new match is played under. Total over both enums, so the form
         * never builds a rule set the constructor above would throw on - which
         * matters more than it looks, because a Kotlin throw crossing the ObjC
         * bridge aborts the iOS app.
         */
        fun of(points: GamePoints, sets: MatchSets): ScoringRules = ScoringRules(
            pointsToWin = points.pointsToWin,
            winBy = 2,
            cap = points.cap,
            intervalAt = points.midGame,
            gamesToWin = sets.gamesToWin,
            changeEndsAt = points.midGame,
        )
    }
}

/**
 * The game lengths a new match offers. Every one is win by two with a cap, because
 * that is what each of them means on a club night; a variant nobody asked for is a
 * pill nobody can explain.
 *
 * Declaration order is the order the picker renders.
 */
enum class GamePoints(
    val pointsToWin: Int,
    /** The score at which the next point takes the game regardless of the lead. */
    val cap: Int,
    /** Where the interval falls, and where ends change in a deciding game. */
    val midGame: Int,
) {
    FIFTEEN(pointsToWin = 15, cap = 21, midGame = 8),
    TWENTY_ONE(pointsToWin = 21, cap = 30, midGame = 11),
    ELEVEN(pointsToWin = 11, cap = 15, midGame = 6);

    companion object { val DEFAULT = TWENTY_ONE }
}

/**
 * How many sets a match is played over. "Sets" is the word on the form because it
 * is the word said on court; the engine calls them games, as the laws do.
 *
 * Declaration order is the order the picker renders.
 */
enum class MatchSets(val sets: Int) {
    THREE(sets = 3),
    ONE(sets = 1);

    /** Best of [sets] is won by taking the majority of them. */
    val gamesToWin: Int get() = sets / 2 + 1

    companion object { val DEFAULT = THREE }
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
