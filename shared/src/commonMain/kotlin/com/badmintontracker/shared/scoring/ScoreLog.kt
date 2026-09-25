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
 * One match: what it was called, under what rules, and every point as it was scored.
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
    /**
     * Optional, and never asked for when the match is created: they are filled in
     * from the details editor. One name for singles, up to two for doubles.
     * Defaulted so a row or a cache written without them still decodes.
     */
    @SerialName("home_players") val homePlayers: List<String> = emptyList(),
    @SerialName("away_players") val awayPlayers: List<String> = emptyList(),
    /** Null until someone says. See [MatchFormat]. */
    val format: MatchFormat? = null,
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

/**
 * The one place a new match's own rules are spelled out, so the Android form, the
 * iOS form and the database CHECK cannot drift into three different opinions about
 * what is complete. Returns a ready to display sentence, or null when the match can
 * be created.
 */
fun createMatchProblem(title: String): String? = createMatchProblem(title, MAX_MATCH_TITLE)

/** [createMatchProblem] against a record's own cap. An overload, not a default: Swift sees no defaults. */
fun createMatchProblem(title: String, cap: Int): String? = when {
    title.trim().isEmpty() -> "Give the match a name."
    title.trim().length > cap -> "The match name can be up to $cap characters."
    else -> null
}
