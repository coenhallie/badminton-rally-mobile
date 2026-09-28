package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What one tracked sample costs once the runtime is warm, stage by stage, on
 * the host JVM. It prints; it gates nothing on time, because a shared CI
 * host's timings are not a contract. It does check that every timed sample
 * was followed, so a speed-up that breaks the tracker cannot pass here
 * unnoticed.
 *
 * JVM only: its 180 rounds added about 13 s to the iOS suite, where the
 * debug Kotlin/Native test binary runs the tracker over ten times slower than
 * the JVM, to print a figure nobody reads there. The same frames, a few
 * rounds on every platform with no clock, are `CameraTrackerWarpedRunTest` in
 * commonTest.
 *
 * The frames are built before the clock starts: what is timed is the pyramid,
 * Lucas-Kanade and RANSAC, and a whole [CameraMotionTracker.offer], nothing
 * else. The first [WARM_UP] rounds are not timed, so the JVM's JIT has
 * compiled the loops before the clock starts.
 */
class CameraTrackerBenchmarkTest {

    @Test fun a_warm_sample_costs_this_much() {
        val base = Synthetic.texture(480, 270, 11)
        val frames = Synthetic.panAndRoll(base, FRAMES)
        val corners = shiTomasi(base)
        val templates = AnchoredTemplates(pyramid(base, PYRAMID_LEVELS), corners)
        val guesses = DoubleArray(corners.size * 2)
        corners.forEachIndexed { i, (x, y) -> guesses[2 * i] = x; guesses[2 * i + 1] = y }

        var pyramidTime = Duration.ZERO
        var lkTime = Duration.ZERO
        var ransacTime = Duration.ZERO
        for (round in 0 until WARM_UP + TIMED) {
            val frame = frames[round % FRAMES]
            val t0 = TimeSource.Monotonic.markNow()
            val target = pyramid(frame, PYRAMID_LEVELS)
            val t1 = TimeSource.Monotonic.markNow()
            val (found, ok) = templates.track(target, guesses)
            val t2 = TimeSource.Monotonic.markNow()
            val src = ArrayList<Point>()
            val dst = ArrayList<Point>()
            corners.forEachIndexed { i, (x, y) ->
                if (ok[i]) { src.add(Point(x, y)); dst.add(Point(found[2 * i], found[2 * i + 1])) }
            }
            val t3 = TimeSource.Monotonic.markNow()
            val fit = ransacHomography(src, dst)
            val t4 = TimeSource.Monotonic.markNow()
            (fit != null && fit.inliers >= CameraMotionTracker.MIN_INLIERS) shouldBe true
            if (round >= WARM_UP) {
                pyramidTime += t1 - t0
                lkTime += t2 - t1
                ransacTime += t4 - t3
            }
        }

        // The same frames through the public API, a sample every 0.1 s.
        val tracker = CameraMotionTracker(0.0, 1920, 1080)
        tracker.offer(0.0, base)
        var offerTime = Duration.ZERO
        for (round in 0 until WARM_UP + TIMED) {
            val frame = frames[round % FRAMES]
            val mark = TimeSource.Monotonic.markNow()
            tracker.offer(0.1 * (round + 1), frame)
            if (round >= WARM_UP) offerTime += mark.elapsedNow()
        }
        tracker.acceptedSamples shouldBe 1 + WARM_UP + TIMED

        fun ms(d: Duration) = (d.inWholeNanoseconds / 1_000.0 / TIMED).roundToLong() / 1_000.0
        println(
            "camera tracker, warm, per sample over $TIMED: offer ${ms(offerTime)} ms; " +
                "pyramid ${ms(pyramidTime)}, Lucas-Kanade ${ms(lkTime)}, RANSAC ${ms(ransacTime)} ms " +
                "(${corners.size} corners)"
        )
    }

    private companion object {
        const val FRAMES = 12
        const val WARM_UP = 60
        const val TIMED = 120
    }
}
