package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.RallyWindow

/**
 * What stretch of the match a heatmap is drawn from.
 *
 * [Rallies] is the default wherever it is offered: the whole match also holds
 * the walk to the shuttle tube, the towel and the wait before every serve,
 * which is where the player waits rather than where they play, and it is
 * often most of the video.
 */
enum class HeatmapScope(val label: String) {
    Rallies("Rallies"),
    Match("Whole match"),
}

/**
 * The scopes this match can be drawn over, the default first.
 *
 * Rallies needs a window with bounds to filter by, the same condition the
 * Base tab is offered on. Without one the whole match is all there is, and a
 * one-option toggle is a control that cannot be actuated, so the hosts draw
 * no toggle at all.
 */
fun heatmapScopes(windows: List<RallyWindow>): List<HeatmapScope> =
    if (windows.any { it.isBounded }) listOf(HeatmapScope.Rallies, HeatmapScope.Match) else listOf(HeatmapScope.Match)

/** The heatmap's empty line when the player was found, but never during play. */
const val HEATMAP_NO_RALLY_SAMPLES: String =
    "The player was not found during any rally. Switch to the whole match to see where they were."
