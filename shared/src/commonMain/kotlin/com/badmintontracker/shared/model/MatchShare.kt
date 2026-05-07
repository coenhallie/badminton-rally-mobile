package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchShare(
    @SerialName("shared_with_user_id") val sharedWithUserId: String,
    val email: String,
    @SerialName("created_at")          val createdAt: Instant,
)
