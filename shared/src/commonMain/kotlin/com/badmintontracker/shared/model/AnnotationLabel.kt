package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A label the signed-in user owns and may apply to annotations. */
@Serializable
data class AnnotationLabel(
    val id: String,
    val name: String,
    @SerialName("color_key")  val colorKey: String,
    @SerialName("created_at") val createdAt: Instant,
) {
    /** Null when the stored key is not in this build's palette. */
    val color: LabelColor? get() = LabelColor.from(colorKey)
}
