package com.badmintontracker.analysis.camera

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The benchmark's frames, a few rounds, on every platform: the Kotlin/Native
 * build follows the same pan-and-roll run the JVM times in
 * `CameraTrackerBenchmarkTest` (jvmTest), sample after sample, with no clock.
 */
class CameraTrackerWarpedRunTest {

    @Test fun every_sample_of_a_panning_rolling_run_is_followed() {
        val base = Synthetic.texture(480, 270, 11)
        val frames = Synthetic.panAndRoll(base, FRAMES)
        val tracker = CameraMotionTracker(0.0, 1920, 1080)
        tracker.offer(0.0, base)
        for (round in 0 until ROUNDS) tracker.offer(0.1 * (round + 1), frames[round % FRAMES])

        tracker.acceptedSamples shouldBe 1 + ROUNDS
        tracker.droppedSamples shouldBe 0
    }

    private companion object {
        const val FRAMES = 12
        const val ROUNDS = 24
    }
}
