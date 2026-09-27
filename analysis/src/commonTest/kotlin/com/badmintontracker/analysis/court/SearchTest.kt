package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.time.TimeSource

/** The two synthetic scenes, each rendered once: a render costs seconds on iOS. */
private object Scenes {
    val cameras = listOf(
        SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = 1.5, height = 3.0, back = 4.0, w = 1280, h = 720, f = 1000.0),
    )
    val work: List<GreyImage> by lazy {
        cameras.map { cam ->
            val img = SyntheticCourt.render(cam, 1280, 720, floor = 0xFF3C8C46.toInt(), line = 0xFFEBEBEB.toInt())
            val (w, h) = longSideSize(1280, 720, 960)
            areaDownsample(img.luma(), w, h)
        }
    }
}

class SearchTest {
    private val scale = 960.0 / 1280.0

    /** A 1280x720 source pixel in the 960x540 work image, pixel centres aligned. */
    private fun toWork(t: Point) = Point((t.x + 0.5) * scale - 0.5, (t.y + 0.5) * scale - 0.5)

    private fun keypointsLandOnTheTruth(s: SearchResult, cam: DoubleArray) {
        SyntheticCourt.truth(cam).forEachIndexed { k, t ->
            val p = project(s.h, CourtModel.KEYPOINTS[k].x, CourtModel.KEYPOINTS[k].y).shouldNotBeNull()
            if (t.x in 0.0..1279.0 && t.y in 0.0..719.0) {
                val q = toWork(t)
                hypot(p.x - q.x, p.y - q.y) shouldBeLessThan 4.0
            }
        }
    }

    @Test fun the_search_lands_on_the_court_to_a_few_work_pixels() {
        for ((i, cam) in Scenes.cameras.withIndex()) {
            val work = Scenes.work[i]
            val mark = TimeSource.Monotonic.markNow()
            val s = searchCourt(work).shouldNotBeNull()
            val took = mark.elapsedNow()
            s.h[8] shouldBe 1.0
            keypointsLandOnTheTruth(s, cam)
            println("case $i: lines ${s.lines} quads ${s.quads} score ${s.score} search $took")
        }
    }

    @Test fun the_corner_guided_search_lands_on_the_court_from_rough_taps() {
        val cam = Scenes.cameras[0]
        val offsets = listOf(Point(15.0, 0.0), Point(0.0, -15.0), Point(-10.6, 10.6), Point(10.6, -10.6))
        // The taps in a different corner order from the model's: they are compared as a set.
        val corners = SyntheticCourt.truth(cam).take(4).map(::toWork)
        val taps = listOf(corners[2], corners[3], corners[0], corners[1]).zip(offsets) { c, o -> Point(c.x + o.x, c.y + o.y) }
        val s = searchCourtNear(Scenes.work[0], taps, radius = 0.05 * 960).shouldNotBeNull()
        s.h[8] shouldBe 1.0
        keypointsLandOnTheTruth(s, cam)
    }

    @Test fun the_corner_guided_search_finds_nothing_far_from_the_taps() {
        val taps = listOf(Point(10.0, 10.0), Point(60.0, 10.0), Point(60.0, 40.0), Point(10.0, 40.0))
        searchCourtNear(Scenes.work[0], taps, radius = 0.05 * 960) shouldBe null
    }

    @Test fun a_blank_frame_has_no_court() {
        searchCourt(GreyImage(960, 540, FloatArray(960 * 540) { 90f })) shouldBe null
    }

    @Test fun canonical_puts_the_origin_top_left() {
        val cam = SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)
        val c = canonical(cam)
        val o = project(c, 0.0, 0.0)!!; val x = project(c, 6.1, 0.0)!!; val y = project(c, 0.0, 13.4)!!
        (o.x < x.x && o.y < y.y) shouldBe true
        c[8] shouldBe 1.0
    }

    @Test fun project_refuses_points_behind_the_camera() {
        val h = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, -2.0)
        project(h, 0.0, 1.0) shouldBe null
        project(h, 0.0, 3.0).shouldNotBeNull().y shouldBe 3.0
    }

    @Test fun a_placement_maps_each_rectangle_onto_the_quad() {
        // A genuinely projective quad (its diagonal sums differ), so the perspective terms are exercised.
        val quad = doubleArrayOf(120.0, 80.0, 610.0, 95.0, 820.0, 470.0, 40.0, 430.0)
        val hq = DoubleArray(9)
        squareToQuad(quad, hq) shouldBe true
        val out = DoubleArray(9)
        for ((r, rect) in CourtModel.RECTANGLES.withIndex()) {
            placement(hq, r, out) shouldBe true
            out[8] shouldBe 1.0
            for (c in 0..3) {
                val p = project(out, rect[2 * c], rect[2 * c + 1]).shouldNotBeNull()
                hypot(p.x - quad[2 * c], p.y - quad[2 * c + 1]) shouldBeLessThan 1e-6
            }
        }
    }

    @Test fun gates_refuse_a_court_too_small_or_with_a_short_net() {
        val cam = SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)
        gatesPass(cam, 1280, 720) shouldBe true
        // The same court shrunk 20x about the origin: under 2% of the frame.
        val tiny = cam.copyOf().also { for (i in 0..5) it[i] /= 20.0 }
        gatesPass(tiny, 1280, 720) shouldBe false
        // Behind the camera: every z negated.
        val behind = cam.copyOf().also { for (i in 6..8) it[i] = -it[i] }
        gatesPass(behind, 1280, 720) shouldBe false
    }
}
