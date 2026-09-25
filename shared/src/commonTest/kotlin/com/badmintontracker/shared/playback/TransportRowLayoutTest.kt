package com.badmintontracker.shared.playback

import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TransportRowLayoutTest {

    // Roughly what "0.25×" and "10s" measure at the default font scale.
    private val readout = 32f
    private val label = 22f
    // "+10s": the sign costs about a third as much again.
    private val signed = 29f

    private fun fit(width: Float, readoutWidth: Float = readout, labelWidth: Float = label, signedWidth: Float = signed) =
        TransportRowLayout.fit(width, readoutWidth, labelWidth, signedWidth)

    @Test
    fun a_wide_row_is_drawn_exactly_as_the_mock_draws_it() {
        // Landscape fullscreen, a tablet: nothing has to give.
        val metrics = fit(800f)
        metrics.gap shouldBe 8f
        metrics.pillPadding shouldBe 16f
        metrics.showPillIcons shouldBe true
    }

    @Test
    fun the_gutter_makes_room_for_the_readout_and_its_insets() {
        fit(800f).gutter shouldBe 32f + 16f
    }

    @Test
    fun the_gutter_never_drops_below_the_page_gutter() {
        fit(800f, readoutWidth = 2f).gutter shouldBe 24f
    }

    @Test
    fun a_pixel_sized_phone_tightens_the_spacing_and_keeps_the_chevrons() {
        val metrics = fit(411f)
        metrics.gap shouldBe 6f
        metrics.pillPadding shouldBe 12f
        metrics.showPillIcons shouldBe true
    }

    @Test
    fun a_narrower_phone_drops_the_chevrons_before_it_clips_the_interval() {
        val metrics = fit(393f)
        metrics.showPillIcons shouldBe false
        metrics.gap shouldBe 6f
    }

    @Test
    fun the_narrowest_supported_phone_still_fits_without_clipping() {
        val metrics = fit(360f)
        metrics.gap shouldBe 4f
        metrics.pillPadding shouldBe 8f
        metrics.showPillIcons shouldBe false
    }

    @Test
    fun whatever_is_chosen_fits_the_width_it_was_chosen_for() {
        // The property the steps exist for, across every width from the narrowest
        // phone to a tablet rather than at the four widths named above.
        var width = 360f
        while (width <= 1000f) {
            val metrics = fit(width)
            TransportRowLayout.controlsWidth(metrics, label, signed) shouldBeLessThanOrEqual width - 2 * metrics.gutter
            width += 1f
        }
    }

    @Test
    fun a_large_font_scale_gives_way_sooner() {
        // The label is measured, so a bigger "10s" costs the row a step at a width
        // that otherwise kept its chevrons.
        fit(411f, labelWidth = 22f, signedWidth = 29f).showPillIcons shouldBe true
        fit(411f, labelWidth = 34f, signedWidth = 45f).showPillIcons shouldBe false
    }

    @Test
    fun a_pill_without_its_chevron_carries_its_direction_in_a_sign() {
        TransportRowLayout.pillLabel(10, forward = false, showPillIcons = true) shouldBe "10s"
        TransportRowLayout.pillLabel(10, forward = true, showPillIcons = true) shouldBe "10s"
        TransportRowLayout.pillLabel(10, forward = false, showPillIcons = false) shouldBe "-10s"
        TransportRowLayout.pillLabel(10, forward = true, showPillIcons = false) shouldBe "+10s"
    }

    @Test
    fun a_width_nothing_fits_gets_the_tightest_step_rather_than_a_crash() {
        val metrics = fit(200f)
        metrics.gap shouldBe 4f
        metrics.showPillIcons shouldBe false
    }
}
