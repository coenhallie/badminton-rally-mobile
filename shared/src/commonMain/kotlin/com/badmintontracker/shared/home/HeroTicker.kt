package com.badmintontracker.shared.home

/**
 * The Home hero's copy and rotation.
 *
 * Lives in :shared, not in either client, so there is exactly one definition
 * of the phrases and the wrap-around: one definition beats two definitions
 * plus a test hoping they agree. Separate from the view so both can be
 * asserted without a running timer.
 */
object HeroTicker {
    /** The fixed first line. Held here so both lines are edited in one file. */
    const val leadLine = "Your game,"

    val phrases = listOf(
        "clipped rally by rally.",
        "mapped as heatmaps.",
        "tracked as skeletons.",
        "annotated and shared.",
    )

    /**
     * The next phrase index, wrapping at the end.
     *
     * Clamps rather than trusting its input: the index is UI state and a value
     * restored after process death has been seen to arrive stale.
     */
    fun next(after: Int): Int {
        if (phrases.isEmpty()) return 0
        val safe = if (after < 0 || after >= phrases.size) 0 else after
        return (safe + 1) % phrases.size
    }
}
