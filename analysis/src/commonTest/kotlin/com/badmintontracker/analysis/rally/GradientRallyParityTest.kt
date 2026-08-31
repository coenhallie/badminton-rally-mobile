package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Golden output captured by running badminton-tracker's rally_detection.py
 * on the identical synthetic tracks.
 *
 * The cases next door probe one rule each. This one runs the whole detector
 * over tracks with the messiness of real input - mixed frame rates, dropped
 * frames, drifting velocity, several exchanges - which is where stride
 * subsampling and the grouping loop interact and where a port drifts without
 * any single rule being wrong. The sweep this was cut from ran 200 seeds and
 * matched the Python on all 230 rallies.
 *
 * The generator is a plain 32-bit LCG so Python reproduces the same track
 * from the same seed, with no fixture files to keep in sync.
 */
class GradientRallyParityTest {

    private class Lcg(var s: Long) {
        fun next(): Long { s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL; return s }
        fun d(): Double = next().toDouble() / 2147483648.0
    }

    private fun track(seed: Int): Triple<Map<Int, ShuttleSample>, Double, Int> {
        val r = Lcg(seed.toLong())
        val total = 200 + (r.next() % 400).toInt()
        val fps = listOf(24.0, 25.0, 30.0, 50.0, 59.94, 60.0)[(r.next() % 6).toInt()]
        var x = 100.0 + r.d() * 1000.0
        var y = 100.0 + r.d() * 600.0
        var vx = (r.d() - 0.5) * 120.0
        var vy = (r.d() - 0.5) * 120.0
        val pos = LinkedHashMap<Int, ShuttleSample>()
        for (i in 0 until total) {
            if (r.d() < 0.06) { vx = -vx; vy = -vy }
            vx += (r.d() - 0.5) * 20.0
            vy += (r.d() - 0.5) * 20.0
            x += vx; y += vy
            pos[i] = ShuttleSample(x, y, r.d() > 0.2)
        }
        return Triple(pos, fps, total)
    }

    private val expected = mapOf(
        1 to listOf(Triple(1, 16, 592)),
        2 to listOf(Triple(1, 17, 150), Triple(2, 327, 596)),
        3 to listOf(Triple(1, 15, 238)),
        4 to listOf(Triple(1, 17, 222)),
        5 to listOf(Triple(1, 62, 269)),
        6 to listOf(Triple(1, 35, 237)),
        7 to listOf(Triple(1, 17, 298)),
        8 to listOf(Triple(1, 86, 254)),
        9 to listOf(Triple(1, 16, 342)),
        10 to listOf(Triple(1, 51, 323)),
        11 to listOf(Triple(1, 15, 379)),
        12 to listOf(Triple(1, 18, 339)),
        13 to listOf(Triple(1, 15, 449)),
        14 to listOf(Triple(1, 52, 373)),
        15 to listOf(Triple(1, 15, 283), Triple(2, 424, 480)),
        16 to listOf(Triple(1, 35, 478)),
    )

    @Test
    fun rally_bounds_match_the_python_detector_on_synthetic_tracks() {
        expected.forEach { (seed, rallies) ->
            val (pos, fps, total) = track(seed)
            detectRalliesGradient(pos, fps, total)
                .map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe rallies
        }
    }
}
