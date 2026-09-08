@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package com.badmintontracker.analysis.geometry

import kotlin.native.ObjCName

data class Point(val x: Double, val y: Double)

/** BWF court dimensions in metres. Mirrors COURT_DIMENSIONS and speed_calc.py. */
object Court {
    const val LENGTH: Double = 13.4
    const val WIDTH_DOUBLES: Double = 6.1
    const val WIDTH_SINGLES: Double = 5.18
    const val SERVICE_LINE: Double = 1.98
}

/**
 * The 12 manually-placed court keypoints, in video pixels.
 *
 * Field names match the persisted `videos.manual_court_keypoints` shape
 * exactly, so there is no remapping between storage and use.
 */
/**
 * Named apart from `com.badmintontracker.shared.model.CourtKeypoints` for
 * Objective-C only, because both modules land in one `Shared` framework for iOS
 * and two classes of the same name collide there. Without this the compiler
 * renames one of them to `CourtKeypoints_`, silently and on whichever it
 * reaches second, and Swift call sites start binding to the other type.
 *
 * The name matches what the framework header carried before `:analysis` was
 * exported, so no Swift moves. The two are not duplicates: this one is the
 * compute type with `Point` corners, the shared one is the wire type with
 * `[Float]` pairs and the `videos.manual_court_keypoints` serial names, and
 * `toAnalysis()` is the single conversion between them.
 */
@ObjCName("AnalysisCourtKeypoints")
data class CourtKeypoints(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
    val netLeft: Point,
    val netRight: Point,
    val serviceLineNearLeft: Point,
    val serviceLineNearRight: Point,
    val serviceLineFarLeft: Point,
    val serviceLineFarRight: Point,
    val centerNear: Point,
    val centerFar: Point,
) {
    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        private val ORDER = listOf(
            "top_left", "top_right", "bottom_right", "bottom_left",
            "net_left", "net_right",
            "service_line_near_left", "service_line_near_right",
            "service_line_far_left", "service_line_far_right",
            "center_near", "center_far",
        )

        /** Returns null when any of the 12 keys is absent or malformed. */
        fun fromMap(raw: Map<String, List<Double>>): CourtKeypoints? {
            val pts = ORDER.map { key ->
                val v = raw[key] ?: return null
                if (v.size < 2) return null
                Point(v[0], v[1])
            }
            return CourtKeypoints(
                pts[0], pts[1], pts[2], pts[3], pts[4], pts[5],
                pts[6], pts[7], pts[8], pts[9], pts[10], pts[11],
            )
        }
    }
}

/** Court-plane positions of the 12 keypoints, in metres, in the same order. */
val COURT_KEYPOINT_POSITIONS: List<Point> = listOf(
    Point(0.0, 0.0),
    Point(Court.WIDTH_DOUBLES, 0.0),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH),
    Point(0.0, Court.LENGTH),
    Point(0.0, Court.LENGTH / 2),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2),
    Point(0.0, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(0.0, Court.LENGTH / 2 + Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2 + Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES / 2, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES / 2, Court.LENGTH / 2 + Court.SERVICE_LINE),
)
