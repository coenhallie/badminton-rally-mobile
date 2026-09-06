package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GradientRallyDetectorTest {

    private val fps = 30.0

    private fun oscillating(startFrame: Int, swings: Int, framesPerSwing: Int = 24):
        Map<Int, ShuttleSample> {
        val out = LinkedHashMap<Int, ShuttleSample>()
        var f = startFrame
        for (s in 0 until swings) {
            val forward = s % 2 == 0
            for (i in 0 until framesPerSwing) {
                val x = if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0
                out[f] = ShuttleSample(x, 400.0, visible = true)
                f++
            }
        }
        return out
    }

    @Test
    fun an_oscillating_shuttle_produces_one_rally() {
        // Bounds, not just the count. rally_detection.py returns exactly one
        // rally over frames 27 to 132 on this input.
        val r = detectRalliesGradient(oscillating(0, 6), fps, totalFrames = 200)
        r.map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe listOf(Triple(1, 27, 132))
    }

    @Test
    fun the_end_frame_carries_a_landing_buffer() {
        // The detected window ends at the last racket contact, so the shuttle
        // is still airborne. The cloud adds half a second for it to land:
        // max(1, int(0.5 * fps)) = 15 frames at 30fps.
        val r = detectRalliesGradient(oscillating(0, 6), fps, totalFrames = 400)
        r[0].endFrame shouldBe 117 + 15
    }

    @Test
    fun an_empty_track_yields_nothing() {
        detectRalliesGradient(emptyMap(), fps, totalFrames = 100) shouldBe emptyList()
    }

    @Test
    fun a_non_positive_frame_rate_yields_nothing() {
        // The guard that made an unreadable frame rate silently produce zero
        // rallies in the cloud. Normalising fps upstream is what prevents it.
        detectRalliesGradient(oscillating(0, 6), 0.0, totalFrames = 200) shouldBe emptyList()
    }

    @Test
    fun two_separated_exchanges_split_into_two_rallies() {
        val combined = oscillating(0, 6) + oscillating(300, 6)
        detectRalliesGradient(combined, fps, totalFrames = 600)
            .map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe
            listOf(Triple(1, 27, 132), Triple(2, 300, 432))
    }
}
