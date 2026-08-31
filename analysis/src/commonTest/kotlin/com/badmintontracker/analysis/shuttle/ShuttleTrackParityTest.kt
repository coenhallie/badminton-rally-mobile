package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Golden output captured by running the worker's own
 * `_build_shuttle_positions_dict` - the real function, sliced out of
 * modal_supabase_processor.py and executed against OpenCV.
 *
 * This is the one algorithm in the module whose reference implementation
 * needs cv2, so it is the one where "reads correct" was doing the most work
 * and evidence the least. It matters more than the others, not less: both
 * places this port deviates from the plan's draft are here, and both were
 * argued from the source rather than measured.
 *
 * Measured now. Against a 40-seed, 12,004-position sweep that this is a
 * trimmed copy of, dropping the ROI's whole-pixel truncation diverges from
 * the worker on 10 of 40 tracks, and dropping the thresholds' truncation on
 * 14 of 40. The draft code had neither.
 *
 * The tracks mix steady flight, dwell periods and teleports out of the court
 * so all three rejection paths fire: 1,477 invisible inputs, 5,395 rejected
 * by the ROI, the rest by static clustering.
 */
class ShuttleTrackParityTest {

    private class Lcg(var s: Long) {
        fun next(): Long { s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL; return s }
        fun d(): Double = next().toDouble() / 2147483648.0
    }

    private val corners = listOf(
        Point(200.0, 200.0), Point(1700.0, 200.0),
        Point(1700.0, 900.0), Point(200.0, 900.0),
    )

    private fun track(seed: Int): Pair<Map<Int, ShuttleSample>, Double> {
        val r = Lcg(seed.toLong())
        val n = 60 + (r.next() % 40).toInt()
        val fps = listOf(24.0, 25.0, 30.0, 50.0, 59.94, 60.0)[(r.next() % 6).toInt()]
        var x = 300.0 + r.d() * 1300.0
        var y = 200.0 + r.d() * 700.0
        var vx = (r.d() - 0.5) * 60.0
        var vy = (r.d() - 0.5) * 60.0
        val positions = LinkedHashMap<Int, ShuttleSample>()
        for (i in 0 until n) {
            val mode = r.d()
            if (mode < 0.10) {
                // A dwell: the shuttle barely moves, which is what the
                // static-cluster branch exists to catch.
                x += (r.d() - 0.5) * 2.0
                y += (r.d() - 0.5) * 2.0
            } else if (mode < 0.16) {
                // A teleport, often outside the court, exercising the ROI.
                x = r.d() * 1920.0
                y = r.d() * 1080.0
            } else {
                vx += (r.d() - 0.5) * 10.0
                vy += (r.d() - 0.5) * 10.0
                x += vx
                y += vy
            }
            positions[i] = ShuttleSample(x, y, r.d() > 0.12)
        }
        return positions to fps
    }

    /** The golden lists are comma-separated so long runs stay readable. */
    private fun String.toFrames(): List<Int> = split(",").map { it.toInt() }

    private val expected = mapOf(
        1 to
            "0,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,19,20,21,22,23,24,25,26,27," +
            "28,29,30,31,32,33,34,52,53,54,55,56,57,59,60,61,62,63,64,65,68,69," +
            "71,72,86,87,89",
        2 to
            "0,1,2,4,5,6,7,8,9,10,12,13,15,16,17,18,19,20,21,23,24,26,27,28,29," +
            "30,31,32,33,34,35,36,38,39,41,52,55,56,57,58,59,60,61,62,63,64,66," +
            "68,69,70,72,73,76,77,78,80,82,83,84,86",
        3 to
            "0,1,6,7,8,9,10,12,18,25,26,27,28,29,30,31,32,33,35,36,37,38,40,71," +
            "72,73,74,75,76,77,79,83,86,87,88,89,91",
        4 to
            "0,1,3,4,5,6,11,12,13,15,16,17,18,19,21,22,23,32,33,35,36,42,44,45," +
            "46,47,48,49,50,51,52,53,54,55,57,58,59,60,67,68,87",
        5 to
            "0,1,12,16,18,19,20,21,22,23,24,25,26,27,28,31,32,34,35,36,37,38,39" +
            ",40,41,42,43,44,45,46,47,48,49,50,51,53,62,63,64,65",
        6 to
            "0,1,2,3,4,5,7,8,11,12,15,16,18,30,32,34,35,36,37,38,40,41,44,45,46" +
            ",47,48,49,52,53,55,56,58,60,62,63,64,65,66,67,68,69,70,71,72,73,75" +
            ",76,77,78,79,80,81,82,84,86,88,89,90",
        7 to
            "0,1,3,4,5,6,32,33,34,36,37,38,39,40,41,42,43,44,45,46,47,49,50,51," +
            "53,54,55,56,65,84,86",
        8 to
            "0,2,3,6,8,11,12,13,14,16,17,21,22,23,26,27,28,29,31,33,34,36,37,38" +
            ",39,40,44,45,49,51,52,53,54,55,56,57,59,69,70,72,73,74,75,76,82,84" +
            ",86,87,88,89,90,91",
    )

    @Test
    fun surviving_frames_match_the_worker_filter() {
        expected.forEach { (seed, frames) ->
            val (positions, fps) = track(seed)
            val out = buildFilteredTrack(positions, fps, 1920, 1080, corners)
            out.keys.sorted().filter { out.getValue(it).visible } shouldBe frames.toFrames()
        }
    }
}
