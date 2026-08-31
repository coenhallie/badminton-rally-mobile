package com.badmintontracker.analysis.shots

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShotDetectionTest {

    private fun frames(points: List<Pair<Double, Double>?>, fps: Double = 30.0) =
        points.mapIndexed { i, p ->
            FrameSample(
                frame = i,
                timestamp = i / fps,
                shuttle = p?.let { ShuttleSample(it.first, it.second, visible = true) },
            )
        }

    /** A shuttle travelling right, reversing, travelling left. One reversal. */
    private fun oneReversal(): List<FrameSample> {
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(30) { pts.add(100.0 + it * 40.0 to 300.0) }
        repeat(30) { pts.add(1300.0 - it * 40.0 to 300.0) }
        return frames(pts)
    }

    /**
     * A zigzag reversing every `period` frames, matching the generator used
     * to cross-check this port against shot_detection.py.
     */
    private fun zigzag(n: Int, period: Int, amp: Double = 40.0): List<Pair<Double, Double>?> {
        val pts = ArrayList<Pair<Double, Double>?>()
        var x = 100.0
        var d = 1.0
        for (i in 0 until n) {
            if (i != 0 && i % period == 0) d = -d
            x += d * amp
            pts.add(x to 300.0)
        }
        return pts
    }

    @Test
    fun a_direction_reversal_is_a_shot() {
        val shots = detectShuttleShots(oneReversal(), fps = 30.0)
        // Frame 27, not 29 where the reversal actually is: stride subsampling
        // takes every 9th frame, so the turn is seen at the sample before it.
        // shot_detection.py returns frame 27 for this input too.
        shots.map { it.frame } shouldBe listOf(27)
    }

    @Test
    fun a_stationary_shuttle_produces_no_shots() {
        // Jitter below the minimum speed must not register. Without this gate
        // a shuttle sitting on the floor generates a shot every few frames.
        val pts = (0 until 60).map { 800.0 + (it % 2) * 2.0 to 500.0 }
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }

    @Test
    fun reversals_closer_than_the_minimum_gap_are_suppressed() {
        // minShotGapFrames = max(3, fps * 0.6) = 18 at 30fps, while stride
        // subsampling places samples 9 frames apart. So every sampled triple
        // here is a reversal and exactly every other one is gated out.
        //
        // Both expectations were produced by running shot_detection.py on
        // this identical track, which is the only way to know the numbers are
        // the source's and not just this implementation's.
        val pts = zigzag(90, period = 9)
        detectShuttleShots(frames(pts), fps = 30.0).map { it.frame } shouldBe
            listOf(9, 27, 45, 63)
        detectShuttleShots(frames(pts), fps = 30.0, minShotGapSec = 0.0).map { it.frame } shouldBe
            listOf(9, 18, 27, 36, 45, 54, 63, 72)
    }

    @Test
    fun a_single_frame_glitch_is_rejected_as_an_outlier() {
        // One TrackNet frame lands 800px away and comes back. Without outlier
        // rejection that fabricates two reversals out of nothing.
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(60) { pts.add(100.0 + it * 20.0 to 300.0) }
        pts[30] = 100.0 + 30 * 20.0 + 900.0 to 1000.0
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }

    @Test
    fun a_long_invisible_gap_does_not_build_velocity_across_it() {
        // Velocity computed across a multi-second gap is meaningless and would
        // read as a reversal at the far side of an inter-rally pause.
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(10) { pts.add(100.0 + it * 60.0 to 300.0) }
        repeat(120) { pts.add(null) }
        repeat(10) { pts.add(700.0 - it * 60.0 to 300.0) }
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }
}
