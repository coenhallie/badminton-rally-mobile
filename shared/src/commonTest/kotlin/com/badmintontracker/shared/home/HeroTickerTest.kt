package com.badmintontracker.shared.home

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * HeroTicker lives in :shared so there is exactly one definition of the
 * hero's copy and rotation for both clients to consume; these tests pin that
 * one definition rather than checking two copies against each other.
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
        // -2 is the value that discriminates. Without the clamp, (-2 + 1) %
        // phrases.size is -1 under truncating remainder, which is negative
        // and so outside indices no matter how many phrases there are, while
        // -5, 99 and Int.MAX_VALUE all happen to land back inside the array
        // and would let a missing clamp pass unnoticed.
        for (i in listOf(-5, -2, 99, Int.MAX_VALUE)) {
            HeroTicker.phrases.indices.contains(HeroTicker.next(i)) shouldBe true
        }
    }

    @Test
    fun next_is_a_pure_function_of_its_argument() {
        // A stateful counter that ignored `after` entirely would pass every
        // test above, because each one calls next() along a single
        // sequence it happens to match. Calling with the same input more
        // than once, out of any one increasing pass, is what actually pins
        // purity: the result must depend only on the argument, never on how
        // many times next() has been called before.
        HeroTicker.next(0) shouldBe HeroTicker.next(0)
        HeroTicker.next(0) shouldBe 1
        HeroTicker.next(0) shouldBe 1
        HeroTicker.next(2) shouldBe 3
        HeroTicker.next(2) shouldBe 3
        HeroTicker.next(0) shouldBe 1
    }
}
