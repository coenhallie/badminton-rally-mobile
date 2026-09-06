package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The fusion track's selection rules, each isolated.
 *
 * Unlike the other ports in this module there is no oracle to run: the logic
 * lives inside a 200-line async worker function that cannot be imported, and
 * neither its inputs (raw TrackNet positions, YOLO detections) nor its
 * intermediate state are persisted anywhere. So these are derived from
 * modal_supabase_processor.py:2296-2360 by reading, and each names the line it
 * comes from.
 */
class FusionTrackTest {

    private val corners = listOf(
        Point(200.0, 200.0), Point(1700.0, 200.0),
        Point(1700.0, 900.0), Point(200.0, 900.0),
    )

    private fun build(
        trackNet: Map<Int, ShuttleSample> = emptyMap(),
        detections: Map<Int, List<ShuttleDetection>> = emptyMap(),
        frames: Int = 4,
        fps: Double = 30.0,
    ) = buildFusionTrack(trackNet, detections, fps, 1920, 1080, corners, frames)

    @Test
    fun tracknet_is_preferred_over_yolo_for_the_same_frame() {
        // ":2296 Pick best shuttle position: TrackNet preferred, YOLO fallback."
        val out = build(
            trackNet = mapOf(0 to ShuttleSample(900.0, 500.0, true)),
            detections = mapOf(0 to listOf(ShuttleDetection(300.0, 300.0, 0.99))),
        )
        out.getValue(0) shouldBe ShuttleSample(900.0, 500.0, visible = true)
    }

    @Test
    fun yolo_fills_in_where_tracknet_saw_nothing() {
        val out = build(
            trackNet = mapOf(0 to ShuttleSample.INVISIBLE),
            detections = mapOf(0 to listOf(ShuttleDetection(900.0, 500.0, 0.8))),
        )
        out.getValue(0) shouldBe ShuttleSample(900.0, 500.0, visible = true)
    }

    @Test
    fun the_most_confident_detection_wins() {
        // ":2327 sorted(..., key=confidence, reverse=True)"
        val out = build(
            detections = mapOf(
                0 to listOf(
                    ShuttleDetection(400.0, 400.0, 0.30),
                    ShuttleDetection(900.0, 500.0, 0.91),
                    ShuttleDetection(600.0, 600.0, 0.70),
                )
            ),
        )
        out.getValue(0) shouldBe ShuttleSample(900.0, 500.0, visible = true)
    }

    @Test
    fun a_position_outside_the_permissive_roi_is_rejected() {
        // The shuttle ROI is far wider than the filtered track's: 40% wider
        // horizontally, and open to y = 0 above the court. So a position high
        // above the net is KEPT here where the filtered track drops it.
        val high = build(trackNet = mapOf(0 to ShuttleSample(900.0, 5.0, true)))
        high.getValue(0).visible shouldBe true

        val outside = build(trackNet = mapOf(0 to ShuttleSample(1900.0, 1070.0, true)))
        outside.getValue(0).visible shouldBe false
    }

    @Test
    fun a_position_that_barely_moved_is_rejected() {
        // ":2319 if movement < SHUTTLE_MIN_MOVE". At 1920x1080 and 30fps the
        // threshold is int(0.007 * 1920) = 13, so a 5px step is rejected and
        // the frame carries no shuttle.
        val out = build(
            trackNet = mapOf(
                0 to ShuttleSample(900.0, 500.0, true),
                1 to ShuttleSample(905.0, 500.0, true),
                2 to ShuttleSample(960.0, 500.0, true),
            ),
            frames = 3,
        )
        out.getValue(0).visible shouldBe true
        out.getValue(1).visible shouldBe false
        out.getValue(2).visible shouldBe true
    }

    @Test
    fun movement_is_measured_from_the_last_accepted_position() {
        // Not from the last frame. Rejected positions do not update prev
        // (":2322 shuttle_position = ..." only on the accepted branch), so a
        // slow drift accumulates until it clears the threshold rather than
        // being rejected forever.
        val out = build(
            trackNet = (0..3).associateWith { ShuttleSample(900.0 + it * 5.0, 500.0, true) },
            frames = 4,
        )
        out.getValue(0).visible shouldBe true
        out.getValue(1).visible shouldBe false
        out.getValue(2).visible shouldBe false
        // 15px from frame 0's accepted position, clearing the 13px threshold.
        out.getValue(3).visible shouldBe true
    }

    @Test
    fun every_frame_gets_an_entry() {
        // The rally detectors index by position, so a sparse map would shift
        // every frame index after the first gap.
        val out = build(trackNet = mapOf(2 to ShuttleSample(900.0, 500.0, true)), frames = 5)
        out.keys shouldBe (0 until 5).toSet()
    }

    @Test
    fun without_court_corners_the_roi_is_skipped() {
        buildFusionTrack(
            trackNet = mapOf(0 to ShuttleSample(10.0, 10.0, true)),
            detections = emptyMap(), fps = 30.0, videoWidth = 1920, videoHeight = 1080,
            courtCorners = null, totalFrames = 1,
        ).getValue(0).visible shouldBe true
    }
}
