package com.badmintontracker.analysis

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoMetadataTest {

    @Test
    fun a_valid_frame_rate_passes_through() {
        normalizeFps(59.94) shouldBe FpsResult(59.94, substituted = false)
    }

    @Test
    fun zero_is_substituted_with_thirty() {
        // OpenCV returns 0 for some containers and for variable-frame-rate
        // sources. An unclamped 0 makes every rally detector bail on its
        // fps <= 0 guard and makes the speed loop divide by zero.
        normalizeFps(0.0) shouldBe FpsResult(30.0, substituted = true)
    }

    @Test
    fun negative_null_and_non_finite_are_substituted() {
        normalizeFps(-1.0) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(null) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(Double.NaN) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(Double.POSITIVE_INFINITY) shouldBe FpsResult(30.0, substituted = true)
    }
}
