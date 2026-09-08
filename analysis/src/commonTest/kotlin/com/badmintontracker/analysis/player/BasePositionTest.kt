package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasePositionTest {

    private fun track(vararg samples: PlayerSample) = PlayerTrack(samples.toList(), samples.size, emptyMap())

    /** [seconds] of standing at [at], from [fromFrame], at 30 fps. */
    private fun standing(at: Point, fromFrame: Int, seconds: Double): List<PlayerSample> =
        (0 until (seconds * 30).toInt()).map { PlayerSample(fromFrame + it, at) }

    @Test
    fun a_lunge_does_not_move_the_base() {
        // Two seconds at the base, then a fast excursion to the net and back.
        // The median stays at the base; a mean would have crept toward the net.
        val base = Point(3.0, 10.0)
        val samples = standing(base, 0, 2.0) +
            (60 until 90).map { PlayerSample(it, Point(3.0, 10.0 - (it - 60) * 0.1)) } +
            standing(base, 90, 1.0)
        val result = basePositions(track(*samples.toTypedArray()), 30.0, listOf(RallyWindow(1, 0.0, 4.0)))

        assertEquals(1, result.rallies.size)
        assertEquals(base, result.rallies[0].position)
        assertEquals(base, result.overall)
    }

    @Test
    fun each_rally_gets_its_own_base_and_the_walk_between_them_counts_for_neither() {
        // Frames 0..59 in rally 1, 70..129 at the shuttle tube between rallies,
        // 140..199 in rally 2. The windows close on frames nobody sampled, so
        // each rally holds exactly its own sixty frames.
        val samples = standing(Point(3.0, 10.0), 0, 2.0) +
            standing(Point(1.0, 12.5), 70, 2.0) +
            standing(Point(3.4, 9.6), 140, 2.0)
        val result = basePositions(
            track(*samples.toTypedArray()),
            30.0,
            listOf(RallyWindow(1, 0.0, 2.1), RallyWindow(2, 4.5, 6.8)),
        )

        assertEquals(listOf(1, 2), result.rallies.map { it.index })
        assertEquals(Point(3.0, 10.0), result.rallies[0].position)
        assertEquals(Point(3.4, 9.6), result.rallies[1].position)
        // The overall base is the median of both rallies' samples, so the
        // tube position cannot appear in it.
        assertEquals(Point(3.2, 9.8), result.overall)
    }

    @Test
    fun a_rally_the_player_was_barely_found_in_is_left_out() {
        val samples = standing(Point(3.0, 10.0), 0, 0.5) // half a second, in a ten second window
        val result = basePositions(track(*samples.toTypedArray()), 30.0, listOf(RallyWindow(1, 0.0, 10.0)))

        assertTrue(result.rallies.isEmpty())
        assertNull(result.overall)
    }

    @Test
    fun a_clip_recovered_without_bounds_is_skipped_rather_than_read_as_an_empty_rally() {
        val samples = standing(Point(3.0, 10.0), 0, 2.0)
        val result = basePositions(
            track(*samples.toTypedArray()),
            30.0,
            listOf(RallyWindow(1, 0.0, 0.0), RallyWindow(2, 0.0, 2.0)),
        )

        assertEquals(listOf(2), result.rallies.map { it.index })
    }

    @Test
    fun coverage_is_the_share_of_the_window_that_produced_a_position() {
        // 30 of the 61 frames in 0..2 s (frames 0..60 inclusive).
        val samples = standing(Point(3.0, 10.0), 0, 1.0)
        val result = basePositions(track(*samples.toTypedArray()), 30.0, listOf(RallyWindow(1, 0.0, 2.0)))

        assertEquals(30, result.rallies[0].samples)
        assertEquals(30.0 / 61.0, result.rallies[0].coverage, 1e-9)
    }

    @Test
    fun rallies_come_out_in_time_order_whatever_order_the_windows_arrived_in() {
        val samples = standing(Point(3.0, 10.0), 0, 2.0) + standing(Point(3.5, 9.0), 90, 2.0)
        val result = basePositions(
            track(*samples.toTypedArray()),
            30.0,
            listOf(RallyWindow(2, 3.0, 5.0), RallyWindow(1, 0.0, 2.0)),
        )

        assertEquals(listOf(1, 2), result.rallies.map { it.index })
    }

    @Test
    fun an_empty_track_or_a_bad_fps_gives_nothing_rather_than_throwing() {
        assertTrue(basePositions(track(), 30.0, listOf(RallyWindow(1, 0.0, 2.0))).rallies.isEmpty())
        val samples = standing(Point(3.0, 10.0), 0, 2.0)
        assertTrue(basePositions(track(*samples.toTypedArray()), 0.0, listOf(RallyWindow(1, 0.0, 2.0))).rallies.isEmpty())
    }
}
