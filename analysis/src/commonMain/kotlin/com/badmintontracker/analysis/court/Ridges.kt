package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The work image's line pixels (design §4.3) and, per pixel, the direction
 * of the line's normal in radians - the orientation the Hough stage votes
 * with. [normal] is defined for every pixel, meaningful only where [mask] is
 * set.
 */
internal class RidgeMap(val width: Int, val height: Int, val mask: BooleanArray, val normal: FloatArray)

/** Hessian scales, px: a line responds most at a scale matched to its width, so three cover thin far lines to wider near ones. */
private val RIDGE_SCALES = doubleArrayOf(1.0, 1.8, 3.0)

/** Local contrast box, px: much wider than a line, so the std it measures is dominated by the surrounding floor. */
private const val CONTRAST_BOX = 41

/** Added to the local std so a perfectly flat floor does not divide by zero or amplify noise. */
private const val CONTRAST_FLOOR = 6.0

/** Response over (local std + [CONTRAST_FLOOR]) a line pixel must exceed. */
private const val CONTRAST_RATIO = 1.2

/** Raw response a line pixel must exceed, whatever the local contrast. */
private const val MIN_RESPONSE = 4.0

/** Shortest kept component, as its bounding-box diagonal over the long side. */
private const val MIN_COMPONENT_DIAGONAL = 0.03

/**
 * `court_probe.py`'s `ridge()`: pixels on painted lines, of either polarity,
 * with their normal direction.
 *
 * At each scale `s` the blurred image's Hessian has eigenvalues
 * `l1 >= l2`; a bright line on a darker floor curves down across itself
 * (`l2 < 0`) and is flat along itself (`l1` near 0), so `(-l2 - |l1|) s^2`
 * rewards it and penalises blobs and corners where both curve. The dark-line
 * response mirrors it, which is how a blue line on a pale floor is found.
 * The `s^2` factor makes scales comparable, so the best scale per pixel is a
 * fair contest. `theta = atan2(2 dxy, dxx - dyy) / 2` is the direction of
 * the `l1` eigenvector: the along-line direction for a bright line (hence
 * the normal `theta + pi/2`) and the across-line direction for a dark one.
 *
 * Non-maximum suppression thins each line to its crest by comparing the
 * response one pixel either side along the normal, bilinear with zero
 * outside the image as `cv2.remap`'s default border does. The contrast test
 * then drops texture on a busy floor, and short connected components (text,
 * crowd, wood grain) go last.
 */
internal fun ridges(work: GreyImage): RidgeMap {
    val w = work.width
    val h = work.height
    val n = w * h
    val best = FloatArray(n)
    val normal = FloatArray(n)
    for (s in RIDGE_SCALES) {
        val d = sobel2(gaussianBlur(work, s))
        val s2 = s * s
        for (i in 0 until n) {
            val dxx = d.dxx[i].toDouble()
            val dyy = d.dyy[i].toDouble()
            val dxy = d.dxy[i].toDouble()
            val half = (dxx - dyy) / 2
            val det = sqrt(half * half + dxy * dxy)
            val l1 = (dxx + dyy) / 2 + det
            val l2 = (dxx + dyy) / 2 - det
            val bright = if (l2 < 0) (-l2 - abs(l1)) * s2 else 0.0
            val dark = if (l1 > 0) (l1 - abs(l2)) * s2 else 0.0
            val r = max(bright, dark)
            if (r > best[i]) {
                best[i] = r.toFloat()
                val theta = 0.5 * atan2(2 * dxy, dxx - dyy)
                normal[i] = (if (bright >= dark) theta + PI / 2 else theta).toFloat()
            }
        }
    }

    val (_, sd) = boxMeanStd(work, CONTRAST_BOX)
    val mask = BooleanArray(n)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val b = best[i].toDouble()
        if (b <= MIN_RESPONSE || b / (sd[i] + CONTRAST_FLOOR) <= CONTRAST_RATIO) continue
        val nx = cos(normal[i].toDouble())
        val ny = sin(normal[i].toDouble())
        if (b < sampleZeroBorder(best, w, h, x + nx, y + ny)) continue
        if (b < sampleZeroBorder(best, w, h, x - nx, y - ny)) continue
        mask[i] = true
    }
    val kept = keepLongComponents(mask, w, h, MIN_COMPONENT_DIAGONAL * max(w, h))
    return RidgeMap(w, h, kept, normal)
}

/**
 * Bilinear sample with every neighbour outside the image read as 0, to
 * match `cv2.remap(..., INTER_LINEAR)` with its default `BORDER_CONSTANT`
 * as the probe calls it ([GreyImage.sample] replicates the border instead).
 */
private fun sampleZeroBorder(v: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val fx = x - x0
    val fy = y - y0
    fun at(xi: Int, yi: Int): Double = if (xi < 0 || yi < 0 || xi >= w || yi >= h) 0.0 else v[yi * w + xi].toDouble()
    return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
        (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
}
