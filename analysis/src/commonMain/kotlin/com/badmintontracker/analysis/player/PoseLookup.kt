package com.badmintontracker.analysis.player

import kotlin.math.abs

/**
 * How far a playback position may sit from a pose's timestamp and still be
 * that frame: half a frame period, plus one millisecond.
 *
 * Half a frame is the boundary between one frame and the next. The extra
 * millisecond is ExoPlayer's: it reports position in whole milliseconds,
 * rounded down, so a frame shown at 33.333ms reads as 33 and would otherwise
 * sit exactly on the boundary.
 */
fun poseToleranceS(fps: Double): Double {
    val rate = if (fps > 0.0) fps else FALLBACK_FPS
    return 0.5 / rate + 0.001
}

/**
 * The pose nearest [seconds], or null when none is within [toleranceS].
 *
 * Null is a result, not a failure: a frame the player was not found in has no
 * skeleton, and drawing a neighbour's would put the figure where the body is
 * not. [poses] must be sorted by timestamp, which a selection pass produces.
 */
fun nearestPose(poses: List<PlayerPose>, seconds: Double, toleranceS: Double): PlayerPose? {
    if (poses.isEmpty()) return null
    var lo = 0
    var hi = poses.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (poses[mid].timestamp < seconds) lo = mid + 1 else hi = mid
    }
    // lo is the first pose at or after seconds; the one before may be nearer.
    val after = poses[lo]
    val before = if (lo > 0) poses[lo - 1] else null
    val best = when {
        before == null -> after
        abs(before.timestamp - seconds) <= abs(after.timestamp - seconds) -> before
        else -> after
    }
    return if (abs(best.timestamp - seconds) <= toleranceS) best else null
}

private const val FALLBACK_FPS = 30.0
