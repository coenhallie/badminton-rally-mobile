package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.RallyWindow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HeatmapScopeTest {

    @Test
    fun with_rally_windows_the_rallies_come_first() {
        heatmapScopes(listOf(RallyWindow(1, 4.0, 21.5))) shouldBe
            listOf(HeatmapScope.Rallies, HeatmapScope.Match)
    }

    @Test
    fun without_bounded_windows_there_is_only_the_whole_match() {
        // Clips recovered without their bounds, or a cloud run whose clips
        // have not landed yet: no rally to filter by, so no choice to offer.
        heatmapScopes(listOf(RallyWindow(1, 0.0, 0.0))) shouldBe listOf(HeatmapScope.Match)
        heatmapScopes(emptyList()) shouldBe listOf(HeatmapScope.Match)
    }

    @Test
    fun the_labels_are_the_ones_both_phones_show() {
        HeatmapScope.entries.map { it.label } shouldBe listOf("Rallies", "Whole match")
    }
}
