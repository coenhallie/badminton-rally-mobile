package com.badmintontracker.shared.matchpage

/** Every string the match page, its sections and the run announcement draw. */
object MatchPageCopy {
    /** The section toggle row, to a screen reader. */
    const val SECTIONS = "Match sections"
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
