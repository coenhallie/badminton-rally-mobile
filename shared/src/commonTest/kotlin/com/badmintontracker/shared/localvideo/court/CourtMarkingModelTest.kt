package com.badmintontracker.shared.localvideo.court

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
}
