package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A refined court fit (design §4.6): [h] maps court metres to fit-image
 * pixels, row-major, `h[8] = 1`, labelled as it came in (not re-canonised).
 * [rmsPx] is the Huber-weighted RMS of the last solve's point-to-line
 * residuals, fit pixels; [observations] the line-centre measurements that
 * solve used; [lastStepPx] the largest distance an in-frame keypoint moved
 * in the last round; [rounds] the measure-and-solve rounds run.
 */
internal class Refinement(val h: DoubleArray, val rmsPx: Double, val observations: Int, val lastStepPx: Double, val rounds: Int)

/** Profile sample spacing along the line-centre model, metres. */
private const val SAMPLE_STEP = 0.05

/** Each segment's two ends are left unsampled over this fraction of its length (crossings, occluded corners). */
private const val TRIM = 0.02

/** BWF lines are 40 mm wide; the expected image width of a line is this projected. */
private const val LINE_WIDTH = 0.04

/** The along-line probe used to find the line's image direction, metres. */
private const val DIRECTION_PROBE = 0.05

/** Profile step across the line, px. */
private const val PROFILE_STEP = 0.5

/** The window reaches this many expected widths, plus the tolerance and [WINDOW_SLACK], either side of the prediction. */
private const val WINDOW_WIDTHS = 1.5
private const val WINDOW_SLACK = 3.0

/** A window closer than this to the image edge (beyond its own half-length) is not sampled, px. */
private const val WINDOW_MARGIN = 2.0

/** The baseline is the median of the outer 20% of the profile, at least 3 samples each side. */
private const val BASELINE_FRACTION = 0.2
private const val MIN_BASELINE = 3

/** A line must stand at least this many grey levels above (or below) the baseline. */
private const val MIN_PEAK = 8.0

/** The measured width must lie in `[0.3 w, 2.5 w + 2]` for the expected width `w`. */
private const val MIN_WIDTH_RATIO = 0.3
private const val MAX_WIDTH_RATIO = 2.5
private const val MAX_WIDTH_SLACK = 2.0

/** Huber scale `1.4826 x median |r| + 0.1` (a robust sigma with a floor), knee at 1.5 scales. */
private const val MAD_TO_SIGMA = 1.4826
private const val SCALE_FLOOR = 0.1
private const val HUBER_KNEE = 1.5

/** Gauss-Newton iterations per round, observations fixed. */
private const val SOLVE_ITERATIONS = 10

/** The solve stops early once no parameter moves by more than this relative amount. */
private const val SOLVE_STILL = 1e-9

/** The window tolerance halves each round down to this floor, px. */
private const val TOLERANCE_FLOOR = 2.5

/** At the floor, a round moving no in-frame keypoint this far (px) ends the loop. */
private const val STILL_PX = 0.02

private const val MAX_ROUNDS = 8

/** A round with fewer line-centre measurements than this ends the loop. */
private const val MIN_OBSERVATIONS = 30

/**
 * The line-centre model (`CourtModel.CENTRE_SEGMENTS`) sampled for
 * profiling: `court_probe.py`'s `samp` in `refine3()`. Per sample, the point,
 * the segment's unit direction `u` and normal `(-u.y, u.x)` in metres, and
 * the segment index. `floor(length / 0.05)` samples per segment, evenly
 * spaced from 2% to 98% of its length (`np.linspace(0.02, 0.98, n)`).
 */
private class ProfileSamples {
    val x: DoubleArray
    val y: DoubleArray
    val ux: DoubleArray
    val uy: DoubleArray
    val segment: IntArray

    init {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val uxs = ArrayList<Double>()
        val uys = ArrayList<Double>()
        val segs = ArrayList<Int>()
        for ((k, s) in CourtModel.CENTRE_SEGMENTS.withIndex()) {
            val len = hypot(s.bx - s.ax, s.by - s.ay)
            val n = floor(len / SAMPLE_STEP).toInt()
            for (i in 0 until n) {
                val t = if (n == 1) TRIM else if (i == n - 1) 1.0 - TRIM else TRIM + i * (((1.0 - TRIM) - TRIM) / (n - 1))
                xs.add(s.ax + (s.bx - s.ax) * t)
                ys.add(s.ay + (s.by - s.ay) * t)
                uxs.add((s.bx - s.ax) / len)
                uys.add((s.by - s.ay) / len)
                segs.add(k)
            }
        }
        x = xs.toDoubleArray()
        y = ys.toDoubleArray()
        ux = uxs.toDoubleArray()
        uy = uys.toDoubleArray()
        segment = segs.toIntArray()
    }

    val size get() = x.size
}

private val PROFILE_SAMPLES by lazy { ProfileSamples() }

/**
 * Observed line centres, each tied to the model segment it lies on: the
 * segment's endpoints in metres (`a`, `b`), the observed centre in pixels
 * (`q`), and the polarity the profile was read with (+1 a bright line, -1 a
 * dark one).
 */
internal class LineObservations(capacity: Int) {
    val ax = DoubleArray(capacity)
    val ay = DoubleArray(capacity)
    val bx = DoubleArray(capacity)
    val by = DoubleArray(capacity)
    val qx = DoubleArray(capacity)
    val qy = DoubleArray(capacity)
    val sign = IntArray(capacity)
    var size = 0
        private set

    fun add(s: Segment, x: Double, y: Double, polarity: Int = 1) {
        ax[size] = s.ax; ay[size] = s.ay; bx[size] = s.bx; by[size] = s.by
        qx[size] = x; qy[size] = y; sign[size] = polarity
        size++
    }

    /** Empties the list; entries past [size] are overwritten before they are read. */
    fun clear() {
        size = 0
    }
}

/**
 * `court_probe.py`'s `refine3()` (design §4.6) on the fit image: alternate
 * [measure] (line centres profiled across the projected line-centre model)
 * and [solve] (Huber IRLS Gauss-Newton on the eight homography parameters)
 * until the keypoints stop moving. `h0` maps court metres to fit pixels.
 *
 * The window tolerance starts at `tolerance0` px and halves each round to a
 * floor of 2.5 px. A round's polarity is decided per sample on the first
 * round, then fixed to the majority. The loop ends when a round at the floor
 * moves no in-frame keypoint (in-frame before the round) 0.02 px or more,
 * or after 8 rounds.
 *
 * A round with fewer than 30 measurements, or whose solve is singular or
 * leaves the parameters non-finite, ends the loop, and the last completed
 * round stands: null when there is none. The first is the probe's rule
 * (`return out`); the second has no probe counterpart (`lstsq` returns a
 * minimum-norm step on a rank-deficient system) and is treated the same way.
 */
internal fun refineCourt(fit: GreyImage, h0: DoubleArray, tolerance0: Double = 12.0): Refinement? {
    val w = fit.width
    val h = fit.height
    val samples = PROFILE_SAMPLES
    val obs = LineObservations(samples.size)
    val profile = Profile()
    var hm = DoubleArray(9) { h0[it] / h0[8] }
    var tol = tolerance0
    var polarity = 0
    var out: Refinement? = null
    val before = DoubleArray(2 * CourtModel.KEYPOINTS.size)
    for (round in 0 until MAX_ROUNDS) {
        measure(fit, hm, samples, tol, polarity, obs, profile)
        if (obs.size < MIN_OBSERVATIONS) return out
        if (polarity == 0) {
            var sum = 0L
            for (i in 0 until obs.size) sum += obs.sign[i]
            polarity = if (sum >= 0) 1 else -1
        }
        keypoints(hm, before)
        val solved = solve(hm, obs) ?: return out
        var step = 0.0
        var stepAll = 0.0
        var anyInside = false
        for (k in CourtModel.KEYPOINTS.indices) {
            val kp = CourtModel.KEYPOINTS[k]
            val z = solved.h[6] * kp.x + solved.h[7] * kp.y + solved.h[8]
            val dx = (solved.h[0] * kp.x + solved.h[1] * kp.y + solved.h[2]) / z - before[2 * k]
            val dy = (solved.h[3] * kp.x + solved.h[4] * kp.y + solved.h[5]) / z - before[2 * k + 1]
            val moved = hypot(dx, dy)
            stepAll = max(stepAll, moved)
            val bx = before[2 * k]
            val by = before[2 * k + 1]
            if (bx >= 0.0 && bx < w && by >= 0.0 && by < h) {
                anyInside = true
                step = max(step, moved)
            }
        }
        if (!anyInside) step = stepAll
        hm = solved.h
        out = Refinement(hm, solved.rms, obs.size, step, round + 1)
        if (step < STILL_PX && tol <= TOLERANCE_FLOOR) break
        tol = max(TOLERANCE_FLOOR, tol * 0.5)
    }
    return out
}

/** The 12 keypoints through `hm`, interleaved `x, y`; plain division as the probe's `proj`. */
private fun keypoints(hm: DoubleArray, out: DoubleArray) {
    for ((k, kp) in CourtModel.KEYPOINTS.withIndex()) {
        val z = hm[6] * kp.x + hm[7] * kp.y + hm[8]
        out[2 * k] = (hm[0] * kp.x + hm[1] * kp.y + hm[2]) / z
        out[2 * k + 1] = (hm[3] * kp.x + hm[4] * kp.y + hm[5]) / z
    }
}

/** A reusable profile buffer, grown as windows widen. */
private class Profile {
    var values = DoubleArray(256)
    var offsets = DoubleArray(256)
    var baseline = DoubleArray(128)

    fun ensure(m: Int) {
        if (values.size < m) {
            values = DoubleArray(m)
            offsets = DoubleArray(m)
            baseline = DoubleArray(m)
        }
        if (baseline.size < m) baseline = DoubleArray(m)
    }
}

/**
 * `court_probe.py`'s `_measure()`: the observed line centre at each
 * profile sample that yields one, into `obs` (cleared first).
 *
 * Each model point `c` is projected, with `c + 0.05 u` for the line's image
 * direction and `c -+ 0.02 n` for its expected width `wpx`. The profile
 * runs across the projected line, `-half..half` at 0.5 px steps with
 * `half = 1.5 wpx + tol + 3`, bilinear on the fit luma; a window that comes
 * within 2 px of the image edge is skipped. The baseline (median of the
 * outer 20%, at least 3 samples a side) is subtracted; the polarity is
 * `polarity`, or when 0, whichever of the brightest and darkest excursions
 * in the centre region (`|offset| <= tol + wpx`) is larger. The peak (first
 * maximum in the centre region, at least 8 grey levels) is walked out to its
 * half-maximum on each side, and the crossings interpolated linearly; the
 * centre is their midpoint. Rejected when a crossing reaches the window's
 * end or the width is outside `[0.3 wpx, 2.5 wpx + 2]`.
 *
 * A sample at or behind the camera (depth `<= 0`) is skipped: the probe's
 * plain division would read the mirror image there. Search's gates keep the
 * whole court in front, so this does not arise on a real placement.
 */
private fun measure(
    fit: GreyImage,
    hm: DoubleArray,
    samples: ProfileSamples,
    tol: Double,
    polarity: Int,
    obs: LineObservations,
    profile: Profile,
) {
    obs.clear()
    val w = fit.width
    val h = fit.height
    val segments = CourtModel.CENTRE_SEGMENTS
    val halfWidth = LINE_WIDTH / 2
    for (i in 0 until samples.size) {
        val cx = samples.x[i]
        val cy = samples.y[i]
        val ux = samples.ux[i]
        val uy = samples.uy[i]
        // The model normal (-u.y, u.x).
        val mx = -uy
        val my = ux

        val z = hm[6] * cx + hm[7] * cy + hm[8]
        if (!(z > 0.0)) continue
        val px = (hm[0] * cx + hm[1] * cy + hm[2]) / z
        val py = (hm[3] * cx + hm[4] * cy + hm[5]) / z

        val ex = cx + ux * DIRECTION_PROBE
        val ey = cy + uy * DIRECTION_PROBE
        val ez = hm[6] * ex + hm[7] * ey + hm[8]
        var dx = (hm[0] * ex + hm[1] * ey + hm[2]) / ez - px
        var dy = (hm[3] * ex + hm[4] * ey + hm[5]) / ez - py
        val nd = hypot(dx, dy)
        if (!nd.isFinite() || nd < 1e-6 || !(ez > 0.0)) continue
        dx /= nd
        dy /= nd
        val nx = -dy
        val ny = dx

        val ax = cx - mx * halfWidth
        val ay = cy - my * halfWidth
        val az = hm[6] * ax + hm[7] * ay + hm[8]
        val bx = cx + mx * halfWidth
        val by = cy + my * halfWidth
        val bz = hm[6] * bx + hm[7] * by + hm[8]
        if (!(az > 0.0 && bz > 0.0)) continue
        val wx = (hm[0] * bx + hm[1] * by + hm[2]) / bz - (hm[0] * ax + hm[1] * ay + hm[2]) / az
        val wy = (hm[3] * bx + hm[4] * by + hm[5]) / bz - (hm[3] * ax + hm[4] * ay + hm[5]) / az
        val wpx = abs(wx * nx + wy * ny)
        val half = WINDOW_WIDTHS * wpx + tol + WINDOW_SLACK
        if (!(half + WINDOW_MARGIN < px && px < w - half - WINDOW_MARGIN)) continue
        if (!(half + WINDOW_MARGIN < py && py < h - half - WINDOW_MARGIN)) continue

        // np.arange(-half, half + 0.001, 0.5)
        val m = ceil((half + 0.001 - (-half)) / PROFILE_STEP).toInt()
        profile.ensure(m)
        val offs = profile.offsets
        val r = profile.values
        for (j in 0 until m) {
            val o = -half + j * PROFILE_STEP
            offs[j] = o
            r[j] = fit.sample(px + o * nx, py + o * ny)
        }
        val edge = max(MIN_BASELINE, (BASELINE_FRACTION * m).toInt())
        val base = profile.baseline
        var nb = 0
        for (j in 0 until minOf(edge, m)) base[nb++] = r[j]
        for (j in max(0, m - edge) until m) base[nb++] = r[j]
        val baseline = median(base, nb)
        for (j in 0 until m) r[j] -= baseline

        val centre = tol + wpx
        var sgn = polarity
        if (sgn == 0) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in 0 until m) {
                if (abs(offs[j]) > centre) continue
                if (r[j] > hi) hi = r[j]
                if (r[j] < lo) lo = r[j]
            }
            if (hi == Double.NEGATIVE_INFINITY) continue
            sgn = if (hi >= -lo) 1 else -1
        }
        if (sgn < 0) for (j in 0 until m) r[j] = -r[j]

        var k = -1
        var pk = Double.NEGATIVE_INFINITY
        for (j in 0 until m) {
            if (abs(offs[j]) > centre) continue
            if (r[j] > pk) {
                pk = r[j]
                k = j
            }
        }
        if (k < 0 || !(pk >= MIN_PEAK)) continue
        val hmax = pk / 2
        var lo = k
        while (lo > 0 && r[lo - 1] >= hmax) lo--
        var hi = k
        while (hi < m - 1 && r[hi + 1] >= hmax) hi++
        if (lo == 0 || hi == m - 1) continue
        val xl = offs[lo - 1] + (hmax - r[lo - 1]) / (r[lo] - r[lo - 1]) * PROFILE_STEP
        val xr = offs[hi] + (r[hi] - hmax) / (r[hi] - r[hi + 1]) * PROFILE_STEP
        val width = xr - xl
        if (width > MAX_WIDTH_RATIO * wpx + MAX_WIDTH_SLACK || width < MIN_WIDTH_RATIO * wpx) continue
        val mid = (xl + xr) / 2
        obs.add(segments[samples.segment[i]], px + mid * nx, py + mid * ny, sgn)
    }
}

/** The median of `v[0 until n]` (the mean of the middle two for an even count), `v` reordered. */
private fun median(v: DoubleArray, n: Int): Double {
    v.sort(0, n)
    return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
}

/** A solve's result: the new homography (`h[8] = 1`) and its weighted RMS residual. */
private class Solved(val h: DoubleArray, val rms: Double)

/**
 * `court_probe.py`'s `_solve()`: Gauss-Newton on the eight parameters of
 * `hm` (scaled so `h[8] = 1`), minimising each observation's signed distance
 * to the projected line of its model segment, Huber-weighted by iteratively
 * reweighted least squares - 10 iterations, the weights recomputed from each
 * iteration's residuals with scale `1.4826 median |r| + 0.1` and knee 1.5.
 * The Jacobian is analytic ([lineResiduals]); each step is the least-squares
 * solution of the weighted system by Householder QR on unit-norm columns (the
 * parameters span six orders of magnitude). The returned RMS is
 * `sqrt(sum w r^2 / sum w)` over the final residuals with the last
 * iteration's weights, as the probe's. Null when the system is singular or
 * the result is not finite.
 */
private fun solve(hm: DoubleArray, obs: LineObservations): Solved? {
    val n = obs.size
    val hv = DoubleArray(8) { hm[it] / hm[8] }
    val r = DoubleArray(n)
    val jac = DoubleArray(n * 8)
    val wt = DoubleArray(n)
    val absR = DoubleArray(n)
    val a = DoubleArray(n * 8)
    val rhs = DoubleArray(n)
    val dx = DoubleArray(8)
    for (iteration in 0 until SOLVE_ITERATIONS) {
        lineResiduals(hv, obs, r, jac)
        for (i in 0 until n) absR[i] = abs(r[i])
        val s = MAD_TO_SIGMA * median(absR, n) + SCALE_FLOOR
        for (i in 0 until n) {
            wt[i] = 1.0 / max(1.0, abs(r[i]) / (HUBER_KNEE * s))
            val sw = sqrt(wt[i])
            for (k in 0 until 8) a[i * 8 + k] = jac[i * 8 + k] * sw
            rhs[i] = -r[i] * sw
        }
        if (!leastSquares(a, rhs, n, dx)) return null
        var still = true
        for (k in 0 until 8) {
            hv[k] += dx[k]
            if (abs(dx[k] / max(abs(hv[k]), 1e-9)) >= SOLVE_STILL) still = false
        }
        if (still) break
    }
    lineResiduals(hv, obs, r, null)
    var num = 0.0
    var den = 0.0
    for (i in 0 until n) {
        num += wt[i] * r[i] * r[i]
        den += wt[i]
    }
    val out = DoubleArray(9) { if (it < 8) hv[it] else 1.0 }
    val rms = sqrt(num / den)
    if (out.any { !it.isFinite() } || !rms.isFinite()) return null
    return Solved(out, rms)
}

/**
 * Each observation's signed distance, px, from its observed centre `q` to
 * the line through `H(a)` and `H(b)`, its model segment projected by the
 * homography with parameters `hv` (`h[8] = 1`): `r = n . (q - H(a))`, `n`
 * the unit normal `(-d.y, d.x) / |d|` of `d = H(b) - H(a)`. With `jac`, also
 * the analytic `dr / dh_k`, row-major `n x 8`.
 */
internal fun lineResiduals(hv: DoubleArray, obs: LineObservations, r: DoubleArray, jac: DoubleArray?) {
    val dpa = DoubleArray(16)
    val dpb = DoubleArray(16)
    for (i in 0 until obs.size) {
        val ax = obs.ax[i]; val ay = obs.ay[i]
        val bx = obs.bx[i]; val by = obs.by[i]
        val za = hv[6] * ax + hv[7] * ay + 1.0
        val zb = hv[6] * bx + hv[7] * by + 1.0
        val pax = (hv[0] * ax + hv[1] * ay + hv[2]) / za
        val pay = (hv[3] * ax + hv[4] * ay + hv[5]) / za
        val pbx = (hv[0] * bx + hv[1] * by + hv[2]) / zb
        val pby = (hv[3] * bx + hv[4] * by + hv[5]) / zb
        val dx = pbx - pax
        val dy = pby - pay
        val len = hypot(dx, dy)
        val ex = obs.qx[i] - pax
        val ey = obs.qy[i] - pay
        // r = cross(d, q - pa) / |d|
        val c = dx * ey - dy * ex
        val ri = c / len
        r[i] = ri
        if (jac == null) continue
        projectionJacobian(ax, ay, za, pax, pay, dpa)
        projectionJacobian(bx, by, zb, pbx, pby, dpb)
        for (k in 0 until 8) {
            val dax = dpa[2 * k]; val day = dpa[2 * k + 1]
            val ddx = dpb[2 * k] - dax
            val ddy = dpb[2 * k + 1] - day
            val dc = ddx * ey - dx * day - ddy * ex + dy * dax
            val dlen = (dx * ddx + dy * ddy) / len
            jac[i * 8 + k] = (dc - ri * dlen) / len
        }
    }
}

/** `d(px, py) / dh_k` for `p = H(x, y)` at depth `z`, interleaved into `out[2k], out[2k+1]`. */
private fun projectionJacobian(x: Double, y: Double, z: Double, px: Double, py: Double, out: DoubleArray) {
    out.fill(0.0)
    out[0] = x / z
    out[2] = y / z
    out[4] = 1.0 / z
    out[7] = x / z
    out[9] = y / z
    out[11] = 1.0 / z
    out[12] = -px * x / z
    out[13] = -py * x / z
    out[14] = -px * y / z
    out[15] = -py * y / z
}

/**
 * The least-squares solution of `a x = b` (`a` row-major `n x 8`, both
 * overwritten) into `x`, by Householder QR with the columns scaled to unit
 * norm first. False when a column is zero or `R` is numerically singular.
 */
private fun leastSquares(a: DoubleArray, b: DoubleArray, n: Int, x: DoubleArray): Boolean {
    val p = 8
    if (n < p) return false
    val scale = DoubleArray(p)
    for (k in 0 until p) {
        var s = 0.0
        for (i in 0 until n) s += a[i * p + k] * a[i * p + k]
        s = sqrt(s)
        if (!(s > 0.0) || !s.isFinite()) return false
        scale[k] = s
        for (i in 0 until n) a[i * p + k] /= s
    }
    val diag = DoubleArray(p)
    for (k in 0 until p) {
        var norm = 0.0
        for (i in k until n) norm += a[i * p + k] * a[i * p + k]
        norm = sqrt(norm)
        if (norm < 1e-10) return false
        val alpha = if (a[k * p + k] > 0) -norm else norm
        // v = column k below the diagonal, minus alpha e_k; stored in place.
        a[k * p + k] -= alpha
        var vv = 0.0
        for (i in k until n) vv += a[i * p + k] * a[i * p + k]
        diag[k] = alpha
        if (vv == 0.0) continue
        for (j in k + 1 until p) {
            var dot = 0.0
            for (i in k until n) dot += a[i * p + k] * a[i * p + j]
            val f = 2.0 * dot / vv
            for (i in k until n) a[i * p + j] -= f * a[i * p + k]
        }
        var dot = 0.0
        for (i in k until n) dot += a[i * p + k] * b[i]
        val f = 2.0 * dot / vv
        for (i in k until n) b[i] -= f * a[i * p + k]
    }
    for (k in p - 1 downTo 0) {
        var s = b[k]
        for (j in k + 1 until p) s -= a[k * p + j] * x[j]
        x[k] = s / diag[k]
    }
    for (k in 0 until p) x[k] /= scale[k]
    return x.all { it.isFinite() }
}
