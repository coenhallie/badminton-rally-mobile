package com.badmintontracker.android.ui.components

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PillLabelScaleTest {

    // A word's width at a factor, the way the text measurer scales it: linearly.
    private fun linear(width: Float): (Float) -> Float = { scale -> width * scale }

    @Test
    fun a_row_whose_words_fit_keeps_its_size() {
        fitScale(limit = 120f, measure = linear(120f)) shouldBe 1f
    }

    @Test
    fun the_widest_word_sets_the_factor_for_the_row() {
        // "Analysis" at 1.3x on a 360dp phone: 198px of word in 171px of pill.
        val scale = fitScale(limit = 171f, measure = linear(198f))
        (198f * scale <= 171f) shouldBe true
        scale shouldBe (171f / 198f plusOrMinus 0.01f)
    }

    // Android scales large text by less than small text, so the width does
    // not fall in proportion to the factor. The first proportional guess
    // leaves the word too wide; the fit measures again until it is not.
    @Test
    fun a_width_that_does_not_scale_linearly_is_measured_again_until_it_fits() {
        val nonLinear: (Float) -> Float = { scale -> 198f * (0.4f + 0.6f * scale) }
        val scale = fitScale(limit = 171f, measure = nonLinear)
        (nonLinear(scale) <= 171f) shouldBe true
        // The proportional guess alone would still be too wide.
        (nonLinear(171f / 198f) > 171f) shouldBe true
    }

    @Test
    fun the_factor_stops_at_the_floor() {
        fitScale(limit = 100f, measure = linear(400f)) shouldBe MIN_PILL_LABEL_SCALE
    }

    @Test
    fun nothing_to_measure_leaves_the_size_alone() {
        fitScale(limit = 100f, measure = { 0f }) shouldBe 1f
    }

    // The ceiling: a secondary row's label size held to the primary's above it.
    @Test
    fun a_secondary_label_is_held_to_the_primary_label_size() {
        // 11sp at 1.3x is 37.5px on a 480dpi phone; a primary shrunk to 30px.
        val scale = fitScale(limit = 30f, measure = linear(37.5f))
        (37.5f * scale <= 30f) shouldBe true
    }

    @Test
    fun a_pill_keeps_its_full_padding_while_the_word_fits_inside_it() {
        pillPadding(pillWidth = 200f, widestWord = 150f, min = 6f, max = 24f) shouldBe 24f
    }

    @Test
    fun a_pill_gives_up_padding_before_the_word_breaks() {
        pillPadding(pillWidth = 200f, widestWord = 180f, min = 6f, max = 24f) shouldBe 10f
    }

    @Test
    fun a_pill_keeps_its_minimum_padding_and_leaves_the_rest_to_the_scale() {
        pillPadding(pillWidth = 200f, widestWord = 199f, min = 6f, max = 24f) shouldBe 6f
    }
}
