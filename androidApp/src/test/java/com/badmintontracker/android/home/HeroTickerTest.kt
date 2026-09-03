package com.badmintontracker.android.home

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The mirror of iosApp's HeroTickerTests. Both platforms transcribe the copy
 * independently, so this pair is what catches one side being edited alone.
 */
class HeroTickerTest {
    @Test
    fun copy_matches_the_mock_verbatim() {
        HeroTicker.leadLine shouldBe "Your game,"
        HeroTicker.phrases shouldBe listOf(
            "clipped rally by rally.",
            "mapped as heatmaps.",
            "tracked as skeletons.",
            "annotated and shared.",
        )
    }

    @Test
    fun advances_through_every_phrase() {
        val seen = mutableListOf(0)
        var i = 0
        repeat(HeroTicker.phrases.size - 1) {
            i = HeroTicker.next(i)
            seen += i
        }
        seen shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun wraps_back_to_the_first_phrase() {
        HeroTicker.next(HeroTicker.phrases.size - 1) shouldBe 0
    }

    @Test
    fun out_of_range_index_does_not_escape() {
        // -2 is the value that discriminates. Without the clamp, (-2 + 1) % 4 is
        // -1 under truncating remainder, which is outside the array, while -5,
        // 99 and Int.MAX_VALUE all happen to land back inside it and would let
        // a missing clamp pass unnoticed.
        for (i in listOf(-5, -2, 99, Int.MAX_VALUE)) {
            HeroTicker.phrases.indices.contains(HeroTicker.next(i)) shouldBe true
        }
    }
}
