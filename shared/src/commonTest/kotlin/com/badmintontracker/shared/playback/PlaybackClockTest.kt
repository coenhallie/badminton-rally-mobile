package com.badmintontracker.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackClockTest {

    @Test
    fun the_clock_reads_the_position_against_the_length() {
        assertEquals("0:03 / 0:40", playbackClock(positionMs = 3_200, durationMs = 40_700))
        assertEquals("1:05 / 12:05", playbackClock(positionMs = 65_000, durationMs = 725_000))
    }

    @Test
    fun before_the_length_is_known_the_clock_reads_the_position_alone() {
        assertEquals("0:03", playbackClock(positionMs = 3_200, durationMs = 0))
        assertEquals("0:00", playbackClock(positionMs = 0, durationMs = -1))
    }
}
