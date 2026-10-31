package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.PLAYER_NAME_TOO_LONG

/**
 * Every sentence the player profiles feature shows, so the Android and iOS
 * screens cannot drift apart. Names are by where a sentence is used; a word two
 * places share (Near, Far, Add player) is spelled once and aliased.
 */
object PlayersCopy {

    // Players screen
    const val SCREEN_TITLE = "Players"
    const val ADD_PLAYER = "Add player"
    const val ALL_MATCHES = "All matches"
    const val EMPTY_TITLE = "Add your first player"
    const val EMPTY_BODY =
        "Each player gets a page with their matches. Matches you already have stay in All matches."
    const val LOAD_FAILED = "Couldn't load your players"

    // Player rows
    const val NO_MATCHES_YET = "No matches yet"

    /**
     * "12 matches · last Oct 28". [lastMatchDate] is already formatted by the caller;
     * a player with matches always has one, but a missing date just drops the tail.
     */
    fun rowDetail(matchCount: Int, lastMatchDate: String?): String {
        if (matchCount <= 0) return NO_MATCHES_YET
        val count = "$matchCount ${if (matchCount == 1) "match" else "matches"}"
        return if (lastMatchDate == null) count else "$count · last $lastMatchDate"
    }

    // Player page
    const val TAB_PROFILE = "Profile"
    const val TAB_MATCHES = "Matches"
    const val TILE_MATCHES = "Matches"
    const val TILE_RALLIES = "Rallies"
    const val TILE_LAST_MATCH = "Last match"
    const val LAST_MATCH_NONE = "None yet"
    const val MENU_RENAME = "Rename"
    const val MENU_PLAYING_HAND = "Playing hand"
    const val MENU_DELETE = "Delete player"
    const val HAND_RIGHT = "Right"
    const val HAND_LEFT = "Left"
    const val HAND_NOT_SET = "Not set"

    /** The profile's hand line; null when the hand is not set, so the line is absent. */
    fun handLine(racketArm: RacketArm?): String? = when (racketArm) {
        RacketArm.RIGHT -> "Right-handed"
        RacketArm.LEFT -> "Left-handed"
        null -> null
    }

    /** The label of a playing-hand option; null is the "Not set" option. */
    fun handOption(racketArm: RacketArm?): String = when (racketArm) {
        RacketArm.RIGHT -> HAND_RIGHT
        RacketArm.LEFT -> HAND_LEFT
        null -> HAND_NOT_SET
    }

    // Add / rename dialog
    const val ADD_DIALOG_TITLE = ADD_PLAYER
    const val RENAME_DIALOG_TITLE = "Rename player"
    const val NAME_FIELD = "Name"
    const val SAVE = "Save"
    const val NAME_REQUIRED = "Enter a name."

    // Delete
    const val DELETE_CONFIRM = "Delete"

    fun deleteTitle(name: String): String = "Delete $name?"

    fun deleteBody(matchCount: Int): String = when {
        matchCount <= 0 -> "No matches are assigned to this player."
        matchCount == 1 -> "1 match stays in All matches, with the name still on it."
        else -> "$matchCount matches stay in All matches, with the name still on them."
    }

    // Failures

    /**
     * What to show for a failed save, delete or load: the repository's own name
     * validation sentence when that is what failed, and [fallback] for everything
     * else (a server error, no connection, a decode failure), so neither platform
     * can show raw "HTTP ..." text. Matched on the sentences rather than on
     * IllegalArgumentException, because a serialization failure is one too.
     */
    fun failureMessage(error: Throwable, fallback: String): String =
        error.message?.takeIf { it == NAME_REQUIRED || it == PLAYER_NAME_TOO_LONG } ?: fallback

    const val SAVE_FAILED = "Couldn't save the player. Check your connection and try again."
    const val DELETE_FAILED = "Couldn't delete the player. Check your connection and try again."
    const val WHOS_PLAYING_SAVE_FAILED = "Couldn't save who's playing. Check your connection and try again."

    // Which end of the court: the analysis toggle, the ends row, the attach question, the swap
    const val NEAR = "Near"
    const val FAR = "Far"
    const val NEAR_FAR_NOT_SET = HAND_NOT_SET

    // Who's playing (the match page)
    const val WHOS_PLAYING_TITLE = "Who's playing"
    const val ONE_PLAYER = "One player"
    const val BOTH_PLAYERS = "Both players"
    const val CHOOSE_PLAYER = "Choose a player"
    const val PICKER_ADD_PLAYER = ADD_PLAYER
    const val OPPONENT_FIELD = "Opponent (optional)"
    const val SWAP_ENDS = "Swap ends"
    const val DEVICE_ANALYSIS_LINE =
        "Analysis on this phone follows only the near player. Cloud analysis follows both."

    // Details editor (score log)
    const val SIDE_HOME = "Home"
    const val SIDE_AWAY = "Away"
    const val SOMEONE_ELSE = "Someone else"
    const val EDITOR_ADD_PLAYER = ADD_PLAYER
    const val HOME_WAS_AT_THE = "Home was at the"
    const val DOUBLES_NOTE = "Remove the players to switch to doubles."

    /** Nets for states the editors should not let happen. */
    const val SAME_PLAYER_BOTH_SIDES = "A player can't be on both sides."

    /** A side holds a profile or typed names, never both. */
    const val PROFILE_AND_NAMES = "A side can have a player or typed names, not both."

    // Attach question
    const val ATTACH_SUBTITLE = "As the camera sees it."
    const val ATTACH_SKIP = "Skip"

    /** [home] is the Home side's display name. */
    fun attachTitle(home: String): String = "Which end was $home on?"

    // Analysis toggle
    /** What a side with no name is called on the toggle. */
    const val OPPONENT = "Opponent"

    // Upload in progress
    const val UPLOAD_IN_PROGRESS = "Who's playing can be changed once the upload finishes."
}
