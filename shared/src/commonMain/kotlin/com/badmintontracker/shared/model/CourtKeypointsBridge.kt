package com.badmintontracker.shared.model

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.CourtKeypoints as AnalysisCourtKeypoints

/**
 * The wire keypoints as the compute keypoints.
 *
 * Two types with the same twelve field names exist on purpose. This one is a
 * JSON contract: `@Serializable`, `List<Float>` per point, field-for-field
 * identical to `videos.manual_court_keypoints`. The `:analysis` one is
 * arithmetic: a `Point` per field, no serialization, usable without knowing
 * where the numbers came from. Collapsing them would put a wire format in the
 * middle of the geometry or `kotlinx.serialization` in a module that is
 * meant to be pure computation.
 *
 * Deliberately not routed through `CourtKeypoints.fromMap`. That exists for
 * untrusted JSON where a key can be missing, so it returns null; these twelve
 * fields are non-nullable, so this conversion cannot fail, and going through a
 * nullable API would force a meaningless `!!` at every call site.
 */
fun CourtKeypoints.toAnalysis(): AnalysisCourtKeypoints = AnalysisCourtKeypoints(
    topLeft = topLeft.toPoint(),
    topRight = topRight.toPoint(),
    bottomRight = bottomRight.toPoint(),
    bottomLeft = bottomLeft.toPoint(),
    netLeft = netLeft.toPoint(),
    netRight = netRight.toPoint(),
    serviceLineNearLeft = serviceLineNearLeft.toPoint(),
    serviceLineNearRight = serviceLineNearRight.toPoint(),
    serviceLineFarLeft = serviceLineFarLeft.toPoint(),
    serviceLineFarRight = serviceLineFarRight.toPoint(),
    centerNear = centerNear.toPoint(),
    centerFar = centerFar.toPoint(),
)

private fun List<Float>.toPoint(): Point = Point(this[0].toDouble(), this[1].toDouble())
