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
    overlapThreshold: Double = 0.5,
): List<Rally> {
    val safeFps = if (fps > 0) fps else 30.0
    val combined = ArrayList<DoubleArray>()  // [start, end]

    for (r in (a + b).sortedBy { it.startTimestamp }) {
        var absorbed = false
        for (c in combined) {
            val s = max(r.startTimestamp, c[0])
            val e = min(r.endTimestamp, c[1])
            if (e <= s) continue
            val shorter = min(r.endTimestamp - r.startTimestamp, c[1] - c[0])
            if (shorter <= 0 || (e - s) / shorter < overlapThreshold) continue
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
