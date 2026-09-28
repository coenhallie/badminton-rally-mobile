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
}
