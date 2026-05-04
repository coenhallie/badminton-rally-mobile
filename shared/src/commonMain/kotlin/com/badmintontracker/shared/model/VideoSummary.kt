package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoSummary(
    val id: String,
    val filename: String,
    @SerialName("created_at") val createdAt: Instant,
)
