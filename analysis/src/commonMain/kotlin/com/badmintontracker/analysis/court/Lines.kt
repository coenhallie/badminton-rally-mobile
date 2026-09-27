package com.badmintontracker.analysis.court

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A straight line through ridge pixels, work-image pixels, in normal form
 * `x cos(theta) + y sin(theta) = rho` with `theta` in `[0, pi)`. [votes] is
 * its Hough peak height; `(ax, ay)`-`(bx, by)` is the extent of its support
 * (the 2nd to 98th percentile of the ridge pixels within 1.5 px of it), the
 * part of the infinite line that is actually painted in this frame.
 */
internal class CandidateLine(
    val rho: Double,
    val theta: Double,
    val votes: Double,
    val ax: Double,
    val ay: Double,
    val bx: Double,
    val by: Double,
)

/**
 * The candidate lines split by the two directions a court's lines run in
 * (design §4.4): each family's lines meet at one vanishing point. Which
 * family is across and which along is not known here; search tries both.
 * Lines keep their [candidateLines] order within a family.
 */
internal class LineFamilies(val first: List<CandidateLine>, val second: List<CandidateLine>)

/** 0.25 degree angle bins over `[0, pi)`. */
private const val ANGLE_BINS = 720

/** Each pixel votes this many times, 0.5 degrees apart, `+-2.5` degrees about its own normal. */
private const val VOTE_SPREAD_STEPS = 11
private const val VOTE_SPREAD_DEGREES = 2.5

/** A taken peak clears this many bins either way in both angle and distance, so the same peak is not taken again. */
private const val PEAK_SUPPRESSION = 8

/** A peak under this fraction of the long side in votes ends the search: shorter runs are texture, not court lines. */
private const val MIN_PEAK_VOTES = 0.025

/** Ridge pixels this close to a line (px) are its support. */
private const val SUPPORT_BAND = 1.5

/** Lines with fewer support pixels than this are dropped: too few to place an extent. */
private const val MIN_SUPPORT = 5

/** A line is in a vanishing point's family when it points within this many degrees of it. */
private const val VP_INLIER_DEGREES = 1.5

/** A line's support midpoint must be this fraction of the image height on the floor side of the horizon. */
private const val HORIZON_MARGIN = 0.01

/**
 * `court_probe.py`'s `hough()` then `support()`: up to [maxLines] straight
 * lines through the ridge pixels, strongest first.
 *
 * The Hough transform is oriented: a ridge pixel votes only for normal
 * angles within 2.5 degrees of its own measured normal, so a pixel on a
 * sideline adds nothing to the lines crossing it. The suppression window
 * wraps in angle (bin 719 neighbours bin 0) but not in `rho`, as the
 * probe's does, so a line very near vertical can be found twice, once
 * either side of the wrap with opposite-sign `rho`. The duplicate is kept,
 * as the probe keeps it. [maxLines] bounds the peaks taken; lines with too
 * little support are then dropped, so fewer may come back.
 */
internal fun candidateLines(r: RidgeMap, maxLines: Int = 40): List<CandidateLine> {
    val w = r.width
    val h = r.height
    var count = 0
    for (on in r.mask) if (on) count++
    val xs = IntArray(count)
    val ys = IntArray(count)
    val th = DoubleArray(count)
    var k = 0
    for (i in r.mask.indices) {
        if (!r.mask[i]) continue
        xs[k] = i % w
        ys[k] = i / w
        th[k] = r.normal[i].toDouble().mod(PI)
        k++
    }

    val d = ceil(hypot(w.toDouble(), h.toDouble())).toInt() + 1
    val rhoBins = 2 * d
    val cosT = DoubleArray(ANGLE_BINS) { cos(it * PI / ANGLE_BINS) }
    val sinT = DoubleArray(ANGLE_BINS) { sin(it * PI / ANGLE_BINS) }
    val acc = IntArray(ANGLE_BINS * rhoBins)
    for (step in 0 until VOTE_SPREAD_STEPS) {
        val dt = -VOTE_SPREAD_DEGREES + step * (2 * VOTE_SPREAD_DEGREES / (VOTE_SPREAD_STEPS - 1))
        val offset = dt * PI / 180.0
        for (p in 0 until count) {
            // The trailing mod guards a floor-mod that rounds up to exactly pi.
            val ti = (((th[p] + offset).mod(PI)) / PI * ANGLE_BINS).toInt() % ANGLE_BINS
            val rho = round(xs[p] * cosT[ti] + ys[p] * sinT[ti]).toInt() + d
            acc[ti * rhoBins + rho]++
        }
    }

    val minVotes = MIN_PEAK_VOTES * max(w, h)
    val lines = ArrayList<CandidateLine>()
    for (peak in 0 until maxLines) {
        // First maximum in row-major order, as numpy's argmax.
        var bestIndex = 0
        for (i in 1 until acc.size) if (acc[i] > acc[bestIndex]) bestIndex = i
        val votes = acc[bestIndex]
        if (votes < minVotes) break
        val ti = bestIndex / rhoBins
        val ri = bestIndex % rhoBins
        for (dti in -PEAK_SUPPRESSION..PEAK_SUPPRESSION) {
            val row = (ti + dti).mod(ANGLE_BINS) * rhoBins
            for (rr in max(0, ri - PEAK_SUPPRESSION)..min(rhoBins - 1, ri + PEAK_SUPPRESSION)) acc[row + rr] = 0
        }
        val line = supported((ri - d).toDouble(), ti * PI / ANGLE_BINS, votes.toDouble(), xs, ys)
        if (line != null) lines += line
    }
    return lines
}

/**
 * `court_probe.py`'s `support()`: the line with its painted extent, the 2nd
 * to 98th percentile (numpy's linear interpolation) of the positions along
 * it of the ridge pixels within [SUPPORT_BAND] of it. The percentiles, not
 * the extremes, so a stray pixel from a crossing line does not stretch it.
 * Null when fewer than [MIN_SUPPORT] pixels are that close.
 */
private fun supported(rho: Double, theta: Double, votes: Double, xs: IntArray, ys: IntArray): CandidateLine? {
    val c = cos(theta)
    val s = sin(theta)
    val along = ArrayList<Double>()
    for (p in xs.indices) {
        if (abs(xs[p] * c + ys[p] * s - rho) < SUPPORT_BAND) along += -s * xs[p] + c * ys[p]
    }
    if (along.size < MIN_SUPPORT) return null
    val sorted = along.toDoubleArray().also { it.sort() }
    val lo = percentile(sorted, 2.0)
    val hi = percentile(sorted, 98.0)
    val baseX = c * rho
    val baseY = s * rho
    return CandidateLine(rho, theta, votes, baseX - lo * s, baseY + lo * c, baseX - hi * s, baseY + hi * c)
}

/** numpy's default (`linear`) percentile of already-sorted values. */
private fun percentile(sorted: DoubleArray, q: Double): Double {
    val pos = q / 100.0 * (sorted.size - 1)
    val i = pos.toInt()
    if (i >= sorted.size - 1) return sorted[sorted.size - 1]
    val frac = pos - i
    return sorted[i] + (sorted[i + 1] - sorted[i]) * frac
}

/**
 * `court_probe.py`'s `vp_groups()` then the horizon filter in `search()`:
 * the lines split into the two directions a court's lines run in, with
 * lines above the horizon dropped. Null when fewer than two directions are
 * found; a family may still come back empty when the horizon removes all of
 * its lines, as in the probe.
 *
 * Vanishing points: every pair of lines meets at a point (in homogeneous
 * coordinates, so parallel lines meet at infinity); the pair whose point
 * the most votes' worth of lines point at, within 1.5 degrees, wins, ties
 * going to the first pair in order. Its lines are taken out and the search
 * runs once more on the rest for the second direction. Pointing is judged
 * from each line's support midpoint along its support direction, so a
 * short line is judged where it actually is.
 *
 * Horizon: the line through the two vanishing points. The court is on the
 * floor, on the same side as the bottom-centre of the frame, and ceiling
 * beams, wall edges and balcony rails that run parallel to the court (and so
 * point at the same vanishing points) are on the other side. A line is kept
 * when its support midpoint is at least 1% of the image height floor-side;
 * when the two points are both at infinity (the horizon is itself at
 * infinity) the filter is skipped. `horizon = false` skips it too: the
 * probe's corner-guided `search_near()` groups the lines without it, the
 * taps being what rules out a ceiling or balcony there.
 */
internal fun lineFamilies(lines: List<CandidateLine>, width: Int, height: Int, horizon: Boolean = true): LineFamilies? {
    val homogeneous = lines.map { doubleArrayOf(cos(it.theta), sin(it.theta), -it.rho) }
    var remaining = lines.indices.toList()
    val groups = ArrayList<List<Int>>()
    val points = ArrayList<DoubleArray>()
    for (g in 0 until 2) {
        var bestWeight = 0.0
        var bestPoint: DoubleArray? = null
        var bestInliers: List<Int> = emptyList()
        for (a in remaining.indices) for (b in a + 1 until remaining.size) {
            val vp = cross(homogeneous[remaining[a]], homogeneous[remaining[b]])
            val norm = sqrt(vp[0] * vp[0] + vp[1] * vp[1] + vp[2] * vp[2])
            if (norm < 1e-12) continue
            for (i in 0..2) vp[i] /= norm
            val inliers = remaining.filter { angleToPoint(lines[it], vp) < VP_INLIER_DEGREES }
            val weight = inliers.sumOf { lines[it].votes }
            if (weight > bestWeight) {
                bestWeight = weight
                bestPoint = vp
                bestInliers = inliers
            }
        }
        if (bestPoint == null) break
        groups += bestInliers
        points += bestPoint
        val taken = bestInliers.toSet()
        remaining = remaining.filter { it !in taken }
    }
    if (groups.size < 2) return null

    var families = groups.map { g -> g.map { lines[it] } }
    if (!horizon) return LineFamilies(families[0], families[1])
    val hz = cross(points[0], points[1])
    val hn = hypot(hz[0], hz[1])
    if (hn > 1e-9 * max(1.0, abs(hz[2]))) {
        for (i in 0..2) hz[i] /= hn
        val floorSide = sign(hz[0] * width / 2.0 + hz[1] * height + hz[2])
        families = families.map { f ->
            f.filter { l -> floorSide * (hz[0] * (l.ax + l.bx) / 2 + hz[1] * (l.ay + l.by) / 2 + hz[2]) > HORIZON_MARGIN * height }
        }
    }
    return LineFamilies(families[0], families[1])
}

/**
 * The angle, degrees, between a line's support direction and the direction
 * from its support midpoint to the vanishing point `vp` (or `vp`'s own
 * direction when it is at infinity). 0 for a degenerate line or a point on
 * the midpoint, as the probe's `err()`.
 */
private fun angleToPoint(l: CandidateLine, vp: DoubleArray): Double {
    val len = max(1e-9, hypot(l.bx - l.ax, l.by - l.ay))
    val dx = (l.bx - l.ax) / len
    val dy = (l.by - l.ay) / len
    val vx: Double
    val vy: Double
    if (abs(vp[2]) < 1e-9) {
        vx = vp[0]
        vy = vp[1]
    } else {
        vx = vp[0] / vp[2] - (l.ax + l.bx) / 2
        vy = vp[1] / vp[2] - (l.ay + l.by) / 2
    }
    val n = hypot(vx, vy)
    if (n < 1e-9) return 0.0
    return acos(min(1.0, abs(dx * vx / n + dy * vy / n))) * 180.0 / PI
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)
