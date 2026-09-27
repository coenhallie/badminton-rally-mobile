package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.HomographySolver
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.toMatrix3x3
import kotlin.math.abs
import kotlin.random.Random

internal class RansacResult(val homography: Matrix3x3, val inliers: Int)

/**
 * RANSAC over 4-point DLT fits, then a least-squares refit on the inliers, as
 * OpenCV's `findHomography(src, dst, RANSAC, threshold, maxIters)` does.
 * Design §4.3 step 4. The generator is seeded so a run is repeatable on every
 * platform. The result is normalised so `h22 == 1.0`.
 *
 * This runs once per tracked sample, and its 300 fits and inlier counts were
 * half the tracker's cost (1.4 of 2.9 ms per sample on the host JVM) when
 * each fit went through `List<List<Double>>` and each projected point was a
 * new [Point]. So the points are copied once into primitive arrays, every fit
 * reuses one [HomographySolver], and the counting allocates nothing: 0.15 ms.
 * The fits, the draws from [random] and the inlier test are the same as
 * through [com.badmintontracker.analysis.geometry.calculateHomography] and
 * [com.badmintontracker.analysis.geometry.apply], to the last bit.
 */
internal fun ransacHomography(
    src: List<Point>,
    dst: List<Point>,
    threshold: Double = 1.0,
    iterations: Int = 300,
    random: Random = Random(0x5EED),
): RansacResult? {
    val n = minOf(src.size, dst.size)
    if (n < 4) return null
    val t2 = threshold * threshold
    val sx = DoubleArray(n) { src[it].x }
    val sy = DoubleArray(n) { src[it].y }
    val dx = DoubleArray(n) { dst[it].x }
    val dy = DoubleArray(n) { dst[it].y }

    /**
     * How many pairs [h] maps to within [threshold] of their match. Stops early
     * once even every remaining pair could not lift the count above [toBeat],
     * because then the exact count does not matter: it cannot win.
     */
    fun countInliers(h: DoubleArray, toBeat: Int = -1): Int {
        var count = 0
        for (i in 0 until n) {
            if (count + (n - i) <= toBeat) break
            if (isInlier(h, sx[i], sy[i], dx[i], dy[i], t2)) count++
        }
        return count
    }

    val solver = HomographySolver(n)
    val h = DoubleArray(9)
    val best = DoubleArray(9)
    var bestCount = -1
    val pick = IntArray(4)
    repeat(iterations) {
        var filled = 0
        while (filled < 4) {
            val c = random.nextInt(n)
            var fresh = true
            for (k in 0 until filled) if (pick[k] == c) fresh = false
            if (fresh) pick[filled++] = c
        }
        if (!solver.fit(sx, sy, dx, dy, pick, 4, h)) return@repeat
        val count = countInliers(h, bestCount)
        if (count > bestCount) {
            bestCount = count
            h.copyInto(best)
        }
    }
    if (bestCount < 0) return null
    val inliers = IntArray(n)
    var m = 0
    for (i in 0 until n) if (isInlier(best, sx[i], sy[i], dx[i], dy[i], t2)) inliers[m++] = i
    val refit = if (solver.fit(sx, sy, dx, dy, inliers, m, h)) h else best
    val final = normalise(refit.toMatrix3x3()) ?: return null
    val finalArray = DoubleArray(9) { final[it / 3][it % 3] }
    return RansacResult(final, countInliers(finalArray))
}

/**
 * Whether [h] (row-major) maps `(x, y)` to within `sqrt(t2)` of `(u, v)`:
 * [com.badmintontracker.analysis.geometry.apply]'s projection, inline, with a
 * point at infinity never an inlier.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun isInlier(h: DoubleArray, x: Double, y: Double, u: Double, v: Double, t2: Double): Boolean {
    val w = h[6] * x + h[7] * y + h[8]
    if (abs(w) < 1e-10) return false
    val px = (h[0] * x + h[1] * y + h[2]) / w
    val py = (h[3] * x + h[4] * y + h[5]) / w
    return (px - u) * (px - u) + (py - v) * (py - v) <= t2
}

/** Divided through so the bottom-right entry is exactly 1.0; null when it is ~0. */
internal fun normalise(h: Matrix3x3): Matrix3x3? {
    val s = h[2][2]
    if (abs(s) < 1e-12) return null
    return List(3) { r -> List(3) { c -> if (r == 2 && c == 2) 1.0 else h[r][c] / s } }
}
