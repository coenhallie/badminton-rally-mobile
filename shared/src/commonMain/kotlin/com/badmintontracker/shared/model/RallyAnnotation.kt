package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RallyAnnotation(
    val id: String,
    @SerialName("clip_id")             val clipId: String,
    @SerialName("timestamp_seconds")   val timestampSeconds: Float,
    val body: String,
    @SerialName("created_at")          val createdAt: Instant,
)
