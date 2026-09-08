package com.badmintontracker.android.analytics

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AvailablePanelsTest {

    @Test
    fun the_heatmap_is_always_offered_and_always_first() {
        availablePanels(hasTrack = false, hasBoundedClips = false, hasSkeleton = false) shouldBe
            listOf(AnalyticsPanel.Heatmap)
    }

    @Test
    fun base_needs_both_a_track_and_rally_windows_with_bounds() {
        // Clips recovered from disk without their index have zero bounds, and a
        // rallies-only run has no track: neither can put a dot on the court.
        availablePanels(hasTrack = true, hasBoundedClips = false, hasSkeleton = false) shouldBe
            listOf(AnalyticsPanel.Heatmap)
        availablePanels(hasTrack = false, hasBoundedClips = true, hasSkeleton = false) shouldBe
            listOf(AnalyticsPanel.Heatmap)
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }

    @Test
    fun all_three_keep_the_order_heatmap_base_skeleton() {
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base, AnalyticsPanel.Skeleton)
    }

    @Test
    fun a_skeleton_without_a_base_still_gets_its_tab() {
        availablePanels(hasTrack = true, hasBoundedClips = false, hasSkeleton = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Skeleton)
    }
}
