package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.analysis.court.CourtFinding
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CourtAutoMarkingTest {

    /** A found court whose table point i sits at (1 + 10i, 2 + 10i): topLeft at (1, 2). */
    private fun foundFinding(): CourtFinding {
        fun p(i: Int) = Point(1.0 + 10 * i, 2.0 + 10 * i)
        return CourtFinding(
            CourtKeypoints(p(0), p(1), p(2), p(3), p(4), p(5), p(6), p(7), p(8), p(9), p(10), p(11)),
            null, "ok", 0.3, 12,
        )
    }

    @Test
    fun a_finding_is_discarded_once_the_coach_has_tapped() {
        val tapped = CourtMarkingState(1920, 1080).tap(10f, 10f, 1920f, 1080f, 22f)
        applyFinding(tapped, foundFinding()) shouldBe null
    }

    @Test
    fun a_finding_fills_an_empty_marking_in_table_order() {
        val s = applyFinding(CourtMarkingState(1920, 1080), foundFinding())!!
        s.points.first() shouldBe CourtPoint(1f, 2f)   // foundFinding() puts topLeft at (1,2)
    }

    @Test
    fun not_found_changes_nothing() {
        applyFinding(CourtMarkingState(1920, 1080), CourtFinding(null, null, "no hypothesis", null, 5)) shouldBe null
    }

    @Test
    fun every_point_comes_across_in_table_order() {
        foundFinding().toCourtPoints() shouldBe List(12) { CourtPoint(1f + 10 * it, 2f + 10 * it) }
        CourtFinding(null, null, "no hypothesis", null, 5).toCourtPoints() shouldBe null
    }

    @Test
    fun an_applied_finding_is_one_undo_step_on_the_frame_on_screen() {
        val empty = CourtMarkingState.restored(1920, 1080, null, frameSeconds = 0.3)
        val s = applyFinding(empty, foundFinding())!!
        s.isComplete shouldBe true
        s.markedAtSeconds shouldBe 0.3
        s.undo().points shouldBe emptyList()
    }

    @Test
    fun finder_status_copy() {
        CourtFinderStatus.IDLE.note shouldBe null
        CourtFinderStatus.IDLE.busyLine shouldBe null
        CourtFinderStatus.FINDING.note shouldBe null
        CourtFinderStatus.FINDING.busyLine shouldBe CourtMarkingSpec.FINDING
        CourtFinderStatus.FOUND.note shouldBe CourtMarkingSpec.FOUND_NOTE
        CourtFinderStatus.FOUND.busyLine shouldBe null
        CourtFinderStatus.NOT_FOUND.note shouldBe CourtMarkingSpec.NOT_FOUND_NOTE
        CourtFinderStatus.NOT_FOUND.busyLine shouldBe null
        CourtFinderStatus.ALIGNING.note shouldBe null
        CourtFinderStatus.ALIGNING.busyLine shouldBe CourtMarkingSpec.ALIGNING
        CourtFinderStatus.ALIGNED.note shouldBe CourtMarkingSpec.ALIGNED_NOTE
        CourtFinderStatus.ALIGNED.busyLine shouldBe null
        CourtFinderStatus.ALIGN_FAILED.note shouldBe CourtMarkingSpec.ALIGN_FAILED_NOTE
        CourtFinderStatus.ALIGN_FAILED.busyLine shouldBe null
        CourtFinderStatus.FIND_FAILED.note shouldBe CourtMarkingSpec.FIND_FAILED_NOTE
        CourtFinderStatus.FIND_FAILED.busyLine shouldBe null
    }

    @Test
    fun the_finder_is_busy_only_while_it_works_on_the_frame() {
        CourtFinderStatus.entries.filter { it.isBusy } shouldBe
            listOf(CourtFinderStatus.FINDING, CourtFinderStatus.ALIGNING)
    }

    @Test
    fun a_failed_find_court_over_saved_marks_says_so() {
        CourtFinderStatus.FIND_FAILED.screenNote(showsSavedMarks = true) shouldBe CourtMarkingSpec.FIND_FAILED_NOTE
    }

    @Test
    fun saved_marks_are_explained_while_the_finder_has_nothing_to_say() {
        CourtFinderStatus.IDLE.screenNote(showsSavedMarks = true) shouldBe CourtMarkingSpec.SAVED_MARKS_NOTE
        CourtFinderStatus.FINDING.screenNote(showsSavedMarks = true) shouldBe CourtMarkingSpec.SAVED_MARKS_NOTE
        CourtFinderStatus.IDLE.screenNote(showsSavedMarks = false) shouldBe null
        CourtFinderStatus.FOUND.screenNote(showsSavedMarks = false) shouldBe CourtMarkingSpec.FOUND_NOTE
    }

    // --- What each finder answer does to the marking ------------------------

    private val notFound = CourtFinding(null, null, "no hypothesis", null, 5)
    private val empty = CourtMarkingState.restored(1920, 1080, null, frameSeconds = 0.1)

    /** Taps at (100 + 10k, 200 + 10k) display pixels on a 1920x1080 frame shown at its own size. */
    private fun tapped(n: Int): CourtMarkingState =
        (0 until n).fold(empty) { s, k -> s.tap(100f + 10 * k, 200f + 10 * k, 1920f, 1080f, 22f) }

    private val found = List(12) { CourtPoint(1f + 10 * it, 2f + 10 * it) }

    @Test
    fun opening_find_fills_an_empty_marking() {
        val out = afterOpeningFind(empty, foundFinding())
        out shouldBe CourtFinderOutcome(empty.withDetected(found), CourtFinderStatus.FOUND, edited = true)
    }

    @Test
    fun opening_find_after_a_tap_is_discarded_quietly() {
        val one = tapped(1)
        afterOpeningFind(one, foundFinding()) shouldBe CourtFinderOutcome(one, CourtFinderStatus.IDLE, edited = false)
    }

    @Test
    fun opening_find_that_finds_nothing_or_throws_asks_for_the_corners() {
        afterOpeningFind(empty, notFound) shouldBe CourtFinderOutcome(empty, CourtFinderStatus.NOT_FOUND, edited = false)
        afterOpeningFind(empty, null) shouldBe CourtFinderOutcome(empty, CourtFinderStatus.NOT_FOUND, edited = false)
    }

    @Test
    fun find_court_replaces_any_marking() {
        val four = tapped(4)
        afterFindCourt(four, foundFinding()) shouldBe
            CourtFinderOutcome(four.withDetected(found), CourtFinderStatus.FOUND, edited = true)
        afterFindCourt(empty, foundFinding()) shouldBe
            CourtFinderOutcome(empty.withDetected(found), CourtFinderStatus.FOUND, edited = true)
    }

    @Test
    fun find_court_that_finds_nothing_over_marks_leaves_them() {
        val one = tapped(1)
        afterFindCourt(one, notFound) shouldBe CourtFinderOutcome(one, CourtFinderStatus.FIND_FAILED, edited = false)
        afterFindCourt(one, null) shouldBe CourtFinderOutcome(one, CourtFinderStatus.FIND_FAILED, edited = false)
    }

    @Test
    fun find_court_that_finds_nothing_on_an_empty_marking_asks_for_the_corners() {
        afterFindCourt(empty, notFound) shouldBe CourtFinderOutcome(empty, CourtFinderStatus.NOT_FOUND, edited = false)
        afterFindCourt(empty, null) shouldBe CourtFinderOutcome(empty, CourtFinderStatus.NOT_FOUND, edited = false)
    }

    @Test
    fun corners_are_completed_onto_the_same_four_taps() {
        val four = tapped(4)
        afterCorners(four, four.points, foundFinding()) shouldBe
            CourtFinderOutcome(four.withDetected(found), CourtFinderStatus.ALIGNED, edited = true)
    }

    @Test
    fun corners_that_cannot_be_completed_keep_the_taps() {
        val four = tapped(4)
        afterCorners(four, four.points, notFound) shouldBe
            CourtFinderOutcome(four, CourtFinderStatus.ALIGN_FAILED, edited = false)
        afterCorners(four, four.points, null) shouldBe
            CourtFinderOutcome(four, CourtFinderStatus.ALIGN_FAILED, edited = false)
    }

    @Test
    fun a_completion_for_taps_since_changed_is_dropped() {
        val four = tapped(4)
        val five = tapped(5)
        val undone = four.undo()
        afterCorners(five, four.points, foundFinding()) shouldBe
            CourtFinderOutcome(five, CourtFinderStatus.IDLE, edited = false)
        afterCorners(undone, four.points, foundFinding()) shouldBe
            CourtFinderOutcome(undone, CourtFinderStatus.IDLE, edited = false)
        afterCorners(undone, four.points, notFound) shouldBe
            CourtFinderOutcome(undone, CourtFinderStatus.IDLE, edited = false)
    }
}
