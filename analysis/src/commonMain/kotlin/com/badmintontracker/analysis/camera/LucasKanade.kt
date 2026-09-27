package com.badmintontracker.analysis.camera

import kotlin.math.sqrt

internal const val PYRAMID_LEVELS = 3
internal const val LK_WINDOW_HALF = 10
internal const val LK_MAX_ITERATIONS = 20
internal const val LK_EPSILON = 0.03

/**
 * Minimum normalised structure-tensor eigenvalue (gradients in grey levels
 * per pixel, divided by the 441-pixel window area) below which a point is
 * too flat to track. Not OpenCV's 1e-4: that threshold is on OpenCV's own
 * gradient scaling, which differs from the Scharr-over-32 used here.
 * Measured on `Synthetic.texture` (seeds 2, 3, 7, 11), over all three
 * pyramid levels, at Shi-Tomasi's 150 corners: the smallest normalised
 * eigenvalue found (level 1, seed 3) was about 0.248, more than two orders
 * of magnitude above this floor, so 1e-3 rejects only a window flatter than
 * about one grey level of gradient without touching any corner the detector
 * already picked, at any level.
 */
internal const val LK_MIN_EIGEN = 1e-3

private const val WINDOW = (2 * LK_WINDOW_HALF + 1) * (2 * LK_WINDOW_HALF + 1)

/**
 * Pyramidal Lucas-Kanade from one fixed reference image, with everything that
 * depends only on the reference computed once. Design §4.3 step 3.
 *
 * The reference is the court-marking frame and never changes during a run,
 * so each point's template (grey levels and Scharr gradients over its 21x21
 * window, at every level) and its structure tensor are built here, and
 * tracking a frame samples only the target. That is Bouguet's formulation
 * with the reference's gradients, which is what OpenCV's
 * `calcOpticalFlowPyrLK` does.
 */
internal class AnchoredTemplates(reference: List<GreyImage>, val points: List<Pair<Double, Double>>) {
    private val levels = reference.size
    // [level][point] -> values, gx, gy over the window, row-major.
    private val values = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gxs = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gys = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    // [level] -> per point a, b, c of [[a, b], [b, c]], and whether usable.
    private val tensor = Array(levels) { DoubleArray(points.size * 3) }
    private val usable = Array(levels) { BooleanArray(points.size) }

    init {
        for (level in 0 until levels) {
            val img = reference[level]
            val g = scharr(img)
            val gxImg = GreyImage(img.width, img.height, g.gx)
            val gyImg = GreyImage(img.width, img.height, g.gy)
            val scale = 1.0 / (1 shl level)
            points.forEachIndexed { i, (px, py) ->
                val cx = px * scale
                val cy = py * scale
                var a = 0.0
                var b = 0.0
                var c = 0.0
                var k = 0
                for (dy in -LK_WINDOW_HALF..LK_WINDOW_HALF) for (dx in -LK_WINDOW_HALF..LK_WINDOW_HALF) {
                    val ix = gxImg.sample(cx + dx, cy + dy)
                    val iy = gyImg.sample(cx + dx, cy + dy)
                    values[level][i][k] = img.sample(cx + dx, cy + dy)
                    gxs[level][i][k] = ix
                    gys[level][i][k] = iy
                    a += ix * ix; b += ix * iy; c += iy * iy
                    k++
                }
                tensor[level][3 * i] = a
                tensor[level][3 * i + 1] = b
                tensor[level][3 * i + 2] = c
                val minEig = ((a + c) - sqrt((a - c) * (a - c) + 4 * b * b)) / 2.0 / WINDOW
                usable[level][i] = minEig >= LK_MIN_EIGEN
            }
        }
    }

    /**
     * Tracks every point into [target] (a pyramid of the same depth), starting
     * from [guesses] (`x0, y0, x1, y1, ...` at level 0). Returns the positions
     * found and whether each point succeeded.
     */
    fun track(target: List<GreyImage>, guesses: DoubleArray): Pair<DoubleArray, BooleanArray> {
        require(target.size == levels)
        val out = DoubleArray(points.size * 2)
        val ok = BooleanArray(points.size)
        val top = levels - 1
        for (i in points.indices) {
            val (px, py) = points[i]
            // The guess as a displacement, carried down the pyramid (Bouguet).
            var gx = (guesses[2 * i] - px) / (1 shl top)
            var gy = (guesses[2 * i + 1] - py) / (1 shl top)
            var failed = false
            var dx = 0.0
            var dy = 0.0
            for (level in top downTo 0) {
                if (!usable[level][i]) { failed = true; break }
                val img = target[level]
                val scale = 1.0 / (1 shl level)
                val cx = px * scale
                val cy = py * scale
                val a = tensor[level][3 * i]
                val b = tensor[level][3 * i + 1]
                val c = tensor[level][3 * i + 2]
                val det = a * c - b * b
                if (det <= 1e-12) { failed = true; break }
                var vx = 0.0
                var vy = 0.0
                val t = values[level][i]
                val ix = gxs[level][i]
                val iy = gys[level][i]
                for (iteration in 0 until LK_MAX_ITERATIONS) {
                    var bx = 0.0
                    var by = 0.0
                    var k = 0
                    val ox = cx + gx + vx
                    val oy = cy + gy + vy
                    for (wy in -LK_WINDOW_HALF..LK_WINDOW_HALF) for (wx in -LK_WINDOW_HALF..LK_WINDOW_HALF) {
                        val diff = t[k] - img.sample(ox + wx, oy + wy)
                        bx += diff * ix[k]
                        by += diff * iy[k]
                        k++
                    }
                    val ex = (c * bx - b * by) / det
                    val ey = (a * by - b * bx) / det
                    vx += ex
                    vy += ey
                    if (ex * ex + ey * ey <= LK_EPSILON * LK_EPSILON) break
                }
                if (level > 0) {
                    gx = 2 * (gx + vx)
                    gy = 2 * (gy + vy)
                } else {
                    dx = gx + vx
                    dy = gy + vy
                }
            }
            val x = px + dx
            val y = py + dy
            val base = target[0]
            if (failed || x.isNaN() || y.isNaN() || x < 0 || y < 0 || x > base.width - 1 || y > base.height - 1) {
                ok[i] = false
            } else {
                out[2 * i] = x
                out[2 * i + 1] = y
                ok[i] = true
            }
        }
        return out to ok
    }
}
