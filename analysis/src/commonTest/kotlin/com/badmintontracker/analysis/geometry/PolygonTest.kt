package com.badmintontracker.analysis.geometry

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PolygonTest {

    private val quad = listOf(
        Point(100.0, 100.0), Point(300.0, 100.0),
        Point(320.0, 300.0), Point(80.0, 300.0),
    )

    @Test
    fun a_point_inside_is_contained() {
        quad.containsPoint(Point(200.0, 200.0)) shouldBe true
    }

    @Test
    fun a_point_outside_is_not() {
        quad.containsPoint(Point(50.0, 200.0)) shouldBe false
        quad.containsPoint(Point(200.0, 50.0)) shouldBe false
    }

    @Test
    fun a_point_level_with_a_vertex_does_not_double_count_the_crossing() {
        // The classic ray-casting bug: a horizontal ray through a vertex
        // counts two edge crossings instead of one and reports inside as
        // outside. The half-open y test below is what prevents it.
        quad.containsPoint(Point(200.0, 100.0)) shouldBe true
    }

    @Test
    fun expanding_about_the_centroid_scales_every_vertex() {
        val e = quad.expandedAbout(2.0)
        val cx = quad.sumOf { it.x } / 4
        e[0].x shouldBe cx + (quad[0].x - cx) * 2.0
    }

    @Test
    fun a_degenerate_net_line_is_rejected() {
        // Both endpoints at the origin is the classic unplaced-keypoint
        // payload. Accepting it makes every position in the frame classify as
        // one court side, silently disabling player identity.
        validNetLine(Point(0.0, 0.0), Point(0.0, 0.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun endpoints_too_close_horizontally_are_rejected() {
        // The net spans the court width; with almost no horizontal separation
        // the y-at-x interpolation is ill-conditioned.
        validNetLine(Point(900.0, 500.0), Point(905.0, 505.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun endpoints_outside_the_frame_are_rejected() {
        validNetLine(Point(-1.0, 500.0), Point(1800.0, 500.0), 1920.0, 1080.0) shouldBe false
        validNetLine(Point(100.0, 500.0), Point(2100.0, 500.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun a_real_net_line_is_accepted() {
        validNetLine(Point(220.0, 560.0), Point(1700.0, 545.0), 1920.0, 1080.0) shouldBe true
    }

    @Test
    fun a_null_endpoint_is_rejected() {
        validNetLine(null, Point(1700.0, 545.0), 1920.0, 1080.0) shouldBe false
    }
}
