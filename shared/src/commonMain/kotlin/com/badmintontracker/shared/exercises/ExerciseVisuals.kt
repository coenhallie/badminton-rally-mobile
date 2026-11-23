package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.geometry.Court
import kotlin.math.floor

/** Ground baseline inside the normalized [0, 1] pose canvas. */
const val POSE_GROUND_Y: Float = 0.88f

/** 2D point in either normalized [0, 1] pose space or player-relative half-court metres. */
data class VisualPoint(val x: Float, val y: Float)

/**
 * 14-joint articulated figure in normalized [0, 1] coordinates (x left-to-right,
 * y top-to-bottom, ground at [POSE_GROUND_Y]) with an optional racket head tip
 * extending from [wristR] (mirrored across x = 0.5 for left-handed players).
 */
data class PoseFigure(
    val head: VisualPoint,
    val neck: VisualPoint,
    val shoulderL: VisualPoint,
    val shoulderR: VisualPoint,
    val elbowL: VisualPoint,
    val elbowR: VisualPoint,
    val wristL: VisualPoint,
    val wristR: VisualPoint,
    val hipL: VisualPoint,
    val hipR: VisualPoint,
    val kneeL: VisualPoint,
    val kneeR: VisualPoint,
    val ankleL: VisualPoint,
    val ankleR: VisualPoint,
    val racketTip: VisualPoint? = null,
) {
    fun allBodyPoints(): List<VisualPoint> = listOf(
        head, neck,
        shoulderL, shoulderR,
        elbowL, elbowR,
        wristL, wristR,
        hipL, hipR,
        kneeL, kneeR,
        ankleL, ankleR,
    )
}

/** One named pose milestone inside a [PoseSequence]. */
data class PoseKeyframe(
    val label: String,
    val figure: PoseFigure,
)

/** Looping pose animation defined by 2 to 4 keyframes over [cycleSeconds]. */
data class PoseSequence(
    val keyframes: List<PoseKeyframe>,
    val cycleSeconds: Double,
)

/** Interpolated pose ready for direct Canvas drawing on Android and iOS. */
data class SampledPose(
    val label: String,
    val figure: PoseFigure,
)

/**
 * Top-down player half-court movement pattern in player-relative metres
 * (`x` in `0..6.1`, `y` = depth from net in `0..6.7`, net at `y = 0`).
 */
data class CourtDrillPath(
    val baseMarker: VisualPoint,
    val waypoints: List<VisualPoint>,
    val shuttleStart: VisualPoint?,
    val shuttleEnd: VisualPoint?,
    val cycleSeconds: Double,
)

/** Complete visual definition for one exercise in [ExerciseCatalog]. */
data class ExerciseVisual(
    val exerciseId: String,
    val courtPath: CourtDrillPath?,
    val pose: PoseSequence,
)

/** Returns the visual definition for [exerciseId]. */
fun visualFor(exerciseId: String): ExerciseVisual =
    requireNotNull(visualIndex[exerciseId]) { "Unknown exercise visual id: $exerciseId" }

/**
 * Samples [sequence] at cyclic phase [phase01] (wrapped into `[0, 1)`),
 * smoothly interpolating between consecutive keyframes and mirroring across
 * `x = 0.5` when [mirrorX] is true (left-handed player).
 */
fun samplePose(sequence: PoseSequence, phase01: Float, mirrorX: Boolean): SampledPose {
    val frames = sequence.keyframes
    require(frames.isNotEmpty()) { "PoseSequence must contain at least one keyframe" }
    if (frames.size == 1) {
        val fig = if (mirrorX) mirrorFigure(frames[0].figure) else frames[0].figure
        return SampledPose(frames[0].label, fig)
    }
    val wrapped = wrapPhase(phase01)
    val scaled = wrapped * frames.size
    val idx = floor(scaled).toInt().coerceIn(0, frames.size - 1)
    val nextIdx = (idx + 1) % frames.size
    val localT = scaled - idx
    // Brief hold at the keyframe (first 25%) before easing cleanly to the next pose.
    val moveT = if (localT <= 0.25f) 0f else smoothstep((localT - 0.25f) / 0.75f)
    val blended = lerpFigure(frames[idx].figure, frames[nextIdx].figure, moveT)
    val label = if (localT < 0.70f) frames[idx].label else frames[nextIdx].label
    return SampledPose(
        label = label,
        figure = if (mirrorX) mirrorFigure(blended) else blended,
    )
}

/**
 * Samples the moving player dot along [path]'s closed waypoint loop at cyclic
 * phase [phase01], mirroring across the centre line when [mirrorX] is true.
 */
fun sampleCourtPoint(path: CourtDrillPath, phase01: Float, mirrorX: Boolean): VisualPoint {
    val pts = path.waypoints
    require(pts.isNotEmpty()) { "CourtDrillPath must contain at least one waypoint" }
    if (pts.size == 1) return maybeMirrorCourt(pts[0], mirrorX)
    val wrapped = wrapPhase(phase01)
    val segments = pts.size - 1
    val scaled = wrapped * segments
    val idx = floor(scaled).toInt().coerceIn(0, segments - 1)
    val t = smoothstep(scaled - idx)
    val raw = lerpPoint(pts[idx], pts[idx + 1], t)
    return maybeMirrorCourt(raw, mirrorX)
}

/** Mirrors a list of half-court points across the centre line when [mirrorX] is true. */
fun mirroredCourtPoints(points: List<VisualPoint>, mirrorX: Boolean): List<VisualPoint> =
    if (!mirrorX) points else points.map { maybeMirrorCourt(it, mirrorX = true) }

/** Mirrors a single half-court point across the centre line when [mirrorX] is true. */
fun mirroredCourtPoint(point: VisualPoint, mirrorX: Boolean): VisualPoint =
    maybeMirrorCourt(point, mirrorX)

private fun wrapPhase(phase: Float): Float {
    if (!phase.isFinite()) return 0f
    val mod = phase % 1f
    return if (mod < 0f) mod + 1f else mod
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpPoint(a: VisualPoint, b: VisualPoint, t: Float): VisualPoint =
    VisualPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun lerpFigure(a: PoseFigure, b: PoseFigure, t: Float): PoseFigure = PoseFigure(
    head = lerpPoint(a.head, b.head, t),
    neck = lerpPoint(a.neck, b.neck, t),
    shoulderL = lerpPoint(a.shoulderL, b.shoulderL, t),
    shoulderR = lerpPoint(a.shoulderR, b.shoulderR, t),
    elbowL = lerpPoint(a.elbowL, b.elbowL, t),
    elbowR = lerpPoint(a.elbowR, b.elbowR, t),
    wristL = lerpPoint(a.wristL, b.wristL, t),
    wristR = lerpPoint(a.wristR, b.wristR, t),
    hipL = lerpPoint(a.hipL, b.hipL, t),
    hipR = lerpPoint(a.hipR, b.hipR, t),
    kneeL = lerpPoint(a.kneeL, b.kneeL, t),
    kneeR = lerpPoint(a.kneeR, b.kneeR, t),
    ankleL = lerpPoint(a.ankleL, b.ankleL, t),
    ankleR = lerpPoint(a.ankleR, b.ankleR, t),
    racketTip = if (a.racketTip != null && b.racketTip != null) lerpPoint(a.racketTip, b.racketTip, t) else a.racketTip,
)

private fun mirrorPoint01(p: VisualPoint): VisualPoint = VisualPoint(1f - p.x, p.y)

private fun mirrorFigure(f: PoseFigure): PoseFigure = PoseFigure(
    head = mirrorPoint01(f.head),
    neck = mirrorPoint01(f.neck),
    shoulderL = mirrorPoint01(f.shoulderL),
    shoulderR = mirrorPoint01(f.shoulderR),
    elbowL = mirrorPoint01(f.elbowL),
    elbowR = mirrorPoint01(f.elbowR),
    wristL = mirrorPoint01(f.wristL),
    wristR = mirrorPoint01(f.wristR),
    hipL = mirrorPoint01(f.hipL),
    hipR = mirrorPoint01(f.hipR),
    kneeL = mirrorPoint01(f.kneeL),
    kneeR = mirrorPoint01(f.kneeR),
    ankleL = mirrorPoint01(f.ankleL),
    ankleR = mirrorPoint01(f.ankleR),
    racketTip = f.racketTip?.let(::mirrorPoint01),
)

private fun maybeMirrorCourt(p: VisualPoint, mirrorX: Boolean): VisualPoint =
    if (!mirrorX) p else VisualPoint(Court.WIDTH_DOUBLES.toFloat() - p.x, p.y)

// --- On-court athletic pose keyframes (right-handed default; mirrored automatically for left-handed) ---

private val readyStance = PoseFigure(
    head = VisualPoint(0.49f, 0.24f),
    neck = VisualPoint(0.49f, 0.31f),
    shoulderL = VisualPoint(0.41f, 0.34f),
    shoulderR = VisualPoint(0.57f, 0.34f),
    elbowL = VisualPoint(0.34f, 0.45f),
    elbowR = VisualPoint(0.64f, 0.44f),
    wristL = VisualPoint(0.40f, 0.41f),
    wristR = VisualPoint(0.61f, 0.39f),
    hipL = VisualPoint(0.43f, 0.55f),
    hipR = VisualPoint(0.55f, 0.55f),
    kneeL = VisualPoint(0.38f, 0.72f),
    kneeR = VisualPoint(0.61f, 0.72f),
    ankleL = VisualPoint(0.36f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.63f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.69f, 0.22f),
)

private val splitStepHop = PoseFigure(
    head = VisualPoint(0.49f, 0.20f),
    neck = VisualPoint(0.49f, 0.27f),
    shoulderL = VisualPoint(0.40f, 0.30f),
    shoulderR = VisualPoint(0.58f, 0.30f),
    elbowL = VisualPoint(0.31f, 0.40f),
    elbowR = VisualPoint(0.66f, 0.39f),
    wristL = VisualPoint(0.36f, 0.37f),
    wristR = VisualPoint(0.63f, 0.34f),
    hipL = VisualPoint(0.43f, 0.51f),
    hipR = VisualPoint(0.56f, 0.51f),
    kneeL = VisualPoint(0.34f, 0.67f),
    kneeR = VisualPoint(0.65f, 0.67f),
    ankleL = VisualPoint(0.31f, 0.82f),
    ankleR = VisualPoint(0.68f, 0.82f),
    racketTip = VisualPoint(0.72f, 0.17f),
)

private val forecourtLunge = PoseFigure(
    head = VisualPoint(0.54f, 0.33f),
    neck = VisualPoint(0.52f, 0.39f),
    shoulderL = VisualPoint(0.44f, 0.42f),
    shoulderR = VisualPoint(0.58f, 0.41f),
    elbowL = VisualPoint(0.32f, 0.44f),
    elbowR = VisualPoint(0.69f, 0.42f),
    wristL = VisualPoint(0.22f, 0.40f),
    wristR = VisualPoint(0.77f, 0.39f),
    hipL = VisualPoint(0.42f, 0.61f),
    hipR = VisualPoint(0.51f, 0.62f),
    kneeL = VisualPoint(0.31f, 0.75f),
    kneeR = VisualPoint(0.67f, 0.70f),
    ankleL = VisualPoint(0.18f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.70f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.91f, 0.27f),
)

private val overheadLoad = PoseFigure(
    head = VisualPoint(0.48f, 0.24f),
    neck = VisualPoint(0.48f, 0.31f),
    shoulderL = VisualPoint(0.41f, 0.32f),
    shoulderR = VisualPoint(0.55f, 0.38f),
    elbowL = VisualPoint(0.36f, 0.23f),
    elbowR = VisualPoint(0.66f, 0.42f),
    wristL = VisualPoint(0.34f, 0.14f),
    wristR = VisualPoint(0.69f, 0.31f),
    hipL = VisualPoint(0.43f, 0.56f),
    hipR = VisualPoint(0.54f, 0.58f),
    kneeL = VisualPoint(0.37f, 0.72f),
    kneeR = VisualPoint(0.60f, 0.73f),
    ankleL = VisualPoint(0.33f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.64f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.81f, 0.18f),
)

private val overheadStrike = PoseFigure(
    head = VisualPoint(0.49f, 0.26f),
    neck = VisualPoint(0.49f, 0.33f),
    shoulderL = VisualPoint(0.42f, 0.39f),
    shoulderR = VisualPoint(0.55f, 0.35f),
    elbowL = VisualPoint(0.35f, 0.50f),
    elbowR = VisualPoint(0.56f, 0.26f),
    wristL = VisualPoint(0.39f, 0.57f),
    wristR = VisualPoint(0.55f, 0.19f),
    hipL = VisualPoint(0.45f, 0.57f),
    hipR = VisualPoint(0.54f, 0.56f),
    kneeL = VisualPoint(0.41f, 0.73f),
    kneeR = VisualPoint(0.57f, 0.73f),
    ankleL = VisualPoint(0.38f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.60f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.54f, 0.02f),
)

private val wideSidelineLunge = PoseFigure(
    head = VisualPoint(0.54f, 0.32f),
    neck = VisualPoint(0.53f, 0.39f),
    shoulderL = VisualPoint(0.45f, 0.41f),
    shoulderR = VisualPoint(0.60f, 0.42f),
    elbowL = VisualPoint(0.33f, 0.43f),
    elbowR = VisualPoint(0.70f, 0.45f),
    wristL = VisualPoint(0.24f, 0.40f),
    wristR = VisualPoint(0.78f, 0.44f),
    hipL = VisualPoint(0.44f, 0.60f),
    hipR = VisualPoint(0.54f, 0.61f),
    kneeL = VisualPoint(0.34f, 0.74f),
    kneeR = VisualPoint(0.68f, 0.72f),
    ankleL = VisualPoint(0.23f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.72f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.94f, 0.36f),
)

private val chinaJumpAirborne = PoseFigure(
    head = VisualPoint(0.56f, 0.18f),
    neck = VisualPoint(0.55f, 0.25f),
    shoulderL = VisualPoint(0.47f, 0.28f),
    shoulderR = VisualPoint(0.62f, 0.25f),
    elbowL = VisualPoint(0.37f, 0.34f),
    elbowR = VisualPoint(0.72f, 0.21f),
    wristL = VisualPoint(0.31f, 0.41f),
    wristR = VisualPoint(0.78f, 0.17f),
    hipL = VisualPoint(0.47f, 0.47f),
    hipR = VisualPoint(0.57f, 0.47f),
    kneeL = VisualPoint(0.37f, 0.61f),
    kneeR = VisualPoint(0.67f, 0.59f),
    ankleL = VisualPoint(0.28f, 0.75f),
    ankleR = VisualPoint(0.74f, 0.74f),
    racketTip = VisualPoint(0.93f, 0.10f),
)

private val scissorKickAirborne = PoseFigure(
    head = VisualPoint(0.45f, 0.21f),
    neck = VisualPoint(0.46f, 0.28f),
    shoulderL = VisualPoint(0.39f, 0.32f),
    shoulderR = VisualPoint(0.52f, 0.27f),
    elbowL = VisualPoint(0.32f, 0.43f),
    elbowR = VisualPoint(0.47f, 0.19f),
    wristL = VisualPoint(0.36f, 0.50f),
    wristR = VisualPoint(0.43f, 0.14f),
    hipL = VisualPoint(0.44f, 0.49f),
    hipR = VisualPoint(0.53f, 0.48f),
    kneeL = VisualPoint(0.56f, 0.63f),
    kneeR = VisualPoint(0.38f, 0.63f),
    ankleL = VisualPoint(0.65f, 0.77f),
    ankleR = VisualPoint(0.30f, 0.76f),
    racketTip = VisualPoint(0.33f, 0.03f),
)

private val jumpSmashTuck = PoseFigure(
    head = VisualPoint(0.49f, 0.16f),
    neck = VisualPoint(0.49f, 0.23f),
    shoulderL = VisualPoint(0.41f, 0.24f),
    shoulderR = VisualPoint(0.56f, 0.28f),
    elbowL = VisualPoint(0.35f, 0.15f),
    elbowR = VisualPoint(0.67f, 0.29f),
    wristL = VisualPoint(0.35f, 0.08f),
    wristR = VisualPoint(0.69f, 0.19f),
    hipL = VisualPoint(0.44f, 0.46f),
    hipR = VisualPoint(0.54f, 0.47f),
    kneeL = VisualPoint(0.38f, 0.61f),
    kneeR = VisualPoint(0.58f, 0.61f),
    ankleL = VisualPoint(0.45f, 0.72f),
    ankleR = VisualPoint(0.64f, 0.72f),
    racketTip = VisualPoint(0.81f, 0.08f),
)

private val jumpSmashStrike = PoseFigure(
    head = VisualPoint(0.51f, 0.18f),
    neck = VisualPoint(0.51f, 0.25f),
    shoulderL = VisualPoint(0.43f, 0.30f),
    shoulderR = VisualPoint(0.57f, 0.25f),
    elbowL = VisualPoint(0.37f, 0.41f),
    elbowR = VisualPoint(0.60f, 0.17f),
    wristL = VisualPoint(0.42f, 0.48f),
    wristR = VisualPoint(0.65f, 0.15f),
    hipL = VisualPoint(0.45f, 0.48f),
    hipR = VisualPoint(0.54f, 0.48f),
    kneeL = VisualPoint(0.40f, 0.64f),
    kneeR = VisualPoint(0.59f, 0.63f),
    ankleL = VisualPoint(0.35f, 0.77f),
    ankleR = VisualPoint(0.64f, 0.76f),
    racketTip = VisualPoint(0.81f, 0.10f),
)

private val backhandRearLoad = PoseFigure(
    head = VisualPoint(0.44f, 0.25f),
    neck = VisualPoint(0.44f, 0.32f),
    shoulderL = VisualPoint(0.51f, 0.36f),
    shoulderR = VisualPoint(0.38f, 0.33f),
    elbowL = VisualPoint(0.59f, 0.47f),
    elbowR = VisualPoint(0.32f, 0.28f),
    wristL = VisualPoint(0.55f, 0.56f),
    wristR = VisualPoint(0.39f, 0.29f),
    hipL = VisualPoint(0.51f, 0.57f),
    hipR = VisualPoint(0.40f, 0.56f),
    kneeL = VisualPoint(0.57f, 0.72f),
    kneeR = VisualPoint(0.32f, 0.71f),
    ankleL = VisualPoint(0.62f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.28f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.48f, 0.15f),
)

private val backhandRearWhip = PoseFigure(
    head = VisualPoint(0.43f, 0.26f),
    neck = VisualPoint(0.43f, 0.33f),
    shoulderL = VisualPoint(0.50f, 0.38f),
    shoulderR = VisualPoint(0.37f, 0.33f),
    elbowL = VisualPoint(0.58f, 0.48f),
    elbowR = VisualPoint(0.31f, 0.23f),
    wristL = VisualPoint(0.63f, 0.54f),
    wristR = VisualPoint(0.26f, 0.15f),
    hipL = VisualPoint(0.50f, 0.58f),
    hipR = VisualPoint(0.39f, 0.57f),
    kneeL = VisualPoint(0.57f, 0.73f),
    kneeR = VisualPoint(0.30f, 0.71f),
    ankleL = VisualPoint(0.63f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.27f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.14f, 0.04f),
)

private val lowDefenceCrouch = PoseFigure(
    head = VisualPoint(0.50f, 0.33f),
    neck = VisualPoint(0.50f, 0.40f),
    shoulderL = VisualPoint(0.41f, 0.43f),
    shoulderR = VisualPoint(0.59f, 0.43f),
    elbowL = VisualPoint(0.33f, 0.54f),
    elbowR = VisualPoint(0.64f, 0.55f),
    wristL = VisualPoint(0.38f, 0.57f),
    wristR = VisualPoint(0.54f, 0.59f),
    hipL = VisualPoint(0.42f, 0.63f),
    hipR = VisualPoint(0.58f, 0.63f),
    kneeL = VisualPoint(0.32f, 0.74f),
    kneeR = VisualPoint(0.68f, 0.74f),
    ankleL = VisualPoint(0.27f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.73f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.38f, 0.52f),
)

private val lowDefenceLift = PoseFigure(
    head = VisualPoint(0.52f, 0.29f),
    neck = VisualPoint(0.51f, 0.36f),
    shoulderL = VisualPoint(0.43f, 0.39f),
    shoulderR = VisualPoint(0.60f, 0.39f),
    elbowL = VisualPoint(0.34f, 0.49f),
    elbowR = VisualPoint(0.69f, 0.46f),
    wristL = VisualPoint(0.32f, 0.56f),
    wristR = VisualPoint(0.75f, 0.39f),
    hipL = VisualPoint(0.43f, 0.60f),
    hipR = VisualPoint(0.56f, 0.60f),
    kneeL = VisualPoint(0.34f, 0.73f),
    kneeR = VisualPoint(0.66f, 0.73f),
    ankleL = VisualPoint(0.29f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.71f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.89f, 0.27f),
)

private val flatDrivePunch = PoseFigure(
    head = VisualPoint(0.50f, 0.25f),
    neck = VisualPoint(0.50f, 0.32f),
    shoulderL = VisualPoint(0.42f, 0.35f),
    shoulderR = VisualPoint(0.58f, 0.34f),
    elbowL = VisualPoint(0.35f, 0.46f),
    elbowR = VisualPoint(0.69f, 0.38f),
    wristL = VisualPoint(0.41f, 0.44f),
    wristR = VisualPoint(0.76f, 0.34f),
    hipL = VisualPoint(0.44f, 0.56f),
    hipR = VisualPoint(0.56f, 0.56f),
    kneeL = VisualPoint(0.39f, 0.72f),
    kneeR = VisualPoint(0.61f, 0.72f),
    ankleL = VisualPoint(0.35f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.65f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.92f, 0.29f),
)

private val bodyDeflectBackhand = PoseFigure(
    head = VisualPoint(0.50f, 0.30f),
    neck = VisualPoint(0.50f, 0.37f),
    shoulderL = VisualPoint(0.41f, 0.40f),
    shoulderR = VisualPoint(0.59f, 0.40f),
    elbowL = VisualPoint(0.34f, 0.51f),
    elbowR = VisualPoint(0.60f, 0.50f),
    wristL = VisualPoint(0.36f, 0.58f),
    wristR = VisualPoint(0.48f, 0.47f),
    hipL = VisualPoint(0.43f, 0.61f),
    hipR = VisualPoint(0.57f, 0.61f),
    kneeL = VisualPoint(0.34f, 0.74f),
    kneeR = VisualPoint(0.66f, 0.74f),
    ankleL = VisualPoint(0.29f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.71f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.31f, 0.39f),
)

private val wideReachLow = PoseFigure(
    head = VisualPoint(0.57f, 0.40f),
    neck = VisualPoint(0.55f, 0.46f),
    shoulderL = VisualPoint(0.47f, 0.49f),
    shoulderR = VisualPoint(0.63f, 0.50f),
    elbowL = VisualPoint(0.34f, 0.48f),
    elbowR = VisualPoint(0.72f, 0.58f),
    wristL = VisualPoint(0.22f, 0.44f),
    wristR = VisualPoint(0.78f, 0.66f),
    hipL = VisualPoint(0.44f, 0.65f),
    hipR = VisualPoint(0.54f, 0.66f),
    kneeL = VisualPoint(0.31f, 0.77f),
    kneeR = VisualPoint(0.70f, 0.74f),
    ankleL = VisualPoint(0.16f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.75f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.95f, 0.60f),
)

private val netSpinTouch = PoseFigure(
    head = VisualPoint(0.54f, 0.31f),
    neck = VisualPoint(0.52f, 0.38f),
    shoulderL = VisualPoint(0.44f, 0.41f),
    shoulderR = VisualPoint(0.59f, 0.39f),
    elbowL = VisualPoint(0.33f, 0.44f),
    elbowR = VisualPoint(0.69f, 0.38f),
    wristL = VisualPoint(0.24f, 0.42f),
    wristR = VisualPoint(0.78f, 0.34f),
    hipL = VisualPoint(0.43f, 0.60f),
    hipR = VisualPoint(0.52f, 0.61f),
    kneeL = VisualPoint(0.33f, 0.75f),
    kneeR = VisualPoint(0.66f, 0.71f),
    ankleL = VisualPoint(0.22f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.70f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.93f, 0.31f),
)

private val serveReady = PoseFigure(
    head = VisualPoint(0.48f, 0.21f),
    neck = VisualPoint(0.48f, 0.28f),
    shoulderL = VisualPoint(0.41f, 0.31f),
    shoulderR = VisualPoint(0.55f, 0.32f),
    elbowL = VisualPoint(0.44f, 0.43f),
    elbowR = VisualPoint(0.61f, 0.44f),
    wristL = VisualPoint(0.52f, 0.44f),
    wristR = VisualPoint(0.55f, 0.50f),
    hipL = VisualPoint(0.44f, 0.53f),
    hipR = VisualPoint(0.53f, 0.53f),
    kneeL = VisualPoint(0.42f, 0.70f),
    kneeR = VisualPoint(0.57f, 0.70f),
    ankleL = VisualPoint(0.39f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.60f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.49f, 0.66f),
)

private val serveFlickFinish = PoseFigure(
    head = VisualPoint(0.50f, 0.21f),
    neck = VisualPoint(0.50f, 0.28f),
    shoulderL = VisualPoint(0.43f, 0.31f),
    shoulderR = VisualPoint(0.57f, 0.31f),
    elbowL = VisualPoint(0.36f, 0.43f),
    elbowR = VisualPoint(0.64f, 0.40f),
    wristL = VisualPoint(0.39f, 0.49f),
    wristR = VisualPoint(0.67f, 0.37f),
    hipL = VisualPoint(0.45f, 0.53f),
    hipR = VisualPoint(0.54f, 0.53f),
    kneeL = VisualPoint(0.42f, 0.70f),
    kneeR = VisualPoint(0.58f, 0.70f),
    ankleL = VisualPoint(0.39f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.61f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.80f, 0.25f),
)

private val receiveStance = PoseFigure(
    head = VisualPoint(0.52f, 0.26f),
    neck = VisualPoint(0.51f, 0.33f),
    shoulderL = VisualPoint(0.43f, 0.36f),
    shoulderR = VisualPoint(0.59f, 0.35f),
    elbowL = VisualPoint(0.37f, 0.45f),
    elbowR = VisualPoint(0.67f, 0.36f),
    wristL = VisualPoint(0.44f, 0.41f),
    wristR = VisualPoint(0.71f, 0.28f),
    hipL = VisualPoint(0.44f, 0.56f),
    hipR = VisualPoint(0.54f, 0.56f),
    kneeL = VisualPoint(0.37f, 0.72f),
    kneeR = VisualPoint(0.62f, 0.72f),
    ankleL = VisualPoint(0.32f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.65f, POSE_GROUND_Y),
    racketTip = VisualPoint(0.82f, 0.14f),
)

// --- Off-court athletic pose keyframes (no racket) ---

private val splitSquatTop = PoseFigure(
    head = VisualPoint(0.49f, 0.18f),
    neck = VisualPoint(0.49f, 0.25f),
    shoulderL = VisualPoint(0.45f, 0.28f),
    shoulderR = VisualPoint(0.53f, 0.28f),
    elbowL = VisualPoint(0.42f, 0.41f),
    elbowR = VisualPoint(0.56f, 0.41f),
    wristL = VisualPoint(0.45f, 0.50f),
    wristR = VisualPoint(0.53f, 0.50f),
    hipL = VisualPoint(0.46f, 0.50f),
    hipR = VisualPoint(0.52f, 0.50f),
    kneeL = VisualPoint(0.36f, 0.69f),
    kneeR = VisualPoint(0.61f, 0.69f),
    ankleL = VisualPoint(0.26f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.67f, POSE_GROUND_Y),
)

private val splitSquatBottom = PoseFigure(
    head = VisualPoint(0.50f, 0.32f),
    neck = VisualPoint(0.50f, 0.39f),
    shoulderL = VisualPoint(0.46f, 0.42f),
    shoulderR = VisualPoint(0.54f, 0.42f),
    elbowL = VisualPoint(0.43f, 0.54f),
    elbowR = VisualPoint(0.57f, 0.54f),
    wristL = VisualPoint(0.46f, 0.62f),
    wristR = VisualPoint(0.54f, 0.62f),
    hipL = VisualPoint(0.46f, 0.64f),
    hipR = VisualPoint(0.53f, 0.64f),
    kneeL = VisualPoint(0.39f, 0.82f),
    kneeR = VisualPoint(0.68f, 0.66f),
    ankleL = VisualPoint(0.24f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.68f, POSE_GROUND_Y),
)

private val boundLeft = PoseFigure(
    head = VisualPoint(0.35f, 0.27f),
    neck = VisualPoint(0.35f, 0.34f),
    shoulderL = VisualPoint(0.28f, 0.37f),
    shoulderR = VisualPoint(0.42f, 0.36f),
    elbowL = VisualPoint(0.22f, 0.48f),
    elbowR = VisualPoint(0.49f, 0.45f),
    wristL = VisualPoint(0.26f, 0.44f),
    wristR = VisualPoint(0.43f, 0.41f),
    hipL = VisualPoint(0.32f, 0.57f),
    hipR = VisualPoint(0.41f, 0.58f),
    kneeL = VisualPoint(0.31f, 0.72f),
    kneeR = VisualPoint(0.47f, 0.72f),
    ankleL = VisualPoint(0.33f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.53f, 0.80f),
)

private val boundRight = mirrorFigure(boundLeft)

private val plyoSplitJump = PoseFigure(
    head = VisualPoint(0.50f, 0.26f),
    neck = VisualPoint(0.50f, 0.33f),
    shoulderL = VisualPoint(0.42f, 0.36f),
    shoulderR = VisualPoint(0.58f, 0.36f),
    elbowL = VisualPoint(0.33f, 0.46f),
    elbowR = VisualPoint(0.67f, 0.46f),
    wristL = VisualPoint(0.38f, 0.43f),
    wristR = VisualPoint(0.62f, 0.43f),
    hipL = VisualPoint(0.44f, 0.57f),
    hipR = VisualPoint(0.56f, 0.57f),
    kneeL = VisualPoint(0.35f, 0.71f),
    kneeR = VisualPoint(0.65f, 0.71f),
    ankleL = VisualPoint(0.30f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.70f, POSE_GROUND_Y),
)

private val standingTall = PoseFigure(
    head = VisualPoint(0.50f, 0.16f),
    neck = VisualPoint(0.50f, 0.23f),
    shoulderL = VisualPoint(0.44f, 0.26f),
    shoulderR = VisualPoint(0.56f, 0.26f),
    elbowL = VisualPoint(0.40f, 0.40f),
    elbowR = VisualPoint(0.60f, 0.40f),
    wristL = VisualPoint(0.41f, 0.52f),
    wristR = VisualPoint(0.59f, 0.52f),
    hipL = VisualPoint(0.46f, 0.51f),
    hipR = VisualPoint(0.54f, 0.51f),
    kneeL = VisualPoint(0.45f, 0.70f),
    kneeR = VisualPoint(0.55f, 0.70f),
    ankleL = VisualPoint(0.44f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.56f, POSE_GROUND_Y),
)

private val pogoAirborne = PoseFigure(
    head = VisualPoint(0.50f, 0.08f),
    neck = VisualPoint(0.50f, 0.15f),
    shoulderL = VisualPoint(0.44f, 0.18f),
    shoulderR = VisualPoint(0.56f, 0.18f),
    elbowL = VisualPoint(0.38f, 0.30f),
    elbowR = VisualPoint(0.62f, 0.30f),
    wristL = VisualPoint(0.41f, 0.26f),
    wristR = VisualPoint(0.59f, 0.26f),
    hipL = VisualPoint(0.46f, 0.43f),
    hipR = VisualPoint(0.54f, 0.43f),
    kneeL = VisualPoint(0.45f, 0.61f),
    kneeR = VisualPoint(0.55f, 0.61f),
    ankleL = VisualPoint(0.45f, 0.77f),
    ankleR = VisualPoint(0.55f, 0.77f),
)

private val dropLungeBrake = PoseFigure(
    head = VisualPoint(0.53f, 0.31f),
    neck = VisualPoint(0.52f, 0.38f),
    shoulderL = VisualPoint(0.46f, 0.41f),
    shoulderR = VisualPoint(0.58f, 0.41f),
    elbowL = VisualPoint(0.37f, 0.49f),
    elbowR = VisualPoint(0.66f, 0.47f),
    wristL = VisualPoint(0.32f, 0.46f),
    wristR = VisualPoint(0.71f, 0.42f),
    hipL = VisualPoint(0.45f, 0.61f),
    hipR = VisualPoint(0.54f, 0.61f),
    kneeL = VisualPoint(0.34f, 0.78f),
    kneeR = VisualPoint(0.70f, 0.68f),
    ankleL = VisualPoint(0.20f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.72f, POSE_GROUND_Y),
)

private val singleLegRdlHinge = PoseFigure(
    head = VisualPoint(0.76f, 0.44f),
    neck = VisualPoint(0.69f, 0.46f),
    shoulderL = VisualPoint(0.65f, 0.48f),
    shoulderR = VisualPoint(0.65f, 0.51f),
    elbowL = VisualPoint(0.64f, 0.61f),
    elbowR = VisualPoint(0.68f, 0.61f),
    wristL = VisualPoint(0.64f, 0.73f),
    wristR = VisualPoint(0.68f, 0.73f),
    hipL = VisualPoint(0.46f, 0.51f),
    hipR = VisualPoint(0.48f, 0.53f),
    kneeL = VisualPoint(0.28f, 0.50f),
    kneeR = VisualPoint(0.49f, 0.70f),
    ankleL = VisualPoint(0.11f, 0.48f),
    ankleR = VisualPoint(0.48f, POSE_GROUND_Y),
)

private val sideLungeRight = PoseFigure(
    head = VisualPoint(0.62f, 0.33f),
    neck = VisualPoint(0.62f, 0.40f),
    shoulderL = VisualPoint(0.55f, 0.43f),
    shoulderR = VisualPoint(0.69f, 0.43f),
    elbowL = VisualPoint(0.51f, 0.54f),
    elbowR = VisualPoint(0.73f, 0.54f),
    wristL = VisualPoint(0.58f, 0.53f),
    wristR = VisualPoint(0.66f, 0.53f),
    hipL = VisualPoint(0.55f, 0.63f),
    hipR = VisualPoint(0.66f, 0.63f),
    kneeL = VisualPoint(0.37f, 0.75f),
    kneeR = VisualPoint(0.76f, 0.72f),
    ankleL = VisualPoint(0.20f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.78f, POSE_GROUND_Y),
)

private val sideLungeLeft = mirrorFigure(sideLungeRight)

private val coreCoilLeft = PoseFigure(
    head = VisualPoint(0.47f, 0.24f),
    neck = VisualPoint(0.47f, 0.31f),
    shoulderL = VisualPoint(0.40f, 0.34f),
    shoulderR = VisualPoint(0.54f, 0.36f),
    elbowL = VisualPoint(0.31f, 0.42f),
    elbowR = VisualPoint(0.42f, 0.45f),
    wristL = VisualPoint(0.26f, 0.38f),
    wristR = VisualPoint(0.28f, 0.40f),
    hipL = VisualPoint(0.43f, 0.56f),
    hipR = VisualPoint(0.55f, 0.56f),
    kneeL = VisualPoint(0.38f, 0.72f),
    kneeR = VisualPoint(0.60f, 0.72f),
    ankleL = VisualPoint(0.34f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.64f, POSE_GROUND_Y),
)

private val coreWhipRight = mirrorFigure(coreCoilLeft)

private val plankBase = PoseFigure(
    head = VisualPoint(0.79f, 0.56f),
    neck = VisualPoint(0.73f, 0.58f),
    shoulderL = VisualPoint(0.69f, 0.60f),
    shoulderR = VisualPoint(0.69f, 0.62f),
    elbowL = VisualPoint(0.70f, 0.74f),
    elbowR = VisualPoint(0.68f, 0.75f),
    wristL = VisualPoint(0.71f, POSE_GROUND_Y),
    wristR = VisualPoint(0.68f, POSE_GROUND_Y),
    hipL = VisualPoint(0.46f, 0.67f),
    hipR = VisualPoint(0.46f, 0.69f),
    kneeL = VisualPoint(0.30f, 0.76f),
    kneeR = VisualPoint(0.30f, 0.78f),
    ankleL = VisualPoint(0.14f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.16f, POSE_GROUND_Y),
)

private val plankShoulderTap = PoseFigure(
    head = VisualPoint(0.79f, 0.56f),
    neck = VisualPoint(0.73f, 0.58f),
    shoulderL = VisualPoint(0.69f, 0.60f),
    shoulderR = VisualPoint(0.69f, 0.62f),
    elbowL = VisualPoint(0.70f, 0.74f),
    elbowR = VisualPoint(0.61f, 0.69f),
    wristL = VisualPoint(0.71f, POSE_GROUND_Y),
    wristR = VisualPoint(0.68f, 0.61f),
    hipL = VisualPoint(0.46f, 0.67f),
    hipR = VisualPoint(0.46f, 0.69f),
    kneeL = VisualPoint(0.30f, 0.76f),
    kneeR = VisualPoint(0.30f, 0.78f),
    ankleL = VisualPoint(0.14f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.16f, POSE_GROUND_Y),
)

private val calfRaiseLow = PoseFigure(
    head = VisualPoint(0.50f, 0.19f),
    neck = VisualPoint(0.50f, 0.26f),
    shoulderL = VisualPoint(0.44f, 0.29f),
    shoulderR = VisualPoint(0.56f, 0.29f),
    elbowL = VisualPoint(0.39f, 0.42f),
    elbowR = VisualPoint(0.61f, 0.42f),
    wristL = VisualPoint(0.41f, 0.52f),
    wristR = VisualPoint(0.59f, 0.52f),
    hipL = VisualPoint(0.46f, 0.53f),
    hipR = VisualPoint(0.54f, 0.53f),
    kneeL = VisualPoint(0.42f, 0.68f),
    kneeR = VisualPoint(0.54f, 0.71f),
    ankleL = VisualPoint(0.36f, 0.76f),
    ankleR = VisualPoint(0.54f, POSE_GROUND_Y),
)

private val calfRaiseHigh = PoseFigure(
    head = VisualPoint(0.50f, 0.13f),
    neck = VisualPoint(0.50f, 0.20f),
    shoulderL = VisualPoint(0.44f, 0.23f),
    shoulderR = VisualPoint(0.56f, 0.23f),
    elbowL = VisualPoint(0.39f, 0.36f),
    elbowR = VisualPoint(0.61f, 0.36f),
    wristL = VisualPoint(0.41f, 0.46f),
    wristR = VisualPoint(0.59f, 0.46f),
    hipL = VisualPoint(0.46f, 0.47f),
    hipR = VisualPoint(0.54f, 0.47f),
    kneeL = VisualPoint(0.42f, 0.62f),
    kneeR = VisualPoint(0.54f, 0.66f),
    ankleL = VisualPoint(0.36f, 0.70f),
    ankleR = VisualPoint(0.54f, 0.82f),
)

private val shoulderPrehabStart = PoseFigure(
    head = VisualPoint(0.50f, 0.18f),
    neck = VisualPoint(0.50f, 0.25f),
    shoulderL = VisualPoint(0.42f, 0.29f),
    shoulderR = VisualPoint(0.58f, 0.29f),
    elbowL = VisualPoint(0.30f, 0.31f),
    elbowR = VisualPoint(0.70f, 0.31f),
    wristL = VisualPoint(0.30f, 0.44f),
    wristR = VisualPoint(0.70f, 0.44f),
    hipL = VisualPoint(0.45f, 0.53f),
    hipR = VisualPoint(0.55f, 0.53f),
    kneeL = VisualPoint(0.44f, 0.71f),
    kneeR = VisualPoint(0.56f, 0.71f),
    ankleL = VisualPoint(0.42f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.58f, POSE_GROUND_Y),
)

private val shoulderPrehabOpen = PoseFigure(
    head = VisualPoint(0.50f, 0.18f),
    neck = VisualPoint(0.50f, 0.25f),
    shoulderL = VisualPoint(0.42f, 0.29f),
    shoulderR = VisualPoint(0.58f, 0.29f),
    elbowL = VisualPoint(0.30f, 0.31f),
    elbowR = VisualPoint(0.70f, 0.31f),
    wristL = VisualPoint(0.28f, 0.17f),
    wristR = VisualPoint(0.72f, 0.17f),
    hipL = VisualPoint(0.45f, 0.53f),
    hipR = VisualPoint(0.55f, 0.53f),
    kneeL = VisualPoint(0.44f, 0.71f),
    kneeR = VisualPoint(0.56f, 0.71f),
    ankleL = VisualPoint(0.42f, POSE_GROUND_Y),
    ankleR = VisualPoint(0.58f, POSE_GROUND_Y),
)

// --- Pose sequences ---

private val baseRecoveryPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Base ready", readyStance),
        PoseKeyframe("Split-step", splitStepHop),
        PoseKeyframe("Corner lunge", forecourtLunge),
    ),
    cycleSeconds = 3.2,
)

private val overheadRecoveryPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Load overhead", overheadLoad),
        PoseKeyframe("Strike high", overheadStrike),
        PoseKeyframe("Drive to base", readyStance),
    ),
    cycleSeconds = 3.2,
)

private val netToRearPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Net lunge", forecourtLunge),
        PoseKeyframe("Reset base", readyStance),
        PoseKeyframe("Rear clear", overheadStrike),
    ),
    cycleSeconds = 3.4,
)

private val lateralBalancePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Centre split", splitStepHop),
        PoseKeyframe("Wide lunge", wideSidelineLunge),
        PoseKeyframe("Centre reset", readyStance),
    ),
    cycleSeconds = 3.0,
)

private val forecourtNetKillPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Racket high", readyStance),
        PoseKeyframe("Heel-first lunge", forecourtLunge),
        PoseKeyframe("Rebound", splitStepHop),
    ),
    cycleSeconds = 2.8,
)

private val chinaJumpPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Ready base", readyStance),
        PoseKeyframe("Lateral jump", chinaJumpAirborne),
        PoseKeyframe("Sideline land", wideSidelineLunge),
    ),
    cycleSeconds = 2.8,
)

private val reactiveSplitPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Bounce ready", readyStance),
        PoseKeyframe("Split on cue", splitStepHop),
        PoseKeyframe("First-step drive", wideSidelineLunge),
    ),
    cycleSeconds = 2.8,
)

private val scissorKickPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Rear load", overheadLoad),
        PoseKeyframe("Scissor kick", scissorKickAirborne),
        PoseKeyframe("Drive forward", readyStance),
    ),
    cycleSeconds = 3.0,
)

private val jumpSmashPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Tuck gather", jumpSmashTuck),
        PoseKeyframe("Steep strike", jumpSmashStrike),
        PoseKeyframe("Follow to net", forecourtLunge),
    ),
    cycleSeconds = 3.0,
)

private val disguisedSlicePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("High prep", overheadLoad),
        PoseKeyframe("Steep slice", overheadStrike),
        PoseKeyframe("Reset base", readyStance),
    ),
    cycleSeconds = 3.0,
)

private val backhandEscapePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Pivot & elbow up", backhandRearLoad),
        PoseKeyframe("Forearm whip", backhandRearWhip),
        PoseKeyframe("Turn to base", readyStance),
    ),
    cycleSeconds = 3.2,
)

private val lowDefencePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Low ready", lowDefenceCrouch),
        PoseKeyframe("Deep lift", lowDefenceLift),
    ),
    cycleSeconds = 2.4,
)

private val flatDrivePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Compact ready", readyStance),
        PoseKeyframe("Flat drive punch", flatDrivePunch),
        PoseKeyframe("Backhand block", bodyDeflectBackhand),
    ),
    cycleSeconds = 2.6,
)

private val bodyDeflectPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Low guard", lowDefenceCrouch),
        PoseKeyframe("Cross deflect", bodyDeflectBackhand),
        PoseKeyframe("Step in", readyStance),
    ),
    cycleSeconds = 2.8,
)

private val wideReachPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Split ready", splitStepHop),
        PoseKeyframe("Low wide save", wideReachLow),
        PoseKeyframe("Recover base", readyStance),
    ),
    cycleSeconds = 3.0,
)

private val netSpinPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Early reach", netSpinTouch),
        PoseKeyframe("Compact reset", readyStance),
    ),
    cycleSeconds = 2.4,
)

private val serveThirdShotPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Poised serve", serveReady),
        PoseKeyframe("Flick or push", serveFlickFinish),
        PoseKeyframe("Racket up", readyStance),
    ),
    cycleSeconds = 3.0,
)

private val receiveRushPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("High receive", receiveStance),
        PoseKeyframe("Forward rush", forecourtLunge),
        PoseKeyframe("Base reset", readyStance),
    ),
    cycleSeconds = 2.8,
)

private val splitSquatPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Tall split stance", splitSquatTop),
        PoseKeyframe("Lower under control", splitSquatBottom),
    ),
    cycleSeconds = 2.6,
)

private val lateralBoundsPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Bound left", boundLeft),
        PoseKeyframe("Bound right", boundRight),
        PoseKeyframe("Wide split-jump", plyoSplitJump),
    ),
    cycleSeconds = 3.0,
)

private val dropLungePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Tall start", standingTall),
        PoseKeyframe("Brake & hold", dropLungeBrake),
    ),
    cycleSeconds = 2.6,
)

private val pogoSplitPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Quick pogo", pogoAirborne),
        PoseKeyframe("Wide split land", plyoSplitJump),
    ),
    cycleSeconds = 2.2,
)

private val singleLegRdlPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Tall balance", standingTall),
        PoseKeyframe("Hip-hinge reach", singleLegRdlHinge),
    ),
    cycleSeconds = 3.0,
)

private val sideLungePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Load right", sideLungeRight),
        PoseKeyframe("Centre tall", standingTall),
        PoseKeyframe("Load left", sideLungeLeft),
    ),
    cycleSeconds = 3.4,
)

private val rotationalCorePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Load hip coil", coreCoilLeft),
        PoseKeyframe("Whip across", coreWhipRight),
    ),
    cycleSeconds = 2.4,
)

private val plankTapPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Braced plank", plankBase),
        PoseKeyframe("Slow shoulder tap", plankShoulderTap),
    ),
    cycleSeconds = 2.6,
)

private val calfRaisePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Slow lower", calfRaiseLow),
        PoseKeyframe("High press", calfRaiseHigh),
    ),
    cycleSeconds = 2.8,
)

private val shoulderPrehabPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("90-deg start", shoulderPrehabStart),
        PoseKeyframe("External rotate", shoulderPrehabOpen),
    ),
    cycleSeconds = 2.8,
)

// --- Standard singles half-court landmarks (metres; net at y = 0, baseline at y = 6.7) ---
private val midBase = VisualPoint(3.05f, 3.40f)
private val defendBase = VisualPoint(3.05f, 4.10f)
private val netCentre = VisualPoint(3.05f, 0.0f)
private val frontBase = VisualPoint(3.05f, 2.35f)
private val frontRight = VisualPoint(5.05f, 0.90f)
private val frontLeft = VisualPoint(1.05f, 0.90f)
private val midRight = VisualPoint(5.30f, 3.40f)
private val midLeft = VisualPoint(0.80f, 3.40f)
private val rearRight = VisualPoint(5.15f, 5.95f)
private val rearLeft = VisualPoint(0.95f, 5.95f)
private val midRearRight = VisualPoint(4.65f, 4.90f)
private val rightServeSpot = VisualPoint(3.55f, 2.25f)
private val leftServeSpot = VisualPoint(2.55f, 2.25f)
private val rightReceiveSpot = VisualPoint(4.25f, 2.55f)
private val leftReceiveSpot = VisualPoint(1.85f, 2.55f)

private val allVisuals: List<ExerciseVisual> = listOf(
    // Footwork, Base & Court Movement
    ExerciseVisual(
        exerciseId = "base-mark-6-corner-shadow",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(
                midBase, frontRight, midBase, rearRight, midBase,
                frontLeft, midBase, rearLeft, midBase,
                midRight, midBase, midLeft, midBase,
            ),
            shuttleStart = null,
            shuttleEnd = null,
            cycleSeconds = 7.2,
        ),
        pose = baseRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "clear-to-base-multi-shuttle",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearRight, midBase, rearLeft, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearRight,
            cycleSeconds = 4.2,
        ),
        pose = overheadRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "net-to-rear-recovery",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontRight, midBase, rearRight, midBase),
            shuttleStart = netCentre,
            shuttleEnd = frontRight,
            cycleSeconds = 4.2,
        ),
        pose = netToRearPose,
    ),
    ExerciseVisual(
        exerciseId = "front-back-2-corner-feed",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontRight, midBase, rearRight, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearRight,
            cycleSeconds = 4.0,
        ),
        pose = netToRearPose,
    ),
    ExerciseVisual(
        exerciseId = "opposite-side-corner-recovery",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, midRight, midBase, midRight, midBase, midLeft, midBase),
            shuttleStart = netCentre,
            shuttleEnd = midLeft,
            cycleSeconds = 4.8,
        ),
        pose = lateralBalancePose,
    ),
    ExerciseVisual(
        exerciseId = "centre-line-split-reset",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, midRight, midBase, midLeft, midBase),
            shuttleStart = null,
            shuttleEnd = null,
            cycleSeconds = 3.8,
        ),
        pose = lateralBalancePose,
    ),
    ExerciseVisual(
        exerciseId = "fatigue-6-corner-shadow",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(
                midBase, rearRight, midBase, frontLeft, midBase,
                rearLeft, midBase, frontRight, midBase,
            ),
            shuttleStart = null,
            shuttleEnd = null,
            cycleSeconds = 5.4,
        ),
        pose = baseRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "cone-touch-base-discipline",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontRight, midBase, rearLeft, midBase, frontLeft, midBase, rearRight, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearLeft,
            cycleSeconds = 5.6,
        ),
        pose = baseRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "drop-and-follow-net-attack",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearRight, frontRight, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearRight,
            cycleSeconds = 4.0,
        ),
        pose = overheadRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "forecourt-lunge-net-kill",
        courtPath = CourtDrillPath(
            baseMarker = frontBase,
            waypoints = listOf(frontBase, frontRight, frontBase, frontLeft, frontBase),
            shuttleStart = netCentre,
            shuttleEnd = frontRight,
            cycleSeconds = 3.6,
        ),
        pose = forecourtNetKillPose,
    ),
    ExerciseVisual(
        exerciseId = "repeat-burst-multi-shuttle",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontLeft, midBase, rearRight, midBase, frontRight, midBase, rearLeft, midBase),
            shuttleStart = netCentre,
            shuttleEnd = frontLeft,
            cycleSeconds = 3.8,
        ),
        pose = baseRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "figure-8-chasse-shadow",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontRight, midBase, rearLeft, midBase, frontLeft, midBase, rearRight, midBase),
            shuttleStart = null,
            shuttleEnd = null,
            cycleSeconds = 5.6,
        ),
        pose = baseRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "china-jump-sideline-intercept",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, VisualPoint(5.25f, 3.00f), midBase, VisualPoint(5.15f, 4.10f), midBase),
            shuttleStart = netCentre,
            shuttleEnd = VisualPoint(5.25f, 3.00f),
            cycleSeconds = 3.6,
        ),
        pose = chinaJumpPose,
    ),
    ExerciseVisual(
        exerciseId = "star-4-corner-split-timing",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, frontLeft, midBase, frontRight, midBase, rearRight, midBase, rearLeft, midBase),
            shuttleStart = null,
            shuttleEnd = null,
            cycleSeconds = 5.4,
        ),
        pose = reactiveSplitPose,
    ),

    // Attack & Rear-Court Weapons
    ExerciseVisual(
        exerciseId = "around-the-head-scissor-kick",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearLeft, midBase, VisualPoint(1.50f, 5.40f), midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearLeft,
            cycleSeconds = 4.0,
        ),
        pose = scissorKickPose,
    ),
    ExerciseVisual(
        exerciseId = "jump-smash-and-follow",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearRight, VisualPoint(4.50f, 1.35f), midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearRight,
            cycleSeconds = 4.0,
        ),
        pose = jumpSmashPose,
    ),
    ExerciseVisual(
        exerciseId = "rear-corner-slice-drop-variation",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearRight, midBase, rearLeft, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearLeft,
            cycleSeconds = 4.2,
        ),
        pose = disguisedSlicePose,
    ),
    ExerciseVisual(
        exerciseId = "stick-smash-half-court-pressure",
        courtPath = CourtDrillPath(
            baseMarker = VisualPoint(4.55f, 3.60f),
            waypoints = listOf(
                VisualPoint(4.55f, 3.60f),
                midRearRight,
                VisualPoint(4.65f, 3.10f),
                VisualPoint(4.55f, 3.60f),
            ),
            shuttleStart = VisualPoint(4.55f, 0.0f),
            shuttleEnd = midRearRight,
            cycleSeconds = 3.6,
        ),
        pose = overheadRecoveryPose,
    ),
    ExerciseVisual(
        exerciseId = "backhand-rear-corner-clear",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, rearLeft, midBase),
            shuttleStart = netCentre,
            shuttleEnd = rearLeft,
            cycleSeconds = 3.6,
        ),
        pose = backhandEscapePose,
    ),

    // Defence & Flat Mid-Court Exchanges
    ExerciseVisual(
        exerciseId = "low-smash-block-and-lift",
        courtPath = CourtDrillPath(
            baseMarker = defendBase,
            waypoints = listOf(
                defendBase,
                VisualPoint(4.95f, 4.10f),
                defendBase,
                VisualPoint(1.15f, 4.10f),
                defendBase,
            ),
            shuttleStart = netCentre,
            shuttleEnd = VisualPoint(4.95f, 4.10f),
            cycleSeconds = 3.8,
        ),
        pose = lowDefencePose,
    ),
    ExerciseVisual(
        exerciseId = "flat-drive-channel-exchange",
        courtPath = CourtDrillPath(
            baseMarker = VisualPoint(4.60f, 3.30f),
            waypoints = listOf(
                VisualPoint(4.60f, 3.30f),
                VisualPoint(4.80f, 2.55f),
                VisualPoint(4.60f, 3.30f),
                VisualPoint(4.50f, 3.95f),
                VisualPoint(4.60f, 3.30f),
            ),
            shuttleStart = VisualPoint(4.60f, 0.0f),
            shuttleEnd = VisualPoint(4.80f, 2.55f),
            cycleSeconds = 3.2,
        ),
        pose = flatDrivePose,
    ),
    ExerciseVisual(
        exerciseId = "defensive-cross-block-recovery",
        courtPath = CourtDrillPath(
            baseMarker = defendBase,
            waypoints = listOf(defendBase, VisualPoint(3.45f, 4.25f), VisualPoint(2.20f, 2.85f), defendBase),
            shuttleStart = VisualPoint(4.60f, 0.0f),
            shuttleEnd = VisualPoint(3.45f, 4.25f),
            cycleSeconds = 3.8,
        ),
        pose = bodyDeflectPose,
    ),
    ExerciseVisual(
        exerciseId = "wide-forehand-dive-save",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(midBase, VisualPoint(5.45f, 3.70f), midBase, VisualPoint(0.65f, 3.70f), midBase),
            shuttleStart = netCentre,
            shuttleEnd = VisualPoint(5.45f, 3.70f),
            cycleSeconds = 4.2,
        ),
        pose = wideReachPose,
    ),
    ExerciseVisual(
        exerciseId = "half-court-consistency-20",
        courtPath = CourtDrillPath(
            baseMarker = VisualPoint(4.60f, 3.45f),
            waypoints = listOf(
                VisualPoint(4.60f, 3.45f),
                VisualPoint(4.65f, 1.15f),
                VisualPoint(4.60f, 3.45f),
                VisualPoint(4.70f, 5.65f),
                VisualPoint(4.60f, 3.45f),
            ),
            shuttleStart = VisualPoint(4.60f, 0.0f),
            shuttleEnd = VisualPoint(4.70f, 5.65f),
            cycleSeconds = 4.6,
        ),
        pose = netToRearPose,
    ),

    // Net Play, Serve & 3rd-Shot Control
    ExerciseVisual(
        exerciseId = "tumbling-net-spin-duel",
        courtPath = CourtDrillPath(
            baseMarker = frontBase,
            waypoints = listOf(frontBase, frontRight, frontBase, frontLeft, frontBase),
            shuttleStart = netCentre,
            shuttleEnd = frontLeft,
            cycleSeconds = 3.8,
        ),
        pose = netSpinPose,
    ),
    ExerciseVisual(
        exerciseId = "cross-net-and-hold-deception",
        courtPath = CourtDrillPath(
            baseMarker = frontBase,
            waypoints = listOf(frontBase, frontRight, frontBase, frontLeft, frontBase),
            shuttleStart = VisualPoint(1.20f, 0.0f),
            shuttleEnd = frontRight,
            cycleSeconds = 4.0,
        ),
        pose = netSpinPose,
    ),
    ExerciseVisual(
        exerciseId = "low-flick-serve-third-shot",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(rightServeSpot, midBase, leftServeSpot, midBase, rightServeSpot),
            shuttleStart = rightServeSpot,
            shuttleEnd = VisualPoint(2.20f, 0.0f),
            cycleSeconds = 4.4,
        ),
        pose = serveThirdShotPose,
    ),
    ExerciseVisual(
        exerciseId = "serve-receive-rush-and-push",
        courtPath = CourtDrillPath(
            baseMarker = midBase,
            waypoints = listOf(rightReceiveSpot, frontRight, midBase, leftReceiveSpot, frontLeft, midBase, rightReceiveSpot),
            shuttleStart = VisualPoint(2.50f, 0.0f),
            shuttleEnd = rightReceiveSpot,
            cycleSeconds = 4.8,
        ),
        pose = receiveRushPose,
    ),

    // Off-Court Strength, Power, Endurance & Prehab
    ExerciseVisual(
        exerciseId = "split-squat-endurance",
        courtPath = null,
        pose = splitSquatPose,
    ),
    ExerciseVisual(
        exerciseId = "lateral-bounds-split-jumps",
        courtPath = null,
        pose = lateralBoundsPose,
    ),
    ExerciseVisual(
        exerciseId = "lunge-deceleration-pushback",
        courtPath = null,
        pose = dropLungePose,
    ),
    ExerciseVisual(
        exerciseId = "pogo-split-step-hops",
        courtPath = null,
        pose = pogoSplitPose,
    ),
    ExerciseVisual(
        exerciseId = "single-leg-rdl-balance",
        courtPath = null,
        pose = singleLegRdlPose,
    ),
    ExerciseVisual(
        exerciseId = "copenhagen-adductor-hold",
        courtPath = null,
        pose = sideLungePose,
    ),
    ExerciseVisual(
        exerciseId = "rotational-core-chop",
        courtPath = null,
        pose = rotationalCorePose,
    ),
    ExerciseVisual(
        exerciseId = "plank-shoulder-taps",
        courtPath = null,
        pose = plankTapPose,
    ),
    ExerciseVisual(
        exerciseId = "eccentric-calf-ankle-control",
        courtPath = null,
        pose = calfRaisePose,
    ),
    ExerciseVisual(
        exerciseId = "shoulder-external-rotation-prehab",
        courtPath = null,
        pose = shoulderPrehabPose,
    ),
)

private val visualIndex: Map<String, ExerciseVisual> = allVisuals.associateBy { it.exerciseId }
