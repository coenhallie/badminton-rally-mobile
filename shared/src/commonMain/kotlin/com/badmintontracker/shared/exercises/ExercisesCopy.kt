package com.badmintontracker.shared.exercises

/** Every user-facing heading, status line, and caveat rendered by the Drills panel. */
object ExercisesCopy {
    const val SUMMARY_HEADING = "MOVEMENT SUMMARY"
    const val SUGGESTIONS_HEADING = "SUGGESTED EXERCISES"
    const val LIBRARY_HEADING = "DRILL LIBRARY"
    const val TRAINING_LOG_HEADING = "TRAINING PROGRESS"
    const val PEAK_SPEED_LABEL = "Peak speed (0.5 s)"
    const val SETUP_LABEL = "Setup"
    const val CUE_LABEL = "Cue"
    const val COURT_VISUAL_CAPTION = "Court pattern"
    const val SPEED_NORMAL = "1x"
    const val SPEED_SLOW = "0.5x"
    const val START_DRILL = "Start drill"
    const val START_SESSION = "Start session"
    const val COMPLETE_SET = "Complete set"
    const val SKIP_REST = "Skip rest"
    const val ADD_REST_15 = "+15 s"
    const val FINISH_EARLY = "Finish early"
    const val SAVE_SESSION = "Save session"
    const val DISCARD_SESSION = "Discard"
    const val HOW_DID_IT_FEEL = "How did it feel?"
    const val NOTE_PLACEHOLDER = "Optional note (e.g. felt sharper on forehand)"
    const val SESSION_COMPLETE_TITLE = "Session complete"
    const val GET_READY_LABEL = "GET READY"
    const val WORK_LABEL = "WORK"
    const val REST_LABEL = "REST"
    const val RECENT_SESSIONS_LABEL = "Recent sessions"
    const val NO_SESSIONS_YET = "Pick any drill below to start a guided session and track progress."
    const val SEARCH_PLACEHOLDER = "Search drills by name or focus"
    const val FILTER_RECENT = "Recent"
    const val NO_SEARCH_MATCHES = "No drills match that search."
    const val CLEAR_SEARCH = "Clear search"
    const val QUICK_START_LABEL = "Quick start"
    const val REPEAT_DRILL_LABEL = "Repeat drill"

    fun poseCaption(phaseLabel: String): String = "Pose: $phaseLabel"

    fun setProgressLabel(currentSet: Int, totalSets: Int): String = "Set $currentSet of $totalSets"

    fun completeSetButton(currentSet: Int, totalSets: Int): String = "Complete set $currentSet / $totalSets"

    fun setsCompletedStat(completed: Int, target: Int): String = "$completed / $target"

    fun browseLibraryLabel(totalCount: Int, expanded: Boolean): String =
        if (expanded) "Hide drill library" else "Browse all $totalCount drills"

    fun librarySubtitle(onCourtCount: Int, offCourtCount: Int): String =
        "$onCourtCount on-court · $offCourtCount off-court with animated guides"

    const val HONESTY_CAPTION =
        "Based on singles rally movement from this video. Doubles play and isolated feeding drills may skew positioning."

    const val NOT_LOADED =
        "This analysis is no longer loaded. Run it again to see suggested exercises."

    const val NO_BOUNDED_WINDOWS =
        "The rally windows for this analysis were not kept, so there is nothing to measure per rally. Run the analysis again."

    const val NO_COUNTED_RALLIES =
        "Not enough tracked rally movement in this video to suggest exercises."

    fun ralliesUnit(countedRallies: Int): String = if (countedRallies == 1) " rally" else " rallies"

    fun ralliesCaption(coveragePercent: Int): String = "$coveragePercent% tracked"

    fun zoneBreakdown(frontPercent: Int, midPercent: Int, rearPercent: Int): String =
        "Front $frontPercent% · Mid $midPercent% · Rear $rearPercent%"

    fun insufficientData(
        rallies: Int,
        coveragePercent: Int,
        minRallies: Int,
        minCoveragePercent: Int,
    ): String {
        val rallyWord = if (rallies == 1) "rally" else "rallies"
        return "Need at least $minRallies tracked rallies with $minCoveragePercent% coverage to suggest exercises. " +
            "This video had $rallies $rallyWord ($coveragePercent% coverage)."
    }

    fun balanced(rallies: Int): String {
        val rallyWord = if (rallies == 1) "rally" else "rallies"
        return "Positioning and movement looked balanced across $rallies $rallyWord. " +
            "Nothing here calls for a corrective drill."
    }
}
