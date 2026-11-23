package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.geometry.Court
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/** Shuttle flight trajectory drawn inside the pose tile for shuttle-fed drills. */
enum class ShuttleArc {
    NONE,
    OVERHEAD_CLEAR,
    SMASH,
    DROP_NET,
    FLAT_DRIVE,
    DEFENCE_LIFT,
    SERVE,
}

/** Looping pose animation defined by 2 to 4 keyframes over [cycleSeconds]. */
data class PoseSequence(
    val keyframes: List<PoseKeyframe>,
    val cycleSeconds: Double,
    val shuttleArc: ShuttleArc = ShuttleArc.NONE,
)

/** Live shuttlecock position and travel orientation inside normalized [0, 1] pose space. */
data class PoseShuttle(
    val position: VisualPoint,
    val velocityAngleRad: Float,
    val alpha: Float,
    /** Non-zero flash intensity (0..1) right at racket contact. */
    val impactFlash: Float,
)

/** Subtle expanding floor ring triggered at split-step or lunge landing moments. */
data class FloorRipple(
    val centerX: Float,
    val progress01: Float,
)

/** Enriched interpolated pose ready for stylized Canvas rendering on Android and iOS. */
data class SampledPose(
    val label: String,
    val figure: PoseFigure,
    val keyframeIndex: Int,
    val keyframeCount: Int,
    /** Recent racket-head positions (oldest to newest) for motion-trail swooshes. */
    val racketTrail: List<VisualPoint>,
    /** Animated incoming/rebounding shuttlecock for shuttle-fed drills, null otherwise. */
    val shuttle: PoseShuttle?,
    /** Expanding ground contact ripple at landing moments, null when steady. */
    val floorRipple: FloorRipple?,
    /** 0 when planted on the floor up to 1 at peak jump height (scales ground shadow). */
    val airborneFactor: Float,
    /** Horizontal facing cue (+1 right, -1 left) used for head profile and shoes. */
    val facingSign: Float,
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

/** Rich frame state for the half-court diagram (curved path, trail, synced shuttle, pulses). */
data class SampledCourtState(
    val playerPosition: VisualPoint,
    val playerTrail: List<VisualPoint>,
    val shuttlePosition: VisualPoint?,
    val basePulse01: Float,
    val cornerPulsePoint: VisualPoint?,
    val cornerPulse01: Float,
    val smoothRoute: List<VisualPoint>,
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
 * Samples [sequence] at cyclic phase [phase01] (wrapped into `[0, 1)`), using
 * bone-length-preserving forward-kinematics rotation between keyframes, plus
 * racket motion trail, shuttle flight, landing ripples, and left-hand mirroring.
 */
fun samplePose(sequence: PoseSequence, phase01: Float, mirrorX: Boolean): SampledPose {
    val frames = sequence.keyframes
    require(frames.isNotEmpty()) { "PoseSequence must contain at least one keyframe" }
    val wrapped = wrapPhase(phase01)
    val baseSample = sampleRawPose(frames, wrapped)
    val figure = if (mirrorX) mirrorFigure(baseSample.figure) else baseSample.figure

    // Build racket-head swoosh trail when the racket moves briskly across recent micro-phases.
    val trail = buildRacketTrail(frames, wrapped, mirrorX)

    // Compute live shuttlecock trajectory synced to contact frame (index 1) for shuttle drills.
    val shuttle = samplePoseShuttle(sequence, wrapped, mirrorX)

    // Trigger expanding ground ripple when feet plant cleanly onto POSE_GROUND_Y.
    val ripple = sampleFloorRipple(frames, wrapped, mirrorX)

    val lowestFootY = max(figure.ankleL.y, figure.ankleR.y)
    val airborneFactor = ((POSE_GROUND_Y - lowestFootY) / 0.14f).coerceIn(0f, 1f)
    val facingSign = computeFacingSign(figure, mirrorX)

    return SampledPose(
        label = baseSample.label,
        figure = figure,
        keyframeIndex = baseSample.activeIndex,
        keyframeCount = frames.size,
        racketTrail = trail,
        shuttle = shuttle,
        floorRipple = ripple,
        airborneFactor = airborneFactor,
        facingSign = facingSign,
    )
}

/**
 * Samples the moving player dot along [path]'s closed waypoint loop at cyclic
 * phase [phase01], mirroring across the centre line when [mirrorX] is true.
 */
fun sampleCourtPoint(path: CourtDrillPath, phase01: Float, mirrorX: Boolean): VisualPoint =
    sampleCourtState(path, phase01, mirrorX).playerPosition

/**
 * Samples the full synchronized half-court frame: distance-weighted curved player
 * movement, fading motion trail, live shuttle feed, and base/corner arrival pulses.
 */
fun sampleCourtState(path: CourtDrillPath, phase01: Float, mirrorX: Boolean): SampledCourtState {
    val pts = path.waypoints
    require(pts.isNotEmpty()) { "CourtDrillPath must contain at least one waypoint" }
    val wrapped = wrapPhase(phase01)
    val baseRaw = path.baseMarker

    if (pts.size == 1) {
        val single = maybeMirrorCourt(pts[0], mirrorX)
        return SampledCourtState(
            playerPosition = single,
            playerTrail = emptyList(),
            shuttlePosition = null,
            basePulse01 = 0f,
            cornerPulsePoint = null,
            cornerPulse01 = 0f,
            smoothRoute = listOf(single),
        )
    }

    val segmentLengths = FloatArray(pts.size - 1) { i ->
        hypot(
            pts[i + 1].x - pts[i].x,
            pts[i + 1].y - pts[i].y,
        ).coerceAtLeast(0.45f)
    }
    val totalLength = segmentLengths.sum().coerceAtLeast(1e-3f)

    fun evalCurvedSegment(segIdx: Int, localT: Float): VisualPoint {
        val a = pts[segIdx]
        val b = pts[segIdx + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) return a
        // Subtle lateral bow separates outbound and recovery paths cleanly like a coaching diagram.
        val bow = min(0.32f, len * 0.085f)
        val nx = -dy / len
        val ny = dx / len
        val ctrl = VisualPoint(
            ((a.x + b.x) * 0.5f + nx * bow).coerceIn(0.15f, Court.WIDTH_DOUBLES.toFloat() - 0.15f),
            ((a.y + b.y) * 0.5f + ny * bow).coerceIn(0.15f, (Court.LENGTH / 2.0).toFloat() - 0.15f),
        )
        return quadBezier(a, ctrl, b, localT)
    }

    fun pointAtPhase(p01: Float): Triple<VisualPoint, Int, Float> {
        val targetDist = wrapPhase(p01) * totalLength
        var acc = 0f
        for (i in segmentLengths.indices) {
            val segLen = segmentLengths[i]
            if (targetDist <= acc + segLen || i == segmentLengths.lastIndex) {
                val rawT = ((targetDist - acc) / segLen).coerceIn(0f, 1f)
                // Brief deceleration hold at each corner/base milestone for crisp footwork feel.
                val easedT = if (rawT < 0.08f) {
                    0f
                } else {
                    smoothstep((rawT - 0.08f) / 0.92f)
                }
                return Triple(evalCurvedSegment(i, easedT), i, rawT)
            }
            acc += segLen
        }
        return Triple(pts.first(), 0, 0f)
    }

    val (pos, activeSeg, rawSegT) = pointAtPhase(wrapped)

    // Fading motion trail sampled across the preceding 6% of the loop.
    val trailSteps = 6
    val trail = ArrayList<VisualPoint>(trailSteps)
    for (k in trailSteps downTo 1) {
        val (tp, _, _) = pointAtPhase(wrapped - k * 0.011f)
        trail += tp
    }

    // Smooth curved route polyline for Canvas path rendering.
    val smoothRoute = ArrayList<VisualPoint>((pts.size - 1) * 6 + 1)
    for (i in segmentLengths.indices) {
        val steps = 6
        for (s in 0 until steps) {
            smoothRoute += evalCurvedSegment(i, s.toFloat() / steps.toFloat())
        }
    }
    smoothRoute += pts.last()

    // Base & corner arrival pulses right as the player reaches each waypoint.
    val arrivalStart = pts[activeSeg]
    val arrivalEnd = pts[activeSeg + 1]
    var basePulse = 0f
    var cornerPulsePoint: VisualPoint? = null
    var cornerPulse = 0f

    fun isNearBase(p: VisualPoint): Boolean = hypot(p.x - baseRaw.x, p.y - baseRaw.y) <= 0.35f

    if (rawSegT < 0.28f) {
        val pulse = 1f - (rawSegT / 0.28f)
        if (isNearBase(arrivalStart)) {
            basePulse = pulse
        } else {
            cornerPulsePoint = arrivalStart
            cornerPulse = pulse
        }
    } else if (rawSegT > 0.85f) {
        val pulse = (rawSegT - 0.85f) / 0.15f * 0.45f
        if (isNearBase(arrivalEnd)) {
            basePulse = max(basePulse, pulse)
        } else {
            cornerPulsePoint = arrivalEnd
            cornerPulse = pulse
        }
    }

    // Synchronized live shuttle flying from feeder to the target corner when the player moves out.
    val shuttlePos = if (path.shuttleStart != null) {
        val feeder = path.shuttleStart
        if (!isNearBase(arrivalEnd)) {
            val flightT = smoothstep(rawSegT.coerceIn(0f, 1f))
            lerpPoint(feeder, arrivalEnd, flightT)
        } else if (!isNearBase(arrivalStart)) {
            val returnT = smoothstep((rawSegT * 1.4f).coerceIn(0f, 1f))
            val targetNet = VisualPoint((arrivalStart.x * 0.4f + feeder.x * 0.6f), 0.15f)
            lerpPoint(arrivalStart, targetNet, returnT)
        } else {
            path.shuttleEnd?.let { target ->
                lerpPoint(feeder, target, smoothstep(rawSegT))
            }
        }
    } else {
        null
    }

    return SampledCourtState(
        playerPosition = maybeMirrorCourt(pos, mirrorX),
        playerTrail = trail.map { maybeMirrorCourt(it, mirrorX) },
        shuttlePosition = shuttlePos?.let { maybeMirrorCourt(it, mirrorX) },
        basePulse01 = basePulse.coerceIn(0f, 1f),
        cornerPulsePoint = cornerPulsePoint?.let { maybeMirrorCourt(it, mirrorX) },
        cornerPulse01 = cornerPulse.coerceIn(0f, 1f),
        smoothRoute = smoothRoute.map { maybeMirrorCourt(it, mirrorX) },
    )
}

/** Mirrors a list of half-court points across the centre line when [mirrorX] is true. */
fun mirroredCourtPoints(points: List<VisualPoint>, mirrorX: Boolean): List<VisualPoint> =
    if (!mirrorX) points else points.map { maybeMirrorCourt(it, mirrorX = true) }

/** Mirrors a single half-court point across the centre line when [mirrorX] is true. */
fun mirroredCourtPoint(point: VisualPoint, mirrorX: Boolean): VisualPoint =
    maybeMirrorCourt(point, mirrorX)

private data class RawPoseSample(
    val label: String,
    val figure: PoseFigure,
    val activeIndex: Int,
)

private fun sampleRawPose(frames: List<PoseKeyframe>, wrappedPhase: Float): RawPoseSample {
    if (frames.size == 1) {
        return RawPoseSample(frames[0].label, frames[0].figure, 0)
    }
    val scaled = wrappedPhase * frames.size
    val idx = floor(scaled).toInt().coerceIn(0, frames.size - 1)
    val nextIdx = (idx + 1) % frames.size
    val localT = scaled - idx
    // Brief poised hold at the keyframe (first 22%) before accelerating cleanly into the next pose.
    val moveT = if (localT <= 0.22f) 0f else smoothstep((localT - 0.22f) / 0.78f)
    val blended = lerpFigureFk(frames[idx].figure, frames[nextIdx].figure, moveT)
    val labelIdx = if (localT < 0.70f) idx else nextIdx
    return RawPoseSample(
        label = frames[labelIdx].label,
        figure = blended,
        activeIndex = labelIdx,
    )
}

private fun buildRacketTrail(
    frames: List<PoseKeyframe>,
    wrappedPhase: Float,
    mirrorX: Boolean,
): List<VisualPoint> {
    if (frames.first().figure.racketTip == null || frames.size < 2) return emptyList()
    val count = 6
    val points = ArrayList<VisualPoint>(count)
    for (k in (count - 1) downTo 0) {
        val raw = sampleRawPose(frames, wrapPhase(wrappedPhase - k * 0.014f)).figure
        val fig = if (mirrorX) mirrorFigure(raw) else raw
        val tip = fig.racketTip ?: return emptyList()
        // Track center of racket oval (78% along wrist->tip) for an authentic swoosh arc.
        points += lerpPoint(fig.wristR, tip, 0.78f)
    }
    val totalArc = (1 until points.size).fold(0f) { acc, i ->
        acc + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    // Hide trail during stationary holds so it only flares on brisk swings.
    return if (totalArc < 0.045f) emptyList() else points
}

private fun samplePoseShuttle(
    sequence: PoseSequence,
    wrappedPhase: Float,
    mirrorX: Boolean,
): PoseShuttle? {
    val arc = sequence.shuttleArc
    if (arc == ShuttleArc.NONE || sequence.keyframes.size < 2) return null
    val contactIdx = 1.coerceAtMost(sequence.keyframes.lastIndex)
    val contactFig = sequence.keyframes[contactIdx].figure
    val contactTip = contactFig.racketTip ?: return null
    val contactPt = lerpPoint(contactFig.wristR, contactTip, 0.78f)
    val contactPhase = (contactIdx.toFloat() + 0.10f) / sequence.keyframes.size.toFloat()
    val rel = wrapPhase(wrappedPhase - contactPhase)

    // Approach window: rel in [0.64, 1.0), Outgoing window: rel in [0.0, 0.36].
    val (rawPos, rawVel, rawAlpha, flash) = when {
        rel >= 0.64f -> {
            val s = ((rel - 0.64f) / 0.36f).coerceIn(0f, 1f)
            val (start, ctrl) = incomingArc(arc, contactPt)
            val pos = quadBezier(start, ctrl, contactPt, s)
            val tan = quadBezierTangent(start, ctrl, contactPt, s)
            val alpha = min(1f, s / 0.18f)
            QuadShuttle(pos, atan2(tan.y, tan.x), alpha, 0f)
        }
        rel <= 0.36f -> {
            val s = (rel / 0.36f).coerceIn(0f, 1f)
            val (ctrl, end) = outgoingArc(arc, contactPt)
            val pos = quadBezier(contactPt, ctrl, end, s)
            val tan = quadBezierTangent(contactPt, ctrl, end, s)
            val alpha = min(1f, (1f - s) / 0.22f)
            val impact = if (s < 0.25f) 1f - (s / 0.25f) else 0f
            QuadShuttle(pos, atan2(tan.y, tan.x), alpha, impact)
        }
        else -> return null
    }

    val pos = if (mirrorX) mirrorPoint01(rawPos) else rawPos
    val velAngle = if (mirrorX) (PI.toFloat() - rawVel) else rawVel
    return PoseShuttle(
        position = pos,
        velocityAngleRad = velAngle,
        alpha = rawAlpha.coerceIn(0f, 1f),
        impactFlash = flash.coerceIn(0f, 1f),
    )
}

private data class QuadShuttle(
    val pos: VisualPoint,
    val angleRad: Float,
    val alpha: Float,
    val impactFlash: Float,
)

private fun incomingArc(arc: ShuttleArc, contact: VisualPoint): Pair<VisualPoint, VisualPoint> = when (arc) {
    ShuttleArc.OVERHEAD_CLEAR, ShuttleArc.SMASH -> Pair(
        VisualPoint(0.96f, 0.14f),
        VisualPoint((contact.x + 0.96f) * 0.5f, min(contact.y, 0.06f)),
    )
    ShuttleArc.DROP_NET -> Pair(
        VisualPoint(0.96f, 0.26f),
        VisualPoint((contact.x + 0.96f) * 0.5f, (contact.y + 0.16f) * 0.5f),
    )
    ShuttleArc.FLAT_DRIVE -> Pair(
        VisualPoint(0.97f, (contact.y + 0.04f).coerceIn(0.10f, 0.80f)),
        VisualPoint((contact.x + 0.97f) * 0.5f, contact.y),
    )
    ShuttleArc.DEFENCE_LIFT -> Pair(
        VisualPoint(0.95f, 0.08f),
        VisualPoint((contact.x + 0.95f) * 0.5f, (contact.y + 0.12f) * 0.5f),
    )
    ShuttleArc.SERVE -> Pair(
        VisualPoint((contact.x - 0.05f).coerceIn(0.1f, 0.9f), (contact.y - 0.12f).coerceIn(0.1f, 0.8f)),
        VisualPoint(contact.x, (contact.y - 0.05f).coerceIn(0.1f, 0.8f)),
    )
    ShuttleArc.NONE -> Pair(contact, contact)
}

private fun outgoingArc(arc: ShuttleArc, contact: VisualPoint): Pair<VisualPoint, VisualPoint> = when (arc) {
    ShuttleArc.OVERHEAD_CLEAR, ShuttleArc.DEFENCE_LIFT -> Pair(
        VisualPoint((contact.x + 0.96f) * 0.5f, 0.05f),
        VisualPoint(0.97f, 0.12f),
    )
    ShuttleArc.SMASH -> Pair(
        VisualPoint((contact.x + 0.96f) * 0.5f, (contact.y + 0.62f) * 0.45f),
        VisualPoint(0.97f, 0.72f),
    )
    ShuttleArc.DROP_NET -> Pair(
        VisualPoint((contact.x + 0.96f) * 0.52f, (contact.y - 0.05f).coerceAtLeast(0.08f)),
        VisualPoint(0.97f, (contact.y + 0.22f).coerceAtMost(0.80f)),
    )
    ShuttleArc.FLAT_DRIVE -> Pair(
        VisualPoint((contact.x + 0.97f) * 0.5f, (contact.y - 0.02f).coerceAtLeast(0.08f)),
        VisualPoint(0.98f, contact.y),
    )
    ShuttleArc.SERVE -> Pair(
        VisualPoint((contact.x + 0.96f) * 0.5f, 0.16f),
        VisualPoint(0.97f, 0.24f),
    )
    ShuttleArc.NONE -> Pair(contact, contact)
}

private fun sampleFloorRipple(
    frames: List<PoseKeyframe>,
    wrappedPhase: Float,
    mirrorX: Boolean,
): FloorRipple? {
    if (frames.size < 2) return null
    val scaled = wrappedPhase * frames.size
    val idx = floor(scaled).toInt().coerceIn(0, frames.size - 1)
    val prevIdx = (idx - 1 + frames.size) % frames.size
    val localT = scaled - idx
    if (localT > 0.45f) return null

    val cur = frames[idx].figure
    val prev = frames[prevIdx].figure
    val curGrounded = max(cur.ankleL.y, cur.ankleR.y) >= POSE_GROUND_Y - 0.015f
    if (!curGrounded) return null

    val prevAirborne = max(prev.ankleL.y, prev.ankleR.y) < POSE_GROUND_Y - 0.03f
    val ankleShift = max(
        hypot(cur.ankleL.x - prev.ankleL.x, cur.ankleL.y - prev.ankleL.y),
        hypot(cur.ankleR.x - prev.ankleR.x, cur.ankleR.y - prev.ankleR.y),
    )
    if (!prevAirborne && ankleShift < 0.08f) return null

    // Center ripple under the lead landing foot (whichever moved further or landed on ground).
    val shiftL = hypot(cur.ankleL.x - prev.ankleL.x, cur.ankleL.y - prev.ankleL.y)
    val shiftR = hypot(cur.ankleR.x - prev.ankleR.x, cur.ankleR.y - prev.ankleR.y)
    val rawCenterX = when {
        prevAirborne -> (cur.ankleL.x + cur.ankleR.x) * 0.5f
        shiftR >= shiftL -> cur.ankleR.x
        else -> cur.ankleL.x
    }
    return FloorRipple(
        centerX = if (mirrorX) 1f - rawCenterX else rawCenterX,
        progress01 = (localT / 0.45f).coerceIn(0f, 1f),
    )
}

private fun computeFacingSign(figure: PoseFigure, mirrorX: Boolean): Float {
    val tip = figure.racketTip
    val midAnkleX = (figure.ankleL.x + figure.ankleR.x) * 0.5f
    if (tip != null) {
        val dx = tip.x - figure.head.x
        if (dx > 0.06f) return 1f
        if (dx < -0.06f) return -1f
    }
    val lean = figure.head.x - midAnkleX
    if (lean > 0.03f) return 1f
    if (lean < -0.03f) return -1f
    return if (mirrorX) -1f else 1f
}

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

private fun midpoint(a: VisualPoint, b: VisualPoint): VisualPoint =
    VisualPoint((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

private fun quadBezier(p0: VisualPoint, p1: VisualPoint, p2: VisualPoint, t: Float): VisualPoint {
    val inv = 1f - t
    return VisualPoint(
        x = inv * inv * p0.x + 2f * inv * t * p1.x + t * t * p2.x,
        y = inv * inv * p0.y + 2f * inv * t * p1.y + t * t * p2.y,
    )
}

private fun quadBezierTangent(p0: VisualPoint, p1: VisualPoint, p2: VisualPoint, t: Float): VisualPoint =
    VisualPoint(
        x = 2f * (1f - t) * (p1.x - p0.x) + 2f * t * (p2.x - p1.x),
        y = 2f * (1f - t) * (p1.y - p0.y) + 2f * t * (p2.y - p1.y),
    )

private fun wrapAnglePi(radians: Float): Float {
    var a = radians
    val twoPi = (2.0 * PI).toFloat()
    while (a > PI.toFloat()) a -= twoPi
    while (a <= -PI.toFloat()) a += twoPi
    return a
}

/**
 * Interpolates a single limb/bone segment in polar coordinates (angle + length)
 * anchored at [origin] so swings and strides sweep in true circular arcs without
 * collapsing bone lengths mid-motion.
 */
private fun lerpBone(
    origin: VisualPoint,
    fromA: VisualPoint,
    toA: VisualPoint,
    fromB: VisualPoint,
    toB: VisualPoint,
    t: Float,
): VisualPoint {
    val dxA = toA.x - fromA.x
    val dyA = toA.y - fromA.y
    val dxB = toB.x - fromB.x
    val dyB = toB.y - fromB.y
    val lenA = hypot(dxA, dyA)
    val lenB = hypot(dxB, dyB)
    if (lenA < 1e-4f || lenB < 1e-4f) {
        return VisualPoint(
            origin.x + dxA + (dxB - dxA) * t,
            origin.y + dyA + (dyB - dyA) * t,
        )
    }
    val angA = atan2(dyA, dxA)
    val angB = atan2(dyB, dxB)
    val ang = angA + wrapAnglePi(angB - angA) * t
    val len = lenA + (lenB - lenA) * t
    return VisualPoint(
        x = origin.x + cos(ang) * len,
        y = origin.y + sin(ang) * len,
    )
}

/**
 * Forward-kinematics hierarchical interpolation from pelvis root out to hands,
 * feet, and racket tip, with gentle floor-penetration clamping so grounded feet
 * never dip underneath [POSE_GROUND_Y] during rotational arcs.
 */
private fun lerpFigureFk(a: PoseFigure, b: PoseFigure, t: Float): PoseFigure {
    if (t <= 0f) return a
    if (t >= 1f) return b
    val midHipA = midpoint(a.hipL, a.hipR)
    val midHipB = midpoint(b.hipL, b.hipR)
    val midHip = lerpPoint(midHipA, midHipB, t)

    val hipL = lerpBone(midHip, midHipA, a.hipL, midHipB, b.hipL, t)
    val hipR = lerpBone(midHip, midHipA, a.hipR, midHipB, b.hipR, t)
    val neck = lerpBone(midHip, midHipA, a.neck, midHipB, b.neck, t)
    val head = lerpBone(neck, a.neck, a.head, b.neck, b.head, t)
    val shoulderL = lerpBone(neck, a.neck, a.shoulderL, b.neck, b.shoulderL, t)
    val shoulderR = lerpBone(neck, a.neck, a.shoulderR, b.neck, b.shoulderR, t)

    val elbowL = lerpBone(shoulderL, a.shoulderL, a.elbowL, b.shoulderL, b.elbowL, t)
    val wristL = lerpBone(elbowL, a.elbowL, a.wristL, b.elbowL, b.wristL, t)
    val elbowR = lerpBone(shoulderR, a.shoulderR, a.elbowR, b.shoulderR, b.elbowR, t)
    val wristR = lerpBone(elbowR, a.elbowR, a.wristR, b.elbowR, b.wristR, t)

    val kneeL = lerpBone(hipL, a.hipL, a.kneeL, b.hipL, b.kneeL, t)
    val ankleL = lerpBone(kneeL, a.kneeL, a.ankleL, b.kneeL, b.ankleL, t)
    val kneeR = lerpBone(hipR, a.hipR, a.kneeR, b.hipR, b.kneeR, t)
    val ankleR = lerpBone(kneeR, a.kneeR, a.ankleR, b.kneeR, b.ankleR, t)

    val racketTip = if (a.racketTip != null && b.racketTip != null) {
        lerpBone(wristR, a.wristR, a.racketTip, b.wristR, b.racketTip, t)
    } else {
        a.racketTip
    }

    val raw = PoseFigure(
        head = head,
        neck = neck,
        shoulderL = shoulderL,
        shoulderR = shoulderR,
        elbowL = elbowL,
        elbowR = elbowR,
        wristL = wristL,
        wristR = wristR,
        hipL = hipL,
        hipR = hipR,
        kneeL = kneeL,
        kneeR = kneeR,
        ankleL = ankleL,
        ankleR = ankleR,
        racketTip = racketTip,
    )

    // Keep feet from sinking below POSE_GROUND_Y and keep extreme arcs inside [0.01, 0.99].
    val maxFootY = max(raw.ankleL.y, raw.ankleR.y)
    var dy = if (maxFootY > POSE_GROUND_Y) POSE_GROUND_Y - maxFootY else 0f
    val minY = minOf(
        raw.allBodyPoints().minOf { it.y },
        raw.racketTip?.y ?: 1f,
    ) + dy
    if (minY < 0.015f) {
        dy += (0.015f - minY)
    }
    var dx = 0f
    val minX = minOf(
        raw.allBodyPoints().minOf { it.x },
        raw.racketTip?.x ?: 1f,
    )
    val maxX = maxOf(
        raw.allBodyPoints().maxOf { it.x },
        raw.racketTip?.x ?: 0f,
    )
    if (minX < 0.015f) dx = 0.015f - minX
    else if (maxX > 0.985f) dx = 0.985f - maxX

    return if (dx == 0f && dy == 0f) raw else shiftFigure(raw, dx, dy)
}

private fun shiftPoint(p: VisualPoint, dx: Float, dy: Float): VisualPoint =
    VisualPoint((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))

private fun shiftFigure(f: PoseFigure, dx: Float, dy: Float): PoseFigure = PoseFigure(
    head = shiftPoint(f.head, dx, dy),
    neck = shiftPoint(f.neck, dx, dy),
    shoulderL = shiftPoint(f.shoulderL, dx, dy),
    shoulderR = shiftPoint(f.shoulderR, dx, dy),
    elbowL = shiftPoint(f.elbowL, dx, dy),
    elbowR = shiftPoint(f.elbowR, dx, dy),
    wristL = shiftPoint(f.wristL, dx, dy),
    wristR = shiftPoint(f.wristR, dx, dy),
    hipL = shiftPoint(f.hipL, dx, dy),
    hipR = shiftPoint(f.hipR, dx, dy),
    kneeL = shiftPoint(f.kneeL, dx, dy),
    kneeR = shiftPoint(f.kneeR, dx, dy),
    ankleL = shiftPoint(f.ankleL, dx, dy),
    ankleR = shiftPoint(f.ankleR, dx, dy),
    racketTip = f.racketTip?.let { shiftPoint(it, dx, dy) },
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

// --- Pose sequences with shuttle storytelling ---

private val baseRecoveryPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Base ready", readyStance),
        PoseKeyframe("Split-step", splitStepHop),
        PoseKeyframe("Corner lunge", forecourtLunge),
    ),
    cycleSeconds = 3.2,
    shuttleArc = ShuttleArc.NONE,
)

private val multiShuttleRecoveryPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Base ready", readyStance),
        PoseKeyframe("Corner strike", forecourtLunge),
        PoseKeyframe("Split-step reset", splitStepHop),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.DROP_NET,
)

private val overheadRecoveryPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Load overhead", overheadLoad),
        PoseKeyframe("Strike high", overheadStrike),
        PoseKeyframe("Drive to base", readyStance),
    ),
    cycleSeconds = 3.2,
    shuttleArc = ShuttleArc.OVERHEAD_CLEAR,
)

private val stickSmashPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Load high", overheadLoad),
        PoseKeyframe("Steep stick smash", overheadStrike),
        PoseKeyframe("Step forward", readyStance),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.SMASH,
)

private val netToRearPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Reset base", readyStance),
        PoseKeyframe("Net lunge", forecourtLunge),
        PoseKeyframe("Rear clear", overheadStrike),
    ),
    cycleSeconds = 3.4,
    shuttleArc = ShuttleArc.DROP_NET,
)

private val lateralBalancePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Centre split", splitStepHop),
        PoseKeyframe("Wide lunge", wideSidelineLunge),
        PoseKeyframe("Centre reset", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.FLAT_DRIVE,
)

private val lateralShadowPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Centre split", splitStepHop),
        PoseKeyframe("Wide lunge", wideSidelineLunge),
        PoseKeyframe("Centre reset", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.NONE,
)

private val forecourtNetKillPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Racket high", readyStance),
        PoseKeyframe("Heel-first lunge", forecourtLunge),
        PoseKeyframe("Rebound", splitStepHop),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.DROP_NET,
)

private val chinaJumpPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Ready base", readyStance),
        PoseKeyframe("Lateral jump", chinaJumpAirborne),
        PoseKeyframe("Sideline land", wideSidelineLunge),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.SMASH,
)

private val reactiveSplitPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Bounce ready", readyStance),
        PoseKeyframe("Split on cue", splitStepHop),
        PoseKeyframe("First-step drive", wideSidelineLunge),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.NONE,
)

private val scissorKickPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Rear load", overheadLoad),
        PoseKeyframe("Scissor kick", scissorKickAirborne),
        PoseKeyframe("Drive forward", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.OVERHEAD_CLEAR,
)

private val jumpSmashPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Tuck gather", jumpSmashTuck),
        PoseKeyframe("Steep strike", jumpSmashStrike),
        PoseKeyframe("Follow to net", forecourtLunge),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.SMASH,
)

private val disguisedSlicePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("High prep", overheadLoad),
        PoseKeyframe("Steep slice", overheadStrike),
        PoseKeyframe("Reset base", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.DROP_NET,
)

private val backhandEscapePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Pivot & elbow up", backhandRearLoad),
        PoseKeyframe("Forearm whip", backhandRearWhip),
        PoseKeyframe("Turn to base", readyStance),
    ),
    cycleSeconds = 3.2,
    shuttleArc = ShuttleArc.OVERHEAD_CLEAR,
)

private val lowDefencePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Low ready", lowDefenceCrouch),
        PoseKeyframe("Deep lift", lowDefenceLift),
    ),
    cycleSeconds = 2.4,
    shuttleArc = ShuttleArc.DEFENCE_LIFT,
)

private val flatDrivePose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Compact ready", readyStance),
        PoseKeyframe("Flat drive punch", flatDrivePunch),
        PoseKeyframe("Backhand block", bodyDeflectBackhand),
    ),
    cycleSeconds = 2.6,
    shuttleArc = ShuttleArc.FLAT_DRIVE,
)

private val bodyDeflectPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Low guard", lowDefenceCrouch),
        PoseKeyframe("Cross deflect", bodyDeflectBackhand),
        PoseKeyframe("Step in", readyStance),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.DEFENCE_LIFT,
)

private val wideReachPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Split ready", splitStepHop),
        PoseKeyframe("Low wide save", wideReachLow),
        PoseKeyframe("Recover base", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.DEFENCE_LIFT,
)

private val netSpinPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Compact reset", readyStance),
        PoseKeyframe("Early net brush", netSpinTouch),
    ),
    cycleSeconds = 2.4,
    shuttleArc = ShuttleArc.DROP_NET,
)

private val serveThirdShotPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("Poised serve", serveReady),
        PoseKeyframe("Flick or push", serveFlickFinish),
        PoseKeyframe("Racket up", readyStance),
    ),
    cycleSeconds = 3.0,
    shuttleArc = ShuttleArc.SERVE,
)

private val receiveRushPose = PoseSequence(
    keyframes = listOf(
        PoseKeyframe("High receive", receiveStance),
        PoseKeyframe("Forward rush", forecourtLunge),
        PoseKeyframe("Base reset", readyStance),
    ),
    cycleSeconds = 2.8,
    shuttleArc = ShuttleArc.FLAT_DRIVE,
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
        pose = lateralShadowPose,
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
        pose = multiShuttleRecoveryPose,
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
        pose = multiShuttleRecoveryPose,
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
        pose = stickSmashPose,
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
