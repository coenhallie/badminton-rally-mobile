package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.scoring.ScoreMatchCard

/**
 * One row of the match list, which since live scoring holds two kinds of thing: a
 * match cut from a video, and a match scored courtside that may never have one.
 *
 * A sealed interface rather than a nullable videoId on MatchSummary. Five places
 * dereference that id - the list key, the cover thumbnail, the rally count, the
 * navigation value and delete_match - and a null there is a different row, not a
 * missing field. This way the compiler asks at each of them.
 */
sealed interface MatchRow {
    /** Prefixed: both ids are UUIDs from the same generator and would otherwise collide. */
    val key: String
    val sortAtEpochMs: Long

    data class Video(val match: MatchSummary) : MatchRow {
        override val key: String get() = "video-${match.videoId}"
        override val sortAtEpochMs: Long get() = match.latestCreatedAt.toEpochMilliseconds()
    }

    data class Score(val card: ScoreMatchCard) : MatchRow {
        override val key: String get() = "score-${card.scoreLogId}"
        override val sortAtEpochMs: Long get() = card.createdAtEpochMs
    }
}

/**
 * Interleaves the two kinds into one newest-first list. Score logs are owner-only
 * by RLS, so this only ever builds the owned section; the shared section is
 * untouched by live scoring.
 *
 * The tie-break on [MatchRow.key] is not decoration: two rows created in the same
 * second must not swap places between refreshes.
 */
internal fun mergeMatchRows(
    videoMatches: List<MatchSummary>,
    scoreMatches: List<ScoreMatchCard>,
): List<MatchRow> =
    (videoMatches.map(MatchRow::Video) + scoreMatches.map(MatchRow::Score))
        .sortedWith(compareByDescending<MatchRow> { it.sortAtEpochMs }.thenBy { it.key })
