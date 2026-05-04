package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RallyClip(
    val id: String,
    @SerialName("video_id")               val videoId: String,
    @SerialName("rally_index")            val rallyIndex: Int,
    @SerialName("start_timestamp")        val startTimestamp: Float,
    @SerialName("end_timestamp")          val endTimestamp: Float,
    @SerialName("duration_seconds")       val durationSeconds: Float,
    @SerialName("clip_storage_path")      val clipStoragePath: String,
    @SerialName("thumbnail_storage_path") val thumbnailStoragePath: String? = null,
    val title: String? = null,
    @SerialName("annotation_count")       val annotationCount: Int,
    @SerialName("created_at")             val createdAt: Instant,
)
