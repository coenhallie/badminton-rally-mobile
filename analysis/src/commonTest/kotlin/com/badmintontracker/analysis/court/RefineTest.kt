package com.badmintontracker.analysis.court

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test

class RefineTest {
    /** The court of the first synthetic scene, 1280x720, labelled as the detector labels it. */
    private val cam = canonical(SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0))

    @Test fun the_analytic_jacobian_matches_central_differences() {
        val obs = LineObservations(6)
        val segs = listOf(0, 3, 5, 6, 9, 11)
        for ((i, k) in segs.withIndex()) {
            val s = CourtModel.CENTRE_SEGMENTS[k]
            val p = project(cam, (s.ax + s.bx) / 2, (s.ay + s.by) / 2)!!
            obs.add(s, p.x + 1.5 * (i - 2), p.y - 0.7 * i)
        }
        val hv = DoubleArray(8) { cam[it] * (1.0 + 0.01 * (it - 3.5) / 3.5) }
        val r = DoubleArray(obs.size)
        val jac = DoubleArray(obs.size * 8)
        lineResiduals(hv, obs, r, jac)
        val rp = DoubleArray(obs.size)
        val rm = DoubleArray(obs.size)
        for (k in 0 until 8) {
            val step = 1e-6 * max(abs(hv[k]), 1e-3)
            val plus = hv.copyOf().also { it[k] += step }
            val minus = hv.copyOf().also { it[k] -= step }
            lineResiduals(plus, obs, rp, null)
            lineResiduals(minus, obs, rm, null)
            for (i in 0 until obs.size) {
                val numeric = (rp[i] - rm[i]) / (2 * step)
                val analytic = jac[i * 8 + k]
                abs(analytic - numeric) shouldBeLessThan 1e-5 * max(abs(numeric), 1e-3 / max(abs(hv[k]), 1e-3))
            }
        }
    }

    @Test fun refinement_pulls_a_few_pixels_off_start_onto_the_lines() {
        val img = SyntheticCourt.render(SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0), 1280, 720,
            floor = 0xFF3C8C46.toInt(), line = 0xFFEBEBEB.toInt())
        // Shift the whole court 3 px right and 2 px down, and stretch it 0.5%.
        val shift = doubleArrayOf(1.005, 0.0, 3.0, 0.0, 1.005, 2.0, 0.0, 0.0, 1.0)
        val h0 = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) h0[r * 3 + c] = (0..2).sumOf { shift[r * 3 + it] * cam[it * 3 + c] }
        val ref = refineCourt(img.luma(), h0).shouldNotBeNull()
        println("rounds ${ref.rounds} observations ${ref.observations} rms ${ref.rmsPx} step ${ref.lastStepPx}")
        ref.lastStepPx shouldBeLessThan 0.1
        for (kp in CourtModel.KEYPOINTS) {
            val got = project(ref.h, kp.x, kp.y)!!
            val want = project(cam, kp.x, kp.y)!!
            hypot(got.x - want.x, got.y - want.y) shouldBeLessThan 0.5
        }
    }
}
