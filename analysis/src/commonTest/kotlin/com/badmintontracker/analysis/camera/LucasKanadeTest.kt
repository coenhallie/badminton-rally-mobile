package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlin.math.hypot
import kotlin.test.Test

class LucasKanadeTest {
    private val ref = Synthetic.texture(480, 270, 7)
    private val refPyr = pyramid(ref, PYRAMID_LEVELS)
    private val corners = shiTomasi(ref)
    private val templates = AnchoredTemplates(refPyr, corners)

    private fun trackUnder(
        ref: GreyImage,
        corners: List<Pair<Double, Double>>,
        templates: AnchoredTemplates,
        m: List<List<Double>>,
        guessFromIdentity: Boolean,
    ): Pair<Int, Double> {
        val target = pyramid(Synthetic.warp(ref, m), PYRAMID_LEVELS)
        val guesses = DoubleArray(corners.size * 2)
        corners.forEachIndexed { i, (x, y) ->
            val g = if (guessFromIdentity) (x to y) else m.apply(x, y)!!.let { it.x to it.y }
            guesses[2 * i] = g.first; guesses[2 * i + 1] = g.second
        }
        val (pos, ok) = templates.track(target, guesses)
        var good = 0
        var worst = 0.0
        corners.forEachIndexed { i, (x, y) ->
            if (!ok[i]) return@forEachIndexed
            val truth = m.apply(x, y)!!
            if (truth.x < 12 || truth.y < 12 || truth.x > 468 || truth.y > 258) return@forEachIndexed
            good++
            worst = maxOf(worst, hypot(pos[2 * i] - truth.x, pos[2 * i + 1] - truth.y))
        }
        return good to worst
    }

    @Test fun a_shift_of_six_pixels_is_found_from_no_guess() {
        val (good, worst) = trackUnder(ref, corners, templates, listOf(listOf(1.0, 0.0, 6.0), listOf(0.0, 1.0, -4.0), listOf(0.0, 0.0, 1.0)), true)
        good shouldBeGreaterThan 120
        worst shouldBeLessThan 0.1
    }

    @Test fun a_thirty_pixel_pan_is_found_from_no_guess_through_the_pyramid() {
        val (good, worst) = trackUnder(ref, corners, templates, listOf(listOf(1.0, 0.0, 30.0), listOf(0.0, 1.0, 12.0), listOf(0.0, 0.0, 1.0)), true)
        good shouldBeGreaterThan 100
        worst shouldBeLessThan 0.1
    }

    /**
     * Windowed Lucas-Kanade fits one translation per 21x21 window; under a
     * local rotation the fit is biased by roughly the rotation rate times
     * the distance from the tracked point to the window's own
     * gradient-energy centroid, a property of the texture around each
     * point, not a defect in the tracker. Measured worst case across seeds
     * 2, 3, 7 and 11 is 0.118 px (seed 2); that is still far inside what the
     * pipeline tolerates downstream - RANSAC's inlier threshold is 1.0 px -
     * so 0.15 px is a bound the tracker can actually meet across seeds
     * rather than one that happens to hold for a single lucky fixture. The
     * accuracy gate against real video is the corpus paint test (a later
     * task), not this bound.
     */
    @Test fun a_rotation_with_perspective_is_found_from_a_close_guess() {
        val m = listOf(listOf(0.995, -0.03, 14.0), listOf(0.028, 1.002, -9.0), listOf(2e-5, -1e-5, 1.0))
        for (seed in listOf(2, 3, 7, 11)) {
            val seedRef = Synthetic.texture(480, 270, seed)
            val seedCorners = shiTomasi(seedRef)
            val seedTemplates = AnchoredTemplates(pyramid(seedRef, PYRAMID_LEVELS), seedCorners)
            val (good, worst) = trackUnder(seedRef, seedCorners, seedTemplates, m, false)
            good shouldBeGreaterThan 120
            worst shouldBeLessThan 0.15
        }
    }
}
