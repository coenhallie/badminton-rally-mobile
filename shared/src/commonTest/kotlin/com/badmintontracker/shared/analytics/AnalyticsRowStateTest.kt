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
    fun a_track_without_a_local_entry_is_still_not_on_this_device() {
        // Defensive, and the one combination worth thinking about: a track can
        // outlive the video it came from, because a run's result is kept on
        // disk while the file itself can be removed. Reporting READY there
        // would send the coach to a heatmap whose match no longer exists on
        // this phone, so the local entry is what decides.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = true) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }
}
