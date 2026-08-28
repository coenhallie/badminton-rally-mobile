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
    /** "Coen vs Marco", or "Coen / Ana vs Marco / Li". */
    val playersLine: String,
    /** "Not started", "11-9", "21-18, 5-3". */
    val scoreLine: String,
    /** "Scoring" while live, "<winner> won" once it has a winner. */
    val statusLine: String,
    val isLive: Boolean,
    /** True once L2 has attached a video. Nothing in this release sets it. */
    val hasVideo: Boolean,
)

/** One name for a singles side, both names for a pair. */
fun sideLabel(players: List<String>): String = players.joinToString(" / ")

/**
 * The match's score as one line: every completed game, then the game in progress.
 *
 * A match nobody has started says so rather than showing 0-0, which reads as a bug
 * on a row. A finished match prints only its completed games: the fold leaves
 * [MatchState.currentGame] holding the final game's score on purpose, so appending
 * it would print the last game twice.
 */
fun scoreLine(state: MatchState): String {
    if (state.points.isEmpty() && state.completedGames.isEmpty()) return "Not started"
    val games = state.completedGames.map { "${it.home}-${it.away}" }
    val parts = if (state.isOver) games else games + "${state.currentGame.home}-${state.currentGame.away}"
    return if (parts.isEmpty()) "Not started" else parts.joinToString(", ")
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
        playersLine = "${sideLabel(log.homePlayers)} vs ${sideLabel(log.awayPlayers)}",
        scoreLine = scoreLine(state),
        statusLine = when {
            winner == Side.HOME -> "${sideLabel(log.homePlayers)} won"
            winner == Side.AWAY -> "${sideLabel(log.awayPlayers)} won"
            log.isPlayable() -> "Scoring"
            // Closed by hand before anyone won. The score line above already says
            // where it stopped, so this only has to say that it did.
            else -> "Ended early"
        },
        isLive = log.isPlayable(),
        hasVideo = log.videoId != null,
    )
}
