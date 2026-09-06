package com.badmintontracker.analysis.geometry

import kotlin.math.abs
import kotlin.math.sqrt

typealias Matrix3x3 = List<List<Double>>

/**
 * Hartley-normalized DLT homography.
 *
 * Ported from badminton-tracker's src/utils/homography.ts rather than from
 * speed_calc.py, because the TypeScript is dependency-free and the Python
 * documents itself as a port of the same reference. Keeping OpenCV out of this
 * module is the whole reason the TS is the source of truth here.
 */
fun calculateHomography(src: List<Point>, dst: List<Point>): Matrix3x3? {
    // The TS pairs by index over min(src, dst) rather than demanding equal
    // lengths, so an over-long dst is truncated, not rejected.
    val n = minOf(src.size, dst.size)
    if (n < 4) return null

    val (nSrc, tSrc) = normalizePoints(src.subList(0, n)) ?: return null
    val (nDst, tDst) = normalizePoints(dst.subList(0, n)) ?: return null

    // Two rows per correspondence: the standard DLT constraint on h with
    // h33 fixed to 1, leaving 8 unknowns.
    val a = ArrayList<List<Double>>(n * 2)
    val b = ArrayList<Double>(n * 2)
    for (i in 0 until n) {
        val (x, y) = nSrc[i]
        val (u, v) = nDst[i]
        a.add(listOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y))
        b.add(u)
        a.add(listOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y))
        b.add(v)
    }

    // Exactly four correspondences make the system square and exactly
    // determined, so solve it directly. Routing it through the normal
    // equations instead would square the condition number for no gain.
    val h = (if (a.size == 8) solveLinearSystem(a, b) else solveLeastSquares(a, b)) ?: return null
    val hn = listOf(
        listOf(h[0], h[1], h[2]),
        listOf(h[3], h[4], h[5]),
        listOf(h[6], h[7], 1.0),
    )

    // Undo normalization: H = Tdst^-1 * Hn * Tsrc
    val tDstInv = invert3x3(tDst) ?: return null
    return multiply3x3(tDstInv, multiply3x3(hn, tSrc))
}

/** Apply a homography to a pixel. Returns null when the point projects to infinity. */
fun Matrix3x3.apply(x: Double, y: Double): Point? {
    val w = this[2][0] * x + this[2][1] * y + this[2][2]
    if (abs(w) < 1e-10) return null
    return Point(
        (this[0][0] * x + this[0][1] * y + this[0][2]) / w,
        (this[1][0] * x + this[1][1] * y + this[1][2]) / w,
    )
}

/** Video pixels to court metres, using all 12 keypoints for a better fit. */
fun CourtKeypoints.homography(): Matrix3x3? {
    val src = listOf(
        topLeft, topRight, bottomRight, bottomLeft, netLeft, netRight,
        serviceLineNearLeft, serviceLineNearRight,
        serviceLineFarLeft, serviceLineFarRight, centerNear, centerFar,
    )
    return calculateHomography(src, COURT_KEYPOINT_POSITIONS)
}

private fun normalizePoints(points: List<Point>): Pair<List<Point>, Matrix3x3>? {
    val n = points.size
    val cx = points.sumOf { it.x } / n
    val cy = points.sumOf { it.y } / n
    val meanDist = points.sumOf {
        sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy))
    } / n
    // The TS falls back to scale = 1 here. Bailing instead is the same outcome
    // by a shorter route: coincident points cannot determine a homography, and
    // the solve downstream would reject them anyway.
    if (meanDist < 1e-12) return null
    val s = sqrt(2.0) / meanDist
    val t = listOf(
        listOf(s, 0.0, -s * cx),
        listOf(0.0, s, -s * cy),
        listOf(0.0, 0.0, 1.0),
    )
    return points.map { Point((it.x - cx) * s, (it.y - cy) * s) } to t
}

private fun multiply3x3(a: Matrix3x3, b: Matrix3x3): Matrix3x3 =
    (0..2).map { r -> (0..2).map { c -> (0..2).sumOf { k -> a[r][k] * b[k][c] } } }

private fun invert3x3(m: Matrix3x3): Matrix3x3? {
    val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
              m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
              m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    if (abs(det) < 1e-12) return null
    return listOf(
        listOf(
            (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det,
            (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det,
            (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det,
        ),
        listOf(
            (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det,
            (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det,
            (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det,
        ),
        listOf(
            (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det,
            (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det,
            (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det,
        ),
    )
}

/**
 * Gaussian elimination with partial pivoting on a square system.
 *
 * The 1e-10 pivot floor is the TS constant, not a rounded-off 1e-12: it is
 * what decides that a degenerate keypoint layout has no homography, so
 * loosening or tightening it changes which frames get court coordinates.
 */
private fun solveLinearSystem(a: List<List<Double>>, b: List<Double>): List<Double>? {
    val n = a.size
    if (n == 0 || a[0].size != n) return null
    val aug = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }

    for (col in 0 until n) {
        var pivot = col
        for (row in col + 1 until n) {
            if (abs(aug[row][col]) > abs(aug[pivot][col])) pivot = row
        }
        val tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp
        if (abs(aug[col][col]) < 1e-10) return null

        for (row in col + 1 until n) {
            val factor = aug[row][col] / aug[col][col]
            for (j in col..n) aug[row][j] -= factor * aug[col][j]
        }
    }

    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var v = aug[i][n]
        for (j in i + 1 until n) v -= aug[i][j] * x[j]
        x[i] = v / aug[i][i]
    }
    return x.toList()
}

/** Overdetermined system by the normal equations, as the TS does. */
private fun solveLeastSquares(a: List<List<Double>>, b: List<Double>): List<Double>? {
    val n = a.firstOrNull()?.size ?: return null
    if (n == 0) return null
    val ata = MutableList(n) { MutableList(n) { 0.0 } }
    val atb = MutableList(n) { 0.0 }
    for (r in a.indices) {
        for (i in 0 until n) {
            atb[i] += a[r][i] * b[r]
            for (j in 0 until n) ata[i][j] += a[r][i] * a[r][j]
        }
    }
    return solveLinearSystem(ata, atb)
}
