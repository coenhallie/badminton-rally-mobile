package com.badmintontracker.shared.analytics

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AvailablePanelsTest {

    @Test
    fun the_heatmap_is_always_offered_and_always_first() {
        availablePanels(hasTrack = false, hasBoundedClips = false, hasSkeleton = false, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap)
    }

    @Test
    fun base_needs_both_a_track_and_rally_windows_with_bounds() {
        // Clips recovered from disk without their index have zero bounds, and a
        // rallies-only run has no track: neither can put a dot on the court.
        availablePanels(hasTrack = true, hasBoundedClips = false, hasSkeleton = false, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap)
        availablePanels(hasTrack = false, hasBoundedClips = true, hasSkeleton = false, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap)
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = false, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }

    @Test
    fun all_three_keep_the_order_heatmap_base_skeleton() {
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base, AnalyticsPanel.Skeleton)
    }

    @Test
    fun a_skeleton_without_a_base_still_gets_its_tab() {
        availablePanels(hasTrack = true, hasBoundedClips = false, hasSkeleton = true, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Skeleton)
    }

    @Test
    fun the_skeleton_needs_a_video_to_draw_over() {
        // A cloud-analysed match reaches a phone that never held the file: it
        // was uploaded from another of this coach's phones. The heatmap is a
        // court and a track and needs neither the video nor a player; the
        // skeleton is an overlay, and offering a tab that can only ever say
        // "no video" is worse than not offering it.
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true, hasVideo = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }

    @Test
    fun with_a_video_every_panel_with_content_is_offered() {
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base, AnalyticsPanel.Skeleton)
    }

    @Test
    fun the_base_position_does_not_need_a_video() {
        // It is measured from the track and the rally windows, both of which
        // are stored. Nothing in it reads a frame.
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = false, hasVideo = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }
}
