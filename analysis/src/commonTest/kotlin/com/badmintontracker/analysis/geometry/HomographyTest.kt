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
    // The three corpus videos' marks as the cloud stored them, rounded to the
    // pixel. Three different readings of "near": 0a654e34 has it at the top of
    // the frame, 2eabfc01 at the camera, and 743d7fb1 one way for the service
    // lines and the other for the centre points. Fitted to the position table
    // as written, the second and third had every corner 1.1 to 1.6m off.
    private val corpus0a654e34 = CourtKeypoints(
        topLeft = Point(382.0, 247.0), topRight = Point(816.0, 251.0),
        bottomRight = Point(1252.0, 763.0), bottomLeft = Point(3.0, 760.0),
        netLeft = Point(267.0, 372.0), netRight = Point(934.0, 370.0),
        serviceLineNearLeft = Point(324.0, 326.0), serviceLineNearRight = Point(888.0, 325.0),
        serviceLineFarLeft = Point(224.0, 448.0), serviceLineFarRight = Point(995.0, 459.0),
        centerNear = Point(614.0, 325.0), centerFar = Point(621.0, 450.0),
    )
    private val corpus2eabfc01 = CourtKeypoints(
        topLeft = Point(511.0, 400.0), topRight = Point(1256.0, 409.0),
        bottomRight = Point(1431.0, 950.0), bottomLeft = Point(4.0, 934.0),
        netLeft = Point(332.0, 573.0), netRight = Point(1317.0, 581.0),
        serviceLineNearLeft = Point(258.0, 656.0), serviceLineNearRight = Point(1337.0, 669.0),
        serviceLineFarLeft = Point(399.0, 516.0), serviceLineFarRight = Point(1294.0, 523.0),
        centerNear = Point(801.0, 665.0), centerFar = Point(845.0, 517.0),
    )
    private val corpus743d7fb1 = CourtKeypoints(
        topLeft = Point(650.0, 485.0), topRight = Point(1277.0, 481.0),
        bottomRight = Point(1579.0, 999.0), bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 664.0), netRight = Point(1382.0, 666.0),
        serviceLineNearLeft = Point(505.0, 744.0), serviceLineNearRight = Point(1422.0, 738.0),
        serviceLineFarLeft = Point(588.0, 595.0), serviceLineFarRight = Point(1339.0, 595.0),
        centerNear = Point(966.0, 593.0), centerFar = Point(966.0, 736.0),
    )

    @Test
    fun every_corpus_marking_fits_whichever_way_near_was_read() {
        listOf("0a654e34" to corpus0a654e34, "2eabfc01" to corpus2eabfc01, "743d7fb1" to corpus743d7fb1)
            .forEach { (name, marks) ->
                val h = marks.homography() ?: throw AssertionError("$name: no homography")
                val residual = marks.maxResidualM(h) ?: throw AssertionError("$name: a mark projected to infinity")
                // Well-placed marks fit to 0.37m at worst; the selector's gate is 1.0m.
                if (residual > 0.4) throw AssertionError("$name: worst residual $residual m")
            }
    }

    @Test
    fun marks_that_agree_with_the_table_give_exactly_the_table_fit() {
        // 0a654e34 is labelled the way COURT_KEYPOINT_POSITIONS reads, so the
        // resolved positions are the table itself and the matrix is byte for
        // byte what the TypeScript reference computes. Resolution changes
        // nothing for input that did not need it.
        val marks = corpus0a654e34
        marks.courtPositions() shouldBe COURT_KEYPOINT_POSITIONS
        marks.homography() shouldBe calculateHomography(marks.pixels(), COURT_KEYPOINT_POSITIONS)
    }

    @Test
    fun a_pair_marked_at_the_camera_takes_the_bottom_half_positions() {
        val positions = corpus2eabfc01.courtPositions()
        // "near" service line pixels sit below the net, so they get the
        // far-from-top positions, and vice versa.
        positions[6] shouldBe COURT_KEYPOINT_POSITIONS[8]
        positions[8] shouldBe COURT_KEYPOINT_POSITIONS[6]
        positions[10] shouldBe COURT_KEYPOINT_POSITIONS[11]
        positions[11] shouldBe COURT_KEYPOINT_POSITIONS[10]
        // The corners and net are never touched.
        positions.take(6) shouldBe COURT_KEYPOINT_POSITIONS.take(6)
    }

    @Test
    fun a_pair_on_the_same_side_of_the_net_is_used_as_labelled() {
        // Nothing to resolve it by, so the labels stand and the residual says
        // what that costs; the selector's gate is what acts on it.
        val bothBelow = corpus0a654e34.copy(serviceLineNearLeft = Point(224.0, 470.0))
        bothBelow.courtPositions() shouldBe COURT_KEYPOINT_POSITIONS
    }

    @Test
    fun the_residual_reports_a_mis_ordered_corner_in_metres() {
        val good = corpus743d7fb1.maxResidualM(corpus743d7fb1.homography()!!)!!
        val swapped = corpus743d7fb1.copy(topLeft = corpus743d7fb1.topRight, topRight = corpus743d7fb1.topLeft)
        val bad = swapped.maxResidualM(swapped.homography()!!)!!
        good shouldBeLessThan 0.4
        if (bad < 1.0) throw AssertionError("swapped corners fit to $bad m, expected metres")
    }

}

private infix fun Double.shouldBeLessThan(other: Double) {
    if (this >= other) throw AssertionError("$this was not less than $other")
}
