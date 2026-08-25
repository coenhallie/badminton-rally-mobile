package com.badmintontracker.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of list_match_metadata(): match-level title and description. */
@Serializable
data class MatchMetadata(
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val description: String? = null,
)
