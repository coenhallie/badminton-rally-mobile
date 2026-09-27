package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CameraMotionTest {
    private fun shift(x: Double) = listOf(listOf(1.0, 0.0, x), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    private val motion = CameraMotion.of(
        0.1,
        listOf(CameraSample(0.1, shift(0.0)), CameraSample(0.2, shift(10.0)), CameraSample(1.2, shift(50.0))),
    )

    @Test fun an_exact_sample_is_returned_as_is() { motion.at(0.2)!![0][2] shouldBe 10.0 }
    @Test fun between_close_samples_is_a_blend() { motion.at(0.15)!![0][2] shouldBe (5.0 plusOrMinus 1e-9) }
    @Test fun a_blend_is_normalised() { motion.at(0.15)!![2][2] shouldBe 1.0 }
    @Test fun inside_a_long_gap_near_a_sample_holds_it() { motion.at(0.3)!![0][2] shouldBe 10.0 }
    @Test fun deep_inside_a_long_gap_is_unknown() { motion.at(0.7).shouldBeNull() }
    @Test fun just_before_the_first_holds_it() { motion.at(0.0)!![0][2] shouldBe 0.0 }
    @Test fun long_after_the_last_is_unknown() { motion.at(1.5).shouldBeNull() }

    @Test fun duplicate_timestamps_keep_the_first() {
        val m = CameraMotion.of(0.0, listOf(CameraSample(0.0, shift(1.0)), CameraSample(0.0, shift(2.0))))
        m.samples.size shouldBe 1
        m.at(0.0)!![0][2] shouldBe 1.0
    }

    @Test fun two_equal_motions_are_equal() {
        CameraMotion.of(0.1, listOf(CameraSample(0.1, shift(3.0)))) shouldBe
            CameraMotion.of(0.1, listOf(CameraSample(0.1, shift(3.0))))
    }
}
