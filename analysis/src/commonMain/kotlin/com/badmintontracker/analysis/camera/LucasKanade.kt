package com.badmintontracker.analysis.camera

import kotlin.math.floor
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

private const val SIDE = 2 * LK_WINDOW_HALF + 1
private const val WINDOW = SIDE * SIDE

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
 *
 * This is most of what following the camera costs: on the corpus recording
 * the inner loop runs about 15 times per point (5 per level) for 150 points
 * on every sample, 441 pixels each. So a window is sampled through
 * [WindowTaps] rather than [GreyImage.sample]: the same bilinear arithmetic,
 * term for term, but with the clamping and flooring done once per window
 * column and row (42 times) instead of once per pixel (441 times), no call
 * per pixel, and each image row's horizontal blend computed once and shared
 * by the two window rows that read it. The results are the same to the last
 * bit. What that saved, and what a sample costs on each platform it was
 * measured on, are in the design doc's §10
 * (`docs/plans/2026-09-27-camera-motion-design.md`, "Tracker cost"); the
 * host figures come from the JVM-only `CameraTrackerBenchmarkTest`.
 *
 * Holds scratch buffers, so one instance must not [track] on two threads at
 * once; the camera tracker is single-threaded.
 */
internal class AnchoredTemplates(reference: List<GreyImage>, val points: List<Pair<Double, Double>>) {
    private val levels = reference.size
    private val px = DoubleArray(points.size) { points[it].first }
    private val py = DoubleArray(points.size) { points[it].second }
    // [level][point] -> values, gx, gy over the window, row-major.
    private val values = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gxs = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gys = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    // [level] -> per point a, b, c of [[a, b], [b, c]], and whether usable.
    private val tensor = Array(levels) { DoubleArray(points.size * 3) }
    private val usable = Array(levels) { BooleanArray(points.size) }
    private val taps = WindowTaps()

    init {
        for (level in 0 until levels) {
            val img = reference[level]
            val g = scharr(img)
            val p = img.pixels
            val gxp = g.gx
            val gyp = g.gy
            val scale = 1.0 / (1 shl level)
            val col0 = taps.col0
            val col1 = taps.col1
            val fxs = taps.fx
            val gfxs = taps.gfx
            for (i in points.indices) {
                taps.place(px[i] * scale, py[i] * scale, img.width, img.height)
                val v = values[level][i]
                val gxw = gxs[level][i]
                val gyw = gys[level][i]
                var a = 0.0
                var b = 0.0
                var c = 0.0
                var k = 0
                for (wy in 0 until SIDE) {
                    val r0 = taps.row0[wy]
                    val r1 = taps.row1[wy]
                    val fy = taps.fy[wy]
                    val gfy = taps.gfy[wy]
                    for (wx in 0 until SIDE) {
                        val i00 = r0 + col0[wx]
                        val i01 = r0 + col1[wx]
                        val i10 = r1 + col0[wx]
                        val i11 = r1 + col1[wx]
                        val fx = fxs[wx]
                        val gfx = gfxs[wx]
                        val ix = bilinear(gxp[i00], gxp[i01], gxp[i10], gxp[i11], fx, gfx, fy, gfy)
                        val iy = bilinear(gyp[i00], gyp[i01], gyp[i10], gyp[i11], fx, gfx, fy, gfy)
                        v[k] = bilinear(p[i00], p[i01], p[i10], p[i11], fx, gfx, fy, gfy)
                        gxw[k] = ix
                        gyw[k] = iy
                        a += ix * ix; b += ix * iy; c += iy * iy
                        k++
                    }
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
        val coarsest = levels - 1
        val col0 = taps.col0
        val col1 = taps.col1
        val fxs = taps.fx
        val gfxs = taps.gfx
        val row0 = taps.row0
        val row1 = taps.row1
        val fys = taps.fy
        val gfys = taps.gfy
        val upper = DoubleArray(SIDE)
        val lower = DoubleArray(SIDE)
        for (i in points.indices) {
            val x0 = px[i]
            val y0 = py[i]
            // The guess as a displacement, carried down the pyramid (Bouguet).
            var gx = (guesses[2 * i] - x0) / (1 shl coarsest)
            var gy = (guesses[2 * i + 1] - y0) / (1 shl coarsest)
            var failed = false
            var dx = 0.0
            var dy = 0.0
            for (level in coarsest downTo 0) {
                if (!usable[level][i]) { failed = true; break }
                val img = target[level]
                val p = img.pixels
                val scale = 1.0 / (1 shl level)
                val cx = x0 * scale
                val cy = y0 * scale
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
                    taps.place(ox, oy, img.width, img.height)
                    var top = upper
                    var bottom = lower
                    for (wy in 0 until SIDE) {
                        val r0 = row0[wy]
                        val r1 = row1[wy]
                        val fy = fys[wy]
                        val gfy = gfys[wy]
                        // A window row's lower image row is, away from the
                        // border, the next one's upper row: blend it across once.
                        if (wy > 0 && r0 == row1[wy - 1]) {
                            val swap = top; top = bottom; bottom = swap
                        } else {
                            for (wx in 0 until SIDE) top[wx] = p[r0 + col0[wx]] * gfxs[wx] + p[r0 + col1[wx]] * fxs[wx]
                        }
                        for (wx in 0 until SIDE) bottom[wx] = p[r1 + col0[wx]] * gfxs[wx] + p[r1 + col1[wx]] * fxs[wx]
                        for (wx in 0 until SIDE) {
                            val sample = top[wx] * gfy + bottom[wx] * fy
                            val diff = t[k] - sample
                            bx += diff * ix[k]
                            by += diff * iy[k]
                            k++
                        }
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
            val x = x0 + dx
            val y = y0 + dy
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

/**
 * Where the bilinear taps of a 21x21 window centred at `(cx, cy)` fall, one
 * axis at a time. [GreyImage.sample] clamps, floors and weighs `x` and `y`
 * independently, so the 441 samples of a window need only these 21 columns
 * and 21 rows of it, computed here by exactly its steps (the same
 * `coerceIn`, `floor` and `coerceAtMost`, so borders and NaN behave the same).
 */
private class WindowTaps {
    /** Left and right tap columns, and the weight of the right one (`fx`) and of the left (`1 - fx`). */
    val col0 = IntArray(SIDE)
    val col1 = IntArray(SIDE)
    val fx = DoubleArray(SIDE)
    val gfx = DoubleArray(SIDE)
    /** Upper and lower tap rows as offsets into the pixels (`y * width`), and their weights likewise. */
    val row0 = IntArray(SIDE)
    val row1 = IntArray(SIDE)
    val fy = DoubleArray(SIDE)
    val gfy = DoubleArray(SIDE)

    fun place(cx: Double, cy: Double, width: Int, height: Int) {
        for (j in 0 until SIDE) {
            val x = (cx + (j - LK_WINDOW_HALF)).coerceIn(0.0, (width - 1).toDouble())
            val x0 = floor(x).toInt().coerceAtMost(width - 1)
            col0[j] = x0
            col1[j] = (x0 + 1).coerceAtMost(width - 1)
            fx[j] = x - x0
            gfx[j] = 1 - fx[j]
            val y = (cy + (j - LK_WINDOW_HALF)).coerceIn(0.0, (height - 1).toDouble())
            val y0 = floor(y).toInt().coerceAtMost(height - 1)
            row0[j] = y0 * width
            row1[j] = (y0 + 1).coerceAtMost(height - 1) * width
            fy[j] = y - y0
            gfy[j] = 1 - fy[j]
        }
    }
}

/**
 * [GreyImage.sample]'s blend of four taps, term for term: `a` top-left, `b`
 * top-right, `c` bottom-left, `d` bottom-right, [gfx] being `1 - fx` and
 * [gfy] `1 - fy`. Inline so the hot loop makes no call.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun bilinear(a: Float, b: Float, c: Float, d: Float, fx: Double, gfx: Double, fy: Double, gfy: Double): Double =
    (a * gfx + b * fx) * gfy + (c * gfx + d * fx) * fy
