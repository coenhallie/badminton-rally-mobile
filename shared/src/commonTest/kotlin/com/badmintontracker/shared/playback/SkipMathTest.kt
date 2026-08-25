package com.badmintontracker.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class SkipMathTest {

    @Test
    fun skips_forward_by_the_interval() {
        assertEquals(22_000L, SkipMath.targetMs(positionMs = 12_000, deltaSeconds = 10, durationMs = 60_000))
    }

    @Test
    fun skips_backward_by_the_interval() {
        assertEquals(2_000L, SkipMath.targetMs(positionMs = 12_000, deltaSeconds = -10, durationMs = 60_000))
    }

    @Test
    fun skipping_back_past_the_start_lands_on_zero() {
        assertEquals(0L, SkipMath.targetMs(positionMs = 3_000, deltaSeconds = -10, durationMs = 60_000))
    }

    @Test
    fun skipping_past_the_end_lands_on_the_last_frame() {
        assertEquals(60_000L, SkipMath.targetMs(positionMs = 57_000, deltaSeconds = 10, durationMs = 60_000))
    }

    @Test
    fun an_unknown_duration_does_not_clamp_the_forward_skip() {
        // ExoPlayer reports C.TIME_UNSET (Long.MIN_VALUE) and AVPlayer an
        // indefinite duration before the item is ready; a skip must still move.
        assertEquals(22_000L, SkipMath.targetMs(positionMs = 12_000, deltaSeconds = 10, durationMs = -1))
        assertEquals(22_000L, SkipMath.targetMs(positionMs = 12_000, deltaSeconds = 10, durationMs = Long.MIN_VALUE))
    }

    @Test
    fun a_negative_reported_position_is_treated_as_the_start() {
        assertEquals(10_000L, SkipMath.targetMs(positionMs = -500, deltaSeconds = 10, durationMs = 60_000))
    }

    @Test
    fun a_position_already_past_the_duration_clamps_back_to_it() {
        assertEquals(60_000L, SkipMath.targetMs(positionMs = 61_000, deltaSeconds = -0, durationMs = 60_000))
    }
}
