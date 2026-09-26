package com.badmintontracker.shared.home

/**
 * Every string the tab host, the Settings tab and the empty Matches tab draw.
 * One place for both clients, the way MatchListCopy holds the list's.
 */
object HomeCopy {
    const val TAB_MATCHES = "Matches"
    const val TAB_ANALYTICS = "Analytics"
    const val TAB_SETTINGS = "Settings"

    /**
     * Not "New match", which the mock drew: the sheet this opens lists "New
     * match" as the first of its three options, and a button named after one
     * of them reads as a shortcut to it.
     */
    const val ADD_MATCH = "Add match"

    /** Under the hero on an empty Matches tab. Names what the Add button does. */
    const val EMPTY_BODY =
        "Score a match courtside, or record or import a video to cut it into rallies. Your matches will show up here."

    const val ANALYTICS_EMPTY_TITLE = "No matches yet"
    const val ANALYTICS_EMPTY_BODY = "Add a match on the Matches tab and it will be listed here, ready to analyze."

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
