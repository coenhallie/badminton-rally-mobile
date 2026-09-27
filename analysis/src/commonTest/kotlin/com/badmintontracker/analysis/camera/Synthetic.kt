package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Test images with texture a tracker can hold on to, and known warps of them. */
object Synthetic {
    /**
     * Two very low frequency crossed sinusoids (near-linear over any tracker
     * window, so the pyramid's coarse levels see a smooth, unambiguous slope
     * instead of an aliased mush) plus a jittered grid of small blobs, one
     * roughly every 22 px, each far enough from its neighbours that a 21x21
     * tracker window sees only its own.
     *
     * Blobs on a plain random scatter (this texture's first shape) gave
     * Shi-Tomasi 150 well-formed corners, but each corner sits 2-4 px off its
     * own blob's centre (that offset is what curvature-based corner detection
     * selects), and with blobs scattered freely a second blob often fell
     * inside the same window, off-centring the window's gradient energy by as
     * much as 4 px from the tracked point. Lucas-Kanade fits one translation
     * per window; under a small local rotation the fit is biased by roughly
     * the rotation angle times that off-centre distance. On the unmodified
     * random-scatter texture (seed 7) that measured 0.346 px against
     * LucasKanadeTest's rotation case, over its bound. Confirmed not a
     * pyramid or convergence bug: tracking the same corners through a
     * single-level pyramid reproduced the same bias to three decimal places.
     *
     * The grid keeps neighbours outside the window so only the corner's own
     * blob contributes, and a smaller sigma (0.9 to 1.4 px) keeps that
     * blob's own centre-to-corner offset small. This is a per-point,
     * per-texture bias, not a single number: it varies by seed because it
     * depends on how each seed's random jitter happens to place blobs
     * relative to the corners Shi-Tomasi picks. Measured worst case in
     * LucasKanadeTest's rotation case on this texture, per seed:
     *
     * | seed | worst (px) |
     * |------|------------|
     * | 2    | 0.118      |
     * | 3    | 0.113      |
     * | 7    | 0.086      |
     * | 11   | 0.088      |
     * | 13   | 0.088      |
     * | 42   | 0.083      |
     *
     * `LucasKanadeTest` bounds this at 0.15 px over seeds 2, 3, 7 and 11 (see
     * its KDoc for why that bound, not a tighter one, is the right test).
     *
     * This configuration (pitch 22, jitter 2, sigma 0.9-1.4, amplitude 20)
     * is a narrow working point, not a wide plateau: it was chosen from
     * roughly a dozen pitch/jitter/sigma/amplitude combinations, and nearby
     * ones fail differently (pitch 21 relocked the 30 px pan case onto the
     * wrong basin at over 5 px; jitter 1.5 or 0 pushed the rotation case
     * back over 0.11-0.13 px). Anyone editing the constants below must
     * re-run both `CornersTest` and `LucasKanadeTest`, not just skim the
     * diff.
     */
    fun texture(width: Int, height: Int, seed: Int): GreyImage {
        val r = Random(seed)
        val pitch = 22.0
        val jitter = 2.0
        val blobs = ArrayList<Triple<Double, Double, Double>>()
        var gy = pitch / 2
        while (gy < height) {
            var gx = pitch / 2
            while (gx < width) {
                val bx = (gx + (r.nextDouble() - 0.5) * 2 * jitter).coerceIn(0.0, width.toDouble())
                val by = (gy + (r.nextDouble() - 0.5) * 2 * jitter).coerceIn(0.0, height.toDouble())
                blobs.add(Triple(bx, by, 0.9 + r.nextDouble() * 0.5))
                gx += pitch
            }
            gy += pitch
        }
        val px = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var v = 128.0 + 55 * sin(x * 0.015) * sin(y * 0.012) + 35 * sin(x * 0.035 + 1.0) * sin(y * 0.03 + 0.5)
            for ((bx, by, s) in blobs) {
                val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
                v += 20 * kotlin.math.exp(-d2 / (2 * s * s)) * (if ((bx + by).toInt() % 2 == 0) 1 else -1)
            }
            px[y * width + x] = v.coerceIn(0.0, 255.0).toFloat()
        }
        return GreyImage(width, height, px)
    }

    /** `dst(p) = src(m⁻¹ p)`: [m] maps src pixels to dst pixels. */
    fun warp(src: GreyImage, m: Matrix3x3): GreyImage {
        val inv = invert3x3(m)!!
        val px = FloatArray(src.width * src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val s = inv.apply(x.toDouble(), y.toDouble())!!
            px[y * src.width + x] = src.sample(s.x, s.y).toFloat()
        }
        return GreyImage(src.width, src.height, px)
    }

    /**
     * [count] frames of [base] under a steady pan (3 px right and 2 px up a
     * frame) and a slow roll (0.004 rad a frame, centred on the middle frame):
     * the run `CameraTrackerBenchmarkTest` times and `CameraTrackerWarpedRunTest`
     * follows.
     */
    fun panAndRoll(base: GreyImage, count: Int): List<GreyImage> = List(count) { i ->
        val a = 0.004 * (i - count / 2)
        warp(base, listOf(
            listOf(cos(a), -sin(a), 3.0 * i - 12),
            listOf(sin(a), cos(a), -2.0 * i + 7),
            listOf(0.0, 0.0, 1.0),
        ))
    }
}
