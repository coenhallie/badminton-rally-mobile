package com.badmintontracker.shared.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisPlanTest {

    private val oneMinuteAt30fps = 1800

    @Test
    fun the_estimate_matches_the_measured_device() {
        // 1800 frames at the S23's measured 235ms is about seven minutes, which
        // is what a Phase 1 run of a one-minute video actually took. If this
        // drifts, either the seed is stale or the arithmetic is wrong.
        val e = estimateAnalysis(oneMinuteAt30fps, 30.0, setOf(AnalysisMetric.PLAYER_MOVEMENT))
        // Base 235 + pose 230 = 465ms a frame.
        assertEquals(837.0, e.fastSeconds, 1.0)
        assertEquals(837.0 * 1.6, e.slowSeconds, 2.0)
    }

    @Test
    fun pose_is_charged_once_however_many_views_want_it() {
        // The trap this guards: two toggles that look independent but share one
        // pass. A user turning the skeleton off to save time must not be shown
        // a smaller number when nothing will actually be skipped.
        val movement = estimateAnalysis(oneMinuteAt30fps, 30.0, setOf(AnalysisMetric.PLAYER_MOVEMENT))
        val both = estimateAnalysis(
            oneMinuteAt30fps, 30.0,
            setOf(AnalysisMetric.PLAYER_MOVEMENT, AnalysisMetric.SKELETON_PLAYBACK),
        )
        assertEquals(movement.fastSeconds, both.fastSeconds, 0.001)
    }

    @Test
    fun pose_roughly_doubles_the_wait() {
        val without = estimateAnalysis(oneMinuteAt30fps, 30.0, emptySet())
        val with = estimateAnalysis(oneMinuteAt30fps, 30.0, setOf(AnalysisMetric.PLAYER_MOVEMENT))
        val ratio = with.fastSeconds / without.fastSeconds
        assertTrue(ratio > 1.8 && ratio < 2.1, "pose should roughly double the wait, got ${ratio}x")
    }

    @Test
    fun frame_rate_costs_more_than_duration_suggests() {
        // The correction that matters: a shorter 50fps video can cost more than
        // a longer 25fps one, which is why this prices frames and not minutes.
        val sixMinutesAt50 = estimateAnalysis((6 * 60 * 50), 50.0, emptySet())
        val eightMinutesAt25 = estimateAnalysis((8 * 60 * 25), 25.0, emptySet())
        assertTrue(
            sixMinutesAt50.fastSeconds > eightMinutesAt25.fastSeconds,
            "6 min at 50fps has more frames than 8 min at 25fps and must cost more",
        )
    }

    @Test
    fun cutting_is_charged_per_clip_second_not_per_frame() {
        val withClips = estimateAnalysis(oneMinuteAt30fps, 30.0, setOf(AnalysisMetric.RALLY_CLIPS))
        val without = estimateAnalysis(oneMinuteAt30fps, 30.0, emptySet())
        assertTrue(withClips.fastSeconds > without.fastSeconds, "cutting is not free")
        // 60s of video, ~35% in rallies, at 120ms per clip second is ~2.5s.
        assertEquals(2.52, withClips.fastSeconds - without.fastSeconds, 0.2)
    }

    @Test
    fun nothing_to_analyse_costs_nothing() {
        assertEquals(0.0, estimateAnalysis(0, 30.0, AnalysisMetric.entries.toSet()).fastSeconds)
    }

    @Test
    fun the_description_rounds_to_units_a_person_plans_around() {
        assertEquals("under a minute", AnalysisEstimate(5.0, 30.0).describe())
        assertEquals("7 min to 11 min", AnalysisEstimate(420.0, 660.0).describe())
        // Past ninety minutes, minutes stop being the useful unit.
        assertEquals("2.0 h to 3.0 h", AnalysisEstimate(7200.0, 10800.0).describe())
        // A range that rounds to the same thing reads as one number.
        assertEquals("about 5 min", AnalysisEstimate(300.0, 310.0).describe())
    }
}
