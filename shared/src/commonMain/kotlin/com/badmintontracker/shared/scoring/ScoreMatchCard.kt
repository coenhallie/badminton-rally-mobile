package com.badmintontracker.shared.scoring

/**
 * One score-only match as a list row. Every string on it is built here rather than
 * per platform, for the same reason [com.badmintontracker.shared.model.buildMatchLabelSummary]
 * exists: two platforms formatting the same match are two chances to format it
 * differently.
 *
 * [createdAtEpochMs] is milliseconds rather than an Instant because it is a sort
 * key, and iOS already sorts its video matches on a Long.
 */
data class ScoreMatchCard(
    val scoreLogId: String,
    /** The video attached to this match, or null. The list folds rows on it. */
    val videoId: String?,
    val title: String,
    val createdAtEpochMs: Long,
    /** "Coen vs Marco", or null while nobody has been named. */
    val playersLine: String?,
    /** "Not started", "11-9", "21-18, 5-3". */
    val scoreLine: String,
    /** "Scoring" while playable, "<winner> won" once decided, "Ended early" if closed without one. */
    val statusLine: String,
    val isLive: Boolean,
    /** True once a video is attached; false again if it is unbound. */
    val hasVideo: Boolean,
)

/**
 * The match's score as one line: every completed game, then the game in progress.
 *
 * A match nobody has started says so rather than showing 0-0, which reads as a bug
 * on a row. A finished match prints only its completed games: the fold leaves
 * [MatchState.currentGame] holding the final game's score on purpose, so appending
 * it would print the last game twice.
 */
fun scoreLine(state: MatchState): String {
    if (state.points.isEmpty() && state.completedGames.isEmpty()) return notStarted(state)
    val games = state.completedGames.map { "${it.home}-${it.away}" }
    val parts = if (state.isOver) games else games + "${state.currentGame.home}-${state.currentGame.away}"
    return if (parts.isEmpty()) "Not started" else parts.joinToString(", ")
}

/** A match with no rally: how many moments it has, or that it has not started. */
private fun notStarted(state: MatchState): String = when (val n = state.moments.size) {
    0 -> "Not started"
    1 -> "1 moment"
    else -> "$n moments"
}

/** The match page's way back to the board. Shared so the two pages cannot word it differently. */
fun scoreButtonLabel(state: MatchState): String = when {
    state.points.isNotEmpty() -> "Resume scoring"
    state.moments.isNotEmpty() -> "Resume"
    else -> "Score"
}

/**
 * Whether another point can still be scored on this match.
 *
 * Not the same as `status == LIVE`. The board's Done button navigates without
 * finishing, deliberately, so that a match ended on a mis-tap can still be undone
 * - which leaves a won match sitting at LIVE. And "Finish match" in the overflow
 * leaves an UNBOUND match with no winner. This is the predicate every surface
 * should ask, rather than either half of it.
 */
fun ScoreLog.isPlayable(): Boolean =
    status == ScoreLogStatus.LIVE && !state().isOver

fun buildScoreMatchCard(log: ScoreLog): ScoreMatchCard {
    val state = log.state()
    val winner = state.winner
    return ScoreMatchCard(
        scoreLogId = log.id,
        videoId = log.videoId,
        title = log.title,
        createdAtEpochMs = log.createdAt.toEpochMilliseconds(),
        playersLine = log.playersLine(),
        scoreLine = scoreLine(state),
        statusLine = when {
            winner != null -> "${log.sideName(winner)} won"
            log.isPlayable() -> "Scoring"
            // Closed by hand before anyone won. The score line above already says
            // where it stopped, so this only has to say that it did.
            else -> "Ended early"
        },
        isLive = log.isPlayable(),
        hasVideo = log.videoId != null,
    )
}
