package com.badmintontracker.analysis.geometry

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class HomographyTest {

    // A synthetic camera: the court rectangle seen as a trapezoid, far edge
    // narrower than the near edge. Any real overhead frame is this shape.
    private val corners = listOf(
        Point(600.0, 200.0),   // top_left     -> (0, 0)
        Point(1320.0, 200.0),  // top_right    -> (6.1, 0)
        Point(1700.0, 950.0),  // bottom_right -> (6.1, 13.4)
        Point(220.0, 950.0),   // bottom_left  -> (0, 13.4)
    )
    private val courtCorners = listOf(
        Point(0.0, 0.0),
        Point(Court.WIDTH_DOUBLES, 0.0),
        Point(Court.WIDTH_DOUBLES, Court.LENGTH),
        Point(0.0, Court.LENGTH),
    )

    @Test
    fun the_four_corners_map_onto_the_court_rectangle() {
        val h = calculateHomography(corners, courtCorners)
        h shouldNotBe null
        corners.zip(courtCorners).forEach { (px, expected) ->
            val got = h!!.apply(px.x, px.y)!!
            // Absolute tolerance in metres: a corner must land on its corner.
            kotlin.math.abs(got.x - expected.x) shouldBeLessThan 1e-6
            kotlin.math.abs(got.y - expected.y) shouldBeLessThan 1e-6
        }
    }

    @Test
    fun the_centre_of_the_image_quad_maps_near_the_centre_of_the_court() {
        val h = calculateHomography(corners, courtCorners)!!
        // Not the centroid of the pixel quad: perspective means the image
        // centroid is NOT the court centre. This asserts only that it lands
        // inside the court, which is the property that catches a transposed
        // or mirrored matrix.
        val got = h.apply(960.0, 575.0)!!
        (got.x > 0.0 && got.x < Court.WIDTH_DOUBLES) shouldBe true
        (got.y > 0.0 && got.y < Court.LENGTH) shouldBe true
    }

    @Test
    fun three_points_is_not_enough() {
        calculateHomography(corners.take(3), courtCorners.take(3)).shouldBeNull()
    }

    @Test
    fun collinear_source_points_are_rejected() {
        // A degenerate configuration has no unique homography. Returning a
        // matrix here would silently produce garbage metres for every frame.
        val collinear = listOf(
            Point(0.0, 0.0), Point(10.0, 0.0), Point(20.0, 0.0), Point(30.0, 0.0),
        )
        calculateHomography(collinear, courtCorners).shouldBeNull()
    }

    @Test
    fun a_point_behind_the_camera_plane_returns_null() {
        // w == 0 means the point projects to infinity. Dividing by it would
        // yield an infinity that propagates into distance totals.
        val h = listOf(
            listOf(1.0, 0.0, 0.0),
            listOf(0.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0),
        )
        h.apply(5.0, 5.0).shouldBeNull()
    }
}

private infix fun Double.shouldBeLessThan(other: Double) {
    if (this >= other) throw AssertionError("$this was not less than $other")
}
