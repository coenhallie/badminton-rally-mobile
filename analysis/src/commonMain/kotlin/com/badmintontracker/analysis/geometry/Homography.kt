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
    val out = DoubleArray(9)
    val fitted = HomographySolver(n).fit(
        DoubleArray(n) { src[it].x }, DoubleArray(n) { src[it].y },
        DoubleArray(n) { dst[it].x }, DoubleArray(n) { dst[it].y },
        IntArray(n) { it }, n, out,
    )
    return if (fitted) out.toMatrix3x3() else null
}

/**
 * [calculateHomography] on primitive arrays, with every buffer it needs held
 * here and reused, for a caller that fits many times: the camera tracker's
 * RANSAC fits 300 four-point models per sample. [calculateHomography] itself
 * goes through this, so there is one DLT and the two cannot drift apart.
 *
 * The arithmetic is operation for operation that of the `List<List<Double>>`
 * DLT this replaced (sums from 0.0 in point order, the same pivoting, the zero
 * terms of each 3x3 product included), so replacing it changed no fit, to the
 * last bit. Keep it that way: the camera tracker's RANSAC counts inliers
 * against a hard threshold, and a last-bit change can move a point across it.
 * Not thread-safe: one solver per thread.
 */
internal class HomographySolver(capacity: Int) {
    // Normalised coordinates of the points being fitted, in fitting order.
    private val nsx = DoubleArray(capacity)
    private val nsy = DoubleArray(capacity)
    private val ndx = DoubleArray(capacity)
    private val ndy = DoubleArray(capacity)
    private val tSrc = DoubleArray(9)
    private val tDst = DoubleArray(9)
    private val tDstInv = DoubleArray(9)
    private val hn = DoubleArray(9)
    private val product = DoubleArray(9)
    // The 8 x 9 augmented system: [A | b] for four points, [AᵀA | Aᵀb] for more.
    private val aug = DoubleArray(8 * 9)
    private val row = DoubleArray(8)
    private val h = DoubleArray(8)

    /**
     * Fits the [count] correspondences `indices[0 until count]` of
     * ([srcX], [srcY]) to ([dstX], [dstY]) into [out], row-major. False, with
     * [out] unspecified, where [calculateHomography] would return null.
     */
    fun fit(
        srcX: DoubleArray, srcY: DoubleArray, dstX: DoubleArray, dstY: DoubleArray,
        indices: IntArray, count: Int, out: DoubleArray,
    ): Boolean {
        if (count < 4) return false
        if (!normalize(srcX, srcY, indices, count, nsx, nsy, tSrc)) return false
        if (!normalize(dstX, dstY, indices, count, ndx, ndy, tDst)) return false

        // Two rows per correspondence: the standard DLT constraint on h with
        // h33 fixed to 1, leaving 8 unknowns. Exactly four correspondences
        // make the system square and exactly determined, so it is solved
        // directly. Routing it through the normal equations instead would
        // square the condition number for no gain.
        aug.fill(0.0)
        for (i in 0 until count) {
            val x = nsx[i]
            val y = nsy[i]
            val u = ndx[i]
            val v = ndy[i]
            setRow(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
            addRow(2 * i, u, count)
            setRow(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
            addRow(2 * i + 1, v, count)
        }
        if (!solveAugmented()) return false
        hn[0] = h[0]; hn[1] = h[1]; hn[2] = h[2]
        hn[3] = h[3]; hn[4] = h[4]; hn[5] = h[5]
        hn[6] = h[6]; hn[7] = h[7]; hn[8] = 1.0

        // Undo normalization: H = Tdst^-1 * Hn * Tsrc
        if (!invert3x3Into(tDst, tDstInv)) return false
        multiply3x3Into(hn, tSrc, product)
        multiply3x3Into(tDstInv, product, out)
        return true
    }

    private fun setRow(a0: Double, a1: Double, a2: Double, a3: Double, a4: Double, a5: Double, a6: Double, a7: Double) {
        row[0] = a0; row[1] = a1; row[2] = a2; row[3] = a3
        row[4] = a4; row[5] = a5; row[6] = a6; row[7] = a7
    }

    /**
     * Puts [row] (with right-hand side [b]) into the system as row [r] of the
     * square one when there are four points, or, when there are more, adds its
     * contribution to the normal equations as the TS does.
     */
    private fun addRow(r: Int, b: Double, count: Int) {
        if (count == 4) {
            for (c in 0 until 8) aug[r * 9 + c] = row[c]
            aug[r * 9 + 8] = b
            return
        }
        for (i in 0 until 8) {
            aug[i * 9 + 8] += row[i] * b
            for (j in 0 until 8) aug[i * 9 + j] += row[i] * row[j]
        }
    }

    /**
     * Gaussian elimination with partial pivoting on the 8 x 9 [aug], into [h].
     *
     * The 1e-10 pivot floor is the TS constant, not a rounded-off 1e-12: it is
     * what decides that a degenerate keypoint layout has no homography, so
     * loosening or tightening it changes which frames get court coordinates.
     */
    private fun solveAugmented(): Boolean {
        val n = 8
        val w = n + 1
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) {
                if (abs(aug[r * w + col]) > abs(aug[pivot * w + col])) pivot = r
            }
            if (pivot != col) {
                for (j in 0 until w) {
                    val tmp = aug[col * w + j]
                    aug[col * w + j] = aug[pivot * w + j]
                    aug[pivot * w + j] = tmp
                }
            }
            if (abs(aug[col * w + col]) < 1e-10) return false

            for (r in col + 1 until n) {
                val factor = aug[r * w + col] / aug[col * w + col]
                for (j in col..n) aug[r * w + j] -= factor * aug[col * w + j]
            }
        }
        for (i in n - 1 downTo 0) {
            var v = aug[i * w + n]
            for (j in i + 1 until n) v -= aug[i * w + j] * h[j]
            h[i] = v / aug[i * w + i]
        }
        return true
    }
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
 * The court fit for a frame the camera had moved in: [h] fits the marking
 * frame, and [camera] maps marking-frame pixels to this frame's pixels, so a
 * pixel here reaches the court through `h · camera⁻¹`. Null [camera] means the
 * camera is not being followed, and the fit is [h] itself. Design §5.3.
 */
fun courtFitAt(h: Matrix3x3, camera: Matrix3x3?): Matrix3x3? {
    if (camera == null) return h
    val inverse = invert3x3(camera) ?: return null
    return multiply3x3(h, inverse)
}

/**
 * Court metres per source pixel along the image x axis at [p], by finite
 * difference; null when either side of [p] projects to infinity.
 *
 * The horizontal scale on the floor at a point. It is the scale of anything
 * at the same depth that lies across the image, which a shoulder line and,
 * up to foreshortening, a torso do. It is not a vertical scale: a camera
 * tilted down sees vertical lengths shorter by the cosine of the tilt.
 */
fun Matrix3x3.metresPerPixelAt(p: Point, stepPx: Double = 4.0): Double? {
    val a = apply(p.x - stepPx, p.y) ?: return null
    val b = apply(p.x + stepPx, p.y) ?: return null
    return sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)) / (2 * stepPx)
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

/**
 * Hartley normalisation of the [count] points `indices[0 until count]`:
 * centroid to the origin, mean distance from it to sqrt(2). Writes the
 * normalised points to [nx], [ny] in that order and the transform to [t];
 * false for coincident points.
 */
private fun normalize(
    xs: DoubleArray, ys: DoubleArray, indices: IntArray, count: Int,
    nx: DoubleArray, ny: DoubleArray, t: DoubleArray,
): Boolean {
    var sumX = 0.0
    var sumY = 0.0
    for (k in 0 until count) sumX += xs[indices[k]]
    for (k in 0 until count) sumY += ys[indices[k]]
    val cx = sumX / count
    val cy = sumY / count
    var sumDist = 0.0
    for (k in 0 until count) {
        val x = xs[indices[k]]
        val y = ys[indices[k]]
        sumDist += sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
    }
    val meanDist = sumDist / count
    // The TS falls back to scale = 1 here. Bailing instead is the same outcome
    // by a shorter route: coincident points cannot determine a homography, and
    // the solve downstream would reject them anyway.
    if (meanDist < 1e-12) return false
    val s = sqrt(2.0) / meanDist
    t[0] = s; t[1] = 0.0; t[2] = -s * cx
    t[3] = 0.0; t[4] = s; t[5] = -s * cy
    t[6] = 0.0; t[7] = 0.0; t[8] = 1.0
    for (k in 0 until count) {
        nx[k] = (xs[indices[k]] - cx) * s
        ny[k] = (ys[indices[k]] - cy) * s
    }
    return true
}

private fun Matrix3x3.toRowMajor(): DoubleArray =
    DoubleArray(9) { this[it / 3][it % 3] }

internal fun DoubleArray.toMatrix3x3(): Matrix3x3 =
    listOf(listOf(this[0], this[1], this[2]), listOf(this[3], this[4], this[5]), listOf(this[6], this[7], this[8]))

/**
 * 3x3 matrix multiply, `a * b`. Public for the camera tracker's [courtFitAt]
 * and for the Skeleton panel, which both compose homographies outside this file.
 */
fun multiply3x3(a: Matrix3x3, b: Matrix3x3): Matrix3x3 {
    val out = DoubleArray(9)
    multiply3x3Into(a.toRowMajor(), b.toRowMajor(), out)
    return out.toMatrix3x3()
}

/** [multiply3x3] on row-major arrays; each entry summed from 0.0 in `k` order. */
internal fun multiply3x3Into(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
    for (r in 0..2) for (c in 0..2) {
        var sum = 0.0
        for (k in 0..2) sum += a[r * 3 + k] * b[k * 3 + c]
        out[r * 3 + c] = sum
    }
}

/**
 * 3x3 matrix inverse, null when singular. Public for the camera tracker's
 * [courtFitAt] and for the Skeleton panel, which both invert homographies
 * outside this file.
 */
fun invert3x3(m: Matrix3x3): Matrix3x3? {
    val out = DoubleArray(9)
    return if (invert3x3Into(m.toRowMajor(), out)) out.toMatrix3x3() else null
}

/** [invert3x3] on row-major arrays; false when singular. */
internal fun invert3x3Into(m: DoubleArray, out: DoubleArray): Boolean {
    val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
              m[1] * (m[3] * m[8] - m[5] * m[6]) +
              m[2] * (m[3] * m[7] - m[4] * m[6])
    if (abs(det) < 1e-12) return false
    out[0] = (m[4] * m[8] - m[5] * m[7]) / det
    out[1] = (m[2] * m[7] - m[1] * m[8]) / det
    out[2] = (m[1] * m[5] - m[2] * m[4]) / det
    out[3] = (m[5] * m[6] - m[3] * m[8]) / det
    out[4] = (m[0] * m[8] - m[2] * m[6]) / det
    out[5] = (m[2] * m[3] - m[0] * m[5]) / det
    out[6] = (m[3] * m[7] - m[4] * m[6]) / det
    out[7] = (m[1] * m[6] - m[0] * m[7]) / det
    out[8] = (m[0] * m[4] - m[1] * m[3]) / det
    return true
}
