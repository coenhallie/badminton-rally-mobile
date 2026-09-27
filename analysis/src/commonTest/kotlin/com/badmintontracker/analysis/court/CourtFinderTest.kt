package com.badmintontracker.analysis.court

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.time.TimeSource

class CourtFinderTest {
    private val colours = listOf(
        0xFF3C8C46.toInt() to 0xFFEBEBEB.toInt(),
        0xFFD2AA78.toInt() to 0xFFF0F0F0.toInt(),
        0xFFC86E46.toInt() to 0xFFE6C828.toInt(),
        0xFF969696.toInt() to 0xFFFAFAFA.toInt(),
    )
    private val cams = listOf(
        SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = 1.5, height = 3.0, back = 4.0, w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = -2.0, height = 6.0, back = 8.0, w = 1280, h = 720, f = 870.0),
    )

    @Test fun synthetic_courts_are_found_to_a_fraction_of_a_pixel() {
        val errors = mutableListOf<Double>()
        for ((c, cam) in cams.withIndex()) for ((j, pair) in colours.withIndex()) {
            val (floor, line) = pair
            val img = SyntheticCourt.render(cam, 1280, 720, floor, line)
            val f = CourtFinder.find(img)
            val truth = SyntheticCourt.truth(cam)
            val scene = mutableListOf<Double>()
            if (truth.all { it.x in 0.0..1279.0 && it.y in 0.0..719.0 }) {
                f.found shouldBe true
                val k = f.keypoints!!
                listOf(k.topLeft, k.topRight, k.bottomRight, k.bottomLeft, k.netLeft, k.netRight,
                    k.serviceLineNearLeft, k.serviceLineNearRight, k.serviceLineFarLeft,
                    k.serviceLineFarRight, k.centerNear, k.centerFar).forEachIndexed { i, p ->
                    scene += hypot(p.x - truth[i].x, p.y - truth[i].y)
                }
                errors += scene
            }
            val stats = if (scene.isEmpty()) "not all keypoints in frame" else
                "median ${scene.sorted()[scene.size / 2]} max ${scene.max()}"
            println("scene $c/$j: ${f.reason} residual ${f.residualPx} ${f.millis} ms; $stats")
        }
        println("all: median ${errors.sorted()[errors.size / 2]} max ${errors.max()} over ${errors.size}")
        errors.sorted()[errors.size / 2] shouldBeLessThan 0.1
        errors.max() shouldBeLessThan 0.5
    }

    @Test fun frames_without_a_court_are_not_found() {
        val grey = RgbImage(640, 360, IntArray(640 * 360) { 0xFF808080.toInt() })
        CourtFinder.find(grey).found shouldBe false
        val rnd = kotlin.random.Random(3)
        val noise = RgbImage(640, 360, IntArray(640 * 360) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) })
        CourtFinder.find(noise).found shouldBe false
    }

    @Test fun degenerate_frames_are_not_found() {
        CourtFinder.find(RgbImage(1, 1, intArrayOf(0xFFFFFFFF.toInt()))).found shouldBe false
        CourtFinder.find(RgbImage(16, 16, IntArray(256) { 0xFF102030.toInt() })).found shouldBe false
    }

    /** Beyond the brief: a frame with no court fails for a reason, not by the catch-all turning an exception into one. */
    @Test fun frames_without_a_court_fail_for_a_reason_not_an_error() {
        val rnd = kotlin.random.Random(3)
        val frames = mapOf(
            "1x1" to RgbImage(1, 1, intArrayOf(0xFFFFFFFF.toInt())),
            "16x16" to RgbImage(16, 16, IntArray(256) { 0xFF102030.toInt() }),
            "grey" to RgbImage(640, 360, IntArray(640 * 360) { 0xFF808080.toInt() }),
            "noise" to RgbImage(640, 360, IntArray(640 * 360) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }),
        )
        for ((name, img) in frames) {
            val f = CourtFinder.find(img)
            println("$name: ${f.reason}")
            f.found shouldBe false
            f.reason shouldNotStartWith "error"
        }
    }

    @Test fun portrait_frame_is_handled() {
        val cam = SyntheticCourt.camera(w = 720, h = 1280, f = 700.0, height = 6.0, back = 8.0)
        val img = SyntheticCourt.render(cam, 720, 1280, 0xFF3C8C46.toInt(), 0xFFEBEBEB.toInt())
        val f = CourtFinder.find(img)
        val k = f.keypoints
        if (k != null) {
            val t = SyntheticCourt.truth(cam)[0]
            println("portrait: found, top-left off by ${hypot(k.topLeft.x - t.x, k.topLeft.y - t.y)} px")
            hypot(k.topLeft.x - t.x, k.topLeft.y - t.y) shouldBeLessThan 1.0
        } else println("portrait: not found (${f.reason})")
    }

    /**
     * The brief used `cams[1]`, whose two near corners lie outside the frame
     * (truth (1395.6, 677.9) and (166.2, 788.0) in 1280x720): a tap there is
     * impossible, and §4.7 refuses a court with a keypoint outside the frame
     * whichever way it was found. `cams[2]` has all twelve in frame.
     */
    @Test fun four_corners_complete_the_court() {
        val cam = cams[2]
        val img = SyntheticCourt.render(cam, 1280, 720, colours[0].first, colours[0].second)
        val t = SyntheticCourt.truth(cam)
        val rnd = kotlin.random.Random(11)
        val taps = t.take(4).map { com.badmintontracker.analysis.geometry.Point(it.x + rnd.nextDouble(-10.0, 10.0), it.y + rnd.nextDouble(-10.0, 10.0)) }
        val f = CourtFinder.fromCorners(img, taps)
        f.reason shouldBe "ok"
        val k = f.keypoints!!
        hypot(k.centerFar.x - t[11].x, k.centerFar.y - t[11].y) shouldBeLessThan 0.5
    }

    /** Beyond the brief: the returned homography maps court metres to the same source pixels as the keypoints. */
    @Test fun the_homography_maps_court_metres_to_the_keypoints() {
        val cam = cams[2]
        val img = SyntheticCourt.render(cam, 1280, 720, colours[0].first, colours[0].second)
        val f = CourtFinder.find(img)
        f.found shouldBe true
        val hm = f.homography!!
        val kp = f.keypoints!!
        for ((m, p) in listOf(CourtModel.KEYPOINTS[0] to kp.topLeft, CourtModel.KEYPOINTS[11] to kp.centerFar)) {
            val z = hm[2][0] * m.x + hm[2][1] * m.y + hm[2][2]
            val x = (hm[0][0] * m.x + hm[0][1] * m.y + hm[0][2]) / z
            val y = (hm[1][0] * m.x + hm[1][1] * m.y + hm[1][2]) / z
            hypot(x - p.x, y - p.y) shouldBeLessThan 1e-9
        }
    }

    /** Beyond the brief: fewer than four corners is a reason, not an exception. */
    @Test fun from_corners_needs_four_corners() {
        val img = RgbImage(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })
        val f = CourtFinder.fromCorners(img, listOf(com.badmintontracker.analysis.geometry.Point(1.0, 1.0)))
        f.found shouldBe false
        f.reason shouldNotStartWith "error"
    }

    /** Not an assertion: the time `find` takes on a 1280x720 frame, first call and again. */
    @Test fun find_timing_on_a_1280x720_frame() {
        val img = SyntheticCourt.render(cams[0], 1280, 720, colours[0].first, colours[0].second)
        val times = (0 until 2).map {
            val mark = TimeSource.Monotonic.markNow()
            val f = CourtFinder.find(img)
            val took = mark.elapsedNow().inWholeMilliseconds
            f.found shouldBe true
            took
        }
        println("find 1280x720: calls ${times.joinToString()} ms")
    }
}
