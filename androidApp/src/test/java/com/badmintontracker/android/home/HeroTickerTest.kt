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
        for (i in listOf(-5, 99, Int.MAX_VALUE)) {
            HeroTicker.phrases.indices.contains(HeroTicker.next(i)) shouldBe true
        }
    }
}
