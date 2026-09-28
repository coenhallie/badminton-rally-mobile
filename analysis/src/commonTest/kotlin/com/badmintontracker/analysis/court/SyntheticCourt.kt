package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.invert3x3Into
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic courts with exact truth: a port of `tools/court/synth.py`'s
 * `camera()` and `render()`, so the detector's tests run on the same scenes
 * the Python reference was tuned on. Homographies are court (metres) to
 * pixel, row-major, 9 entries.
 */
internal object SyntheticCourt {
    private const val W = CourtModel.W
    private const val L = CourtModel.L
    private const val S = CourtModel.S
    private const val LS = CourtModel.LS
    private const val O = CourtModel.O
    private const val LINE_WIDTH = 0.04

    /** `synth.py`'s distractor colour, BGR `(40,40,190)`: a red line. */
    private const val DISTRACTOR = 0xFFBE2828.toInt()

    /** `synth.py`'s occluder colour, BGR `(30,30,160)`: a dark red player-sized blob. */
    private const val OCCLUDER = 0xFFA01E1E.toInt()

    /**
     * A pinhole camera `back` metres behind the near baseline (model `y = L`),
     * `side` metres off the centre line and `height` metres up, looking at the
     * court centre; focal length `f` px, principal point at the image centre.
     * The camera's x axis is `cross(view, up)`, which runs toward model -x, so
     * raw model `(0,0)` lands at the top right of the frame (the far corner
     * on the right) - the reason [truth] relabels before projecting.
     */
    fun camera(
        side: Double = 0.0,
        height: Double = 4.5,
        back: Double = 6.0,
        f: Double = 1500.0,
        w: Int = 1920,
        h: Int = 1080,
    ): DoubleArray {
        val c = doubleArrayOf(W / 2 + side, L + back, height)
        val t = doubleArrayOf(W / 2, L / 2, 0.0)
        val z = normalise(doubleArrayOf(t[0] - c[0], t[1] - c[1], t[2] - c[2]))
        val x = normalise(cross(z, doubleArrayOf(0.0, 0.0, 1.0)))
        val y = cross(z, x)
        val r = arrayOf(x, y, z)
        // P = K [R | -R C]; the floor plane (Z = 0) keeps columns 0, 1 and 3.
        val k = arrayOf(doubleArrayOf(f, 0.0, w / 2.0), doubleArrayOf(0.0, f, h / 2.0), doubleArrayOf(0.0, 0.0, 1.0))
        val rt = DoubleArray(3) { i -> -(r[i][0] * c[0] + r[i][1] * c[1] + r[i][2] * c[2]) }
        val cols = arrayOf(DoubleArray(3) { r[it][0] }, DoubleArray(3) { r[it][1] }, rt)
        val out = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) {
            out[row * 3 + col] = k[row][0] * cols[col][0] + k[row][1] * cols[col][1] + k[row][2] * cols[col][2]
        }
        val s = out[8]
        for (i in 0..8) out[i] /= s
        return out
    }

    /**
     * The court drawn through `homography` onto a `width x height` frame:
     * painted lines 40 mm wide, laid out as `synth.py` lays them, on a flat
     * `floor`, a red circle and an adjacent court's sideline as distractors, two player-sized occluders, a Gaussian blur and
     * Gaussian noise. `floor` and `line` are ARGB.
     *
     * Classification is on a grid `supersample` (`ss`) times finer. The
     * homography is lifted to it so that output pixel centre `x` lands on
     * fine coordinate `ss * x + (ss - 1) / 2`, the centre of its `ss x ss`
     * block - the pixel-centre fix in `synth.py`, without which the whole
     * scene is shifted by `(ss - 1) / (2 ss)` px. Each output
     * pixel is the mean of its samples, rounded (`cv2.INTER_AREA` at an
     * integer ratio), computed per pixel so the supersampled image is never
     * allocated.
     *
     * Randomness comes from `Random(seed)`, drawn in `synth.py`'s order
     * (occluder positions, then noise); it cannot reproduce NumPy's stream,
     * so a scene matches the Python one in geometry, not pixel for pixel.
     */
    fun render(
        homography: DoubleArray,
        width: Int,
        height: Int,
        floor: Int,
        line: Int,
        supersample: Int = 3,
        noise: Double = 4.0,
        blur: Double = 0.8,
        distractors: Boolean = true,
        occluders: Boolean = true,
        seed: Int = 7,
    ): RgbImage {
        val ss = supersample
        val half = (ss - 1) / 2.0
        // Hs = [[ss,0,half],[0,ss,half],[0,0,1]] @ H; Hi = inverse(Hs) maps big pixels to metres.
        val hs = DoubleArray(9)
        for (col in 0..2) {
            hs[col] = ss * homography[col] + half * homography[6 + col]
            hs[3 + col] = ss * homography[3 + col] + half * homography[6 + col]
            hs[6 + col] = homography[6 + col]
        }
        val hi = DoubleArray(9)
        check(invert3x3Into(hs, hi)) { "singular homography" }

        val n = width * height
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        val samples = (ss * ss).toFloat()
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val bx = (x * ss + sx).toDouble()
                val by = (y * ss + sy).toDouble()
                val c = sampleColour(hi, bx, by, floor, line, distractors)
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val i = y * width + x
            red[i] = round(r / samples)
            green[i] = round(g / samples)
            blue[i] = round(b / samples)
        }

        val rng = Random(seed)
        if (occluders) {
            repeat(2) {
                val mx = 1.0 + 4.0 * rng.nextDouble()
                val my = 2.0 + 10.0 * rng.nextDouble()
                val p = project(homography, mx, my)
                fillEllipse(red, green, blue, width, height, p.x.toInt(), (p.y - 60).toInt(), 25, 70, OCCLUDER)
            }
        }

        val channels = arrayOf(red, green, blue).map { channel ->
            val blurred = gaussianBlur(GreyImage(width, height, channel), blur).pixels
            FloatArray(n) { round(blurred[it]).coerceIn(0f, 255f) }
        }
        val argb = IntArray(n)
        for (i in 0 until n) {
            var px = 0xFF shl 24
            for (ch in 0..2) {
                val v = (channels[ch][i] + noise * gaussian(rng)).coerceIn(0.0, 255.0).toInt()
                px = px or (v shl (16 - 8 * ch))
            }
            argb[i] = px
        }
        return RgbImage(width, height, argb)
    }

    /**
     * The 12 keypoints of `COURT_KEYPOINT_POSITIONS`, in pixels, through
     * `homography` relabelled the way the detector labels its output: mirror
     * x if model `(0,0)` projects right of `(W,0)`, then mirror y if `(0,0)`
     * projects below `(0,L)` (`court_probe.py`'s `canon()`).
     */
    fun truth(homography: DoubleArray): List<Point> {
        var hm = homography
        if (project(hm, 0.0, 0.0).x > project(hm, W, 0.0).x) hm = times(hm, doubleArrayOf(-1.0, 0.0, W, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        if (project(hm, 0.0, 0.0).y > project(hm, 0.0, L).y) hm = times(hm, doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, L, 0.0, 0.0, 1.0))
        return CourtModel.KEYPOINTS.map { project(hm, it.x, it.y) }
    }

    private fun sampleColour(hi: DoubleArray, bx: Double, by: Double, floor: Int, line: Int, distractors: Boolean): Int {
        val vz = hi[6] * bx + hi[7] * by + hi[8]
        if (vz <= 0.0) return floor
        val mx = (hi[0] * bx + hi[1] * by + hi[2]) / vz
        val my = (hi[3] * bx + hi[4] * by + hi[5]) / vz
        if (onLine(mx, my)) return line
        if (distractors && (abs(hypot(mx - W / 2, my - L / 2) - 3.6) < 0.03 || abs(mx - (W + 1.8)) < 0.025)) return DISTRACTOR
        return floor
    }

    private fun onLine(x: Double, y: Double): Boolean {
        val lw = LINE_WIDTH
        val inX = x >= 0.0 && x <= W
        val inY = y >= 0.0 && y <= L
        if (inX) {
            for (y0 in ACROSS_STARTS) if (y >= y0 && y <= y0 + lw) return true
        }
        if (inY) {
            for (x0 in ALONG_STARTS) if (x >= x0 && x <= x0 + lw) return true
        }
        return abs(x - W / 2) <= lw / 2 && ((y >= 0.0 && y <= L / 2 - S) || (y >= L / 2 + S && y <= L))
    }

    private val ACROSS_STARTS = doubleArrayOf(0.0, LS, L / 2 - S - LINE_WIDTH, L / 2 + S, L - LS - LINE_WIDTH, L - LINE_WIDTH)
    private val ALONG_STARTS = doubleArrayOf(0.0, O, W - O - LINE_WIDTH, W - LINE_WIDTH)

    /** A filled axis-aligned ellipse, the pixel-centre test standing in for `cv2.ellipse(..., -1)`. */
    private fun fillEllipse(
        red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int,
        cx: Int, cy: Int, ax: Int, ay: Int, colour: Int,
    ) {
        for (y in (cy - ay).coerceAtLeast(0)..(cy + ay).coerceAtMost(height - 1)) {
            for (x in (cx - ax).coerceAtLeast(0)..(cx + ax).coerceAtMost(width - 1)) {
                val dx = (x - cx).toDouble() / ax
                val dy = (y - cy).toDouble() / ay
                if (dx * dx + dy * dy > 1.0) continue
                val i = y * width + x
                red[i] = ((colour shr 16) and 0xFF).toFloat()
                green[i] = ((colour shr 8) and 0xFF).toFloat()
                blue[i] = (colour and 0xFF).toFloat()
            }
        }
    }

    /** A standard normal draw by Box-Muller; `1 - nextDouble()` keeps the log argument in (0, 1]. */
    private fun gaussian(rng: Random): Double {
        val u1 = 1.0 - rng.nextDouble()
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private fun project(hm: DoubleArray, x: Double, y: Double): Point {
        val z = hm[6] * x + hm[7] * y + hm[8]
        return Point((hm[0] * x + hm[1] * y + hm[2]) / z, (hm[3] * x + hm[4] * y + hm[5]) / z)
    }

    private fun times(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        val s = out[8]
        for (i in 0..8) out[i] /= s
        return out
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalise(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return DoubleArray(3) { v[it] / n }
    }
}
