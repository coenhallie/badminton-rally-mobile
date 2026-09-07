package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoseLookupTest {

    private fun pose(frame: Int, timestamp: Double) = PlayerPose(
        frame, timestamp, List(Coco.COUNT) { Point(0.0, 0.0) }, List(Coco.COUNT) { 0.9f },
    )

    // 25fps, with frame 2 missing: the player was not found in it.
    private val poses = listOf(pose(0, 0.0), pose(1, 0.04), pose(3, 0.12), pose(4, 0.16))
    private val tolerance = poseToleranceS(25.0)

    @Test
    fun an_exact_hit_returns_that_pose() {
        assertEquals(3, nearestPose(poses, 0.12, tolerance)?.frame)
    }

    @Test
    fun within_tolerance_on_either_side_returns_the_pose() {
        assertEquals(1, nearestPose(poses, 0.04 - 0.015, tolerance)?.frame)
        assertEquals(1, nearestPose(poses, 0.04 + 0.015, tolerance)?.frame)
    }

    @Test
    fun a_missing_frame_draws_nothing_rather_than_a_neighbour() {
        // 0.08 is frame 2's time. Frames 1 and 3 are a whole frame away,
        // outside half a frame, so the overlay must be empty there: holding a
        // neighbour would draw the player where they are not.
        assertNull(nearestPose(poses, 0.08, tolerance))
    }

    @Test
    fun between_two_poses_the_nearer_wins() {
        assertEquals(3, nearestPose(poses, 0.13, tolerance)?.frame)
        assertEquals(4, nearestPose(poses, 0.15, tolerance)?.frame)
    }

    @Test
    fun the_players_millisecond_truncation_still_resolves() {
        // ExoPlayer reports 33ms for a frame shown at 33.333ms. The tolerance
        // covers half a frame plus that millisecond.
        val thirty = listOf(pose(0, 0.0), pose(1, 1.0 / 30.0), pose(2, 2.0 / 30.0))
        assertEquals(1, nearestPose(thirty, 0.033, poseToleranceS(30.0))?.frame)
        assertEquals(2, nearestPose(thirty, 0.066, poseToleranceS(30.0))?.frame)
    }

    @Test
    fun before_the_first_and_after_the_last_are_within_tolerance_only() {
        assertEquals(0, nearestPose(poses, 0.01, tolerance)?.frame)
        assertNull(nearestPose(poses, 0.5, tolerance))
    }

    @Test
    fun an_empty_list_returns_null() {
        assertNull(nearestPose(emptyList(), 0.0, tolerance))
    }

    @Test
    fun the_tolerance_is_half_a_frame_plus_a_millisecond() {
        assertEquals(0.02 + 0.001, poseToleranceS(25.0), 1e-9)
        // A frame rate the container could not report falls back to 30fps
        // rather than to an infinite window.
        assertEquals(1.0 / 60.0 + 0.001, poseToleranceS(0.0), 1e-9)
    }
}
