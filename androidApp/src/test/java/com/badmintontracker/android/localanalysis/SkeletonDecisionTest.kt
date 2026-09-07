package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.PlayerPose
import com.badmintontracker.shared.local.AnalysisMetric
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The pure save-or-delete rule the runner applies to a completed run's
 * skeleton, isolated from the store and the coroutine around it.
 */
class SkeletonDecisionTest {

    private val pose = PlayerPose(
        frame = 0,
        timestamp = 0.0,
        keypoints = listOf(Point(0.0, 0.0)),
        confidence = listOf(1f),
    )

    @Test
    fun asked_for_and_someone_was_found_saves() {
        skeletonAction(setOf(AnalysisMetric.SKELETON_PLAYBACK), listOf(pose)) shouldBe SkeletonAction.SAVE
    }

    @Test
    fun asked_for_but_nobody_was_found_deletes() {
        skeletonAction(setOf(AnalysisMetric.SKELETON_PLAYBACK), emptyList()) shouldBe SkeletonAction.DELETE
    }

    @Test
    fun not_asked_for_but_someone_was_found_deletes() {
        skeletonAction(setOf(AnalysisMetric.RALLY_CLIPS), listOf(pose)) shouldBe SkeletonAction.DELETE
    }

    @Test
    fun not_asked_for_and_nobody_was_found_deletes() {
        skeletonAction(setOf(AnalysisMetric.RALLY_CLIPS), emptyList()) shouldBe SkeletonAction.DELETE
    }
}
