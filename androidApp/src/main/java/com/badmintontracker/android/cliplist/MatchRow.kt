package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.scoring.AttachStatus
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

    /**
     * A scored match, with whatever video it has acquired. Both facets on one row
     * rather than two rows: a match the coach scored and then filmed is one match.
     */
    data class Score(
        val card: ScoreMatchCard,
        /** Non-null once the pipeline has produced clips for this match's video. */
        val video: MatchSummary? = null,
        /** Non-null while a video is attached but not yet clipped. */
        val attach: AttachStatus? = null,
    ) : MatchRow {
        override val key: String get() = "score-${card.scoreLogId}"
        override val sortAtEpochMs: Long get() = card.createdAtEpochMs
    }
}

/**
 * Interleaves the two kinds into one newest-first list, folding a video match into
 * the score match that claims it. Score logs are owner-only by RLS, so this only
 * ever builds the owned section; the shared section is untouched.
 *
 * Sorting a bound row on the score log's createdAt rather than its clips' is
 * deliberate: the match was created before the video existed and must not jump
 * down the list when the clips arrive.
 *
 * The tie-break on [MatchRow.key] is not decoration: two rows created in the same
 * second must not swap places between refreshes.
 */
internal fun mergeMatchRows(
    videoMatches: List<MatchSummary>,
    scoreMatches: List<ScoreMatchCard>,
    attachByScoreLogId: Map<String, AttachStatus> = emptyMap(),
): List<MatchRow> {
    val videoById = videoMatches.associateBy { it.videoId }
    val claimed = scoreMatches.mapNotNull { it.videoId }.toSet()
    val scoreRows = scoreMatches.map { card ->
        MatchRow.Score(
            card = card,
            video = card.videoId?.let(videoById::get),
            attach = attachByScoreLogId[card.scoreLogId],
        )
    }
    val videoRows = videoMatches.filterNot { it.videoId in claimed }.map(MatchRow::Video)
    return (videoRows + scoreRows)
        .sortedWith(compareByDescending<MatchRow> { it.sortAtEpochMs }.thenBy { it.key })
}
