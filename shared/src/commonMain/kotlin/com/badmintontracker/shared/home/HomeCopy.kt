package com.badmintontracker.shared.home

/**
 * Every string the match list's title bar, its empty state and the Settings
 * page draw. One place for both clients, the way MatchListCopy holds the
 * list's rows.
 */
object HomeCopy {
    /** The match list's title: the app's root screen. */
    const val MATCHES_TITLE = "Matches"

    /**
     * Not "New match", which the mock drew: the sheet this opens lists "New
     * match" as the first of its three options, and a button named after one
     * of them reads as a shortcut to it.
     */
    const val ADD_MATCH = "Add match"

    /** Under the hero on an empty match list. Names what the Add button does. */
    const val EMPTY_BODY =
        "Score a match courtside, or record or import a video to cut it into rallies. Your matches will show up here."

    const val SETTINGS_LABELS = "Labels"
    const val SETTINGS_LABELS_DETAIL = "Tags you add to rallies"
    const val SETTINGS_DARK_THEME = "Dark theme"
    const val SETTINGS_SIGNED_IN_AS = "Signed in as"
    const val SETTINGS_SIGN_OUT = "Sign out"

    /**
     * [code] is a string on purpose: iOS's build number (CFBundleVersion) is
     * one, and need not be an integer ("1.2.3" is valid). Parsing it here, or
     * on iOS before the call, printed "(0)" for any build it could not parse.
     */
    fun version(name: String, code: String): String = "Version $name ($code)"
}
