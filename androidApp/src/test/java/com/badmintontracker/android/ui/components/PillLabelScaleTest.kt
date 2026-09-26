package com.badmintontracker.android.ui.components

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PillLabelScaleTest {

    @Test
    fun a_row_whose_words_fit_keeps_its_size() {
        pillLabelScale(listOf(100f, 120f), available = 120f) shouldBe 1f
    }

    @Test
    fun the_widest_word_sets_the_factor_for_the_row() {
        // "Analysis" at 1.3x on a 360dp phone: 198px of word in 171px of pill.
        pillLabelScale(listOf(150f, 198f, 120f), available = 171f) shouldBe (171f / 198f plusOrMinus 0.0001f)
    }

    @Test
    fun the_factor_stops_at_the_floor() {
        pillLabelScale(listOf(400f), available = 100f) shouldBe MIN_PILL_LABEL_SCALE
    }

    @Test
    fun no_labels_or_no_width_leave_the_size_alone() {
        pillLabelScale(emptyList(), available = 100f) shouldBe 1f
        pillLabelScale(listOf(0f), available = 0f) shouldBe 1f
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
