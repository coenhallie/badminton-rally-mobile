package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two competitors. Not court ends: ends change during a match and these do
 * not, so a side identifies the same player or pair for the whole log.
 */
@Serializable
enum class Side {
    @SerialName("home") HOME,
    @SerialName("away") AWAY;

    val other: Side get() = if (this == HOME) AWAY else HOME
}

/**
 * A label as it was when the coach tapped it. Snapshotted rather than referenced,
 * exactly as [com.badmintontracker.shared.model.RallyAnnotation] snapshots
 * label_name and label_color, and for the same reason: renaming or deleting a
 * label months later must not rewrite or orphan courtside history.
 */
@Serializable
data class PointTag(
    @SerialName("label_name")  val labelName: String,
    @SerialName("label_color") val labelColor: String?,
)

/**
 * One entry in a match's log. The log is the whole truth about a match: the score,
 * the history view, the text export and (in L2) the binding onto rally clips are
 * all folds over it, so there is no second copy of the score that can drift.
 *
 * Append-only. A tag names an already played point by ordinal rather than being
 * carried on the point itself, which keeps replay-from-scratch valid for a later
 * live-sync layer and makes undo mean "drop the last thing I did".
 *
 * The SerialName on each entry is the stored wire format of every match a coach
 * has ever scored. Treat it as a schema: rename the Kotlin class freely, never
 * these strings.
 */
@Serializable
sealed interface ScoreEvent {

    /** One rally, won by [side]. The Nth PointTo in a log is point N, counting from zero. */
    @Serializable
    @SerialName("point")
    data class PointTo(val side: Side) : ScoreEvent

    /**
     * Sets the tags and the comment on the point at [pointOrdinal], replacing
     * whatever an earlier TagPoint for the same ordinal set - the fold reads the
     * last entry for an ordinal, so re-tagging is another append rather than an
     * edit. An ordinal naming no point, which undo leaves behind, is ignored.
     */
    @Serializable
    @SerialName("tag")
    data class TagPoint(
        @SerialName("point_ordinal") val pointOrdinal: Int,
        val tags: List<PointTag>,
        val comment: String?,
    ) : ScoreEvent

    /**
     * [side] retires or is disqualified and the other side takes the match. The
     * only ending the score cannot determine on its own, which is why it is the
     * only non-scoring entry the log has. A match merely abandoned needs no entry:
     * its log stays as it is and its state stays live.
     */
    @Serializable
    @SerialName("retire")
    data class Retire(val side: Side) : ScoreEvent
}

/**
 * Drops the last entry. Undo is defined as re-folding a shorter log rather than as
 * an inverse operation on the score, so it can never drift from the forward path.
 *
 * An empty log comes back unchanged rather than throwing. The control that calls
 * this is disabled on an empty log, and if that ever slips, a no-op is a much
 * better answer courtside than a crash.
 */
fun List<ScoreEvent>.undoLast(): List<ScoreEvent> = if (isEmpty()) this else dropLast(1)

/**
 * Appends the tags and comment for [pointOrdinal]. Named rather than left as a
 * bare list append so that callers never have to know that appending, rather than
 * rewriting, is what keeps the log replayable.
 */
fun List<ScoreEvent>.tagPoint(
    pointOrdinal: Int,
    tags: List<PointTag>,
    comment: String?,
): List<ScoreEvent> = this + ScoreEvent.TagPoint(pointOrdinal, tags, comment)

/**
 * Drops the point at [fromOrdinal] and everything after it, tags and retirement
 * included. This is what "reset game" is built from: fold to find the first
 * ordinal of the current game, then cut there.
 *
 * Entries below the cut are untouched, so every surviving tag still names the
 * point it named before - no renumbering, and therefore no way for a tag to slide
 * onto a neighbouring rally.
 */
fun List<ScoreEvent>.dropPointsFrom(fromOrdinal: Int): List<ScoreEvent> {
    val kept = ArrayList<ScoreEvent>(size)
    var ordinal = 0
    for (event in this) {
        when (event) {
            is ScoreEvent.PointTo -> {
                if (ordinal < fromOrdinal) kept += event
                ordinal += 1
            }
            is ScoreEvent.TagPoint -> if (event.pointOrdinal < fromOrdinal) kept += event
            is ScoreEvent.Retire -> Unit
        }
    }
    return kept
}
