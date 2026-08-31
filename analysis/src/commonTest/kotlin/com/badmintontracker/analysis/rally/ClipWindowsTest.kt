package com.badmintontracker.analysis.rally

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ClipWindowsTest {

    @Test
    fun padding_adds_pre_and_post_roll() {
        // A serve is not a direction reversal, so the first detected shot is
        // the RETURN of serve; the serve itself is always before the window.
        // And the window ends at the last contact, so the outcome is after it.
        val w = padRallyWindows(listOf(rally(1, 30.0, 40.0)), videoDuration = 120.0)
        w[0].clipStart shouldBe 28.0
        w[0].clipEnd shouldBe 41.5
    }

    @Test
    fun padding_never_reaches_into_a_neighbour() {
        val w = padRallyWindows(
            listOf(rally(1, 10.0, 20.0), rally(2, 21.0, 30.0)),
            videoDuration = 120.0,
        )
        w[0].clipEnd shouldBe 21.0
        w[1].clipStart shouldBe 20.0
    }

    @Test
    fun padding_is_clamped_to_the_video() {
        val w = padRallyWindows(listOf(rally(1, 1.0, 10.0)), videoDuration = 10.5)
        w[0].clipStart shouldBe 0.0
        w[0].clipEnd shouldBe 10.5
    }

    @Test
    fun padding_never_shrinks_the_detected_window() {
        // Relevant when incoming rallies already overlap: clamping to a
        // neighbour must not cut into the rally's own detected bounds.
        val w = padRallyWindows(
            listOf(rally(1, 10.0, 25.0), rally(2, 20.0, 30.0)),
            videoDuration = 120.0,
        )
        w[0].clipEnd shouldBe 25.0
        w[1].clipStart shouldBe 20.0
    }

    @Test
    fun input_is_sorted_and_not_mutated() {
        val input = listOf(rally(1, 30.0, 40.0), rally(2, 10.0, 20.0))
        val w = padRallyWindows(input, videoDuration = 120.0)
        w.map { it.rally.startTimestamp } shouldBe listOf(10.0, 30.0)
        input[0].startTimestamp shouldBe 30.0
    }

    @Test
    fun a_missing_duration_leaves_the_post_roll_unclamped() {
        val w = padRallyWindows(listOf(rally(1, 30.0, 40.0)), videoDuration = null)
        w[0].clipEnd shouldBe 41.5
    }
}
