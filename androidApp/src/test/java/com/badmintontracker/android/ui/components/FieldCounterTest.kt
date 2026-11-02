package com.badmintontracker.android.ui.components

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FieldCounterTest {

    @Test
    fun the_counter_shares_the_line_while_the_widest_word_fits_beside_it() {
        // "OPPONENT (OPTIONAL)" at font scale 2.0 in Who's playing on a 360dp
        // phone, at 3px/dp: 220dp of field, a 62dp "38/40", a 139dp word.
        counterBesideLabel(rowWidth = 660, counterWidth = 187, widestLabelWord = 417) shouldBe true
    }

    @Test
    fun the_counter_takes_its_own_line_when_a_word_would_break() {
        // The same field on a 300dp screen: 160dp of field.
        counterBesideLabel(rowWidth = 480, counterWidth = 187, widestLabelWord = 417) shouldBe false
    }

    @Test
    fun a_word_exactly_filling_what_is_left_still_fits() {
        counterBesideLabel(rowWidth = 300, counterWidth = 100, widestLabelWord = 200) shouldBe true
    }
}
