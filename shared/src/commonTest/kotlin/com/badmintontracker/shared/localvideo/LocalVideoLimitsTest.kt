package com.badmintontracker.shared.localvideo

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

private const val TEN_GB = 10L * 1024 * 1024 * 1024

class LocalVideoLimitsTest {

    @Test
    fun a_file_at_exactly_the_cap_is_accepted() {
        LocalVideoLimits.oversizeMessage(TEN_GB).shouldBeNull()
    }

    @Test
    fun one_byte_over_the_cap_is_rejected() {
        LocalVideoLimits.oversizeMessage(TEN_GB + 1) shouldBe
            "Video is larger than 10GB. Please use a shorter recording."
    }

    @Test
    fun the_cap_is_ten_gibibytes() {
        // Both platforms read this constant; the number living in one place is the
        // whole point of the type. A drift here is a drift between Android and iOS.
        LocalVideoLimits.MAX_SIZE_BYTES shouldBe 10_737_418_240L
    }

    @Test
    fun an_unreported_size_is_not_treated_as_oversize() {
        // 0 means "the provider gave us no size", not "empty file". The cap has
        // nothing to say about it, so intake lets it through - a known gap that
        // this change deliberately does not alter.
        LocalVideoLimits.oversizeMessage(0).shouldBeNull()
    }
}
