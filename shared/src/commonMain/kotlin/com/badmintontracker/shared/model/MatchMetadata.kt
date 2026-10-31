package com.badmintontracker.shared.model

import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.Side
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
    @SerialName("home_player_id") val homePlayerId: String? = null,
    @SerialName("away_player_id") val awayPlayerId: String? = null,
    /** Which end of the court the home side was at, as the camera sees it. Null until said. */
    @SerialName("near_side") val nearSide: Side? = null,
    /**
     * What each side is called, resolved by the RPC: the profile's name when a
     * side holds one (a share recipient cannot read `players`), otherwise the
     * typed names. Display only; [homePlayers] and [awayPlayers] are the record.
     */
    @SerialName("home_names") val homeNames: List<String> = emptyList(),
    @SerialName("away_names") val awayNames: List<String> = emptyList(),
)
