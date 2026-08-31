package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShotGapRallyDetectorTest {

    private val fps = 30.0

    /** Build frames where the shuttle oscillates, producing a shot per swing. */
    private fun rallyFrames(startFrame: Int, swings: Int, framesPerSwing: Int = 24):
        List<FrameSample> {
        val out = ArrayList<FrameSample>()
        var f = startFrame
        for (s in 0 until swings) {
            val forward = s % 2 == 0
            for (i in 0 until framesPerSwing) {
                val x = if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0
                out.add(FrameSample(f, f / fps, ShuttleSample(x, 400.0, visible = true)))
                f++
            }
        }
        return out
    }

    private fun idleFrames(startFrame: Int, count: Int): List<FrameSample> =
        (0 until count).map { FrameSample(startFrame + it, (startFrame + it) / fps, null) }

    @Test
    fun a_single_exchange_is_one_rally() {
        val r = detectRalliesFromShots(rallyFrames(0, swings = 6), fps)
        // Bounds, not just the count: rally_detection_shot_gap.py returns
        // exactly one rally spanning frames 27 to 117 on this input.
        r.map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe
            listOf(Triple(1, 27, 117))
    }

    @Test
    fun a_gap_longer_than_the_threshold_splits_two_rallies() {
        // RALLY_GAP_SECONDS is 3.1s; 120 idle frames at 30fps is 4s.
        val frames = rallyFrames(0, 6) + idleFrames(144, 120) + rallyFrames(264, 6)
        detectRalliesFromShots(frames, fps).map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe
            listOf(Triple(1, 27, 117), Triple(2, 291, 381))
    }

    @Test
    fun a_trailing_isolated_shot_does_not_weld_dead_air_onto_the_last_rally() {
        // The defect this guards: when the final shot is both last AND beyond
        // the gap threshold, including it stretched the previous rally's end
        // across the whole inter-rally pause.
        val frames = rallyFrames(0, 6) + idleFrames(144, 150) + rallyFrames(294, 2)
        val r = detectRalliesFromShots(frames, fps)
        // The rally must end at frame 117, where the exchange actually stops,
        // not be stretched to the isolated trailing shot 170-odd frames later.
        r.map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe
            listOf(Triple(1, 27, 117))
        (r[0].endTimestamp < 144 / fps + 0.5) shouldBe true
    }

    @Test
    fun a_rally_shorter_than_the_minimum_duration_is_rejected() {
        detectRalliesFromShots(rallyFrames(0, swings = 2, framesPerSwing = 6), fps) shouldBe
            emptyList()
    }

    @Test
    fun a_window_with_too_little_shuttle_data_is_rejected() {
        // Replay cuts and crowd shots produce shots from noise but carry almost
        // no shuttle. Below 25% visibility in the window the rally is dropped.
        val sparse = rallyFrames(0, 6).mapIndexed { i, f ->
            if (i % 5 == 0) f else f.copy(shuttle = null)
        }
        val padded = sparse + idleFrames(144, 400)
        detectRalliesFromShots(padded, fps) shouldBe emptyList()
    }

    @Test
    fun too_few_frames_yields_nothing() {
        detectRalliesFromShots(rallyFrames(0, 1).take(5), fps) shouldBe emptyList()
    }
}
