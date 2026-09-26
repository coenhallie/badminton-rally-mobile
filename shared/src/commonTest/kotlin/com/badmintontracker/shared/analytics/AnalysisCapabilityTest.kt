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
    fun no_row_names_a_condition_any_more() {
        // The two paths have fully converged. The skeleton was the last row to
        // carry an "if", and it carried it because a cloud run released the
        // video the skeleton is drawn over - the run removing the thing its own
        // output needed. It keeps the video now, so every row is unconditional.
        // A new "if" here is a real divergence and worth noticing.
        panelCapabilities.filter { it.cloud.contains(" if ") } shouldBe emptyList()
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

    @Test
    fun the_cloud_notes_say_the_video_stays_and_what_that_costs() {
        // The one thing on this sheet a coach acts on before starting: a cloud
        // run does NOT free the footage, and they may have picked it expecting
        // that it would. Better said here than discovered as storage that never
        // came back.
        cloudNotes.any { it.contains("Keeps the video on this phone") } shouldBe true

        // And it must not go back to claiming a phone that never held the
        // video gets a heatmap. Nothing fetches an artifact except the phone
        // that uploaded, so that sentence described a feature, not the app.
        cloudNotes.any { it.contains("never held") } shouldBe false
    }
}
