package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One rally's span in the analysed video, seconds. [index] is the 1-based rally number. */
data class RallyWindow(val index: Int, val startSeconds: Double, val endSeconds: Double) {
    val isBounded: Boolean get() = endSeconds > startSeconds
}

/**
 * Where the player stood in one rally.
 *
 * [coverage] is the share of the window's frames that produced a position,
 * the same quality figure the heatmap reports: the number stands on the
 * ankles, and a rally where the player was found in a fifth of its frames is
 * a different kind of fact from one where they were found in all of them.
 */
data class RallyBase(val index: Int, val position: Point, val samples: Int, val coverage: Double)

/**
 * The per-rally bases and, across all of them, the match's.
 *
 * [overall] is the median of every sample that fell inside a counted rally,
 * not the median of the rally medians: a long rally is more of the match than
 * a short one, and walking to the shuttle tube between rallies is not part of
 * where the player plays from.
 */
data class BasePositions(val rallies: List<RallyBase>, val overall: Point?)

/**
 * Median court position of the near player, per rally.
 *
 * The median, per axis, because the question is where the player plays FROM,
 * and a median is the one summary that a lunge to the net or a scramble to
 * the back corner cannot drag around. Measured on the corpus this is the most
 * stable number the track supports: two different pose models put a rally's
 * median within 1-2 cm of each other, on a track whose standing-still noise
 * is a 1.7 cm standard deviation (docs/plans/2026-09-08-more-pose-metrics-research.md, §4).
 *
 * A rally counts only when it is [RallyWindow.isBounded] and holds at least
 * [minSeconds] of samples. Clips recovered from disk without their index have
 * no bounds, and a rally the player was barely found in would report a
 * median of a handful of frames as if it were a base.
 *
 * Windows are in seconds and samples carry frames, so the boundary goes
 * through [fps]. On a variable-frame-rate source that is a few frames off at
 * either end of a rally, which does not move a median.
 */
fun basePositions(
    track: PlayerTrack,
    fps: Double,
    windows: List<RallyWindow>,
    minSeconds: Double = MIN_RALLY_SECONDS,
): BasePositions {
    if (fps <= 0.0 || track.samples.isEmpty()) return BasePositions(emptyList(), null)
    val minSamples = max(1, ceil(minSeconds * fps).toInt())
    val sorted = track.samples.sortedBy { it.frame }
    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    val rallies = ArrayList<RallyBase>()
    for (w in windows.sortedBy { it.startSeconds }) {
        if (!w.isBounded) continue
        val first = floor(w.startSeconds * fps).toInt()
        val last = ceil(w.endSeconds * fps).toInt()
        val inside = sorted.filter { it.frame in first..last }
        if (inside.size < minSamples) continue
        val rx = inside.map { it.courtPosition.x }
        val ry = inside.map { it.courtPosition.y }
        xs += rx
        ys += ry
        rallies += RallyBase(
            index = w.index,
            position = Point(median(rx), median(ry)),
            samples = inside.size,
            coverage = inside.size.toDouble() / (last - first + 1),
        )
    }
    val overall = if (xs.isEmpty()) null else Point(median(xs), median(ys))
    return BasePositions(rallies, overall)
}

/** The shortest rally worth a base: a serve and one return. */
const val MIN_RALLY_SECONDS: Double = 1.0

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}
