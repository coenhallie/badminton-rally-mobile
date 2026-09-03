package com.badmintontracker.android.home

/**
 * The Home hero's copy and rotation.
 *
 * Separate from the composable so the phrases and the wrap-around can be
 * asserted without a running timer. Mirrors iosApp's HeroTicker.swift word for
 * word; the two test files check them against each other.
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
