package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TwoPlayerScoreTest {

    private fun sample(k: Int, x: Double, y: Double = 3.0) = PlayerSample(k, Point(x, y))

    private fun reference(vararg frames: Pair<Int, Point>) =
        SideReference(empty = false, positions = frames.toMap())

    @Test
    fun a_pose_file_frame_joins_the_reference_frame_before_it() {
        // Pose-file frame 5 is the fifth decoded frame, reference index 4.
        val score = scoreSide(
            listOf(sample(5, 2.0)), framesWithPose = 1,
            reference = reference(4 to Point(2.0, 3.0), 5 to Point(4.0, 3.0)),
        )
        assertEquals(1, score.right)
        assertEquals(0, score.wrong)
    }

    @Test
    fun a_sample_where_the_reference_has_nobody_is_unknown_not_wrong() {
        val score = scoreSide(listOf(sample(3, 2.0)), framesWithPose = 1, reference = reference())
        assertEquals(1, score.unknown)
        assertEquals(0, score.wrong)
        assertEquals(0, score.right)
    }

    @Test
    fun a_sample_more_than_a_metre_from_the_reference_is_the_wrong_person() {
        val score = scoreSide(
            listOf(sample(1, 2.0), sample(2, 3.5)), framesWithPose = 2,
            reference = reference(0 to Point(2.5, 3.0), 1 to Point(2.0, 3.0)),
        )
        assertEquals(1, score.right)
        assertEquals(1, score.wrong)
    }

    @Test
    fun every_sample_on_an_empty_side_is_a_false_positive() {
        val score = scoreSide(
            listOf(sample(1, 2.0), sample(2, 2.0)), framesWithPose = 10,
            reference = SideReference(empty = true, positions = emptyMap()),
        )
        assertEquals(2, score.falsePositives)
        assertEquals(0, score.right)
        assertEquals(0, score.unknown)
    }

    @Test
    fun camera_lost_frames_leave_every_count_and_the_coverage_denominator() {
        val score = scoreSide(
            listOf(sample(1, 2.0), sample(2, 9.0)), framesWithPose = 4,
            reference = reference(0 to Point(2.0, 3.0), 1 to Point(2.0, 3.0)),
            excluded = setOf(2), excludedPoseFrames = 2,
        )
        assertEquals(1, score.samples)
        assertEquals(1, score.right)
        assertEquals(0, score.wrong)
        assertEquals(0.5, score.coverage)
    }

    @Test
    fun the_error_figures_are_over_the_right_person_only() {
        val score = scoreSide(
            listOf(sample(1, 2.1), sample(2, 2.3), sample(3, 8.0)), framesWithPose = 3,
            reference = reference(0 to Point(2.0, 3.0), 1 to Point(2.0, 3.0), 2 to Point(2.0, 3.0)),
        )
        assertEquals(0.2, score.medianM!!, 1e-9)
        assertEquals(0.29, score.p95M!!, 1e-9)
        assertEquals(1, score.wrong)
    }

    @Test
    fun no_right_person_means_no_error_figures() {
        val score = scoreSide(emptyList(), framesWithPose = 3, reference = reference())
        assertNull(score.medianM)
        assertEquals(0.0, score.coverage)
    }
}
