package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What kind of match it was. Information about the match and nothing more: the fold
 * never reads it, and choosing doubles does not change what the board draws. It is
 * nullable on [ScoreLog] because the new match form does not ask, and "nobody said"
 * is a different fact from "singles".
 */
@Serializable
enum class MatchFormat(
    val label: String,
    /** For the editor's picker, where four choices share one row. */
    val shortLabel: String,
    val playersPerSide: Int,
) {
    @SerialName("singles")       SINGLES("Singles", "Singles", playersPerSide = 1),
    @SerialName("doubles")       DOUBLES("Doubles", "Doubles", playersPerSide = 2),
    @SerialName("mixed_doubles") MIXED_DOUBLES("Mixed doubles", "Mixed", playersPerSide = 2),
}

const val MAX_PLAYER_NAME = 40

/** How many names a side can carry under [format]. One when nobody has said what the format is. */
fun playersPerSide(format: MatchFormat?): Int = format?.playersPerSide ?: 1

/**
 * The one place the details editor's rules are spelled out, so the Android sheet,
 * the iOS sheet and the database CHECK cannot drift apart. Everything but the name
 * is optional: a match is complete the moment it is created, and these are things a
 * coach adds when there is time. Returns a ready to display sentence, or null when
 * the details can be saved.
 *
 * [titleCap] is the record's own: a score log takes [MAX_MATCH_TITLE], a video the
 * shorter cap the web app types against (see `VIDEO_MATCH_TITLE_CAP`). Two
 * overloads rather than a default, because Swift sees no defaults.
 */
fun matchDetailsProblem(title: String, homePlayers: List<String>, awayPlayers: List<String>): String? =
    matchDetailsProblem(title, homePlayers, awayPlayers, MAX_MATCH_TITLE)

fun matchDetailsProblem(
    title: String,
    homePlayers: List<String>,
    awayPlayers: List<String>,
    titleCap: Int,
): String? =
    createMatchProblem(title, titleCap) ?: when {
        (homePlayers + awayPlayers).any { it.trim().length > MAX_PLAYER_NAME } ->
            "A player name can be up to $MAX_PLAYER_NAME characters."
        else -> null
    }

/**
 * The names a side is saved with: trimmed, blanks dropped, and no more of them than
 * [format] has players. The editor keeps a typed partner on screen while the format
 * is switched back and forth; this is where a partner in a singles match stops.
 */
fun submittedPlayers(typed: List<String>, format: MatchFormat?): List<String> =
    typed.take(playersPerSide(format)).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * What [side] is called wherever it is named: its players when the match has any,
 * and where it stands when it does not. One function so the board, the match page,
 * the list and the shared text cannot disagree about who won.
 */
fun sideName(homePlayers: List<String>, awayPlayers: List<String>, side: Side): String {
    val players = if (side == Side.HOME) homePlayers else awayPlayers
    return if (players.isEmpty()) side.label else players.joinToString(" / ")
}

fun ScoreLog.sideName(side: Side): String = sideName(homePlayers, awayPlayers, side)

/**
 * "Coen vs Marco", or null while neither side has been named: "Home vs Away" says
 * nothing on a list row. Takes the two lists rather than a record, because a score
 * log and a video-born match's metadata both carry them and must read the same.
 */
fun playersLine(homePlayers: List<String>, awayPlayers: List<String>): String? =
    if (homePlayers.isEmpty() && awayPlayers.isEmpty()) null
    else "${sideName(homePlayers, awayPlayers, Side.HOME)} vs ${sideName(homePlayers, awayPlayers, Side.AWAY)}"

fun ScoreLog.playersLine(): String? = playersLine(homePlayers, awayPlayers)

/**
 * The one line a match page prints about who played and how: "Coen vs Marco ·
 * Doubles", either half alone, or null when nobody has said anything, so the page
 * does not reserve a row for nothing.
 */
fun matchDetailsLine(playersLine: String?, format: MatchFormat?): String? =
    listOfNotNull(playersLine, format?.label).takeIf { it.isNotEmpty() }?.joinToString(" · ")
