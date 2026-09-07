package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.homography
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseMetricsTest {

    // 743d7fb1's marks, as NearPlayerSelectorTest uses them.
    private val marks = CourtKeypoints(
        topLeft = Point(649.5, 484.8), topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6), bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9), netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5), serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2), serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4), centerFar = Point(966.1, 736.3),
    )
    private val h = marks.homography()!!

    private fun near(expected: Double, actual: Double?, tolerance: Double, what: String) {
        assertNotNull(actual, "$what is absent")
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    /** All joints confident at [where]; callers overwrite the ones under test. */
    private fun figure(where: Point = Point(900.0, 800.0)): Pair<MutableList<Point>, MutableList<Float>> =
        MutableList(Coco.COUNT) { where } to MutableList(Coco.COUNT) { 0.9f }

    @Test
    fun a_right_angle_is_ninety_degrees_whichever_way_it_opens() {
        near(90.0, jointAngleDeg(Point(0.0, 0.0), Point(0.0, 1.0), Point(1.0, 1.0)), 1e-9, "angle")
        near(90.0, jointAngleDeg(Point(1.0, 1.0), Point(0.0, 1.0), Point(0.0, 0.0)), 1e-9, "angle")
    }

    @Test
    fun a_straight_limb_is_one_hundred_eighty_and_a_folded_one_is_zero() {
        near(180.0, jointAngleDeg(Point(0.0, 0.0), Point(1.0, 0.0), Point(2.0, 0.0)), 1e-9, "straight")
        near(0.0, jointAngleDeg(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 0.0)), 1e-9, "folded")
    }

    @Test
    fun a_joint_on_top_of_its_neighbour_has_no_angle() {
        assertNull(jointAngleDeg(Point(1.0, 1.0), Point(1.0, 1.0), Point(2.0, 2.0)))
    }

    @Test
    fun an_unconfident_joint_removes_only_the_angles_that_use_it() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_SHOULDER] = Point(0.0, 0.0)
        kps[Coco.LEFT_ELBOW] = Point(0.0, 10.0)
        kps[Coco.LEFT_WRIST] = Point(10.0, 10.0)
        kps[Coco.RIGHT_SHOULDER] = Point(100.0, 0.0)
        kps[Coco.RIGHT_ELBOW] = Point(100.0, 10.0)
        kps[Coco.RIGHT_WRIST] = Point(100.0, 20.0)
        conf[Coco.LEFT_WRIST] = 0.3f
        val m = poseMetrics(kps, conf, homography = null)
        assertNull(m.elbowLeftDeg, "left elbow needs the left wrist")
        near(180.0, m.elbowRightDeg, 1e-9, "right elbow")
    }

    @Test
    fun without_a_homography_the_court_plane_metrics_are_absent_and_the_angles_are_not() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(0.0, 0.0)
        kps[Coco.LEFT_KNEE] = Point(0.0, 10.0)
        kps[Coco.LEFT_ANKLE] = Point(0.0, 20.0)
        val m = poseMetrics(kps, conf, homography = null)
        assertNull(m.stanceM)
        assertNull(m.behindServiceLineM)
        assertNull(m.fromCentreLineM)
        near(180.0, m.kneeLeftDeg, 1e-9, "knee")
    }

    @Test
    fun ankles_on_the_near_corners_stand_a_court_width_apart_a_court_length_behind_the_net() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.bottomLeft
        kps[Coco.RIGHT_ANKLE] = marks.bottomRight
        val m = poseMetrics(kps, conf, h)
        // The corners fit to well under the 0.37m worst-point residual.
        near(6.1, m.stanceM, 0.2, "stance")
        near(13.4 - 8.68, m.behindServiceLineM, 0.2, "behind the service line")
        near(0.0, m.fromCentreLineM, 0.2, "from the centre line")
    }

    @Test
    fun ankles_on_the_far_half_have_a_stance_but_no_service_line_distance() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.topLeft
        kps[Coco.RIGHT_ANKLE] = marks.topRight
        val m = poseMetrics(kps, conf, h)
        near(6.1, m.stanceM, 0.2, "stance")
        assertNull(m.behindServiceLineM, "the service-line distance is the near player's")
        assertNull(m.fromCentreLineM)
    }

    @Test
    fun an_unconfident_ankle_removes_the_court_plane_metrics() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.bottomLeft
        kps[Coco.RIGHT_ANKLE] = marks.bottomRight
        conf[Coco.RIGHT_ANKLE] = 0.1f
        val m = poseMetrics(kps, conf, h)
        assertNull(m.stanceM)
        assertNull(m.behindServiceLineM)
    }

    @Test
    fun trunk_lean_is_zero_upright_and_positive_toward_the_frames_right() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(90.0, 100.0)
        kps[Coco.RIGHT_HIP] = Point(110.0, 100.0)
        kps[Coco.LEFT_SHOULDER] = Point(90.0, 0.0)
        kps[Coco.RIGHT_SHOULDER] = Point(110.0, 0.0)
        near(0.0, poseMetrics(kps, conf, null).trunkLeanDeg, 1e-9, "upright")
        kps[Coco.LEFT_SHOULDER] = Point(190.0, 0.0)
        kps[Coco.RIGHT_SHOULDER] = Point(210.0, 0.0)
        near(45.0, poseMetrics(kps, conf, null).trunkLeanDeg, 1e-9, "leaning right")
    }

    @Test
    fun arm_angle_is_zero_hanging_and_one_eighty_straight_up() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(0.0, 100.0)
        kps[Coco.LEFT_SHOULDER] = Point(0.0, 0.0)
        kps[Coco.LEFT_ELBOW] = Point(0.0, 50.0)
        near(0.0, poseMetrics(kps, conf, null).armLeftDeg, 1e-9, "hanging")
        kps[Coco.LEFT_ELBOW] = Point(0.0, -50.0)
        near(180.0, poseMetrics(kps, conf, null).armLeftDeg, 1e-9, "raised")
    }

    @Test
    fun a_short_keypoint_list_measures_nothing() {
        val m = poseMetrics(List(5) { Point(0.0, 0.0) }, List(5) { 0.9f }, h)
        assertEquals(PoseMetrics.NONE, m)
    }

    @Test
    fun every_kind_reads_its_own_field() {
        val m = PoseMetrics(
            stanceM = 1.0, behindServiceLineM = 2.0, fromCentreLineM = 3.0,
            elbowLeftDeg = 4.0, elbowRightDeg = 5.0, armLeftDeg = 6.0, armRightDeg = 7.0,
            kneeLeftDeg = 8.0, kneeRightDeg = 9.0, trunkLeanDeg = 10.0,
        )
        val seen = MetricKind.entries.map { it.of(m) }
        assertEquals(seen.size, seen.toSet().size, "two kinds read the same field: $seen")
        assertEquals(1.0, MetricKind.STANCE.of(m))
        assertEquals(2.0, MetricKind.BEHIND_LINE.of(m))
        assertEquals(5.0, MetricKind.ELBOW_RIGHT.of(m))
        assertEquals(10.0, MetricKind.LEAN.of(m))
    }

    @Test
    fun angle_kinds_name_the_vertex_between_the_right_neighbours() {
        assertEquals(Triple(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST), MetricKind.ELBOW_LEFT.angleJoints)
        assertEquals(Triple(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP), MetricKind.ARM_RIGHT.angleJoints)
        assertEquals(Triple(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE), MetricKind.KNEE_RIGHT.angleJoints)
        assertNull(MetricKind.STANCE.angleJoints)
        assertNull(MetricKind.LEAN.angleJoints)
        assertTrue(MetricKind.entries.filter { it.isAngle }.all { it.range == 0.0..180.0 || it == MetricKind.LEAN })
    }
}
