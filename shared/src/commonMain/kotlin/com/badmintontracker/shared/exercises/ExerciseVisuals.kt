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

// Reusable athletic pose keyframes (right-handed default; mirrored automatically for left-handed).

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
    racketTip = null,
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
    racketTip = null,
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
    racketTip = null,
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
    racketTip = null,
)

// Pose sequences mapped to the 13 catalog exercises.

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

// Standard singles half-court landmarks (metres): base ~1.42 m behind service line (y = 3.40).
private val midBase = VisualPoint(3.05f, 3.40f)
private val netCentre = VisualPoint(3.05f, 0.0f)
private val frontRight = VisualPoint(5.05f, 0.90f)
private val frontLeft = VisualPoint(1.05f, 0.90f)
private val midRight = VisualPoint(5.30f, 3.40f)
private val midLeft = VisualPoint(0.80f, 3.40f)
private val rearRight = VisualPoint(5.15f, 5.95f)
private val rearLeft = VisualPoint(0.95f, 5.95f)

private val allVisuals: List<ExerciseVisual> = listOf(
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
        exerciseId = "split-squat-endurance",
        courtPath = null,
        pose = splitSquatPose,
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
            baseMarker = VisualPoint(3.05f, 2.35f),
            waypoints = listOf(
                VisualPoint(3.05f, 2.35f), frontRight,
                VisualPoint(3.05f, 2.35f), frontLeft,
                VisualPoint(3.05f, 2.35f),
            ),
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
        exerciseId = "lateral-bounds-split-jumps",
        courtPath = null,
        pose = lateralBoundsPose,
    ),
)

private val visualIndex: Map<String, ExerciseVisual> = allVisuals.associateBy { it.exerciseId }
