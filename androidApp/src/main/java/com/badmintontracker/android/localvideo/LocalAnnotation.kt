package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.serialization.Serializable

/** A timestamped note on a local (on-phone) video. Stored on-device only. */
@Serializable
data class LocalAnnotation(
    val id: String,
    val timestampSeconds: Float,
    val body: String,                 // may be blank when only a kind is set
    val kind: AnnotationKind? = null,
    val createdAtEpochMs: Long,
)
