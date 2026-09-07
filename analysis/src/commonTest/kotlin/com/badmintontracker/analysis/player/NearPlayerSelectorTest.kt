package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearPlayerSelectorTest {

    // The corpus video 743d7fb1's own marks, 1920x1080: the far baseline is
    // narrower than the near one, which is what makes the homography do real
    // work, and the marks are the ones the on-device tests run against.
    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        // Marked with "near" meaning near the camera for the service lines and
        // near the top for the centre points, as the cloud stored them. The
        // fit resolves each pair by pixel, so both readings are fine.
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    private fun selector() = NearPlayerSelector(keypoints, 1920.0, 1080.0)

    /** A person whose ankles are at [x],[y]; everything else is placed above them. */
    private fun person(
        x: Double,
        y: Double,
        boxConfidence: Float = 0.9f,
        ankleConfidence: Float = 0.9f,
        hipConfidence: Float = 0.9f,
    ): PosePerson {
        val kps = MutableList(Coco.COUNT) { Point(x, y - 100.0) }
        val conf = MutableList(Coco.COUNT) { 0.9f }
        kps[Coco.LEFT_ANKLE] = Point(x - 10.0, y)
        kps[Coco.RIGHT_ANKLE] = Point(x + 10.0, y)
        kps[Coco.LEFT_HIP] = Point(x - 10.0, y - 90.0)
        kps[Coco.RIGHT_HIP] = Point(x + 10.0, y - 90.0)
        conf[Coco.LEFT_ANKLE] = ankleConfidence
        conf[Coco.RIGHT_ANKLE] = ankleConfidence
        conf[Coco.LEFT_HIP] = hipConfidence
        conf[Coco.RIGHT_HIP] = hipConfidence
        return PosePerson(boxConfidence, kps, conf)
    }

    @Test
    fun the_setup_is_usable() {
        assertTrue(selector().usable, "the fixture must give a valid homography and net line")
    }

    @Test
    fun a_player_on_the_near_court_is_selected_and_projected() {
        // Well inside the near half, below the net line.
        val result = selector().select(PoseFrame(7, listOf(person(966.0, 900.0))))
        val sample = assertNotNull(result.sample)
        assertEquals(7, sample.frame)
        // Near half of a 13.4m court, roughly on the centre line.
        assertTrue(sample.courtPosition.y > Court_HALF, "expected the near half, got ${sample.courtPosition}")
        assertTrue(sample.courtPosition.x in 1.0..5.0, "expected mid-width, got ${sample.courtPosition}")
    }

    @Test
    fun a_player_on_the_far_side_of_the_net_is_rejected() {
        // Above the net line: the other player, who this pipeline does not track.
        val result = selector().select(PoseFrame(1, listOf(person(966.0, 550.0))))
        assertNull(result.sample)
        assertEquals(RejectionReason.WRONG_SIDE, result.rejection)
    }

    @Test
    fun the_crowd_is_rejected_by_court_position_not_by_the_net_line() {
        // Below the net line, so the side gate passes it, but well wide of the
        // tramlines: a coach or spectator standing at the side of the court.
        // It projects to court x = -2.7, outside the 2m margin. On real footage
        // this gate rejected more detections than it kept, which is why it is a
        // requirement rather than a refinement.
        val result = selector().select(PoseFrame(1, listOf(person(150.0, 700.0))))
        assertNull(result.sample)
        assertEquals(RejectionReason.OFF_COURT, result.rejection)
    }

    @Test
    fun confident_hips_without_ankles_yield_nothing() {
        // The hips used to stand in here. Measured, a hip midpoint projects two
        // to three metres from the ankles on real footage, and no estimate
        // built on it gets within an order of magnitude of an ankle. A frame
        // without ankles is a gap in coverage, never a guess.
        val result = selector().select(
            PoseFrame(1, listOf(person(966.0, 900.0, ankleConfidence = 0.1f))),
        )
        assertNull(result.sample)
        assertEquals(RejectionReason.NO_GROUND_POINT, result.rejection)
    }

    @Test
    fun marks_that_do_not_fit_a_court_disable_selection() {
        // Two corners clicked in the wrong order. Every keypoint is still a
        // real pixel and a homography still comes out of the solver; it is
        // just one that puts the player metres from where they stand. The
        // residual is what notices.
        val scrambled = NearPlayerSelector(
            keypoints.copy(topLeft = keypoints.bottomRight, bottomRight = keypoints.topLeft),
            1920.0,
            1080.0,
        )
        assertTrue(scrambled.courtFitResidualM!! > NearPlayerSelector.MAX_COURT_RESIDUAL_M)
        assertFalse(scrambled.courtUsable)
        assertFalse(scrambled.usable)
        val result = scrambled.select(PoseFrame(1, listOf(person(966.0, 900.0))))
        assertNull(result.sample)
        assertEquals(RejectionReason.BAD_COURT, result.rejection)
    }

    @Test
    fun the_real_marks_fit_the_court_closely() {
        // The fixture is a real marking with the service lines labelled one
        // way round and the centre points the other. Resolved by pixel, it
        // fits to well under the gate; as labelled it fit to 3.7m.
        val residual = selector().courtFitResidualM!!
        assertTrue(residual < 0.4, "residual $residual m")
    }

    @Test
    fun a_person_with_neither_ankles_nor_hips_yields_nothing() {
        val result = selector().select(
            PoseFrame(1, listOf(person(966.0, 900.0, ankleConfidence = 0.1f, hipConfidence = 0.1f))),
        )
        assertNull(result.sample)
        assertEquals(RejectionReason.NO_GROUND_POINT, result.rejection)
    }

    @Test
    fun the_most_confident_on_court_person_wins() {
        val result = selector().select(
            PoseFrame(
                1,
                listOf(
                    person(700.0, 900.0, boxConfidence = 0.4f),
                    person(1200.0, 950.0, boxConfidence = 0.95f),
                    person(966.0, 550.0, boxConfidence = 0.99f), // far side, must not win
                ),
            ),
        )
        val sample = assertNotNull(result.sample)
        // The 0.99 detection is on the far side, so the 0.95 near one wins.
        assertTrue(sample.courtPosition.x > 3.0, "expected the right-hand player, got ${sample.courtPosition}")
    }

    @Test
    fun a_degenerate_net_line_disables_selection_rather_than_guessing() {
        // Both endpoints at the origin. Without this guard the interpolated net
        // line classifies every point in the frame as one side, which switches
        // identity off silently instead of failing.
        val broken = NearPlayerSelector(
            keypoints.copy(netLeft = Point(0.0, 0.0), netRight = Point(0.0, 0.0)),
            1920.0,
            1080.0,
        )
        assertFalse(broken.netLineUsable)
        assertFalse(broken.usable)
        assertNull(broken.select(PoseFrame(1, listOf(person(966.0, 900.0)))).sample)
    }

    private companion object {
        /** Half of Court.LENGTH; the near half is the larger y in court space here. */
        const val Court_HALF = 6.7
    }
}
