package com.badmintontracker.android.localanalysis

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.exercises.CourtDrillPath
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.POSE_GROUND_Y
import com.badmintontracker.shared.exercises.PoseSequence
import com.badmintontracker.shared.exercises.PoseShuttle
import com.badmintontracker.shared.exercises.SampledCourtState
import com.badmintontracker.shared.exercises.SampledPose
import com.badmintontracker.shared.exercises.VisualPoint
import com.badmintontracker.shared.exercises.mirroredCourtPoint
import com.badmintontracker.shared.exercises.mirroredCourtPoints
import com.badmintontracker.shared.exercises.sampleCourtState
import com.badmintontracker.shared.exercises.samplePose
import com.badmintontracker.shared.exercises.samplePoseMeta
import com.badmintontracker.shared.exercises.visualFor
import com.badmintontracker.shared.prefs.RacketArm
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val DOUBLES_BACK_SERVICE_INSET_M = 0.76f

/**
 * Stylized animated drill visual rendered inside an expanded [ExerciseCard].
 *
 * Uses forward-kinematics pose sampling, tapered vector athlete silhouettes,
 * live shuttle flight, racket swing swooshes, curved half-court movement with
 * synced shuttle & arrival pulses, plus slow-motion (0.5x) and phase-step controls.
 */
@Composable
fun ExerciseVisualView(
    exerciseId: String,
    racketArm: RacketArm?,
    modifier: Modifier = Modifier,
) {
    val visual = remember(exerciseId) { visualFor(exerciseId) }
    val mirrorX = racketArm == RacketArm.LEFT
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    val frameCount = visual.pose.keyframes.size.coerceAtLeast(1)
    var paused by remember(exerciseId) { mutableStateOf(reduceMotion) }
    var slowMo by remember(exerciseId) { mutableStateOf(false) }
    var elapsedSec by remember(exerciseId) {
        val initial = if (reduceMotion && frameCount > 1) {
            (1.08 / frameCount.toDouble() * visual.pose.cycleSeconds).toFloat()
        } else {
            0f
        }
        mutableFloatStateOf(initial)
    }
    var stepIndex by remember(exerciseId) { mutableIntStateOf(if (frameCount > 1) 1 else 0) }

    LaunchedEffect(exerciseId, paused, reduceMotion, slowMo) {
        if (paused || reduceMotion) return@LaunchedEffect
        var lastNanos = 0L
        val rate = if (slowMo) 0.5f else 1f
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                    elapsedSec += dt * rate
                }
                lastNanos = frameNanos
            }
        }
    }

    val courtPath = visual.courtPath
    val poseCycle = visual.pose.cycleSeconds.toFloat().coerceAtLeast(0.4f)
    val courtCycle = courtPath?.cycleSeconds?.toFloat()?.coerceAtLeast(0.4f) ?: 1f

    // Deferred phase readers: Canvas reads elapsedSec in the draw phase so 60-120 Hz
    // frames never trigger Compose recomposition of captions, pills, or semantics.
    val posePhaseProvider: () -> Float = {
        if (reduceMotion) {
            (stepIndex.toFloat() + 0.08f) / frameCount.toFloat()
        } else {
            (elapsedSec / poseCycle) % 1f
        }
    }
    val courtPhaseProvider: () -> Float = {
        if (courtPath == null) {
            0f
        } else if (reduceMotion) {
            val segments = (courtPath.waypoints.size - 1).coerceAtLeast(1)
            (stepIndex % segments).toFloat() / segments.toFloat()
        } else {
            (elapsedSec / courtCycle) % 1f
        }
    }

    // Recompose captions and scrubber dots only when the active keyframe milestone changes (~2-4x/sec).
    val poseMeta by remember(exerciseId, frameCount, reduceMotion, visual.pose) {
        derivedStateOf { samplePoseMeta(visual.pose, posePhaseProvider()) }
    }
    val accessibilitySummary = if (courtPath != null) {
        "${ExercisesCopy.COURT_VISUAL_CAPTION} and ${ExercisesCopy.poseCaption(poseMeta.label)}"
    } else {
        ExercisesCopy.poseCaption(poseMeta.label)
    }

    val onCanvasTap = {
        if (reduceMotion) {
            stepIndex = (stepIndex + 1) % frameCount
        } else {
            paused = !paused
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val stageModifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = accessibilitySummary }
            .clickable(role = Role.Button, onClick = onCanvasTap)

        if (courtPath != null) {
            Row(
                modifier = stageModifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisualTile(
                    caption = ExercisesCopy.COURT_VISUAL_CAPTION,
                    captionColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tileHeight = 152.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    HalfCourtCanvas(
                        path = courtPath,
                        courtPhaseProvider = courtPhaseProvider,
                        mirrorX = mirrorX,
                        modifier = Modifier.fillMaxWidth().height(152.dp),
                    )
                }
                VisualTile(
                    caption = ExercisesCopy.poseCaption(poseMeta.label),
                    captionColor = ShuttlTheme.extended.accentDark,
                    tileHeight = 152.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    PoseFigureCanvas(
                        sequence = visual.pose,
                        posePhaseProvider = posePhaseProvider,
                        mirrorX = mirrorX,
                        modifier = Modifier.fillMaxWidth().height(152.dp),
                    )
                }
            }
        } else {
            VisualTile(
                caption = ExercisesCopy.poseCaption(poseMeta.label),
                captionColor = ShuttlTheme.extended.accentDark,
                tileHeight = 164.dp,
                modifier = stageModifier,
            ) {
                PoseFigureCanvas(
                    sequence = visual.pose,
                    posePhaseProvider = posePhaseProvider,
                    mirrorX = mirrorX,
                    modifier = Modifier.fillMaxWidth().height(164.dp),
                )
            }
        }

        // Compact playback & phase-scrubber control strip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until poseMeta.keyframeCount) {
                    val active = i == poseMeta.keyframeIndex
                    val dotShape = RoundedCornerShape(ShuttlRadius.pill)
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (active) 18.dp else 7.dp)
                            .clip(dotShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                            .clickable(role = Role.Button) {
                                if (reduceMotion) {
                                    stepIndex = i
                                } else {
                                    elapsedSec = ((i + 0.08f) / frameCount.toFloat()) * poseCycle
                                    paused = true
                                }
                            },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!reduceMotion) {
                    ControlPill(
                        label = if (slowMo) ExercisesCopy.SPEED_SLOW else ExercisesCopy.SPEED_NORMAL,
                        highlighted = slowMo,
                        onClick = { slowMo = !slowMo },
                    )
                }
                ControlPill(
                    label = when {
                        reduceMotion -> "Next"
                        paused -> "Play"
                        else -> "Pause"
                    },
                    highlighted = false,
                    onClick = onCanvasTap,
                )
            }
        }
    }
}

@Composable
private fun ControlPill(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.background,
                shape,
            )
            .border(
                width = 1.dp,
                color = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VisualTile(
    caption: String,
    captionColor: Color,
    tileHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            content()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HalfCourtCanvas(
    path: CourtDrillPath,
    courtPhaseProvider: () -> Float,
    mirrorX: Boolean,
    modifier: Modifier = Modifier,
) {
    val tileBg = MaterialTheme.colorScheme.background
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val netColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
    val accentColor = MaterialTheme.colorScheme.primary
    val headingColor = MaterialTheme.colorScheme.onBackground

    val waypoints = remember(path, mirrorX) { mirroredCourtPoints(path.waypoints, mirrorX) }
    val baseMarker = remember(path, mirrorX) { mirroredCourtPoint(path.baseMarker, mirrorX) }

    Canvas(modifier = modifier) {
        val state = sampleCourtState(path, courtPhaseProvider(), mirrorX)
        val courtW = Court.WIDTH_DOUBLES.toFloat()
        val courtH = (Court.LENGTH / 2.0).toFloat()
        val singlesInset = ((Court.WIDTH_DOUBLES - Court.WIDTH_SINGLES) / 2.0).toFloat()
        val serviceY = Court.SERVICE_LINE.toFloat()
        val doublesBackY = courtH - DOUBLES_BACK_SERVICE_INSET_M
        val centreX = courtW / 2f

        val pad = 10.dp.toPx()
        val availW = (size.width - pad * 2f).coerceAtLeast(1f)
        val availH = (size.height - pad * 2f).coerceAtLeast(1f)
        val scale = min(availW / courtW, availH / courtH)
        val drawW = courtW * scale
        val drawH = courtH * scale
        val left = (size.width - drawW) / 2f
        val top = (size.height - drawH) / 2f

        fun toCanvas(p: VisualPoint): Offset = Offset(left + p.x * scale, top + p.y * scale)
        fun toCanvas(x: Float, y: Float): Offset = Offset(left + x * scale, top + y * scale)

        // Subtle court floor tint with slightly stronger singles floor band.
        drawRect(
            color = accentColor.copy(alpha = 0.05f),
            topLeft = Offset(left, top),
            size = Size(drawW, drawH),
        )
        drawRect(
            color = accentColor.copy(alpha = 0.04f),
            topLeft = toCanvas(singlesInset, 0f),
            size = Size((courtW - singlesInset * 2f) * scale, drawH),
        )

        val lineStroke = 1.dp.toPx()
        drawRect(
            color = lineColor,
            topLeft = Offset(left, top),
            size = Size(drawW, drawH),
            style = Stroke(width = lineStroke),
        )
        drawLine(lineColor, toCanvas(singlesInset, 0f), toCanvas(singlesInset, courtH), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(courtW - singlesInset, 0f), toCanvas(courtW - singlesInset, courtH), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(0f, serviceY), toCanvas(courtW, serviceY), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(0f, doublesBackY), toCanvas(courtW, doublesBackY), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(centreX, serviceY), toCanvas(centreX, courtH), strokeWidth = lineStroke)

        // Net tape across top edge with post caps.
        drawLine(
            color = netColor,
            start = toCanvas(-0.22f, 0f),
            end = toCanvas(courtW + 0.22f, 0f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Curved dashed movement pattern connecting drill waypoints.
        if (state.smoothRoute.size >= 2) {
            val route = Path().apply {
                val first = toCanvas(state.smoothRoute.first())
                moveTo(first.x, first.y)
                for (i in 1 until state.smoothRoute.size) {
                    val pt = toCanvas(state.smoothRoute[i])
                    lineTo(pt.x, pt.y)
                }
            }
            drawPath(
                path = route,
                color = accentColor.copy(alpha = 0.38f),
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
            // Subtle directional chevrons mid-way along each curved leg.
            var idx = 3
            while (idx < state.smoothRoute.size - 1) {
                val prev = toCanvas(state.smoothRoute[idx - 1])
                val cur = toCanvas(state.smoothRoute[idx])
                drawChevron(cur, atan2(cur.y - prev.y, cur.x - prev.x), 3.4.dp.toPx(), accentColor.copy(alpha = 0.45f))
                idx += 6
            }
        }

        // Corner target rings away from base marker.
        val cornerRadius = 2.8.dp.toPx()
        waypoints.forEach { wp ->
            if (hypot(wp.x - baseMarker.x, wp.y - baseMarker.y) > 0.35f) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.30f),
                    radius = cornerRadius,
                    center = toCanvas(wp),
                )
            }
        }

        // Expanding corner arrival pulse ring.
        state.cornerPulsePoint?.let { cp ->
            if (state.cornerPulse01 > 0.02f) {
                val expand = 1f - state.cornerPulse01
                drawCircle(
                    color = accentColor.copy(alpha = 0.42f * state.cornerPulse01),
                    radius = (3.5f + expand * 7f).dp.toPx(),
                    center = toCanvas(cp),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
        }

        // Base marker ring + expanding arrival pulse.
        val baseCenter = toCanvas(baseMarker)
        if (state.basePulse01 > 0.02f) {
            val expand = 1f - state.basePulse01
            drawCircle(
                color = accentColor.copy(alpha = 0.38f * state.basePulse01),
                radius = (6f + expand * 7f).dp.toPx(),
                center = baseCenter,
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }
        drawCircle(
            color = accentColor,
            radius = 5.8.dp.toPx(),
            center = baseCenter,
            style = Stroke(width = 1.75.dp.toPx()),
        )

        // Fading comet motion trail behind the player dot.
        val trailCount = state.playerTrail.size
        state.playerTrail.forEachIndexed { idx, tp ->
            val frac = (idx + 1).toFloat() / (trailCount + 1).toFloat()
            drawCircle(
                color = accentColor.copy(alpha = 0.06f + frac * 0.28f),
                radius = (1.6f + frac * 2.2f).dp.toPx(),
                center = toCanvas(tp),
            )
        }

        // Synchronized live shuttle marker flying on the half-court.
        state.shuttlePosition?.let { sp ->
            val sc = toCanvas(sp)
            drawCircle(
                color = headingColor.copy(alpha = 0.20f),
                radius = 4.6.dp.toPx(),
                center = sc,
            )
            drawCircle(
                color = headingColor,
                radius = 2.4.dp.toPx(),
                center = sc,
            )
        }

        // Animated player dot with halo + crisp core.
        val dotCenter = toCanvas(state.playerPosition)
        drawCircle(
            color = accentColor.copy(alpha = 0.22f),
            radius = 7.8.dp.toPx(),
            center = dotCenter,
        )
        drawCircle(
            color = accentColor,
            radius = 4.4.dp.toPx(),
            center = dotCenter,
        )
        drawCircle(
            color = tileBg,
            radius = 1.7.dp.toPx(),
            center = dotCenter,
        )
    }
}

@Composable
private fun PoseFigureCanvas(
    sequence: PoseSequence,
    posePhaseProvider: () -> Float,
    mirrorX: Boolean,
    modifier: Modifier = Modifier,
) {
    val headingColor = MaterialTheme.colorScheme.onBackground
    val frontLimbColor = headingColor.copy(alpha = 0.90f)
    val backLimbColor = headingColor.copy(alpha = 0.42f)
    val shortsColor = headingColor.copy(alpha = 0.86f)
    val jerseyColor = ShuttlTheme.extended.accentDark.copy(alpha = 0.90f)
    val jerseyHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val groundColor = MaterialTheme.colorScheme.outlineVariant
    val shadowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val sampled = samplePose(sequence, posePhaseProvider(), mirrorX)
        val figure = sampled.figure
        val pad = 8.dp.toPx()
        val side = min(size.width - pad * 2f, size.height - pad * 2f).coerceAtLeast(1f)
        val originX = (size.width - side) / 2f
        val originY = (size.height - side) / 2f

        fun pt(p: VisualPoint): Offset = Offset(originX + p.x * side, originY + p.y * side)

        // Subtle perspective floor line + dynamic height-responsive shadow.
        val groundY = originY + POSE_GROUND_Y * side
        drawLine(
            color = groundColor,
            start = Offset(originX + 0.06f * side, groundY),
            end = Offset(originX + 0.94f * side, groundY),
            strokeWidth = 1.25.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val ankleL = pt(figure.ankleL)
        val ankleR = pt(figure.ankleR)
        val shadowScale = 1f - sampled.airborneFactor * 0.45f
        val shadowAlpha = (0.18f * (1f - sampled.airborneFactor * 0.65f)).coerceAtLeast(0.04f)
        val rawLeft = min(ankleL.x, ankleR.x) - side * 0.07f
        val rawRight = maxOf(ankleL.x, ankleR.x) + side * 0.07f
        val shadowCenter = (rawLeft + rawRight) * 0.5f
        val shadowW = ((rawRight - rawLeft).coerceAtLeast(side * 0.20f)) * shadowScale
        val shadowH = side * 0.032f * shadowScale
        drawOval(
            color = shadowColor.copy(alpha = shadowAlpha),
            topLeft = Offset(shadowCenter - shadowW * 0.5f, groundY - shadowH * 0.5f),
            size = Size(shadowW, shadowH),
        )

        // Expanding ground ripple on split-step or lunge footplant.
        sampled.floorRipple?.let { ripple ->
            val rx = side * (0.04f + ripple.progress01 * 0.14f)
            val ry = side * (0.012f + ripple.progress01 * 0.022f)
            val cx = originX + ripple.centerX * side
            drawOval(
                color = accentColor.copy(alpha = 0.45f * (1f - ripple.progress01)),
                topLeft = Offset(cx - rx, groundY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }

        val shoulderL = pt(figure.shoulderL)
        val shoulderR = pt(figure.shoulderR)
        val elbowL = pt(figure.elbowL)
        val elbowR = pt(figure.elbowR)
        val wristL = pt(figure.wristL)
        val wristR = pt(figure.wristR)
        val hipL = pt(figure.hipL)
        val hipR = pt(figure.hipR)
        val kneeL = pt(figure.kneeL)
        val kneeR = pt(figure.kneeR)
        val neck = pt(figure.neck)
        val head = pt(figure.head)

        val thighTop = side * 0.030f
        val kneeRad = side * 0.021f
        val ankleRad = side * 0.013f
        val upperArmTop = side * 0.021f
        val elbowRad = side * 0.016f
        val wristRad = side * 0.012f

        // 1. Back/non-racket leg & arm (depth-shaded behind torso).
        drawTaperedLimb(hipL, kneeL, thighTop, kneeRad, backLimbColor)
        drawTaperedLimb(kneeL, ankleL, kneeRad * 0.96f, ankleRad, backLimbColor)
        drawShoe(ankleL, kneeL, sampled.facingSign, side, backLimbColor, accentSole = null)
        drawTaperedLimb(shoulderL, elbowL, upperArmTop, elbowRad, backLimbColor)
        drawTaperedLimb(elbowL, wristL, elbowRad * 0.94f, wristRad, backLimbColor)

        // 2. Athletic shorts pelvis band.
        val shortDirL = unitToward(hipL, kneeL)
        val shortDirR = unitToward(hipR, kneeR)
        val shortsL = Offset(hipL.x + shortDirL.x * side * 0.065f, hipL.y + shortDirL.y * side * 0.065f)
        val shortsR = Offset(hipR.x + shortDirR.x * side * 0.065f, hipR.y + shortDirR.y * side * 0.065f)
        val shortsPath = Path().apply {
            moveTo(hipL.x, hipL.y)
            lineTo(hipR.x, hipR.y)
            lineTo(shortsR.x, shortsR.y)
            lineTo(shortsL.x, shortsL.y)
            close()
        }
        drawPath(shortsPath, color = shortsColor)
        drawTaperedLimb(hipL, hipR, side * 0.026f, side * 0.026f, shortsColor)

        // 3. Tailored athletic jersey torso with curved waist + collar trim.
        val shoulderVec = Offset(shoulderR.x - shoulderL.x, shoulderR.y - shoulderL.y)
        val shLen = hypot(shoulderVec.x, shoulderVec.y).coerceAtLeast(1f)
        val shExpand = side * 0.018f
        val shOuterL = Offset(shoulderL.x - shoulderVec.x / shLen * shExpand, shoulderL.y - shoulderVec.y / shLen * shExpand)
        val shOuterR = Offset(shoulderR.x + shoulderVec.x / shLen * shExpand, shoulderR.y + shoulderVec.y / shLen * shExpand)
        val midTorsoL = Offset((shOuterL.x + hipL.x) * 0.5f, (shOuterL.y + hipL.y) * 0.5f)
        val midTorsoR = Offset((shOuterR.x + hipR.x) * 0.5f, (shOuterR.y + hipR.y) * 0.5f)
        val jerseyPath = Path().apply {
            moveTo(shOuterL.x, shOuterL.y)
            quadraticTo(neck.x, neck.y - side * 0.012f, shOuterR.x, shOuterR.y)
            quadraticTo(midTorsoR.x, midTorsoR.y, hipR.x, hipR.y)
            lineTo(hipL.x, hipL.y)
            quadraticTo(midTorsoL.x, midTorsoL.y, shOuterL.x, shOuterL.y)
            close()
        }
        drawPath(jerseyPath, color = jerseyColor)
        drawPath(jerseyPath, color = jerseyHighlight, style = Stroke(width = 1.dp.toPx()))

        // 4. Neck & athletic head silhouette with subtle facing contour.
        drawTaperedLimb(neck, head, side * 0.018f, side * 0.016f, frontLimbColor)
        val headRad = side * 0.056f
        drawCircle(color = headingColor, radius = headRad, center = head)
        // Subtle facing highlight notch on front of head so gaze direction reads cleanly.
        drawCircle(
            color = accentColor.copy(alpha = 0.65f),
            radius = headRad * 0.22f,
            center = Offset(head.x + sampled.facingSign * headRad * 0.58f, head.y - headRad * 0.08f),
        )

        // 5. Foreground/racket-side leg, shoe, and arm.
        drawTaperedLimb(hipR, kneeR, thighTop, kneeRad, frontLimbColor)
        drawTaperedLimb(kneeR, ankleR, kneeRad * 0.96f, ankleRad, frontLimbColor)
        drawShoe(ankleR, kneeR, sampled.facingSign, side, headingColor, accentSole = accentColor)
        drawTaperedLimb(shoulderR, elbowR, upperArmTop, elbowRad, frontLimbColor)
        drawTaperedLimb(elbowR, wristR, elbowRad * 0.94f, wristRad, frontLimbColor)

        // 6. Motion-trail swoosh ribbon behind fast racket swings.
        if (sampled.racketTrail.size >= 2) {
            val trailSize = sampled.racketTrail.size
            for (i in 1 until trailSize) {
                val frac = i.toFloat() / (trailSize - 1).toFloat()
                drawLine(
                    color = accentColor.copy(alpha = 0.04f + frac * 0.34f),
                    start = pt(sampled.racketTrail[i - 1]),
                    end = pt(sampled.racketTrail[i]),
                    strokeWidth = (1.2f + frac * 4.5f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 7. Detailed badminton racket (grip, shaft, T-throat, oval frame + string grid).
        figure.racketTip?.let { tipPoint ->
            val tip = pt(tipPoint)
            val dx = tip.x - wristR.x
            val dy = tip.y - wristR.y
            val len = hypot(dx, dy)
            if (len > 1f) {
                val shaftW = (side * 0.015f).coerceAtLeast(1.4.dp.toPx())
                val gripEnd = Offset(wristR.x + dx * 0.16f, wristR.y + dy * 0.16f)
                val throatBase = Offset(wristR.x + dx * 0.52f, wristR.y + dy * 0.52f)
                drawLine(
                    color = headingColor,
                    start = wristR,
                    end = gripEnd,
                    strokeWidth = shaftW * 1.65f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accentColor,
                    start = gripEnd,
                    end = throatBase,
                    strokeWidth = shaftW,
                    cap = StrokeCap.Round,
                )
                val headCenter = Offset(wristR.x + dx * 0.77f, wristR.y + dy * 0.77f)
                val rx = len * 0.24f
                val ry = len * 0.145f
                val angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
                withTransform({ rotate(degrees = angleDeg, pivot = headCenter) }) {
                    val ovalTopLeft = Offset(headCenter.x - rx, headCenter.y - ry)
                    val ovalSize = Size(rx * 2f, ry * 2f)
                    drawOval(
                        color = accentColor.copy(alpha = 0.14f),
                        topLeft = ovalTopLeft,
                        size = ovalSize,
                    )
                    // Subtle string grid inside the isometric racket head.
                    val stringColor = accentColor.copy(alpha = 0.34f)
                    val stringW = 0.75.dp.toPx()
                    drawLine(
                        stringColor,
                        Offset(headCenter.x - rx * 0.82f, headCenter.y - ry * 0.36f),
                        Offset(headCenter.x + rx * 0.82f, headCenter.y - ry * 0.36f),
                        strokeWidth = stringW,
                    )
                    drawLine(
                        stringColor,
                        Offset(headCenter.x - rx * 0.82f, headCenter.y + ry * 0.36f),
                        Offset(headCenter.x + rx * 0.82f, headCenter.y + ry * 0.36f),
                        strokeWidth = stringW,
                    )
                    for (step in -1..1) {
                        val sx = headCenter.x + rx * 0.42f * step
                        val sy = ry * if (step == 0) 0.90f else 0.78f
                        drawLine(
                            stringColor,
                            Offset(sx, headCenter.y - sy),
                            Offset(sx, headCenter.y + sy),
                            strokeWidth = stringW,
                        )
                    }
                    drawOval(
                        color = accentColor,
                        topLeft = ovalTopLeft,
                        size = ovalSize,
                        style = Stroke(width = shaftW * 1.08f),
                    )
                }
            }
        }

        // 8. Live animated shuttlecock + contact flash ring.
        sampled.shuttle?.let { shuttle ->
            drawPoseShuttle(
                shuttle = shuttle,
                center = pt(shuttle.position),
                side = side,
                headingColor = headingColor,
                accentColor = accentColor,
            )
        }
    }
}

private fun DrawScope.drawTaperedLimb(
    start: Offset,
    end: Offset,
    startRadius: Float,
    endRadius: Float,
    color: Color,
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = hypot(dx, dy)
    drawCircle(color = color, radius = startRadius, center = start)
    drawCircle(color = color, radius = endRadius, center = end)
    if (len < 0.5f) return
    val nx = -dy / len
    val ny = dx / len
    val path = Path().apply {
        moveTo(start.x + nx * startRadius, start.y + ny * startRadius)
        lineTo(end.x + nx * endRadius, end.y + ny * endRadius)
        lineTo(end.x - nx * endRadius, end.y - ny * endRadius)
        lineTo(start.x - nx * startRadius, start.y - ny * startRadius)
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawShoe(
    ankle: Offset,
    knee: Offset,
    facingSign: Float,
    side: Float,
    shoeColor: Color,
    accentSole: Color?,
) {
    val dir = if (ankle.x - knee.x > side * 0.05f) {
        1f
    } else if (ankle.x - knee.x < -side * 0.05f) {
        -1f
    } else {
        facingSign
    }
    val shoeW = side * 0.068f
    val shoeH = side * 0.026f
    val left = if (dir >= 0f) ankle.x - shoeW * 0.25f else ankle.x - shoeW * 0.75f
    val top = ankle.y - shoeH * 0.55f
    drawRoundRect(
        color = shoeColor,
        topLeft = Offset(left, top),
        size = Size(shoeW, shoeH),
        cornerRadius = CornerRadius(shoeH * 0.5f, shoeH * 0.5f),
    )
    if (accentSole != null) {
        drawLine(
            color = accentSole,
            start = Offset(left + shoeW * 0.14f, top + shoeH * 0.82f),
            end = Offset(left + shoeW * 0.86f, top + shoeH * 0.82f),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPoseShuttle(
    shuttle: PoseShuttle,
    center: Offset,
    side: Float,
    headingColor: Color,
    accentColor: Color,
) {
    if (shuttle.impactFlash > 0.02f) {
        val ringR = side * (0.028f + (1f - shuttle.impactFlash) * 0.055f)
        drawCircle(
            color = accentColor.copy(alpha = 0.52f * shuttle.impactFlash),
            radius = ringR,
            center = center,
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
    val angleDeg = (shuttle.velocityAngleRad * 180.0 / PI).toFloat()
    val corkR = (side * 0.017f).coerceAtLeast(2.2.dp.toPx())
    val skirtLen = corkR * 3.1f
    val skirtHalfW = corkR * 1.45f
    withTransform({ rotate(degrees = angleDeg, pivot = center) }) {
        // Feathered cone trailing behind the leading cork tip.
        val skirt = Path().apply {
            moveTo(center.x, center.y)
            lineTo(center.x - skirtLen, center.y - skirtHalfW)
            lineTo(center.x - skirtLen * 0.86f, center.y)
            lineTo(center.x - skirtLen, center.y + skirtHalfW)
            close()
        }
        drawPath(skirt, color = accentColor.copy(alpha = 0.28f * shuttle.alpha))
        drawPath(
            skirt,
            color = headingColor.copy(alpha = 0.78f * shuttle.alpha),
            style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round),
        )
        drawCircle(
            color = headingColor.copy(alpha = shuttle.alpha),
            radius = corkR,
            center = Offset(center.x + corkR * 0.25f, center.y),
        )
    }
}

private fun DrawScope.drawChevron(
    tip: Offset,
    angleRad: Float,
    sizePx: Float,
    color: Color,
) {
    val spread = 0.58f
    val left = Offset(
        tip.x - cos(angleRad - spread) * sizePx,
        tip.y - sin(angleRad - spread) * sizePx,
    )
    val right = Offset(
        tip.x - cos(angleRad + spread) * sizePx,
        tip.y - sin(angleRad + spread) * sizePx,
    )
    drawLine(color, left, tip, strokeWidth = 1.25.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, right, tip, strokeWidth = 1.25.dp.toPx(), cap = StrokeCap.Round)
}

private fun unitToward(from: Offset, to: Offset): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    return if (len < 1e-3f) Offset(0f, 1f) else Offset(dx / len, dy / len)
}
