package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.PlayerPose

/** What a completed run should do with the skeleton stored for its entry. */
enum class SkeletonAction { SAVE, DELETE }

/**
 * Whether a completed run's skeleton should be written or removed.
 *
 * Here rather than in either app, because it is the rule that decides what a
 * coach's storage holds and both platforms have to apply it the same way. It
 * started in androidApp, where iOS could not see it.
 *
 * SAVE only when [AnalysisMetric.SKELETON_PLAYBACK] was asked for and the pose
 * pass actually found the near player somewhere: `poses.isNotEmpty()`. Every
 * other case is DELETE, including a run that asked for the metric but found
 * nobody - a completed run is the new truth for its entry, so it must not
 * leave an earlier run's skeleton behind it, offered against a track that has
 * since moved on.
 */
fun skeletonAction(metrics: Set<AnalysisMetric>, poses: List<PlayerPose>): SkeletonAction =
    if (AnalysisMetric.SKELETON_PLAYBACK in metrics && poses.isNotEmpty()) {
        SkeletonAction.SAVE
    } else {
        SkeletonAction.DELETE
    }
