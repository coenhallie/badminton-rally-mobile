package com.badmintontracker.shared.local

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SkeletonSummaryTest {

    @Test
    fun the_footer_names_the_frame_or_says_there_is_none() {
        skeletonFooter(76) shouldBe "Frame 76"
        skeletonFooter(null) shouldBe "No skeleton at this frame"
    }

    @Test
    fun the_court_warning_tells_missing_marks_from_marks_that_do_not_fit() {
        courtWarning(CourtFit.OK) shouldBe null
        courtWarning(CourtFit.NONE) shouldBe "No court marks in this file: stance and position need a re-run."
        courtWarning(CourtFit.BAD) shouldBe "The court marks do not fit: mark the court again for stance and position."
    }

    @Test
    fun the_detail_carries_the_frame_count_and_the_court_outcome() {
        skeletonDetail(730, CourtFit.OK) shouldBe "Skeleton in 730 frames · court marks in file"
        skeletonDetail(12, CourtFit.BAD) shouldBe "Skeleton in 12 frames · court marks do not fit"
        skeletonDetail(0, CourtFit.NONE) shouldBe "Skeleton in 0 frames · no court marks"
    }
}
