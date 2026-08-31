package com.badmintontracker.analysis.geometry

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
