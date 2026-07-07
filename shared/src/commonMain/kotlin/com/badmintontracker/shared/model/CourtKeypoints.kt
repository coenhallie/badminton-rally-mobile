package com.badmintontracker.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 12-point manual court calibration, field-for-field identical to the JSON the
 * desktop app (badminton-tracker CourtSetup.vue) writes to
 * videos.manual_court_keypoints. Coordinates are [x, y] in source-video pixels.
 */
@Serializable
data class CourtKeypoints(
    @SerialName("top_left")                val topLeft: List<Float>,
    @SerialName("top_right")               val topRight: List<Float>,
    @SerialName("bottom_right")            val bottomRight: List<Float>,
    @SerialName("bottom_left")             val bottomLeft: List<Float>,
    @SerialName("net_left")                val netLeft: List<Float>,
    @SerialName("net_right")               val netRight: List<Float>,
    @SerialName("service_line_near_left")  val serviceLineNearLeft: List<Float>,
    @SerialName("service_line_near_right") val serviceLineNearRight: List<Float>,
    @SerialName("service_line_far_left")   val serviceLineFarLeft: List<Float>,
    @SerialName("service_line_far_right")  val serviceLineFarRight: List<Float>,
    @SerialName("center_near")             val centerNear: List<Float>,
    @SerialName("center_far")              val centerFar: List<Float>,
)
