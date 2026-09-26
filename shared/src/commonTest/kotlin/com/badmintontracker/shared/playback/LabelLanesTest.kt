package com.badmintontracker.shared.playback

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LabelLanesTest {

    private fun fit(vararg widths: Float, available: Float = 300f, spacing: Float = 8f) =
        LabelLanes.fit(widths.toList(), spacing, available)

    @Test
    fun nothing_to_lay_out_is_no_lanes_and_no_width() {
        val fit = fit()
        fit.lanes shouldBe 0
        fit.width shouldBe 0f
        fit.placements.shouldBeEmpty()
    }

    @Test
    fun chips_that_fit_one_line_stay_on_one_line() {
        // The three default labels on a phone: 90 + 8 + 90 + 8 + 90 = 286 of 300.
        val fit = fit(90f, 90f, 90f)
        fit.lanes shouldBe 1
        fit.width shouldBe 286f
        fit.placements.map { it.lane } shouldBe listOf(0, 0, 0)
        fit.placements.map { it.x } shouldBe listOf(0f, 98f, 196f)
    }

    @Test
    fun a_line_that_exactly_fills_the_width_still_fits() {
        fit(146f, 146f).lanes shouldBe 1
    }

    @Test
    fun one_chip_wider_than_the_row_is_still_one_lane() {
        // There is nothing to deal it against, and a second empty lane would be a
        // blank line under it.
        val fit = fit(400f)
        fit.lanes shouldBe 1
        fit.width shouldBe 400f
    }

    @Test
    fun overflow_deals_chips_alternately_into_two_lanes_in_label_order() {
        // 1 top, 2 bottom, 3 top, 4 bottom: the order still reads left to right.
        val fit = fit(90f, 90f, 90f, 90f)
        fit.lanes shouldBe 2
        fit.placements.map { it.lane } shouldBe listOf(0, 1, 0, 1)
        fit.placements.map { it.x } shouldBe listOf(0f, 0f, 98f, 98f)
        fit.width shouldBe 188f
    }

    @Test
    fun each_lane_keeps_its_own_cursor_so_a_wide_chip_does_not_push_the_other_lane() {
        // "Testesteststedfghj" over "test" wastes no width beside "test".
        val fit = fit(200f, 40f, 60f, 40f, 60f)
        fit.placements.map { it.x } shouldBe listOf(0f, 0f, 208f, 48f, 276f)
        fit.width shouldBe 336f
    }

    @Test
    fun never_a_third_lane_however_many_labels() {
        val fit = fit(*FloatArray(20) { 100f }, available = 300f)
        fit.lanes shouldBe 2
        fit.placements.count { it.lane == 0 } shouldBe 10
        fit.placements.count { it.lane == 1 } shouldBe 10
        // Ten chips a lane, 108 apart: the strip is what the row scrolls through.
        fit.width shouldBe 1072f
    }

    @Test
    fun the_strip_width_is_the_wider_lane() {
        val fit = fit(100f, 10f, 100f, available = 200f)
        fit.lanes shouldBe 2
        fit.width shouldBe 208f
    }
}
