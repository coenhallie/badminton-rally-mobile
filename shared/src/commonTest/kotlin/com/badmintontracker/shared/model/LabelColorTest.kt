package com.badmintontracker.shared.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LabelColorTest {

    @Test
    fun palette_has_ten_swatches_with_unique_keys() {
        LabelColor.PALETTE shouldHaveSize 10
        LabelColor.PALETTE.map { it.key }.toSet() shouldHaveSize 10
    }

    @Test
    fun from_resolves_a_known_key() {
        LabelColor.from("green") shouldBe LabelColor.GREEN
    }

    @Test
    fun from_returns_null_rather_than_throwing_on_an_unknown_key() {
        LabelColor.from("chartreuse").shouldBeNull()
        LabelColor.from(null).shouldBeNull()
    }

    @Test
    fun seeded_swatches_keep_the_colours_the_three_badges_ship_with() {
        LabelColor.GREEN.background shouldBe 0xFF2E7D32
        LabelColor.GREEN.foreground shouldBe 0xFFFFFFFF
        LabelColor.AMBER.background shouldBe 0xFFB26A00
        LabelColor.AMBER.foreground shouldBe 0xFF000000
        LabelColor.RED.background shouldBe 0xFFC62828
        LabelColor.RED.foreground shouldBe 0xFFFFFFFF
    }
}
