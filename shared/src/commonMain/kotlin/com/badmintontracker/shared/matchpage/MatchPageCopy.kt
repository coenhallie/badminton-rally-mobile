package com.badmintontracker.shared.matchpage

/** Every string the match page, its sections and the run announcement draw. */
object MatchPageCopy {
    /** The section toggle row, to a screen reader. */
    const val SECTIONS = "Match sections"

    /** The Analysis section's panel row (Heatmap | Base | Skeleton), to a screen reader. */
    const val ANALYSIS_PANELS = "Analysis panels"

    /**
     * What heads the Analysis section when the heatmap is its only panel and
     * there is no panel row. Uppercase because it is drawn as a small section
     * label, not a title.
     */
    const val HEATMAP_HEADING = "HEATMAP"

    const val VIEW = "View"
    fun analyzed(title: String) = "$title is analyzed"
    fun analyzedCount(count: Int) = "$count matches are analyzed"

    const val SORT = "Sort rallies"
    const val SORT_RALLY_ORDER = "Rally order"
    const val SORT_MOST_NOTES = "Most notes"

    /** The list's gear, and the page it opens. */
    const val SETTINGS = "Settings"

    const val ADD_NOTE = "Add note"
    const val DEVICE_CLIPS_EMPTY = "No clips are stored for this video."
    const val MISSING = "This match is no longer on this phone."
}
