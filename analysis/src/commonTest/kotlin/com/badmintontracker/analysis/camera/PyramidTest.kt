package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PyramidTest {
    @Test fun each_level_halves_rounding_up() {
        val levels = pyramid(Synthetic.texture(481, 271, 1), 3)
        levels.map { it.width to it.height } shouldBe listOf(481 to 271, 241 to 136, 121 to 68)
    }

    @Test fun a_flat_image_stays_flat() {
        val flat = GreyImage(8, 8, FloatArray(64) { 77f })
        pyrDown(flat).pixels.all { it == 77f } shouldBe true
    }

    @Test fun scharr_of_a_ramp_is_its_slope() {
        val ramp = GreyImage(10, 10, FloatArray(100) { (it % 10) * 3f })
        val g = scharr(ramp)
        g.gx[5 * 10 + 5].toDouble() shouldBe (3.0 plusOrMinus 1e-5)
        g.gy[5 * 10 + 5].toDouble() shouldBe (0.0 plusOrMinus 1e-5)
    }
}
