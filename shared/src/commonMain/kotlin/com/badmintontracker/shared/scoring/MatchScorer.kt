package com.badmintontracker.shared.scoring

/**
 * Everything that can change a match, in one place.
 *
 * Both platforms drive the courtside surface through this rather than reaching for
 * [ScoreLogsRepository.replaceEvents] themselves, so "undo" and "reset game" cannot
 * come to mean two different things on two phones. It deliberately publishes no
 * flow of its own: the surface observes [ScoreLogsRepository.logs], which it
 * already does for the match list, and folds through [ScoreLog.state].
 *
 * Every method is a no-op when the match is not on this device. That is reachable
 * two ways - deleted from another screen while this one is open, and a cold start
 * before the cache has loaded - and neither is worth a crash on a bench.
 */
class MatchScorer(
    private val repo: ScoreLogsRepository,
    private val scoreLogId: String,
) {

    /** Null when the match is not on this device. */
    fun state(): MatchState? = repo.get(scoreLogId)?.state()

    /** False once the match has a winner, so a stray tap is never recorded as a rally. */
    fun canScore(): Boolean = state()?.isOver == false

    /** True whenever there is anything at all to take back, a finished match included. */
    fun canUndo(): Boolean = repo.get(scoreLogId)?.events?.isNotEmpty() == true

    fun score(side: Side) {
        if (!canScore()) return
        edit { it + ScoreEvent.PointTo(side) }
    }

    /**
     * Sets the tags and comment on point [ordinal], replacing whatever was there.
     * An ordinal rather than "the last point", because a comment written at the
     * interval belongs to a rally three points ago.
     */
    fun tagPoint(ordinal: Int, tags: List<PointTag>, comment: String?) =
        edit { it.tagPoint(ordinal, tags, comment) }

    /** Drops the last entry, which is the tag if one was just added, and the point otherwise. */
    fun undo() = edit { it.undoLast() }

    /**
     * Clears the game being played and leaves every finished game alone. The cut is
     * by ordinal rather than by position in the list precisely so the tags on
     * earlier games keep naming the points they named.
     */
    fun resetCurrentGame() {
        val state = state() ?: return
        edit { it.dropPointsFrom(firstOrdinalOfCurrentGame(state)) }
    }

    /** Ends the match as [ScoreLogStatus.UNBOUND] - a terminal state, not a half-done one. */
    fun finish() {
        if (repo.get(scoreLogId) == null) return
        repo.finish(scoreLogId)
    }

    /**
     * Where a reset cuts. When the current game has no points yet - the instant
     * after a game ends - this is the match's point count, so the cut is a no-op
     * rather than eating the game that just finished.
     */
    fun firstOrdinalOfCurrentGame(state: MatchState): Int =
        state.points.firstOrNull { it.gameIndex == state.gameIndex }?.ordinal ?: state.pointCount

    private fun edit(transform: (List<ScoreEvent>) -> List<ScoreEvent>) {
        val log = repo.get(scoreLogId) ?: return
        repo.replaceEvents(scoreLogId, transform(log.events))
    }
}
