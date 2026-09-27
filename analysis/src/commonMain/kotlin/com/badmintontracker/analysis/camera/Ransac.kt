package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.calculateHomography
import kotlin.random.Random

internal class RansacResult(val homography: Matrix3x3, val inliers: Int)

/**
 * RANSAC over 4-point DLT fits, then a least-squares refit on the inliers, as
 * OpenCV's `findHomography(src, dst, RANSAC, threshold, maxIters)` does.
 * Design §4.3 step 4. The generator is seeded so a run is repeatable on every
 * platform. The result is normalised so `h22 == 1.0`.
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

    fun inliersOf(h: Matrix3x3): BooleanArray = BooleanArray(n) { i ->
        val p = h.apply(src[i].x, src[i].y)
        p != null && (p.x - dst[i].x) * (p.x - dst[i].x) + (p.y - dst[i].y) * (p.y - dst[i].y) <= t2
    }

    var best: Matrix3x3? = null
    var bestCount = -1
    val pick = IntArray(4)
    repeat(iterations) {
        var filled = 0
        while (filled < 4) {
            val c = random.nextInt(n)
            if ((0 until filled).none { pick[it] == c }) pick[filled++] = c
        }
        val h = calculateHomography(pick.map { src[it] }, pick.map { dst[it] }) ?: return@repeat
        val count = inliersOf(h).count { it }
        if (count > bestCount) {
            bestCount = count
            best = h
        }
    }
    val seed = best ?: return null
    val mask = inliersOf(seed)
    val refit = calculateHomography(
        src.filterIndexed { i, _ -> i < n && mask[i] },
        dst.filterIndexed { i, _ -> i < n && mask[i] },
    ) ?: seed
    val final = normalise(refit) ?: return null
    return RansacResult(final, inliersOf(final).count { it })
}

/** Divided through so the bottom-right entry is exactly 1.0; null when it is ~0. */
internal fun normalise(h: Matrix3x3): Matrix3x3? {
    val s = h[2][2]
    if (kotlin.math.abs(s) < 1e-12) return null
    return List(3) { r -> List(3) { c -> if (r == 2 && c == 2) 1.0 else h[r][c] / s } }
}
