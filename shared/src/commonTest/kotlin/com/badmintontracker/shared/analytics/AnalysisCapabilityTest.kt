package com.badmintontracker.shared.analytics

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The "?" sheet's contents, pinned.
 *
 * Worth its own tests for one reason: this is a promise about what the app
 * does, made at the moment the coach chooses between the two paths, and it is
 * the copy furthest from the code that makes it true. A panel added later
 * that quietly missed the sheet would leave a tab nothing explains.
 */
class AnalysisCapabilityTest {

    @Test
    fun every_panel_the_app_can_offer_has_a_row() {
        // The whole point. AnalyticsPanel.entries is the source of truth for
        // what a detail screen can show, so the sheet is checked against it
        // rather than against a count.
        panelCapabilities.map { it.panel } shouldBe AnalyticsPanel.entries
    }

    @Test
    fun no_row_is_blank_on_either_side() {
        // A blank cell reads as "not supported" rather than as missing copy,
        // which is the wrong claim to make by accident.
        panelCapabilities.forEach {
            it.cloud.isNotBlank() shouldBe true
            it.device.isNotBlank() shouldBe true
        }
    }

    @Test
    fun the_skeleton_is_the_only_row_that_names_a_condition() {
        // Everything else converged: after the cloud pose artifact, both paths
        // produce the same panels. The skeleton overlays playback, so it alone
        // still depends on the footage being here. If a second row ever grows
        // an "if", that is a real divergence and worth noticing here.
        val conditional = panelCapabilities.filter { it.cloud.contains(" if ") }
        conditional.map { it.panel } shouldBe listOf(AnalyticsPanel.Skeleton)
    }

    @Test
    fun both_sides_carry_notes_and_neither_oversells_the_cloud() {
        cloudNotes.isNotEmpty() shouldBe true
        deviceNotes.isNotEmpty() shouldBe true
        // The estimator on this screen prices the device run only, and the
        // MetricSelector already says a cloud run decides its own stages. The
        // sheet must not contradict it with a duration.
        cloudNotes.any { it.contains("takes as long as it takes") } shouldBe true
    }
}
