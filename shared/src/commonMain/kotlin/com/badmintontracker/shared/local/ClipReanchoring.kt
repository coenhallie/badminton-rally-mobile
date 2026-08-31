package com.badmintontracker.shared.local

import kotlin.math.abs

/** One annotation's new position, and whether it still lands inside the clip. */
data class Reanchored(
    val annotationId: String,
    val newTimestampSeconds: Double,
    val outsideNewClip: Boolean,
)

/**
 * What to write when a re-analysis moved a clip's boundary.
 *
 * [moved] and [flagged] partition the input: an annotation in neither list
 * would keep a stale timestamp pointing at footage that has shifted.
 */
data class ReanchorPlan(
    val shiftSeconds: Double,
    val moved: List<Reanchored>,
    val flagged: List<Reanchored>,
)

/**
 * Default epsilon, in seconds, below which a boundary is treated as unmoved.
 *
 * Matches the neighbour-clamp guard `refineRallies` already uses, so the two
 * places that reason about "close enough to the same boundary" agree.
 */
const val REANCHOR_EPSILON_SECONDS: Double = 0.05

/**
 * Plan the annotation shift for a clip whose bounds have moved.
 *
 * Section 5.4's rule, and the one place local deliberately does something the
 * cloud does not. New weights produce a different RawInference, hence
 * different clip bounds, hence annotation timestamps pointing into footage
 * that has moved. Annotation timestamps are relative to clip start, so an
 * earlier start means every annotation sits further into the clip.
 *
 * The epsilon test is `abs(shift) <= epsilonSeconds`. Behaviour at exactly
 * the threshold is not a meaningful contract: a shift written as 0.05 in
 * decimal is rarely 0.05 in binary, so callers should treat the epsilon as a
 * band rather than a line.
 *
 * Returns null when the boundary did not move beyond [epsilonSeconds]: the
 * caller opens a transaction on a non-null result, and rewriting every
 * annotation whenever a boundary wobbles by a rounding error is how this
 * becomes row churn instead of a correction.
 *
 * Annotations that would fall outside the new clip are reported in
 * [ReanchorPlan.flagged] with their computed timestamp intact, never coerced
 * into range. Clamping would silently relocate a coach's note to the clip edge
 * where it is indistinguishable from one deliberately placed there.
 */
fun planReanchor(
    oldStart: Double,
    newStart: Double,
    newDuration: Double,
    annotations: List<Pair<String, Double>>,
    epsilonSeconds: Double = REANCHOR_EPSILON_SECONDS,
): ReanchorPlan? {
    val shift = oldStart - newStart
    if (abs(shift) <= epsilonSeconds) return null

    val moved = ArrayList<Reanchored>()
    val flagged = ArrayList<Reanchored>()
    for ((id, timestamp) in annotations) {
        val moved0 = timestamp + shift
        val outside = moved0 < 0.0 || moved0 > newDuration
        val entry = Reanchored(id, moved0, outside)
        if (outside) flagged.add(entry) else moved.add(entry)
    }
    return ReanchorPlan(shiftSeconds = shift, moved = moved, flagged = flagged)
}
