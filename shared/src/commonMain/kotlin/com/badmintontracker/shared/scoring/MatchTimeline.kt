package com.badmintontracker.shared.scoring

/** One row of a match's history: a rally, or a moment logged between rallies. */
sealed interface TimelineEntry {
    data class Rally(val point: ScoredPoint) : TimelineEntry
    data class Moment(val moment: LoggedMoment) : TimelineEntry
}

/**
 * Rallies and moments in the order they happened, oldest first. A moment anchored
 * after k rallies sits just before rally k. One copy for the match page on both
 * platforms and for the export.
 */
fun matchTimeline(state: MatchState): List<TimelineEntry> {
    val out = ArrayList<TimelineEntry>(state.points.size + state.moments.size)
    var next = 0
    for (point in state.points) {
        while (next < state.moments.size && state.moments[next].afterPoints <= point.ordinal) {
            out += TimelineEntry.Moment(state.moments[next++])
        }
        out += TimelineEntry.Rally(point)
    }
    while (next < state.moments.size) out += TimelineEntry.Moment(state.moments[next++])
    return out
}
