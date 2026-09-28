package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.analysis.court.COURT_LINE_SEGMENTS
import com.badmintontracker.analysis.geometry.COURT_KEYPOINT_POSITIONS
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.calculateHomography
import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
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

        val restored = CourtMarkingState.restored(1920, 1080, s.toCourtKeypoints(), frameSeconds = 0.1)

        restored.points shouldBe s.points
        restored.isComplete shouldBe true
        restored.nextIndex shouldBe 12
        restored.toCourtKeypoints() shouldBe s.toCourtKeypoints()
    }

    @Test
    fun restored_without_saved_keypoints_is_an_empty_marking() {
        val restored = CourtMarkingState.restored(1920, 1080, null, frameSeconds = 0.1)
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

        CourtMarkingState.restored(1920, 1080, truncated, frameSeconds = 0.1).points shouldBe emptyList()
    }

    /**
     * Twelve points placed by [CourtMarkingState.place] on a 1920x1080 display
     * of a 1920x1080 video (source = display), point i at
     * (100 + 140i, 100 + 70i): point 0 at (100, 100), every two more than
     * 150 px apart, and nothing near (1500, 50).
     */
    private fun complete(state: CourtMarkingState = CourtMarkingState(1920, 1080)): CourtMarkingState {
        var s = state
        repeat(12) { i -> s = s.place(100f + 140f * i, 100f + 70f * i, 1920f, 1080f) }
        return s
    }

    @Test
    fun marks_carry_the_time_of_the_frame_they_were_drawn_on() {
        complete(CourtMarkingState(1920, 1080, markedAtSeconds = 1.0))
            .toCourtKeypoints().markedAtSeconds shouldBe 1.0
    }

    @Test
    fun restored_marks_keep_their_own_time_not_the_frame_on_screen() {
        val saved = complete(CourtMarkingState(1920, 1080, markedAtSeconds = 0.5)).toCourtKeypoints()

        CourtMarkingState.restored(1920, 1080, saved, frameSeconds = 0.1)
            .toCourtKeypoints().markedAtSeconds shouldBe 0.5
    }

    @Test
    fun restored_marks_saved_without_a_time_stay_without_one() {
        // Null means "ask the platform", which on iOS recovers the frame the
        // legacy marks were drawn on. Stamping the frame on screen here would
        // be a guess dressed as a measurement.
        val legacy = complete(CourtMarkingState(1920, 1080, markedAtSeconds = null)).toCourtKeypoints()

        CourtMarkingState.restored(1920, 1080, legacy, frameSeconds = 1.0)
            .toCourtKeypoints().markedAtSeconds shouldBe null
    }

    @Test
    fun marks_drawn_after_clearing_restored_ones_carry_the_frame_on_screen() {
        val saved = complete(CourtMarkingState(1920, 1080, markedAtSeconds = 0.5)).toCourtKeypoints()
        val restored = CourtMarkingState.restored(1920, 1080, saved, frameSeconds = 0.1)

        complete(restored.clear()).toCourtKeypoints().markedAtSeconds shouldBe 0.1

        var undone = restored
        repeat(12) { undone = undone.undo() }
        complete(undone).toCourtKeypoints().markedAtSeconds shouldBe 0.1
    }

    @Test
    fun a_fresh_marking_is_on_the_frame_on_screen() {
        CourtMarkingState.restored(1920, 1080, null, frameSeconds = 1.0).markedAtSeconds shouldBe 1.0
    }

    // --- history, selection, moving, detection, overlay -------------------

    private fun tap(s: CourtMarkingState, x: Float, y: Float) = s.tap(x, y, 1920f, 1080f, 22f)

    /**
     * The 12 table points seen by a real camera: court metres to pixels
     * through the homography that puts the four corners on a trapezoid
     * narrowing towards the top of a 1920x1080 frame, placed in table order.
     */
    private fun idealMarks(): CourtMarkingState {
        val camera = calculateHomography(
            COURT_KEYPOINT_POSITIONS.take(4),
            listOf(Point(700.0, 300.0), Point(1220.0, 300.0), Point(1500.0, 950.0), Point(420.0, 950.0)),
        )!!
        var s = CourtMarkingState(1920, 1080)
        COURT_KEYPOINT_POSITIONS.forEach { court ->
            val px = camera.apply(court.x, court.y)!!
            s = s.place(px.x.toFloat(), px.y.toFloat(), 1920f, 1080f)
        }
        return s
    }

    private fun distance(a: CourtPoint, b: CourtPoint): Double =
        kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    @Test
    fun undo_reverses_a_move() {
        var s = complete()
        val before = s.points
        s = s.tap(100f, 100f, 1920f, 1080f, 22f)   // select point 0 at (100,100) in a 1920x1080 display = source
        s.selected shouldBe 0
        s = s.tap(300f, 300f, 1920f, 1080f, 22f)
        s.points[0] shouldBe CourtPoint(300f, 300f)
        s.selected shouldBe null
        s.undo().points shouldBe before
    }

    @Test
    fun tapping_the_selected_point_again_cancels() {
        var s = complete().tap(100f, 100f, 1920f, 1080f, 22f)
        s = s.tap(105f, 102f, 1920f, 1080f, 22f)
        s.selected shouldBe null
        s.points shouldBe complete().points
    }

    @Test
    fun a_tap_away_from_every_point_selects_nothing() {
        complete().tap(1500f, 50f, 1920f, 1080f, 22f).selected shouldBe null
    }

    @Test
    fun a_detection_is_one_undo_step() {
        val empty = CourtMarkingState(1920, 1080, markedAtSeconds = 0.1, frameSeconds = 0.1)
        val s = empty.withDetected(List(12) { CourtPoint(it * 10f, it * 5f) })
        s.isComplete shouldBe true
        s.undo().points shouldBe emptyList()
    }

    @Test
    fun the_fourth_tap_triggers_completion_and_undo_does_not() {
        var s = CourtMarkingState(1920, 1080)
        repeat(3) { s = s.tap(10f + it, 10f, 1920f, 1080f, 22f) }
        val four = s.tap(50f, 50f, 1920f, 1080f, 22f)
        four.justReachedFourCorners(s) shouldBe true
        four.undo().justReachedFourCorners(four) shouldBe false
    }

    @Test
    fun court_lines_follow_the_marks() {
        val s = idealMarks()
        s.courtLines().size shouldBe 12
    }

    @Test
    fun selection_takes_the_nearest_point_within_the_radius_in_source_pixels() {
        // Shown at a tenth of its size, the 22 px display radius is 220 source
        // px: points 0 (100,100) and 1 (240,170) are both within it of
        // (190,140), and point 1 is the nearer.
        val s = complete()
        s.tap(19f, 14f, 192f, 108f, 22f).selected shouldBe 1
        s.tap(11.5f, 11f, 192f, 108f, 22f).selected shouldBe 0
        // At full size the same source spot is 58 px from point 1: nothing.
        s.tap(190f, 140f, 1920f, 1080f, 22f).selected shouldBe null
    }

    @Test
    fun a_move_lands_where_place_would_put_it() {
        val s = complete().tap(10f, 10f, 192f, 108f, 22f)
        s.selected shouldBe 0
        s.tap(50f, 40f, 192f, 108f, 22f).points[0] shouldBe CourtPoint(500f, 400f)
    }

    @Test
    fun a_selected_point_moves_even_onto_another_point() {
        val s = tap(tap(complete(), 100f, 100f), 240f, 170f)
        s.selected shouldBe null
        s.points[0] shouldBe CourtPoint(240f, 170f)
        s.points[1] shouldBe CourtPoint(240f, 170f)
    }

    @Test
    fun selection_is_not_an_undo_step_and_undo_drops_it() {
        val s = complete()
        val selected = tap(s, 100f, 100f)
        selected.history shouldBe s.history
        val undone = selected.undo()
        undone.selected shouldBe null
        undone.points shouldBe s.points.dropLast(1)
    }

    @Test
    fun an_incomplete_marking_places_on_tap_and_never_selects() {
        val s = tap(CourtMarkingState(1920, 1080), 100f, 100f)
        tap(s, 100f, 100f).points shouldBe listOf(CourtPoint(100f, 100f), CourtPoint(100f, 100f))
        tap(s, 100f, 100f).selected shouldBe null
    }

    @Test
    fun undo_on_restored_marks_still_drops_the_last_point_and_clears_a_selection() {
        // The ring must not be left on a point that no longer exists.
        val restored = CourtMarkingState.restored(1920, 1080, complete().toCourtKeypoints(), frameSeconds = 0.1)
        val selected = tap(restored, 1640f, 870f)
        selected.selected shouldBe 11
        val undone = selected.undo()
        undone.points shouldBe restored.points.dropLast(1)
        undone.selected shouldBe null
    }

    @Test
    fun clear_is_one_undo_step() {
        val s = complete()
        s.clear().undo().points shouldBe s.points
        tap(s, 100f, 100f).clear().selected shouldBe null
    }

    @Test
    fun clearing_an_empty_marking_adds_no_undo_step() {
        val s = CourtMarkingState(1920, 1080)
        s.clear().history shouldBe emptyList()
    }

    @Test
    fun undoing_a_clear_of_legacy_marks_keeps_them_without_a_time() {
        // Undo brings back the marks that were cleared, and with them the
        // frame they were drawn on; for marks saved without one, none.
        val legacy = complete(CourtMarkingState(1920, 1080, markedAtSeconds = null)).toCourtKeypoints()
        val restored = CourtMarkingState.restored(1920, 1080, legacy, frameSeconds = 1.0)

        val cleared = restored.clear()
        cleared.markedAtSeconds shouldBe 1.0
        val undone = cleared.undo()
        undone.points shouldBe restored.points
        undone.markedAtSeconds shouldBe null
    }

    @Test
    fun a_detection_is_on_the_frame_on_screen_and_undo_restores_the_old_time() {
        val legacy = complete(CourtMarkingState(1920, 1080, markedAtSeconds = null)).toCourtKeypoints()
        val restored = CourtMarkingState.restored(1920, 1080, legacy, frameSeconds = 1.0)
        val detected = idealMarks().points

        val s = restored.withDetected(detected)
        s.points shouldBe detected
        s.markedAtSeconds shouldBe 1.0
        s.undo().markedAtSeconds shouldBe null
        s.undo().points shouldBe restored.points
    }

    @Test
    fun a_detection_on_an_empty_marking_is_on_the_frame_on_screen() {
        val empty = CourtMarkingState.restored(1920, 1080, null, frameSeconds = 0.4)
        val s = empty.withDetected(idealMarks().points)
        s.markedAtSeconds shouldBe 0.4
        s.undo().markedAtSeconds shouldBe 0.4
    }

    @Test
    fun a_detection_needs_all_twelve_points() {
        shouldThrow<IllegalArgumentException> {
            CourtMarkingState(1920, 1080).withDetected(List(4) { CourtPoint(0f, 0f) })
        }
    }

    @Test
    fun a_detection_clears_a_selection() {
        tap(complete(), 100f, 100f).withDetected(idealMarks().points).selected shouldBe null
    }

    @Test
    fun only_the_step_from_three_to_four_points_reaches_four_corners() {
        var s = CourtMarkingState(1920, 1080)
        val sizes = mutableListOf<Boolean>()
        repeat(12) {
            val next = s.place(100f + 140f * it, 100f + 70f * it, 1920f, 1080f)
            sizes += next.justReachedFourCorners(s)
            s = next
        }
        sizes shouldBe List(12) { it == 3 }
        // A move on a complete marking never does.
        val selected = tap(s, 100f, 100f)
        tap(selected, 400f, 400f).justReachedFourCorners(selected) shouldBe false
    }

    @Test
    fun court_lines_run_through_the_marks() {
        // court -> pixel: the first across line (the top baseline) runs from
        // TL to TR, the first along line (the left sideline) from TL to BL.
        val s = idealMarks()
        val lines = s.courtLines()
        val tl = s.points[0]
        val tr = s.points[1]
        val bl = s.points[3]
        distance(lines[0].first, tl) shouldBeLessThan 0.01
        distance(lines[0].second, tr) shouldBeLessThan 0.01
        distance(lines[6].first, tl) shouldBeLessThan 0.01
        distance(lines[6].second, bl) shouldBeLessThan 0.01
        lines.size shouldBe COURT_LINE_SEGMENTS.size
    }

    @Test
    fun court_lines_are_empty_until_the_marking_is_complete() {
        CourtMarkingState(1920, 1080).courtLines().shouldBeEmpty()
        idealMarks().undo().courtLines().shouldBeEmpty()
    }

    @Test
    fun court_lines_are_empty_when_the_marks_have_no_homography() {
        // Twelve points on one line fit no court.
        var s = CourtMarkingState(1920, 1080)
        repeat(12) { s = s.place(100f + 10f * it, 100f + 10f * it, 1920f, 1080f) }
        s.courtLines().shouldBeEmpty()
    }

    @Test
    fun finder_copy_is_verbatim() {
        CourtMarkingSpec.FINDING shouldBe "Finding the court..."
        CourtMarkingSpec.FOUND_NOTE shouldBe
            "Found the court automatically. Check the lines sit on the court's lines; tap a point to move it."
        CourtMarkingSpec.NOT_FOUND_NOTE shouldBe
            "Couldn't find the court. Tap the four corners and the rest will be placed for you."
        CourtMarkingSpec.ALIGNING shouldBe "Lining the points up with the court..."
        CourtMarkingSpec.ALIGNED_NOTE shouldBe "Lined up with the court's lines. Check them; tap a point to move it."
        CourtMarkingSpec.ALIGN_FAILED_NOTE shouldBe "Couldn't line the points up. Tap the rest."
        CourtMarkingSpec.FIND_FAILED_NOTE shouldBe "Couldn't find the court. Your marks are unchanged."
        CourtMarkingSpec.FIND_COURT shouldBe "Find court"
        CourtMarkingSpec.moveInstruction(6) shouldBe "Tap the new spot for Service Near-Left"
    }
}
