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

    /** True whenever there is a rally to take back, a finished match included. */
    fun canUndo(): Boolean = (state()?.pointCount ?: 0) > 0

    /**
     * True until the first point is scored. A new match starts with home serving
     * because the form no longer asks; this is the window in which the board can be
     * told it was the other side that won the toss.
     */
    fun canSwitchFirstServer(): Boolean {
        val log = repo.get(scoreLogId) ?: return false
        return log.isPlayable() && log.events.isEmpty()
    }

    /** Hands the first serve to the other side. A no-op once a point has been scored. */
    fun switchFirstServer() {
        if (!canSwitchFirstServer()) return
        val log = repo.get(scoreLogId) ?: return
        repo.setFirstServer(scoreLogId, log.setup.firstServer.other)
    }

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

    /**
     * Takes back the last rally with its tags and note, and says which rally that
     * was. Null when there was none to take back.
     */
    fun undo(): UndoneRally? {
        val log = repo.get(scoreLogId) ?: return null
        val last = log.state().points.lastOrNull() ?: return null
        // Cut at the rally the board shows rather than the log's last point: a
        // log from elsewhere can run past the end of the match, and the fold
        // ignores what is past it, so that point is not the one being named.
        val after = log.events.dropPointsFrom(last.ordinal)
        repo.replaceEvents(scoreLogId, after)
        return UndoneRally(last.wonBy, last.scoreAfter, before = log.events, after = after)
    }

    /**
     * Whether the board offers the score editor. Not on a match a side retired
     * from: the retirement sits after the game the editor rebuilds, and dropping
     * it would quietly un-retire them. Nothing on the board records one; a log
     * from elsewhere can carry it.
     */
    fun canEditScore(): Boolean {
        val log = repo.get(scoreLogId) ?: return false
        return log.events.none { it is ScoreEvent.Retire }
    }

    /** What setting the game to [home]-[away] would do. Null when the match is not on this device. */
    fun planScoreEdit(home: Int, away: Int, nextServer: Side?): ScoreEditPlan? =
        repo.get(scoreLogId)?.planScoreEdit(home, away, nextServer)

    /**
     * Sets the game being played to [home]-[away], see [ScoreLog.planScoreEdit].
     * False when it could not be saved, and then nothing was written.
     */
    fun editScore(home: Int, away: Int, nextServer: Side?): Boolean {
        if (!canEditScore()) return false
        val plan = planScoreEdit(home, away, nextServer) ?: return false
        if (!plan.canSave) return false
        edit { plan.events }
        return true
    }

    /**
     * True while nothing has changed the match since [undone]. Once anything has -
     * a rally, a tag, a reset, a sync - putting the old log back would erase it.
     */
    fun canRedo(undone: UndoneRally): Boolean = repo.get(scoreLogId)?.events == undone.after

    /** Puts back what [undone] took, tags and note included. A no-op once [canRedo] is false. */
    fun redo(undone: UndoneRally) {
        if (!canRedo(undone)) return
        repo.replaceEvents(scoreLogId, undone.before)
    }

    /**
     * Clears the game being played and leaves every finished game alone. The cut is
     * by ordinal rather than by position in the list precisely so the tags on
     * earlier games keep naming the points they named.
     */
    fun resetCurrentGame() {
        val state = state() ?: return
        edit { it.dropPointsFrom(firstOrdinalOfCurrentGame(state)) }
    }

    /**
     * The details editor's save, from the board. Through here rather than straight
     * to the store for the same reason everything else is: the board has one way to
     * change a match. Never touches the events, so it is safe at any score.
     */
    fun updateDetails(title: String, format: MatchFormat?, homePlayers: List<String>, awayPlayers: List<String>) {
        if (repo.get(scoreLogId) == null) return
        repo.updateDetails(scoreLogId, title, format, homePlayers, awayPlayers)
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

/**
 * What one undo took back, and everything [MatchScorer.redo] needs to put it back.
 * Holds the whole log on either side of the cut rather than the removed entries,
 * so a redo is a comparison and a write rather than a merge.
 */
data class UndoneRally(
    val side: Side,
    /** The game's score as that rally left it, i.e. before the undo. */
    val scoreAfter: SideScore,
    val before: List<ScoreEvent>,
    val after: List<ScoreEvent>,
)

/** The board's one-line confirmation of an undo. Shared so both boards say the same thing. */
fun ScoreLog.undoneNotice(undone: UndoneRally): String = "Point to ${sideName(undone.side)} undone"
