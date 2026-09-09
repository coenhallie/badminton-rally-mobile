package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CourtMarkingModelTest {

    @Test
    fun spec_matches_desktop_order_labels_colors() {
        CourtMarkingSpec.shortLabels shouldBe listOf(
            "TL", "TR", "BR", "BL", "NL", "NR", "SNL", "SNR", "SFL", "SFR", "CTN", "CTF",
        )
        CourtMarkingSpec.fullLabels.first() shouldBe "Top-Left Corner"
        CourtMarkingSpec.fullLabels.last() shouldBe "Center-Far"
        CourtMarkingSpec.colors shouldBe listOf(
            0xFFFF4444, 0xFF44FF44, 0xFF4444FF, 0xFFFFFF44,
            0xFFFF00FF, 0xFF00FFFF,
            0xFFFF8800, 0xFF88FF00,
            0xFF0088FF, 0xFFFF0088,
            0xFFFFFFFF, 0xFF888888,
        )
    }

    @Test
    fun place_maps_display_to_source_pixels_like_desktop_handleCanvasClick() {
        // video 1920x1080 shown at 960x540 (scale 2x): tap (100, 50) -> source (200, 100)
        val s = CourtMarkingState(1920, 1080)
            .place(displayX = 100f, displayY = 50f, displayWidth = 960f, displayHeight = 540f)
        s.points.single().x shouldBe (200f plusOrMinus 0.001f)
        s.points.single().y shouldBe (100f plusOrMinus 0.001f)
    }

    @Test
    fun place_ignores_taps_beyond_12_points() {
        var s = CourtMarkingState(100, 100)
        repeat(13) { s = s.place(1f, 1f, 100f, 100f) }
        s.points.size shouldBe 12
        s.isComplete shouldBe true
    }

    @Test
    fun undo_and_clear() {
        var s = CourtMarkingState(100, 100).place(1f, 1f, 100f, 100f).place(2f, 2f, 100f, 100f)
        s.nextIndex shouldBe 2
        s = s.undo()
        s.points.size shouldBe 1
        s.clear().points shouldBe emptyList()
        CourtMarkingState(100, 100).undo().points shouldBe emptyList()
    }

    @Test
    fun toCourtKeypoints_maps_points_in_desktop_order() {
        var s = CourtMarkingState(100, 100)
        repeat(12) { i -> s = s.place(i.toFloat(), (i * 2).toFloat(), 100f, 100f) }
        val kp = s.toCourtKeypoints()
        kp.topLeft shouldBe listOf(0f, 0f)
        kp.netLeft shouldBe listOf(4f, 8f)          // 5th point (index 4)
        kp.centerFar shouldBe listOf(11f, 22f)      // 12th point
    }

    @Test
    fun toCourtKeypoints_requires_completion() {
        shouldThrow<IllegalStateException> { CourtMarkingState(100, 100).toCourtKeypoints() }
    }

    @Test
    fun restored_round_trips_a_saved_marking_in_the_same_order() {
        var s = CourtMarkingState(1920, 1080)
        repeat(12) { i -> s = s.place(i.toFloat(), (i * 2).toFloat(), 1920f, 1080f) }

        val restored = CourtMarkingState.restored(1920, 1080, s.toCourtKeypoints())

        restored.points shouldBe s.points
        restored.isComplete shouldBe true
        restored.nextIndex shouldBe 12
        restored.toCourtKeypoints() shouldBe s.toCourtKeypoints()
    }

    @Test
    fun restored_without_saved_keypoints_is_an_empty_marking() {
        val restored = CourtMarkingState.restored(1920, 1080, null)
        restored.points shouldBe emptyList()
        restored.videoWidth shouldBe 1920
        restored.videoHeight shouldBe 1080
    }

    @Test
    fun restored_drops_a_malformed_marking_rather_than_placing_part_of_it() {
        // A pair short of its y: taking the eleven good ones would relabel
        // every point after the gap as its neighbour.
        val truncated = CourtKeypoints(
            topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
            bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
            netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
            serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
            serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
            centerNear = listOf(21f), centerFar = listOf(23f, 24f),
        )

        CourtMarkingState.restored(1920, 1080, truncated).points shouldBe emptyList()
    }
}
