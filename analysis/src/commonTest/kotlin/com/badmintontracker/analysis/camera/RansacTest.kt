package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test

class RansacTest {
    private val truth = listOf(listOf(1.01, 0.02, 12.0), listOf(-0.015, 0.99, -7.0), listOf(1e-5, 2e-5, 1.0))

    private fun pairs(outlierShare: Double): Pair<List<Point>, List<Point>> {
        val r = Random(3)
        val src = List(150) { Point(r.nextDouble() * 480, r.nextDouble() * 270) }
        val dst = src.mapIndexed { i, p ->
            val q = truth.apply(p.x, p.y)!!
            if (i < outlierShare * src.size) Point(q.x + 20 + r.nextDouble() * 60, q.y - 30 + r.nextDouble() * 60)
            else Point(q.x + (r.nextDouble() - 0.5) * 0.4, q.y + (r.nextDouble() - 0.5) * 0.4)
        }
        return src to dst
    }

    @Test fun thirty_percent_outliers_do_not_move_the_fit() {
        val (src, dst) = pairs(0.3)
        val fit = ransacHomography(src, dst)!!
        (fit.inliers >= 100) shouldBe true
        for (p in listOf(Point(0.0, 0.0), Point(480.0, 0.0), Point(480.0, 270.0), Point(0.0, 270.0))) {
            val a = truth.apply(p.x, p.y)!!
            val b = fit.homography.apply(p.x, p.y)!!
            hypot(a.x - b.x, a.y - b.y) shouldBeLessThan 0.5
        }
    }

    @Test fun the_same_input_gives_the_same_answer() {
        val (src, dst) = pairs(0.3)
        ransacHomography(src, dst)!!.homography shouldBe ransacHomography(src, dst)!!.homography
    }

    @Test fun fewer_than_four_pairs_is_no_fit() {
        ransacHomography(listOf(Point(0.0, 0.0)), listOf(Point(1.0, 1.0))).shouldBeNull()
    }

    @Test fun the_fit_is_normalised() {
        val (src, dst) = pairs(0.0)
        ransacHomography(src, dst)!!.homography[2][2] shouldBe 1.0
    }
}
