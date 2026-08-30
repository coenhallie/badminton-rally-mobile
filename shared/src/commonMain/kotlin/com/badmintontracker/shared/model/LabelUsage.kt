package com.badmintontracker.shared.model

/**
 * Where a label may be offered. One value rather than two booleans: two
 * booleans admit a fourth state - neither - which is a label that exists and
 * appears nowhere, and that state has no meaning worth defending against.
 *
 * The keys match the `usage` column's CHECK constraint exactly. Nothing here
 * knows about colours or ordering; see [LabelColor] for the parallel case.
 */
enum class LabelUsage(val key: String) {
    BOTH("both"),
    SCOREBOARD("scoreboard"),
    CLIPS("clips"),
    ;

    // Written as negations rather than as whitelists so BOTH cannot drift out
    // of either set if a value is ever added. Each predicate exists once.
    val onScoreboard: Boolean get() = this != CLIPS
    val onClips: Boolean get() = this != SCOREBOARD

    companion object {
        /**
         * Resolves a stored key, or null when this build does not know it.
         * Callers fall back to [BOTH] rather than hiding the label: a chip you
         * did not expect beats a chip that has silently vanished.
         */
        fun from(key: String?): LabelUsage? = entries.firstOrNull { it.key == key }
    }
}
