package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class CameraMotionTrackerTest {
    private val base = Synthetic.texture(480, 270, 11)

    private fun run(tracker: CameraMotionTracker, times: List<Double>, frameAt: (Double) -> GreyImage) {
        for (t in times) if (tracker.wants(t)) tracker.offer(t, frameAt(t))
    }

    private fun cornerError(expected: List<List<Double>>, got: List<List<Double>>): Double =
        listOf(0.0 to 0.0, 1920.0 to 0.0, 1920.0 to 1080.0, 0.0 to 1080.0).maxOf { (x, y) ->
            val a = expected.apply(x, y)!!
            val b = got.apply(x, y)!!
            hypot(a.x - b.x, a.y - b.y)
        }

    @Test fun a_pan_is_followed_at_ten_hertz_to_a_pixel_in_source_space() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        val times = (0 until 90).map { it / 30.0 }
        // Frames are the texture panned relative to the marking frame at t = 0.1.
        run(tracker, times) { t -> Synthetic.warp(base, multiplyPan(t)) }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.size shouldBe 30
        for (t in listOf(0.0, 0.5, 1.0, 2.9)) {
            cornerError(sourcePanRelative(t), motion.at(t)!!) shouldBeLessThan 2.0
        }
    }

    @Test fun the_marking_frame_is_the_identity_and_is_sampled_even_off_a_boundary() {
        val tracker = CameraMotionTracker(0.1234, 1920, 1080)
        val times = (0 until 12).map { it / 30.0 }
        run(tracker, times) { base }
        val motion = tracker.finish().shouldNotBeNull()
        motion.referenceTimestamp shouldBe (4 / 30.0)
        motion.at(4 / 30.0) shouldBe listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    }

    @Test fun frames_before_a_late_marking_time_are_kept() {
        val tracker = CameraMotionTracker(1.0, 1920, 1080)
        val times = (0 until 60).map { it / 30.0 }
        run(tracker, times) { t -> Synthetic.warp(base, multiplyPanFrom(t, 1.0)) }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.first().timestamp shouldBe 0.0
        cornerError(sourcePanRelativeFrom(0.0, 1.0), motion.at(0.0)!!) shouldBeLessThan 2.0
    }

    @Test fun frames_before_a_marking_time_ten_seconds_in_keep_only_the_last_five_seconds() {
        // Each pending frame is a 480x270 float image, 518 KB; ten seconds at
        // 10 Hz would hold 100 of them. The cap keeps the 50 nearest the
        // marking frame and drops the oldest, which then read as no camera.
        val tracker = CameraMotionTracker(10.0, 1920, 1080)
        val times = (0..100).map { it / 10.0 }
        run(tracker, times) { base }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.first().timestamp shouldBe 5.0
        motion.samples.size shouldBe CameraMotionTracker.MAX_PENDING_SAMPLES + 1
        motion.at(4.8).shouldBeNull()
        motion.at(5.0).shouldNotBeNull()
    }

    @Test fun variable_frame_rate_samples_by_time_not_by_count() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        // Irregular spacing, with a duplicate timestamp as the cloud produces.
        val times = listOf(0.0, 0.0, 0.03, 0.07, 0.1, 0.13, 0.19, 0.21, 0.25, 0.33, 0.41, 0.52)
        run(tracker, times) { base }
        tracker.finish().shouldNotBeNull().samples.map { it.timestamp } shouldBe
            listOf(0.0, 0.1, 0.21, 0.33, 0.41, 0.52)
    }

    @Test fun a_textureless_marking_frame_gives_up() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        run(tracker, listOf(0.0, 0.1, 0.2)) { GreyImage(480, 270, FloatArray(480 * 270) { 40f }) }
        tracker.finish().shouldBeNull()
        tracker.wants(0.3) shouldBe false
    }

    @Test fun a_video_that_ends_before_the_marking_time_has_no_camera() {
        val tracker = CameraMotionTracker(1.0, 1920, 1080)
        run(tracker, listOf(0.0, 0.1, 0.2)) { base }
        tracker.finish().shouldBeNull()
    }

    @Test fun a_frame_from_another_scene_is_dropped_not_accepted() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        val other = Synthetic.texture(480, 270, 99)
        run(tracker, listOf(0.1, 0.2, 0.3)) { t -> if (t == 0.2) other else base }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.map { it.timestamp } shouldBe listOf(0.1, 0.3)
        tracker.droppedSamples shouldBe 1
    }

    @Test fun a_portrait_source_uses_a_portrait_tracker() {
        val tracker = CameraMotionTracker(0.1, 1080, 1920)
        tracker.trackerWidth shouldBe 270
        tracker.trackerHeight shouldBe 480
    }

    // The marking frame is at t = 0.1, so a frame at t shows the pan from 0.1 to t.
    private fun multiplyPan(t: Double) = panRelative(t, 0.1)
    private fun multiplyPanFrom(t: Double, ref: Double) = panRelative(t, ref)
    private fun panRelative(t: Double, ref: Double) =
        listOf(listOf(1.0, 0.0, 24.0 * (t - ref)), listOf(0.0, 1.0, -9.0 * (t - ref)), listOf(0.0, 0.0, 1.0))
    private fun sourcePanRelative(t: Double) = sourcePanRelativeFrom(t, 0.1)
    private fun sourcePanRelativeFrom(t: Double, ref: Double) =
        listOf(listOf(1.0, 0.0, 96.0 * (t - ref)), listOf(0.0, 1.0, -36.0 * (t - ref)), listOf(0.0, 0.0, 1.0))
}
