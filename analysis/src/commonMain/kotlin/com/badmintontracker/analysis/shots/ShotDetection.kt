package com.badmintontracker.analysis.shots

import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FrameSample(val frame: Int, val timestamp: Double, val shuttle: ShuttleSample?)

data class Shot(val frame: Int, val timestamp: Double, val x: Double, val y: Double)

/** Single-frame jump beyond this squared distance, on both sides, is a glitch. */
private const val OUTLIER_DIST_SQ = 400.0 * 400.0

/** Beyond this gap between sampled positions, velocity is meaningless. */
private const val MAX_GAP_S = 2.5

private data class Sample(val frame: Int, val t: Double, val x: Double, val y: Double)

/**
 * Shots as shuttle direction reversals.
 *
 * One implementation replacing two: shot_detection.py and
 * utils/shotDetection.ts are the same algorithm maintained separately, and
 * their docstrings name each other as sync targets.
 *
 * Three gates in those sources are deliberately absent. The wrist-proximity
 * and acceleration gates are off because the rally caller passes null for
 * both, so they were never active on this path. The require_players gate is
 * off because Phase 1 frames carry no player data at all, and the strict gate
 * there drops every shot - which it did, leaving the shot-gap detector
 * returning empty for the whole of Phase 1.
 */
fun detectShuttleShots(
    frames: List<FrameSample>,
    fps: Double,
    minShotGapSec: Double = 0.6,
    minSpeedSq: Double = 225.0,
    cosAngleMax: Double = 0.0,
    rejectOutliers: Boolean = true,
    autoStrideSec: Double = 0.3,
): List<Shot> {
    if (fps <= 0) return emptyList()
    val minShotGapFrames = max(3, (fps * minShotGapSec).toInt())

    val raw = frames.mapNotNull { f ->
        f.shuttle?.takeIf { it.visible }?.let { Sample(f.frame, f.timestamp, it.x, it.y) }
    }
    if (raw.size < 5) return emptyList()

    val cleaned = if (rejectOutliers) filterOutliers(raw) else raw

    // Stride only on dense tracks. Sparse client-side data is already spread
    // out; TrackNet gives consecutive frames whose velocity vectors turn too
    // gradually for a reversal to register without subsampling.
    val coverage = raw.size.toDouble() / max(frames.size, 1)
    val strideFrames = if (coverage > 0.5) max(3, (fps * autoStrideSec).roundToInt()) else 0
    val samples = if (strideFrames > 0 && cleaned.isNotEmpty()) {
        val acc = arrayListOf(cleaned.first())
        for (s in cleaned.drop(1)) if (s.frame - acc.last().frame >= strideFrames) acc.add(s)
        acc
    } else cleaned
    if (samples.size < 3) return emptyList()

    val shots = ArrayList<Shot>()
    // Null, not Int.MIN_VALUE. The Python seeds this with -inf, and the
    // obvious Int translation overflows: `frame - Int.MIN_VALUE` wraps
    // negative, so the gap test passes for no shot and the function returns
    // empty for every input.
    var lastShotFrame: Int? = null
    for (i in 2 until samples.size) {
        val a = samples[i - 2]
        val b = samples[i - 1]
        val c = samples[i]
        if (b.t - a.t > MAX_GAP_S || c.t - b.t > MAX_GAP_S) continue

        val vx1 = b.x - a.x; val vy1 = b.y - a.y
        val vx2 = c.x - b.x; val vy2 = c.y - b.y
        val s1 = vx1 * vx1 + vy1 * vy1
        val s2 = vx2 * vx2 + vy2 * vy2
        if (s1 < minSpeedSq && s2 < minSpeedSq) continue

        val dot = vx1 * vx2 + vy1 * vy2
        val threshold = if (cosAngleMax < 0) cosAngleMax * sqrt(s1 * s2) else 0.0
        if (dot >= threshold) continue
        val since = lastShotFrame
        if (since != null && b.frame - since < minShotGapFrames) continue

        shots.add(Shot(b.frame, b.t, b.x, b.y))
        lastShotFrame = b.frame
    }
    return shots
}

/** Drop a position whose squared distance to BOTH neighbours exceeds the threshold. */
private fun filterOutliers(points: List<Sample>): List<Sample> {
    if (points.size < 3) return points
    val out = arrayListOf(points.first())
    for (i in 1 until points.size - 1) {
        val prev = points[i - 1]; val cur = points[i]; val next = points[i + 1]
        val dPrev = (cur.x - prev.x) * (cur.x - prev.x) + (cur.y - prev.y) * (cur.y - prev.y)
        val dNext = (cur.x - next.x) * (cur.x - next.x) + (cur.y - next.y) * (cur.y - next.y)
        if (dPrev > OUTLIER_DIST_SQ && dNext > OUTLIER_DIST_SQ) continue
        out.add(cur)
    }
    out.add(points.last())
    return out
}
