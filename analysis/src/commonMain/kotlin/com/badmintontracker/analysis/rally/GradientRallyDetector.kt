package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlin.math.max

/**
 * Sentinel for "no shot yet", taken from the source rather than invented.
 *
 * `rally_detection.py` seeds this with -10000, and the tempting Int.MIN_VALUE
 * translation is not equivalent: `frame - Int.MIN_VALUE` overflows to a large
 * negative, the minimum-gap test then rejects every candidate, and the
 * detector returns no rallies for any input.
 */
private const val NO_PREVIOUS_SHOT = -10000

/**
 * Rally detection over a filtered shuttle track.
 *
 * Port of `rally_detection.detect_rallies`. Overhead-camera thresholds only,
 * matching the cloud: the app supports one camera position.
 *
 * Note the deliberate constant mismatch with the shot-gap detector: this one
 * groups on a 3.0s gap and that one on 3.1s. The cloud has the same split and
 * the clipping audit calls it harmless but unshared. Reproducing it keeps the
 * union comparable; unifying them would be a behaviour change to measure, not
 * a tidy-up to make silently.
 *
 * The stride here truncates with int() where shot_detection.py rounds. That
 * is not an oversight in either place - the two sources genuinely differ - so
 * the port keeps each one as written.
 */
fun detectRalliesGradient(
    shuttlePositions: Map<Int, ShuttleSample>,
    fps: Double,
    totalFrames: Int,
    minRallyDurationS: Double = 0.8,
    minGapDurationS: Double = 3.0,
): List<Rally> {
    if (shuttlePositions.isEmpty() || fps <= 0) return emptyList()

    val minShotGapFrames = max(3, (fps * 0.6).toInt())
    val minSpeedSq = 15.0 * 15.0
    val strideFrames = max(3, (fps * 0.3).toInt())

    val all = (0 until totalFrames).mapNotNull { f ->
        shuttlePositions[f]?.takeIf { it.visible }?.let { Triple(f, it.x, it.y) }
    }
    if (all.size < 5) return emptyList()

    val pts = arrayListOf(all.first())
    for (p in all.drop(1)) if (p.first - pts.last().first >= strideFrames) pts.add(p)
    if (pts.size < 3) return emptyList()

    val shotFrames = ArrayList<Int>()
    var lastShot = NO_PREVIOUS_SHOT
    for (i in 2 until pts.size) {
        val (_, x0, y0) = pts[i - 2]
        val (f1, x1, y1) = pts[i - 1]
        val (_, x2, y2) = pts[i]
        val vx1 = x1 - x0; val vy1 = y1 - y0
        val vx2 = x2 - x1; val vy2 = y2 - y1
        val s1 = vx1 * vx1 + vy1 * vy1
        val s2 = vx2 * vx2 + vy2 * vy2
        if (s1 < minSpeedSq && s2 < minSpeedSq) continue
        if (vx1 * vx2 + vy1 * vy2 >= 0.0) continue
        if (f1 - lastShot < minShotGapFrames) continue
        shotFrames.add(f1)
        lastShot = f1
    }
    if (shotFrames.size < MIN_SHOTS) return emptyList()

    val rallyGapFrames = max(1, (minGapDurationS * fps).toInt())
    val minRallyFrames = max(1, (minRallyDurationS * fps).toInt())
    val landingBuffer = max(1, (0.5 * fps).toInt())

    val out = ArrayList<Rally>()
    var groupStart = 0
    for (i in 1 until shotFrames.size) {
        val gap = shotFrames[i] - shotFrames[i - 1]
        val isLast = i == shotFrames.size - 1
        if (gap <= rallyGapFrames && !isLast) continue

        val endIdx = if (isLast && gap <= rallyGapFrames) i else i - 1
        val group = shotFrames.subList(groupStart, endIdx + 1)
        if (group.size >= MIN_SHOTS) {
            val startFrame = group.first()
            val endFrame = group.last() + landingBuffer
            if (endFrame - startFrame >= minRallyFrames) {
                out.add(
                    Rally(
                        id = out.size + 1,
                        startFrame = startFrame,
                        endFrame = endFrame,
                        startTimestamp = startFrame / fps,
                        endTimestamp = endFrame / fps,
                        durationSeconds = (endFrame - startFrame) / fps,
                    )
                )
            }
        }
        groupStart = i
    }
    return out
}
