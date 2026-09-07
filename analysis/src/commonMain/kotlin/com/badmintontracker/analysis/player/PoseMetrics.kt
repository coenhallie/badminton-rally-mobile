package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

enum class Side { LEFT, RIGHT }

/**
 * What one frame of the near player's joints measures, or does not.
 *
 * Every field is nullable and null means "not measurable in this frame": a
 * joint under the confidence threshold, no court homography, ankles that do
 * not project. A number is never estimated from a joint the model was unsure
 * of, because a coach reading "174°" cannot tell a guess from a measurement.
 *
 * The court-plane fields are in metres and rest on the same footing as the
 * heatmap (ankles on the floor, the homography maps the floor). The angles are
 * image-plane angles: the true angle projected onto the camera, exact when
 * the joint's plane faces the camera and increasingly wrong as it turns
 * edge-on. See the 2026-09-07 research, §3 and §4.
 */
data class PoseMetrics(
    /** Distance between the two ankles on the court, metres. */
    val stanceM: Double?,
    /**
     * Ankle midpoint's distance behind the near service line, metres, positive
     * toward the baseline. Null on the far half: it is the near player's number.
     */
    val behindServiceLineM: Double?,
    /** Ankle midpoint's signed distance from the centre line, metres, positive to the frame's right. */
    val fromCentreLineM: Double?,
    /** Angle at the elbow between shoulder and wrist; 180 is a straight arm. */
    val elbowLeftDeg: Double?,
    val elbowRightDeg: Double?,
    /** Angle at the shoulder between elbow and hip; 0 is an arm hanging along the torso. */
    val armLeftDeg: Double?,
    val armRightDeg: Double?,
    /** Angle at the knee between hip and ankle; 180 is a straight leg. */
    val kneeLeftDeg: Double?,
    val kneeRightDeg: Double?,
    /** Hip midpoint to shoulder midpoint, degrees from vertical, positive toward the frame's right. */
    val trunkLeanDeg: Double?,
) {
    companion object {
        val NONE = PoseMetrics(null, null, null, null, null, null, null, null, null, null)
    }
}

/**
 * The measurements a coach can pick, one per tile.
 *
 * [range] is the graph's fixed vertical extent, fixed so a curve keeps its
 * shape from frame to frame instead of rescaling under the eye. [angleJoints]
 * is (a, vertex, c) for the overlay's arc, null for the kinds with no arc.
 */
enum class MetricKind(
    val side: Side?,
    val range: ClosedFloatingPointRange<Double>,
    val angleJoints: Triple<Int, Int, Int>?,
) {
    STANCE(null, 0.0..2.0, null),
    BEHIND_LINE(null, -1.0..6.0, null),
    ELBOW_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST)),
    ELBOW_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_SHOULDER, Coco.RIGHT_ELBOW, Coco.RIGHT_WRIST)),
    ARM_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_ELBOW, Coco.LEFT_SHOULDER, Coco.LEFT_HIP)),
    ARM_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP)),
    KNEE_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_HIP, Coco.LEFT_KNEE, Coco.LEFT_ANKLE)),
    KNEE_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE)),
    LEAN(null, -45.0..45.0, null),
    ;

    /** Angles, including the lean; the rest are metres. */
    val isAngle: Boolean get() = this != STANCE && this != BEHIND_LINE

    /** Whether this kind needs the court homography. */
    val needsCourt: Boolean get() = this == STANCE || this == BEHIND_LINE

    fun of(m: PoseMetrics): Double? = when (this) {
        STANCE -> m.stanceM
        BEHIND_LINE -> m.behindServiceLineM
        ELBOW_LEFT -> m.elbowLeftDeg
        ELBOW_RIGHT -> m.elbowRightDeg
        ARM_LEFT -> m.armLeftDeg
        ARM_RIGHT -> m.armRightDeg
        KNEE_LEFT -> m.kneeLeftDeg
        KNEE_RIGHT -> m.kneeRightDeg
        LEAN -> m.trunkLeanDeg
    }
}

/** The image-plane angle at [vertex] between [a] and [c], degrees in 0..180; null when a limb has no length. */
fun jointAngleDeg(a: Point, vertex: Point, c: Point): Double? {
    val ux = a.x - vertex.x
    val uy = a.y - vertex.y
    val vx = c.x - vertex.x
    val vy = c.y - vertex.y
    val lu = hypot(ux, uy)
    val lv = hypot(vx, vy)
    if (lu == 0.0 || lv == 0.0) return null
    val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
    return acos(cos) * 180.0 / PI
}

/**
 * Measures one frame. [homography] is the resolved court fit
 * (`CourtKeypoints.homography()`), or null when the court is not known, in
 * which case only the angles are produced.
 */
fun poseMetrics(
    keypoints: List<Point>,
    confidence: List<Float>,
    homography: Matrix3x3?,
    minConfidence: Float = NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE,
): PoseMetrics {
    if (keypoints.size < Coco.COUNT || confidence.size < Coco.COUNT) return PoseMetrics.NONE
    fun ok(vararg idx: Int) = idx.all { confidence[it] >= minConfidence }
    fun angle(a: Int, vertex: Int, c: Int): Double? =
        if (ok(a, vertex, c)) jointAngleDeg(keypoints[a], keypoints[vertex], keypoints[c]) else null

    var stance: Double? = null
    var behind: Double? = null
    var fromCentre: Double? = null
    if (homography != null && ok(Coco.LEFT_ANKLE, Coco.RIGHT_ANKLE)) {
        val l = keypoints[Coco.LEFT_ANKLE].let { homography.apply(it.x, it.y) }
        val r = keypoints[Coco.RIGHT_ANKLE].let { homography.apply(it.x, it.y) }
        if (l != null && r != null) {
            stance = hypot(l.x - r.x, l.y - r.y)
            val midX = (l.x + r.x) / 2.0
            val midY = (l.y + r.y) / 2.0
            if (midY > Court.LENGTH / 2.0) {
                behind = midY - (Court.LENGTH / 2.0 + Court.SERVICE_LINE)
                fromCentre = midX - Court.WIDTH_DOUBLES / 2.0
            }
        }
    }

    var lean: Double? = null
    if (ok(Coco.LEFT_SHOULDER, Coco.RIGHT_SHOULDER, Coco.LEFT_HIP, Coco.RIGHT_HIP)) {
        val sx = (keypoints[Coco.LEFT_SHOULDER].x + keypoints[Coco.RIGHT_SHOULDER].x) / 2.0
        val sy = (keypoints[Coco.LEFT_SHOULDER].y + keypoints[Coco.RIGHT_SHOULDER].y) / 2.0
        val hx = (keypoints[Coco.LEFT_HIP].x + keypoints[Coco.RIGHT_HIP].x) / 2.0
        val hy = (keypoints[Coco.LEFT_HIP].y + keypoints[Coco.RIGHT_HIP].y) / 2.0
        // Image y grows downward, so "up" is hy - sy.
        if (sx != hx || sy != hy) lean = atan2(sx - hx, hy - sy) * 180.0 / PI
    }

    return PoseMetrics(
        stanceM = stance,
        behindServiceLineM = behind,
        fromCentreLineM = fromCentre,
        elbowLeftDeg = angle(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST),
        elbowRightDeg = angle(Coco.RIGHT_SHOULDER, Coco.RIGHT_ELBOW, Coco.RIGHT_WRIST),
        armLeftDeg = angle(Coco.LEFT_ELBOW, Coco.LEFT_SHOULDER, Coco.LEFT_HIP),
        armRightDeg = angle(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP),
        kneeLeftDeg = angle(Coco.LEFT_HIP, Coco.LEFT_KNEE, Coco.LEFT_ANKLE),
        kneeRightDeg = angle(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE),
        trunkLeanDeg = lean,
    )
}
