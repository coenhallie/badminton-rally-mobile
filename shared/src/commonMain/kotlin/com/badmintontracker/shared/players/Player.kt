package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.PLAYER_NAME_TOO_LONG
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the coach's players: a row of `players`, owned by whoever is signed in.
 * [racketArm] null means the coach has not said.
 */
@Serializable
data class Player(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("racket_arm") val racketArm: RacketArm? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

/**
 * The problem with [name] as a player's name, as a sentence ready to show, or
 * null when it can be saved. Judged on the trimmed name, as the database CHECK is.
 */
fun playerNameProblem(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> PlayersCopy.NAME_REQUIRED
        trimmed.length > MAX_PLAYER_NAME -> PLAYER_NAME_TOO_LONG
        else -> null
    }
}

private val WHITESPACE = Regex("\\s+")

/** Generational suffixes, compared without case or a trailing "." or ",". */
private val NAME_SUFFIXES = setOf("jr", "junior", "sr", "senior", "ii", "iii", "iv")

private fun isNameSuffix(word: String): Boolean = word.trimEnd('.', ',').lowercase() in NAME_SUFFIXES

/**
 * The avatar's letters: the first word's first letter and, when there are two or
 * more words, the last word's first letter. Empty for a blank name.
 *
 * A trailing suffix ("Jr.", "Junior", "II") is not the surname, so it is passed
 * over while more than two words remain: "Ken Griffey Jr." is KG. Two words are a
 * first name and a surname whatever they are, so "Marco Junior" stays MJ.
 */
fun initials(name: String): String {
    val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()
    while (words.size > 2 && isNameSuffix(words.last())) words.removeAt(words.lastIndex)
    return when (words.size) {
        0 -> ""
        1 -> words.first().take(1).uppercase()
        else -> words.first().take(1).uppercase() + words.last().take(1).uppercase()
    }
}

/** The order every list of players is shown in: by name ignoring case, then id so ties never shuffle. */
fun sortedPlayers(players: List<Player>): List<Player> =
    players.sortedWith(compareBy<Player> { it.name.lowercase() }.thenBy { it.id })
