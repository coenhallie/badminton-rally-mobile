package com.badmintontracker.analysis.camera

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class CornersTest {
    @Test fun a_textured_image_yields_the_requested_count() {
        // This texture, quality and spacing top out at 117 well-spaced corners
        // (measured), short of the 150 default: request a count the fixture
        // can actually fill, so the test still proves the cap is honoured.
        shiTomasi(Synthetic.texture(480, 270, 2), maxCorners = 100) shouldHaveSize 100
    }

    @Test fun corners_keep_their_distance_and_the_border() {
        val c = shiTomasi(Synthetic.texture(480, 270, 3))
        for (i in c.indices) for (j in i + 1 until c.size) {
            (hypot(c[i].first - c[j].first, c[i].second - c[j].second) >= 8.0) shouldBe true
        }
        c.all { it.first >= 10 && it.second >= 10 && it.first < 470 && it.second < 260 } shouldBe true
    }

    @Test fun a_flat_image_has_none() {
        shiTomasi(GreyImage(100, 100, FloatArray(10_000) { 50f })) shouldHaveSize 0
    }

    @Test fun a_single_square_gives_its_four_corners_first() {
        val px = FloatArray(100 * 100)
        for (y in 40 until 60) for (x in 40 until 60) px[y * 100 + x] = 200f
        val c = shiTomasi(GreyImage(100, 100, px), maxCorners = 4)
        c.size shouldBeGreaterThanOrEqual 4
        // Each found corner sits within 2 px of one of the square's corners.
        val truth = listOf(39.5 to 39.5, 59.5 to 39.5, 39.5 to 59.5, 59.5 to 59.5)
        c.all { p -> truth.any { hypot(p.first - it.first, p.second - it.second) <= 2.0 } } shouldBe true
    }
}
