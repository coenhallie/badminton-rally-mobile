package com.badmintontracker.shared.scoring

/**
 * Which service court a serve is delivered from. Right when the server's own side
 * score is even, left when it is odd.
 */
enum class ServiceCourt { RIGHT, LEFT }

/**
 * Which member of a pair. An enum rather than an index because a nullable Kotlin
 * Int reaches Swift as KotlinInt?, and "which of the two" is not arithmetic.
 */
enum class PairPlayer {
    FIRST, SECOND;

    val other: PairPlayer get() = if (this == FIRST) SECOND else FIRST
}

/**
 * A pair of per-side integers: points within a game, or games won within a match.
 * One type for both because they are the same shape and the same accessor, and two
 * near-identical pair types is two places to get [of] backwards.
 */
data class SideScore(val home: Int, val away: Int) {
    fun of(side: Side): Int = if (side == Side.HOME) home else away

    fun plusOne(side: Side): SideScore =
        if (side == Side.HOME) copy(home = home + 1) else copy(away = away + 1)

    companion object { val ZERO = SideScore(home = 0, away = 0) }
}

/**
 * What a match needs beyond its rules before a point can be scored. Deliberately
 * not the match's name, its players or its date: those belong to the match record
 * that L1 creates, and the fold must not need any of them to produce a score.
 */
data class MatchSetup(
    val doubles: Boolean,
    /** The side serving the first point of the first game. The coin toss sets this. */
    val firstServer: Side,
    /**
     * Doubles only: which player of each side starts a game in the right service
     * court. Reset at every game start, because a pair may rearrange between games
     * and the app has no way to know that it did - a stale arrangement is a wrong
     * name on screen, and resetting keeps it from also being a wrong name in the
     * game after that.
     *
     * These are the one place this package allows a default argument: they are
     * meaningless in singles. Swift has no defaults and passes all four.
     */
    val homeStartsRight: PairPlayer = PairPlayer.FIRST,
    val awayStartsRight: PairPlayer = PairPlayer.FIRST,
)

/**
 * One played point, as the fold resolved it.
 *
 * This is the unit L1's history view renders and the unit L2 binds to a rally
 * clip - [ordinal] is the binding key, since point N maps to rally_index N - and
 * [scoreBefore], [servedBy], [gameIndex] and [wonBy] are exactly the four values
 * L2 denormalises onto rally_clips as score_at_start, serving_side, game_index and
 * point_won_by.
 */
data class ScoredPoint(
    val ordinal: Int,
    val gameIndex: Int,
    val wonBy: Side,
    /** The game's score before the rally. */
    val scoreBefore: SideScore,
    val scoreAfter: SideScore,
    /** The side that served the rally, which is whoever won the one before it. */
    val servedBy: Side,
    val tags: List<PointTag>,
    val comment: String?,
)

/**
 * The whole state of a match, derived and never stored. Produced only by
 * [foldMatchState].
 */
data class MatchState(
    val rules: ScoringRules,
    val setup: MatchSetup,
    /** Points in the game being played, or in the final game once the match is over. */
    val currentGame: SideScore,
    /** Zero-based index of [currentGame]. */
    val gameIndex: Int,
    /** The final score of each finished game, in order. */
    val completedGames: List<SideScore>,
    val gamesWon: SideScore,
    val points: List<ScoredPoint>,
    /** Null once the match is over. */
    val server: Side?,
    /** Null once the match is over. */
    val serviceCourt: ServiceCourt?,
    /** Doubles only. Null in singles, and null once the match is over. */
    val servingPlayer: PairPlayer?,
    val receivingPlayer: PairPlayer?,
    val winner: Side?,
) {
    val isOver: Boolean get() = winner != null
    val pointCount: Int get() = points.size
}

/**
 * The side that has won [score] under [rules], or null while the game is still
 * live. Public because the game point and match point tests in L1 read more
 * clearly against it than against a folded log, and because keeping the rule in
 * one function is what stops "has this game been won" from being written twice.
 */
fun gameWinner(score: SideScore, rules: ScoringRules): Side? {
    val leader = when {
        score.home > score.away -> Side.HOME
        score.away > score.home -> Side.AWAY
        else -> return null                       // level, so nobody has won
    }
    val top = score.of(leader)
    val chase = score.of(leader.other)
    if (top < rules.pointsToWin) return null
    // The cap ends setting: at the cap the next point takes the game regardless of
    // the lead, which is why it is checked before winBy rather than after.
    if (rules.cap != null && top >= rules.cap) return leader
    return if (top - chase >= rules.winBy) leader else null
}

/**
 * Which member of a pair stands in [court], given which member is currently in
 * that pair's right service court. The pair occupies both courts, so the other
 * player is in the other one - that is the whole rule.
 */
private fun playerIn(court: ServiceCourt, rightCourtPlayer: PairPlayer): PairPlayer =
    if (court == ServiceCourt.RIGHT) rightCourtPlayer else rightCourtPlayer.other

/**
 * Folds a log into the score. The only way a [MatchState] is ever produced: there
 * is no mutable score anywhere in this package, so undo (re-fold a shorter log)
 * and replay (fold from scratch) cannot disagree with the forward path.
 *
 * Total by construction. A log that scores past the end of the match, or tags a
 * point that undo removed, folds to the same state as the log without those
 * entries rather than throwing. Both are prevented by the UI, and neither is worth
 * a crash on a bench between rallies - and a log arriving from another device is
 * not our UI.
 */
fun foldMatchState(
    rules: ScoringRules,
    setup: MatchSetup,
    events: List<ScoreEvent>,
): MatchState {
    // Resolved up front so a tag is order independent: a sync layer that delivers
    // a tag before its point must not fold to a different match. Later entries for
    // the same ordinal overwrite earlier ones, which is what makes re-tagging an
    // append rather than an edit.
    val tagsByOrdinal = HashMap<Int, ScoreEvent.TagPoint>()
    for (event in events) if (event is ScoreEvent.TagPoint) tagsByOrdinal[event.pointOrdinal] = event

    var currentGame = SideScore.ZERO
    var gameIndex = 0
    val completedGames = ArrayList<SideScore>()
    var gamesWon = SideScore.ZERO
    var server = setup.firstServer
    var homeRight = setup.homeStartsRight
    var awayRight = setup.awayStartsRight
    var winner: Side? = null
    val points = ArrayList<ScoredPoint>()

    for (event in events) {
        if (winner != null) break
        when (event) {
            is ScoreEvent.TagPoint -> Unit                      // already resolved above
            is ScoreEvent.Retire -> winner = event.side.other
            is ScoreEvent.PointTo -> {
                val scoreBefore = currentGame
                val scoreAfter = currentGame.plusOne(event.side)
                val tag = tagsByOrdinal[points.size]
                points += ScoredPoint(
                    ordinal = points.size,
                    gameIndex = gameIndex,
                    wonBy = event.side,
                    scoreBefore = scoreBefore,
                    scoreAfter = scoreAfter,
                    servedBy = server,
                    tags = tag?.tags ?: emptyList(),
                    comment = tag?.comment,
                )

                // The serving pair swaps service courts only when it wins the rally,
                // which is exactly why the same player then serves again from the
                // other court. The receiving pair never swaps on winning: the new
                // server is simply whoever is standing in the court their new score
                // calls for.
                if (setup.doubles && event.side == server) {
                    if (server == Side.HOME) homeRight = homeRight.other else awayRight = awayRight.other
                }

                // Rally point scoring: the side that wins the rally serves the next one.
                server = event.side
                currentGame = scoreAfter

                val wonGame = gameWinner(scoreAfter, rules)
                if (wonGame != null) {
                    completedGames += scoreAfter
                    gamesWon = gamesWon.plusOne(wonGame)
                    if (gamesWon.of(wonGame) >= rules.gamesToWin) {
                        // currentGame is deliberately left at the final score rather
                        // than reset: the match page shows how the match ended.
                        winner = wonGame
                    } else {
                        gameIndex += 1
                        currentGame = SideScore.ZERO
                        server = wonGame
                        homeRight = setup.homeStartsRight
                        awayRight = setup.awayStartsRight
                    }
                }
            }
        }
    }

    val servingSide = if (winner != null) null else server
    val court = servingSide?.let {
        if (currentGame.of(it) % 2 == 0) ServiceCourt.RIGHT else ServiceCourt.LEFT
    }
    // A serve crosses diagonally, so the receiver stands in the same-named court on
    // the other side of the net.
    val servingPlayer = if (!setup.doubles || servingSide == null || court == null) null else {
        playerIn(court, if (servingSide == Side.HOME) homeRight else awayRight)
    }
    val receivingPlayer = if (!setup.doubles || servingSide == null || court == null) null else {
        playerIn(court, if (servingSide.other == Side.HOME) homeRight else awayRight)
    }

    return MatchState(
        rules = rules,
        setup = setup,
        currentGame = currentGame,
        gameIndex = gameIndex,
        completedGames = completedGames,
        gamesWon = gamesWon,
        points = points,
        server = servingSide,
        serviceCourt = court,
        servingPlayer = servingPlayer,
        receivingPlayer = receivingPlayer,
        winner = winner,
    )
}
