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

/**
 * Video pixels to court metres, using all 12 keypoints for a better fit.
 *
 * The service-line and centre pairs are matched to the court by where their
 * pixels sit, not by the "near" and "far" in their names. See [courtPositions].
 */
fun CourtKeypoints.homography(): Matrix3x3? = calculateHomography(pixels(), courtPositions())

/** The 12 marked pixels, in [COURT_KEYPOINT_POSITIONS] order. */
fun CourtKeypoints.pixels(): List<Point> = listOf(
    topLeft, topRight, bottomRight, bottomLeft, netLeft, netRight,
    serviceLineNearLeft, serviceLineNearRight,
    serviceLineFarLeft, serviceLineFarRight, centerNear, centerFar,
)

/**
 * The court position of each marked pixel, in [pixels] order.
 *
 * [COURT_KEYPOINT_POSITIONS] puts the "near" service line and centre point on
 * the top-of-frame half of the court, 1.98m short of the net, and the "far"
 * ones on the bottom half. The marking screens on both the web app and this
 * app label those points only "Service Near-Left", "Center-Near" and so on,
 * and a person marking a court reads "near" as near the camera at least as
 * often as near the top. Of the three corpus videos, one was marked top-as-near,
 * one bottom-as-near and one with the service lines one way and the centre
 * points the other, and the stored labels carry no hint which. Fitting the
 * table as written to the two mismarked videos gave residuals of 3.5m at the
 * swapped points and 1.1 to 1.6m at every corner: the whole map off by more
 * than a metre, from four points that were placed perfectly well.
 *
 * So the label decides nothing here. Each pair is resolved by which side of
 * the marked net line its pixels fall on: the one above the net takes the
 * top-half position and the other the bottom-half one. Pixels that agree with
 * the table give exactly the table, so the parity with the TypeScript reference
 * is unchanged for correctly labelled input. A pair whose two pixels fall on
 * the same side of the net cannot be resolved and is used as labelled; the
 * residual that leaves is what [maxResidualM] exists to report.
 */
fun CourtKeypoints.courtPositions(): List<Point> {
    val positions = COURT_KEYPOINT_POSITIONS.toMutableList()
    fun resolve(nearIndex: Int, farIndex: Int) {
        val near = pixels()[nearIndex]
        val far = pixels()[farIndex]
        val nearAbove = near.y < netYAt(near.x)
        val farAbove = far.y < netYAt(far.x)
        if (nearAbove == farAbove) return
        if (!nearAbove) {
            positions[nearIndex] = COURT_KEYPOINT_POSITIONS[farIndex]
            positions[farIndex] = COURT_KEYPOINT_POSITIONS[nearIndex]
        }
    }
    resolve(SERVICE_NEAR_LEFT, SERVICE_FAR_LEFT)
    resolve(SERVICE_NEAR_RIGHT, SERVICE_FAR_RIGHT)
    resolve(CENTER_NEAR, CENTER_FAR)
    return positions
}

/**
 * The net's y at this x, interpolated between the two marked net points.
 *
 * The same rule the near-player selector uses to decide sides, and for the
 * same reason: on an angled camera a pixel midline puts play near the net on
 * the wrong side.
 */
internal fun CourtKeypoints.netYAt(x: Double): Double {
    val span = netRight.x - netLeft.x
    if (span == 0.0) return (netLeft.y + netRight.y) / 2.0
    val t = (x - netLeft.x) / span
    return netLeft.y + t * (netRight.y - netLeft.y)
}

/**
 * How badly the marks fit a court, as the largest distance in metres between
 * where a marked pixel lands under [h] and where its court position says it
 * should. Null if any pixel projects to infinity.
 *
 * Well-placed marks on the corpus videos fit to 0.37m at worst. A pair of
 * points swapped, a corner clicked in the wrong order, or a service line marked
 * on the wrong side of the net shows up here as metres.
 */
fun CourtKeypoints.maxResidualM(h: Matrix3x3): Double? {
    var worst = 0.0
    pixels().zip(courtPositions()).forEach { (pixel, court) ->
        val got = h.apply(pixel.x, pixel.y) ?: return null
        val d = sqrt((got.x - court.x) * (got.x - court.x) + (got.y - court.y) * (got.y - court.y))
        if (d > worst) worst = d
    }
    return worst
}

private const val SERVICE_NEAR_LEFT = 6
private const val SERVICE_NEAR_RIGHT = 7
private const val SERVICE_FAR_LEFT = 8
private const val SERVICE_FAR_RIGHT = 9
private const val CENTER_NEAR = 10
private const val CENTER_FAR = 11

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
