package com.badmintontracker.shared.scoring

/**
 * The score editor's answer for one pair of numbers: whether it can be saved, what
 * saving would do in words, and the log it would write.
 *
 * An edit is expressed in rallies, never as a stored "score set to" entry. Point N
 * of the log is bound to rally clip N, and a new entry kind would stop every app
 * build that predates it from reading the match at all. So raising a side adds
 * rallies it won (they happened, they just went unscored) and lowering a side takes
 * its latest rallies of this game back, tags and note with them.
 */
data class ScoreEditPlan(
    val home: Int,
    val away: Int,
    /** A ready to display sentence when the score cannot be set, null when it can. */
    val problem: String?,
    /** What saving does, as one or two short sentences. Empty when there is a [problem]. */
    val summary: String,
    /** The log after the edit. The current log, unchanged, when there is a [problem]. */
    val events: List<ScoreEvent>,
    /**
     * The sides that can be made to serve next. Both false when nobody serves next,
     * because the edit ends the match. Whoever wins the last rally serves, so a side
     * can only be handed the serve by an edit that gives it a rally.
     */
    val servers: SideFlags,
    /** Who serves once the edit is saved. Null when it ends the match. */
    val server: Side?,
    /** False when the numbers are the score as it stands. */
    val changesScore: Boolean,
) {
    val canSave: Boolean get() = problem == null && changesScore
}

/** Highest number the editor's fields and steppers go to. Two digits, like the board. */
const val MAX_EDIT_SCORE = 99

/** What the editor says while a field has been cleared and not yet typed into. */
const val SCORE_EDIT_BLANK = "Enter a score for both sides."

/** The editor's heading. Names the game only when the match has more than one. */
fun MatchState.scoreEditTitle(): String =
    if (rules.gamesToWin > 1) "Set the score · Game ${gameIndex + 1}" else "Set the score"

/**
 * Plans setting the game being played to [home]-[away]. The game being played is
 * the final one once the match is over, and the fresh 0-0 one the instant after a
 * game ends, exactly as the board shows it. Finished games are never touched.
 *
 * [nextServer] is honoured when that side can be given the serve (see
 * [ScoreEditPlan.servers]); otherwise the side serving now keeps it if it can.
 *
 * Total: every input comes back as a plan, the impossible ones with a
 * [ScoreEditPlan.problem] and the log unchanged.
 */
fun ScoreLog.planScoreEdit(home: Int, away: Int, nextServer: Side?): ScoreEditPlan {
    val state = state()
    fun refused(problem: String) = ScoreEditPlan(
        home = home, away = away, problem = problem, summary = "", events = events,
        servers = SideFlags.NONE, server = state.server, changesScore = false,
    )
    if (home < 0 || away < 0) return refused("A score can't be below 0.")
    if (home > MAX_EDIT_SCORE || away > MAX_EDIT_SCORE) return refused("A score can't be above $MAX_EDIT_SCORE.")

    val target = SideScore(home = home, away = away)
    val game = state.gameIndex
    val gamePoints = state.points.filter { it.gameIndex == game }
    val start = gamePoints.firstOrNull()?.ordinal ?: state.pointCount
    val current = gamePoints.lastOrNull()?.scoreAfter ?: SideScore.ZERO

    // Each side loses its latest rallies of the game: the most recent are the ones
    // a coach correcting the board has in mind.
    val removed = HashSet<Int>()
    for (side in Side.entries) {
        val count = (current.of(side) - target.of(side)).coerceAtLeast(0)
        gamePoints.filter { it.wonBy == side }.takeLast(count).forEach { removed += it.ordinal }
    }
    val kept = gamePoints.filterNot { it.ordinal in removed }
    val add = SideScore(
        home = (home - current.home).coerceAtLeast(0),
        away = (away - current.away).coerceAtLeast(0),
    )

    // Everything before the game stays exactly as written, tags included. The
    // game's kept rallies are rewritten with their tags resolved and renumbered,
    // since a removal shifts every ordinal after it.
    // Moments survive the cut (dropPointsFrom keeps them), but a removal shifts
    // every rally after it, so a moment inside this game is re-anchored after the
    // same kept rallies it followed. The same promise this edit makes for tags.
    val base = events.dropPointsFrom(start).map { event ->
        if (event is ScoreEvent.Moment && event.afterPoints > start) {
            event.copy(afterPoints = start + kept.count { it.ordinal < event.afterPoints })
        } else {
            event
        }
    }
    fun build(added: List<Side>): List<ScoreEvent> {
        val rallies = kept.map { ScoreEvent.PointTo(it.wonBy) } + added.map { ScoreEvent.PointTo(it) }
        val tags = kept.mapIndexedNotNull { i, point ->
            if (point.tags.isEmpty() && point.comment == null) null
            else ScoreEvent.TagPoint(start + i, point.tags, point.comment)
        }
        return base + rallies + tags
    }

    // The fold is the judge rather than a second copy of the rules: the edit is
    // good when every rally from the game's start is still in the game (only the
    // last may finish it) and the game stands at the target.
    val rallyCount = start + kept.size + add.home + add.away
    fun lands(folded: MatchState): Boolean {
        if (folded.pointCount != rallyCount) return false
        if (folded.points.drop(start).any { it.gameIndex != game }) return false
        val finished = folded.completedGames.getOrNull(game)
        return if (finished != null) finished == target else folded.currentGame == target
    }

    // One order per side that could take the last rally; with nothing added there
    // is only the order the kept rallies are already in.
    val from = kept.lastOrNull()?.scoreAfter ?: SideScore.ZERO
    val orders: List<List<Side>> =
        if (add == SideScore.ZERO) listOf(emptyList())
        else Side.entries.filter { add.of(it) > 0 }.mapNotNull { rallyOrder(from, add, it, rules) }
    val options = orders
        .map { build(it) }
        .map { it to foldMatchState(rules, setup, it) }
        .filter { lands(it.second) }
    if (options.isEmpty()) return refused("The game would be over before $home-$away.")

    val (after, afterState) = options.firstOrNull { nextServer != null && it.second.server == nextServer }
        ?: options.firstOrNull { it.second.server == state.server }
        ?: options.first()

    return ScoreEditPlan(
        home = home,
        away = away,
        problem = null,
        summary = describe(gamePoints.filter { it.ordinal in removed }, add, state, afterState, game),
        events = after,
        servers = SideFlags(
            home = options.any { it.second.server == Side.HOME },
            away = options.any { it.second.server == Side.AWAY },
        ),
        server = afterState.server,
        changesScore = target != current,
    )
}

/**
 * An order for [add] rallies, starting from [from], that ends with a rally to
 * [last] and passes through no finished game on the way. Only the last rally may
 * finish one: that is a target like 21-19.
 *
 * A table over every (home added, away added) pair rather than a greedy walk, so
 * an order is found whenever one exists - a capped deuce like 21-20 in a 15-point
 * game leaves very few. Walking back, it takes away the leader's rally first,
 * which keeps the added rallies level the way a real close game runs.
 */
private fun rallyOrder(from: SideScore, add: SideScore, last: Side, rules: ScoringRules): List<Side>? {
    fun score(h: Int, a: Int) = SideScore(home = from.home + h, away = from.away + a)
    val live = Array(add.home + 1) { BooleanArray(add.away + 1) }
    for (h in 0..add.home) for (a in 0..add.away) {
        if (gameWinner(score(h, a), rules) != null) continue
        live[h][a] = (h == 0 && a == 0) || (h > 0 && live[h - 1][a]) || (a > 0 && live[h][a - 1])
    }
    // The score just before the last rally has to be live and reachable.
    var h = add.home - if (last == Side.HOME) 1 else 0
    var a = add.away - if (last == Side.AWAY) 1 else 0
    if (h < 0 || a < 0 || !live[h][a]) return null
    val backwards = arrayListOf(last)
    while (h > 0 || a > 0) {
        val canHome = h > 0 && live[h - 1][a]
        val canAway = a > 0 && live[h][a - 1]
        val home = if (canHome && canAway) score(h, a).home >= score(h, a).away else canHome
        if (home) { backwards += Side.HOME; h -= 1 } else { backwards += Side.AWAY; a -= 1 }
    }
    return backwards.asReversed()
}

/** What saving does, in the words the sheet shows under the numbers. */
private fun ScoreLog.describe(
    removed: List<ScoredPoint>,
    add: SideScore,
    before: MatchState,
    after: MatchState,
    game: Int,
): String {
    fun rallies(n: Int) = if (n == 1) "1 rally" else "$n rallies"
    val adds = Side.entries.filter { add.of(it) > 0 }.map { add.of(it) to it }
    val removes = Side.entries.map { side -> removed.count { it.wonBy == side } to side }.filter { it.first > 0 }

    // "Adds 2 rallies for Home and 1 for Away": the unit is said once per verb.
    fun clause(verb: String, preposition: String, parts: List<Pair<Int, Side>>): String? =
        parts.takeIf { it.isNotEmpty() }?.mapIndexed { i, (n, side) ->
            (if (i == 0) rallies(n) else "$n") + " $preposition ${sideName(side)}"
        }?.joinToString(" and ", prefix = "$verb ")

    val noted = removed.count { it.tags.isNotEmpty() || it.comment != null }
    val removal = clause("removes", "from", removes)?.let {
        when {
            noted == 0 -> it
            noted == removed.size && noted == 1 -> "$it, with its tags or note"
            noted == removed.size -> "$it, with their tags and notes"
            else -> "$it, $noted of them with tags or a note"
        }
    }
    val change = listOfNotNull(clause("adds", "for", adds), removal)
        .joinToString(" and ")
        .replaceFirstChar { it.uppercase() }
        .ifEmpty { return "No change." }

    val winner = after.winner
    val gameWonBy = after.completedGames.getOrNull(game)?.let { gameWinner(it, rules) }
    val outcome = when {
        winner != null -> if (winner != before.winner) "${sideName(winner)} wins the match." else null
        before.winner != null -> "The match is back in play."
        gameWonBy != null -> "${sideName(gameWonBy)} takes game ${game + 1}."
        else -> null
    }
    return listOfNotNull("$change.", outcome).joinToString(" ")
}
