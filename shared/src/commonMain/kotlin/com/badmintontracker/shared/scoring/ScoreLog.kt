package com.badmintontracker.shared.scoring

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a match sits between being scored and being mapped onto a video. */
@Serializable
enum class ScoreLogStatus {
    /** Being scored right now. */
    @SerialName("live") LIVE,

    /** Finished, no video attached. A perfectly good place for a match to end. */
    @SerialName("unbound") UNBOUND,

    /** Mapped onto a video's rally clips. Not reachable until L2. */
    @SerialName("bound") BOUND,

    /** A human has confirmed or corrected that mapping. Not reachable until L2. */
    @SerialName("reconciled") RECONCILED,
}

/**
 * One match: who played, under what rules, and every point as it was scored.
 *
 * The score is not stored. [state] folds it on demand, so a match read back out of
 * the database and the same match still being scored cannot disagree about what
 * the score is - there is still exactly one, and it is derived.
 */
@Serializable
data class ScoreLog(
    val id: String,
    /** Null until L2 attaches a video, and null again if that video is deleted. */
    @SerialName("video_id")     val videoId: String?,
    val title: String,
    /** One name for singles, two for doubles. Index 0 starts in the right service court. */
    @SerialName("home_players") val homePlayers: List<String>,
    @SerialName("away_players") val awayPlayers: List<String>,
    val rules: ScoringRules,
    val setup: MatchSetup,
    val events: List<ScoreEvent>,
    val status: ScoreLogStatus,
    @SerialName("created_at")   val createdAt: Instant,
    @SerialName("updated_at")   val updatedAt: Instant,
) {
    fun state(): MatchState = foldMatchState(rules, setup, events)
}

const val MAX_MATCH_TITLE = 80
const val MAX_PLAYER_NAME = 40

/**
 * The one place a new match's own rules are spelled out, so the Android form, the
 * iOS form and the database CHECK cannot drift into three different opinions about
 * what is complete. Returns a ready to display sentence, or null when the match can
 * be created.
 */
fun newMatchProblem(
    title: String,
    homePlayers: List<String>,
    awayPlayers: List<String>,
    doubles: Boolean,
): String? {
    val named = { side: List<String> -> side.map { it.trim() }.filter { it.isNotEmpty() } }
    val home = named(homePlayers)
    val away = named(awayPlayers)
    val expected = if (doubles) 2 else 1
    return when {
        title.trim().isEmpty() -> "Give the match a name."
        title.trim().length > MAX_MATCH_TITLE -> "The match name can be up to $MAX_MATCH_TITLE characters."
        (home + away).any { it.length > MAX_PLAYER_NAME } ->
            "A player name can be up to $MAX_PLAYER_NAME characters."
        home.isEmpty() || away.isEmpty() -> "Name both players."
        doubles && (home.size != expected || away.size != expected) ->
            "Doubles needs two players on each side."
        !doubles && (home.size != expected || away.size != expected) ->
            "Singles has one player on each side."
        else -> null
    }
}
