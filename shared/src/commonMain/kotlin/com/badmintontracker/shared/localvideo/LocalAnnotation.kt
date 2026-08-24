package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.LabelColor
import kotlinx.serialization.Serializable

/** A timestamped note on a local (on-phone) video. Stored on-device only. */
@Serializable
data class LocalAnnotation(
    val id: String,
    val timestampSeconds: Float,
    val body: String,                 // may be blank when only a label is set
    val labelName: String? = null,
    val labelColor: String? = null,
    val createdAtEpochMs: Long,
) {
    val color: LabelColor? get() = LabelColor.from(labelColor)
}
