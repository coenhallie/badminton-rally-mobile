package com.badmintontracker.shared.home

/**
 * The match list's title, its Add button and empty state, and the Settings
 * page's contents. One place for both clients, the way MatchListCopy holds the
 * list's rows. The Settings title and the gear's label are
 * `MatchPageCopy.SETTINGS`.
 */
object HomeCopy {
    /** The match list's title. */
    const val MATCHES_TITLE = "Matches"

    /**
     * Not "New match", which the mock drew: the sheet this opens lists "New
     * match" as the first of its three options, and a button named after one
     * of them reads as a shortcut to it.
     */
    const val ADD_MATCH = "Add match"

    /**
     * The Add match sheet's three ways in, each with the one line that tells
     * them apart: "New match" alone did not say that it is the one without a
     * video.
     */
    const val ADD_NEW_MATCH = "New match"
    const val ADD_NEW_MATCH_DETAIL = "Score it courtside, point by point"
    const val ADD_RECORD_VIDEO = "Record video"
    const val ADD_RECORD_VIDEO_DETAIL = "Film it, then cut it into rallies"
    const val ADD_IMPORT_VIDEO = "Import video"
    const val ADD_IMPORT_VIDEO_DETAIL = "Use a video already on this phone"

    /** Under the hero on an empty match list: how matches get onto it (the Add button on the same screen). */
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
