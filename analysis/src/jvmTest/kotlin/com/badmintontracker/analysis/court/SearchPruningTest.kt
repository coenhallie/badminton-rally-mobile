package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The search's shortcuts change nothing it returns (design §9, Performance):
 * with pruning off, [searchCourt] and [searchCourtNear] score every placement
 * in full, and must give the same homography and score to the last bit.
 *
 * Pruning here is the coarse and fine scores stopping once they cannot reach
 * the cutoff, the corner-guided search's per-quad origin check, and its
 * corner-by-corner check before the gates. JVM only: the unpruned search is
 * several times slower, and the arithmetic is the same common code on iOS.
 */
class SearchPruningTest {
    private val cameras = listOf(
        SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = 1.5, height = 3.0, back = 4.0, w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = -2.0, height = 6.0, back = 8.0, w = 1280, h = 720, f = 870.0),
    )
    private val colours = listOf(
        0xFF3C8C46.toInt() to 0xFFEBEBEB.toInt(),
        0xFFC86E46.toInt() to 0xFFE6C828.toInt(),
    )
    private val scale = 960.0 / 1280.0

    private fun work(cam: DoubleArray, colour: Pair<Int, Int>): GreyImage {
        val img = SyntheticCourt.render(cam, 1280, 720, floor = colour.first, line = colour.second)
        val (w, h) = longSideSize(1280, 720, 960)
        return areaDownsample(img.luma(), w, h)
    }

    private fun toWork(t: Point) = Point((t.x + 0.5) * scale - 0.5, (t.y + 0.5) * scale - 0.5)

    private fun bits(s: SearchResult?): List<Long>? =
        s?.let { r -> r.h.map { it.toRawBits() } + r.score.toRawBits() + r.lines.toLong() + r.quads.toLong() }

    @Test fun the_search_returns_the_same_bits_with_pruning_off() {
        var found = 0
        for ((c, cam) in cameras.withIndex()) for ((j, colour) in colours.withIndex()) {
            val work = work(cam, colour)
            val ridge = ridges(work)
            val pruned = searchCourt(work, ridge)
            val full = searchCourt(work, ridge, pruning = false)
            println("scene $c/$j: score ${pruned?.score} (full ${full?.score}), quads ${pruned?.quads}")
            bits(pruned) shouldBe bits(full)
            if (pruned != null) found++
        }
        found shouldBe cameras.size * colours.size
    }

    @Test fun the_corner_guided_search_returns_the_same_bits_with_pruning_off() {
        val rnd = kotlin.random.Random(5)
        var found = 0
        for ((c, cam) in cameras.withIndex()) {
            val work = work(cam, colours[0])
            val ridge = ridges(work)
            val corners = SyntheticCourt.truth(cam).take(4).map(::toWork)
            // Rough taps, the same taps started from the opposite corner (any
            // of the rectangle's eight corner orders matches), then four taps
            // nowhere near the court.
            val rough = corners.map { Point(it.x + rnd.nextDouble(-12.0, 12.0), it.y + rnd.nextDouble(-12.0, 12.0)) }
            val tapSets = listOf(
                rough,
                listOf(rough[2], rough[3], rough[0], rough[1]),
                listOf(Point(10.0, 10.0), Point(60.0, 10.0), Point(60.0, 40.0), Point(10.0, 40.0)),
            )
            for ((t, taps) in tapSets.withIndex()) {
                val pruned = searchCourtNear(work, taps, radius = 0.05 * 960, ridge = ridge)
                val full = searchCourtNear(work, taps, radius = 0.05 * 960, ridge = ridge, pruning = false)
                println("camera $c taps $t: score ${pruned?.score} (full ${full?.score})")
                bits(pruned) shouldBe bits(full)
                if (pruned != null) found++
            }
        }
        // Every camera's rough taps are near enough in both orders; the far taps never are.
        found shouldBe cameras.size * 2
    }
}
