package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RallyFramesTest {

    private fun at(frame: Int) = PlayerSample(frame, Point(3.0, 10.0))

    @Test
    fun a_window_covers_the_frames_a_base_position_is_measured_over() {
        // floor(start * fps) to ceil(end * fps), as basePositions reads a
        // window: the heatmap and the base must mean the same frames by "rally".
        val rallies = RallyFrames.of(listOf(RallyWindow(1, 1.05, 2.01)), fps = 10.0)
        // Frames 10..21.
        assertEquals(21, rallies.endOf(10))
        assertEquals(21, rallies.endOf(21))
        assertNull(rallies.endOf(9))
        assertNull(rallies.endOf(22))
        assertEquals(12, rallies.frameCount)
    }

    @Test
    fun a_window_without_bounds_is_no_rally() {
        val rallies = RallyFrames.of(listOf(RallyWindow(1, 0.0, 0.0)), fps = 30.0)
        assertTrue(rallies.isEmpty)
        assertEquals(0, rallies.frameCount)
        assertNull(rallies.endOf(0))
    }

    @Test
    fun overlapping_clips_count_the_frames_they_share_once() {
        // Two rallies closer than the clip padding give clips that overlap, and
        // a frame in both is one frame of play, not two.
        val rallies = RallyFrames.of(
            listOf(RallyWindow(2, 1.5, 3.0), RallyWindow(1, 0.0, 2.0)),
            fps = 10.0,
        )
        assertEquals(31, rallies.frameCount)
        assertEquals(30, rallies.endOf(0))
        assertEquals(30, rallies.endOf(18))
    }

    @Test
    fun rallies_apart_stay_apart() {
        val rallies = RallyFrames.of(
            listOf(RallyWindow(1, 0.0, 1.0), RallyWindow(2, 3.0, 4.0)),
            fps = 10.0,
        )
        assertEquals(22, rallies.frameCount)
        assertEquals(10, rallies.endOf(5))
        assertNull(rallies.endOf(20))
        assertEquals(40, rallies.endOf(30))
    }

    @Test
    fun coverage_is_the_share_of_rally_frames_with_the_player() {
        val rallies = RallyFrames.of(listOf(RallyWindow(1, 0.0, 0.9)), fps = 10.0)
        val track = PlayerTrack(
            samples = listOf(at(0), at(2), at(4), at(6), at(8), at(50), at(60)),
            framesWithPose = 70,
            rejections = emptyMap(),
        )
        // Five of the rally's ten frames; the two samples between rallies are
        // not in the count or the denominator.
        assertEquals(0.5, rallies.coverage(track), 1e-9)
    }

    @Test
    fun no_rally_frames_is_no_coverage_rather_than_a_division_by_zero() {
        val track = PlayerTrack(listOf(at(3)), framesWithPose = 1, rejections = emptyMap())
        assertEquals(0.0, RallyFrames.of(emptyList(), fps = 30.0).coverage(track), 1e-9)
    }
}
