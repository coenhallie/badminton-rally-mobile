package com.badmintontracker.analysis.shots

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Golden output over synthetic tracks, captured by running
 * badminton-tracker's shot_detection.py on the identical input.
 *
 * The hand-written cases next door each probe one gate. This probes the
 * whole thing at once on tracks that look like real ones - mixed frame
 * rates, dropped frames, drifting velocity - which is where a port diverges
 * without any single gate being wrong. The full sweep this was cut from ran
 * 200 seeds and agreed with the Python on all 386 shots.
 *
 * The generator is a plain 32-bit LCG so Python reproduces the exact same
 * track from the same seed, with no fixture files to keep in sync.
 */
class ShotDetectionParityTest {

    private class Lcg(var s: Long) {
        fun next(): Long { s = (s * 1103515245L + 12345L) and 0x7FFFFFFFL; return s }
        fun d(): Double = next().toDouble() / 2147483648.0
    }

    private fun track(seed: Int): Pair<List<FrameSample>, Double> {
        val r = Lcg(seed.toLong())
        val n = 40 + (r.next() % 120).toInt()
        val fps = listOf(24.0, 25.0, 30.0, 50.0, 59.94, 60.0)[(r.next() % 6).toInt()]
        var x = 100.0 + r.d() * 1000.0
        var y = 100.0 + r.d() * 600.0
        var vx = (r.d() - 0.5) * 120.0
        var vy = (r.d() - 0.5) * 120.0
        val out = ArrayList<FrameSample>(n)
        for (i in 0 until n) {
            if (r.d() < 0.08) { vx = -vx; vy = -vy }
            vx += (r.d() - 0.5) * 20.0
            vy += (r.d() - 0.5) * 20.0
            x += vx; y += vy
            val visible = r.d() > 0.15
            out.add(FrameSample(i, i / fps, if (visible) ShuttleSample(x, y, true) else null))
        }
        return out to fps
    }

    private val expected = mapOf(
        1 to listOf<Int>(16, 46),
        2 to listOf<Int>(),
        3 to listOf<Int>(),
        4 to listOf<Int>(18),
        5 to listOf<Int>(46, 77),
        6 to listOf<Int>(36),
        7 to listOf<Int>(32),
        8 to listOf<Int>(54),
        9 to listOf<Int>(16, 91, 121),
        10 to listOf<Int>(37),
        11 to listOf<Int>(15, 46),
        12 to listOf<Int>(54),
        13 to listOf<Int>(15, 45),
        14 to listOf<Int>(18, 90),
        15 to listOf<Int>(),
        16 to listOf<Int>(37),
        17 to listOf<Int>(16, 61, 91),
        18 to listOf<Int>(),
        19 to listOf<Int>(15),
        20 to listOf<Int>(27, 54, 92),
        21 to listOf<Int>(15),
        22 to listOf<Int>(18, 37),
        23 to listOf<Int>(31, 106),
        24 to listOf<Int>(),
    )

    @Test
    fun shot_frames_match_the_python_detector_on_synthetic_tracks() {
        expected.forEach { (seed, frames) ->
            val (samples, fps) = track(seed)
            detectShuttleShots(samples, fps).map { it.frame } shouldBe frames
        }
    }
}
