package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShuttleTrackTest {

    private val corners = listOf(
        Point(200.0, 200.0), Point(1700.0, 200.0),
        Point(1700.0, 900.0), Point(200.0, 900.0),
    )

    private fun moving(count: Int, step: Double = 60.0) =
        (0 until count).associateWith {
            ShuttleSample(400.0 + it * step, 400.0 + it * step * 0.3, visible = true)
        }

    @Test
    fun a_moving_shuttle_inside_the_court_survives() {
        val out = buildFilteredTrack(moving(10), 30.0, 1920, 1080, corners)
        out.values.count { it.visible } shouldBe 10
    }

    @Test
    fun a_position_outside_the_court_roi_is_dropped() {
        val raw = mapOf(0 to ShuttleSample(50.0, 50.0, visible = true))
        buildFilteredTrack(raw, 30.0, 1920, 1080, corners)[0]!!.visible shouldBe false
    }

    @Test
    fun a_stationary_false_positive_is_suppressed_after_confirmation() {
        // A logo or a shuttle on the floor sits still. The filter needs three
        // observations before it trusts a cluster, so the first few survive and
        // everything after is dropped. That trailing suppression is the point.
        val raw = (0 until 30).associateWith { ShuttleSample(800.0, 500.0, visible = true) }
        val out = buildFilteredTrack(raw, 30.0, 1920, 1080, corners)
        out.values.count { it.visible } shouldBe 1
    }

    @Test
    fun an_invisible_input_stays_invisible() {
        val raw = mapOf(0 to ShuttleSample(0.0, 0.0, visible = false))
        buildFilteredTrack(raw, 30.0, 1920, 1080, corners)[0]!!.visible shouldBe false
    }

    @Test
    fun with_no_court_corners_the_roi_filter_is_skipped() {
        val raw = mapOf(0 to ShuttleSample(50.0, 50.0, visible = true))
        buildFilteredTrack(raw, 30.0, 1920, 1080, null)[0]!!.visible shouldBe true
    }

    @Test
    fun thresholds_scale_with_frame_rate() {
        // At 60fps the shuttle moves half as far between frames, so a fixed
        // pixel threshold would classify real movement as static. The 30/fps
        // scale factor is what keeps behaviour equivalent across frame rates.
        val slow = moving(10, step = 6.0)
        val at30 = buildFilteredTrack(slow, 30.0, 1920, 1080, corners).values.count { it.visible }
        val at60 = buildFilteredTrack(slow, 60.0, 1920, 1080, corners).values.count { it.visible }
        (at60 > at30) shouldBe true
    }

    @Test
    fun the_movement_threshold_is_truncated_to_whole_pixels() {
        // The worker computes int(0.007 * longEdge * fpsScale), so at
        // 1920x1080 and 60fps the min-move threshold is 6, not 6.72. This
        // track crosses 6.002 pixels a frame, which lands in that band: real
        // movement under the worker's threshold, a static false positive
        // under an unrounded one. Dropping the truncation takes this from 40
        // visible frames to 11, so nothing else in the suite would notice.
        val track = (0 until 40).associateWith {
            ShuttleSample(400.0 + it * 6.002, 400.0, visible = true)
        }
        buildFilteredTrack(track, 60.0, 1920, 1080, corners)
            .values.count { it.visible } shouldBe 40
    }
}
