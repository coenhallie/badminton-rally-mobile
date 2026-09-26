package com.badmintontracker.shared.model

import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.playersLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of list_match_metadata(): everything a video-born match knows about
 * itself beyond its clips. The players and the format are the same optional
 * details a score log carries, kept on the video because a match that was only
 * ever filmed has no score log to keep them on. Defaulted so a row from before
 * those columns existed still decodes.
 */
@Serializable
data class MatchMetadata(
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val description: String? = null,
    @SerialName("home_players") val homePlayers: List<String> = emptyList(),
    @SerialName("away_players") val awayPlayers: List<String> = emptyList(),
    val format: MatchFormat? = null,
)

/** "Coen vs Marco", or null while neither side has been named. Same rule as a score log's. */
fun MatchMetadata.playersLine(): String? = playersLine(homePlayers, awayPlayers)
