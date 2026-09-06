package com.badmintontracker.analysis.rally

import kotlin.math.max
import kotlin.math.min

/**
 * Merge the filtered track's rally list with the raw track's bounds.
 *
 * The filtered track's splits are trustworthy but its noise rejection trims
 * rally tails; the raw track has accurate bounds but fabricates rallies in
 * idle windows. So: keep the filtered list, widen each edge toward any
 * overlapping raw bound by at most `maxExtensionSec`, and clamp to neighbours.
 * Raw rallies overlapping nothing are dropped entirely.
 *
 * The neighbour clamp reads the ORIGINAL filtered bounds, not the refined
 * ones, so the output windows can overlap each other: one raw rally spanning
 * two filtered rallies widens the first one forwards and the second one
 * backwards past it. That is the source's behaviour and callers must expect
 * it - `padRallyWindows` is written to tolerate overlapping input for exactly
 * this reason.
 */
fun refineRallies(
    filtered: List<Rally>,
    raw: List<Rally>,
    fps: Double,
    maxExtensionSec: Double = RALLY_GAP_SECONDS,
): List<Rally> {
    val safeFps = if (fps > 0) fps else 30.0
    val out = ArrayList<Rally>(filtered.size)
    for ((i, f) in filtered.withIndex()) {
        var start = f.startTimestamp
        var end = f.endTimestamp
        val overlapping = raw.filter {
            min(end, it.endTimestamp) > max(start, it.startTimestamp)
        }
        if (overlapping.isNotEmpty()) {
            val rawStart = overlapping.minOf { it.startTimestamp }
            val rawEnd = overlapping.maxOf { it.endTimestamp }
            start = max(start - maxExtensionSec, min(rawStart, start))
            end = min(end + maxExtensionSec, max(rawEnd, end))
        }
        if (i > 0) start = max(start, filtered[i - 1].endTimestamp + 0.05)
        if (i + 1 < filtered.size) end = min(end, filtered[i + 1].startTimestamp - 0.05)
        out.add(
            Rally(
                id = i + 1,
                startFrame = (start * safeFps).toInt(),
                endFrame = (end * safeFps).toInt(),
                startTimestamp = start,
                endTimestamp = end,
                durationSeconds = end - start,
            )
        )
    }
    return out
}

/**
 * Fraction of the shorter rally that must overlap for two to be "the same".
 *
 * Named because the A/B comparator has to use the same number: if the union
 * merges a pair at 0.5 and the comparator matched at some other value, the
 * two disagree about how many rallies exist and nothing reports it.
 */
const val RALLY_OVERLAP_THRESHOLD: Double = 0.5

/**
 * Combine two rally lists, deduplicating by temporal overlap.
 *
 * Two rallies are the same when their overlap exceeds `overlapThreshold` of
 * the SHORTER one's duration. Overlapping pairs collapse to the union of
 * their bounds.
 */
fun unionRallies(
    a: List<Rally>,
    b: List<Rally>,
    fps: Double,
    overlapThreshold: Double = RALLY_OVERLAP_THRESHOLD,
): List<Rally> {
    val safeFps = if (fps > 0) fps else 30.0
    val combined = ArrayList<DoubleArray>()  // [start, end]

    for (r in (a + b).sortedBy { it.startTimestamp }) {
        var absorbed = false
        for (c in combined) {
            if (!overlapsByFraction(
                    r.startTimestamp, r.endTimestamp, c[0], c[1], overlapThreshold
                )
            ) continue
            c[0] = min(c[0], r.startTimestamp)
            c[1] = max(c[1], r.endTimestamp)
            absorbed = true
            break
        }
        if (!absorbed) combined.add(doubleArrayOf(r.startTimestamp, r.endTimestamp))
    }

    return combined.mapIndexed { i, c ->
        Rally(
            id = i + 1,
            startFrame = (c[0] * safeFps).toInt(),
            endFrame = (c[1] * safeFps).toInt(),
            startTimestamp = c[0],
            endTimestamp = c[1],
            durationSeconds = c[1] - c[0],
        )
    }
}

/**
 * Are these two time ranges "the same rally"?
 *
 * True when they overlap by more than [threshold] of the SHORTER one's
 * duration. Shared by [unionRallies] and by the A/B comparator on purpose:
 * two definitions of rally identity would let the union merge a pair the
 * comparator reports as unmatched, and that disagreement is invisible in
 * both places.
 */
internal fun overlapsByFraction(
    aStart: Double,
    aEnd: Double,
    bStart: Double,
    bEnd: Double,
    threshold: Double,
): Boolean {
    val start = max(aStart, bStart)
    val end = min(aEnd, bEnd)
    if (end <= start) return false
    val shorter = min(aEnd - aStart, bEnd - bStart)
    return shorter > 0 && (end - start) / shorter >= threshold
}
