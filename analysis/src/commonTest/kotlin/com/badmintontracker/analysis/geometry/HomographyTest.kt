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
    fun keypoints_map_back_onto_their_own_court_positions() {
        // CourtKeypoints.homography() exists to feed calculateHomography the
        // twelve keypoints in the order COURT_KEYPOINT_POSITIONS expects, and
        // that ordering is the only thing it does. Swap any two of them - the
        // net pair, the near and far service lines, the two centre points -
        // and it still returns a plausible matrix built from twelve real
        // correspondences, just one that maps the court wrong. Nothing else
        // in the suite calls this function, so nothing else would notice.
        //
        // Projecting the court positions through a camera and requiring each
        // pixel to land back on the position it came from pins the order:
        // a swapped pair no longer round-trips.
        val camera = listOf(
            listOf(0.9, -0.35, 640.0),
            listOf(0.05, 0.6, 180.0),
            listOf(0.00004, -0.0007, 1.0),
        )
        fun project(p: Point): Point {
            val w = camera[2][0] * p.x + camera[2][1] * p.y + camera[2][2]
            return Point(
                (camera[0][0] * p.x + camera[0][1] * p.y + camera[0][2]) / w,
                (camera[1][0] * p.x + camera[1][1] * p.y + camera[1][2]) / w,
            )
        }

        val names = listOf(
            "top_left", "top_right", "bottom_right", "bottom_left",
            "net_left", "net_right",
            "service_line_near_left", "service_line_near_right",
            "service_line_far_left", "service_line_far_right",
            "center_near", "center_far",
        )
        val pixels = COURT_KEYPOINT_POSITIONS.map(::project)
        val keypoints = CourtKeypoints.fromMap(
            names.zip(pixels).associate { (n, p) -> n to listOf(p.x, p.y) }
        )!!

        val h = keypoints.homography()!!
        pixels.zip(COURT_KEYPOINT_POSITIONS).forEach { (pixel, expected) ->
            val got = h.apply(pixel.x, pixel.y)!!
            kotlin.math.abs(got.x - expected.x) shouldBeLessThan 1e-9
            kotlin.math.abs(got.y - expected.y) shouldBeLessThan 1e-9
        }
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
