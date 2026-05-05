package com.badmintontracker.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AnnotationKind {
    @SerialName("good_shot")      GOOD_SHOT,
    @SerialName("forced_error")   FORCED_ERROR,
    @SerialName("unforced_error") UNFORCED_ERROR,
}
