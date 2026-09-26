package com.badmintontracker.shared.analytics

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The rule deciding what a coach can do with each match on the Analytics list.
 *
 * Lives in :shared rather than twice in the two clients, so the two cannot
 * drift apart. Phase 2 learned that the hard way: the hero copy was asserted on
 * each platform against a literal in that platform's own test file, so both
 * could have changed together and nothing would have failed.
 */
class AnalyticsRowStateTest {

    @Test
    fun a_match_with_a_stored_track_is_ready() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = true) shouldBe
            AnalyticsRowState.READY
    }

    @Test
    fun a_local_video_without_a_track_can_be_analysed() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = false) shouldBe
            AnalyticsRowState.ANALYSABLE
    }

    @Test
    fun a_match_with_no_local_video_is_not_on_this_device() {
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = false) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun a_cloud_analysed_match_with_no_local_video_opens_its_heatmap() {
        // Reversed deliberately. This combination used to report
        // NOT_ON_DEVICE on the reasoning that a track can outlive its video,
        // so READY would send the coach to a heatmap whose footage was gone.
        // A cloud run makes the same combination mean something else: the
        // track came DOWN to a phone that never held the file, and there is
        // real data to draw. READY_NO_VIDEO is the distinction the old binary
        // could not make.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = true) shouldBe
            AnalyticsRowState.READY_NO_VIDEO
    }

    @Test
    fun a_match_with_neither_is_still_inert() {
        // Nothing to show and no way to make anything: offering to analyse it
        // would promise something that cannot run.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = false) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun both_kinds_of_ready_open_something() {
        // The whole point of the predicate: a cloud-analysed match has a
        // heatmap behind it even though the footage is on another phone.
        opensAnalytics(AnalyticsRowState.READY) shouldBe true
        opensAnalytics(AnalyticsRowState.READY_NO_VIDEO) shouldBe true
    }

    @Test
    fun a_row_with_nothing_stored_opens_nothing() {
        opensAnalytics(AnalyticsRowState.ANALYSABLE) shouldBe false
        opensAnalytics(AnalyticsRowState.NOT_ON_DEVICE) shouldBe false
    }
}
