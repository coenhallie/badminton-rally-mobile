package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RallyAnnotation(
    val id: String,
    @SerialName("clip_id")           val clipId: String,
    @SerialName("timestamp_seconds") val timestampSeconds: Float,
    val body: String,
    /**
     * The label's name and colour as they were when the annotation was made.
     * Snapshotted rather than referenced so a share recipient needs no read on
     * the sharer's labels, and deleting a label cannot orphan an annotation.
     */
    @SerialName("label_name")        val labelName: String? = null,
    @SerialName("label_color")       val labelColor: String? = null,
    @SerialName("created_at")        val createdAt: Instant,
) {
    val color: LabelColor? get() = LabelColor.from(labelColor)
}
