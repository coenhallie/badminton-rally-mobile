package com.badmintontracker.android.localanalysis

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.badmintontracker.shared.exercises.PoseFigure
import com.badmintontracker.shared.exercises.VisualPoint
import com.badmintontracker.shared.exercises.mirroredCourtPoint
import com.badmintontracker.shared.exercises.mirroredCourtPoints
import com.badmintontracker.shared.exercises.sampleCourtPoint
import com.badmintontracker.shared.exercises.samplePose
import com.badmintontracker.shared.exercises.visualFor
import com.badmintontracker.shared.prefs.RacketArm
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private const val DOUBLES_BACK_SERVICE_INSET_M = 0.76f

/**
 * Compact animated drill visual rendered inside an expanded [ExerciseCard].
 *
 * On-court drills display a top-down half-court movement pattern beside an
 * articulated badminton pose figure; off-court exercises display a single
 * wider pose stage. Mirrors automatically when [racketArm] is [RacketArm.LEFT]
 * and honours system animator-scale (Reduce Motion) with tap-to-step playback.
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
    var stepIndex by remember(exerciseId) { mutableIntStateOf(if (frameCount > 1) 1 else 0) }
    var frozenPosePhase by remember(exerciseId) { mutableFloatStateOf(0f) }
    var frozenCourtPhase by remember(exerciseId) { mutableFloatStateOf(0f) }

    val courtPath = visual.courtPath
    val posePhase = when {
        reduceMotion -> stepIndex.toFloat() / frameCount.toFloat()
        paused -> frozenPosePhase
        else -> rememberLoopPhase(visual.pose.cycleSeconds, label = "pose")
    }
    val courtPhase = when {
        courtPath == null -> 0f
        reduceMotion -> {
            val segments = (courtPath.waypoints.size - 1).coerceAtLeast(1)
            (stepIndex % segments).toFloat() / segments.toFloat()
        }
        paused -> frozenCourtPhase
        else -> rememberLoopPhase(courtPath.cycleSeconds, label = "court")
    }

    val sampledPose = samplePose(visual.pose, posePhase, mirrorX)
    val accessibilitySummary = if (courtPath != null) {
        "${ExercisesCopy.COURT_VISUAL_CAPTION} and ${ExercisesCopy.poseCaption(sampledPose.label)}"
    } else {
        ExercisesCopy.poseCaption(sampledPose.label)
    }

    val onTap = {
        if (reduceMotion) {
            stepIndex = (stepIndex + 1) % frameCount
        } else if (paused) {
            paused = false
        } else {
            frozenPosePhase = posePhase
            frozenCourtPhase = courtPhase
            paused = true
        }
    }

    val containerModifier = modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) { contentDescription = accessibilitySummary }
        .clickable(role = Role.Button, onClick = onTap)

    if (courtPath != null) {
        Row(
            modifier = containerModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VisualTile(
                caption = ExercisesCopy.COURT_VISUAL_CAPTION,
                captionColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tileHeight = 136.dp,
                modifier = Modifier.weight(1f),
            ) {
                HalfCourtCanvas(
                    path = courtPath,
                    phase01 = courtPhase,
                    mirrorX = mirrorX,
                    modifier = Modifier.fillMaxWidth().height(136.dp),
                )
            }
            VisualTile(
                caption = ExercisesCopy.poseCaption(sampledPose.label),
                captionColor = ShuttlTheme.extended.accentDark,
                tileHeight = 136.dp,
                modifier = Modifier.weight(1f),
            ) {
                PoseFigureCanvas(
                    figure = sampledPose.figure,
                    modifier = Modifier.fillMaxWidth().height(136.dp),
                )
            }
        }
    } else {
        VisualTile(
            caption = ExercisesCopy.poseCaption(sampledPose.label),
            captionColor = ShuttlTheme.extended.accentDark,
            tileHeight = 148.dp,
            modifier = containerModifier,
        ) {
            PoseFigureCanvas(
                figure = sampledPose.figure,
                modifier = Modifier.fillMaxWidth().height(148.dp),
            )
        }
    }
}

@Composable
private fun rememberLoopPhase(cycleSeconds: Double, label: String): Float {
    val durationMs = (cycleSeconds * 1000.0).roundToInt().coerceAtLeast(400)
    val transition = rememberInfiniteTransition(label = "exerciseVisual_$label")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = label,
    )
    return phase
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
    phase01: Float,
    mirrorX: Boolean,
    modifier: Modifier = Modifier,
) {
    val tileBg = MaterialTheme.colorScheme.background
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val netColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f)
    val accentColor = MaterialTheme.colorScheme.primary
    val shuttleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    val waypoints = remember(path, mirrorX) { mirroredCourtPoints(path.waypoints, mirrorX) }
    val baseMarker = remember(path, mirrorX) { mirroredCourtPoint(path.baseMarker, mirrorX) }
    val shuttleStart = remember(path, mirrorX) { path.shuttleStart?.let { mirroredCourtPoint(it, mirrorX) } }
    val shuttleEnd = remember(path, mirrorX) { path.shuttleEnd?.let { mirroredCourtPoint(it, mirrorX) } }
    val playerPos = sampleCourtPoint(path, phase01, mirrorX)

    Canvas(modifier = modifier) {
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

        // Subtle court tint inside the doubles boundary.
        drawRect(
            color = accentColor.copy(alpha = 0.06f),
            topLeft = Offset(left, top),
            size = Size(drawW, drawH),
        )

        val lineStroke = 1.dp.toPx()
        drawRect(
            color = lineColor,
            topLeft = Offset(left, top),
            size = Size(drawW, drawH),
            style = Stroke(width = lineStroke),
        )
        // Singles sidelines.
        drawLine(lineColor, toCanvas(singlesInset, 0f), toCanvas(singlesInset, courtH), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(courtW - singlesInset, 0f), toCanvas(courtW - singlesInset, courtH), strokeWidth = lineStroke)
        // Short service line and doubles long service line.
        drawLine(lineColor, toCanvas(0f, serviceY), toCanvas(courtW, serviceY), strokeWidth = lineStroke)
        drawLine(lineColor, toCanvas(0f, doublesBackY), toCanvas(courtW, doublesBackY), strokeWidth = lineStroke)
        // Centre line from service line to baseline.
        drawLine(lineColor, toCanvas(centreX, serviceY), toCanvas(centreX, courtH), strokeWidth = lineStroke)
        // Net across top edge with small post overhangs.
        drawLine(
            color = netColor,
            start = toCanvas(-0.20f, 0f),
            end = toCanvas(courtW + 0.20f, 0f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Optional shuttle feed trajectory.
        if (shuttleStart != null && shuttleEnd != null) {
            drawLine(
                color = shuttleColor,
                start = toCanvas(shuttleStart),
                end = toCanvas(shuttleEnd),
                strokeWidth = 1.25.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 3.dp.toPx())),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = shuttleColor,
                radius = 2.2.dp.toPx(),
                center = toCanvas(shuttleEnd),
            )
        }

        // Dashed movement pattern connecting drill waypoints.
        if (waypoints.size >= 2) {
            val route = Path().apply {
                val first = toCanvas(waypoints.first())
                moveTo(first.x, first.y)
                for (i in 1 until waypoints.size) {
                    val pt = toCanvas(waypoints[i])
                    lineTo(pt.x, pt.y)
                }
            }
            drawPath(
                path = route,
                color = accentColor.copy(alpha = 0.45f),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }

        // Subtle corner target dots away from the base marker.
        val cornerRadius = 2.4.dp.toPx()
        waypoints.forEach { wp ->
            if (hypot(wp.x - baseMarker.x, wp.y - baseMarker.y) > 0.35f) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.32f),
                    radius = cornerRadius,
                    center = toCanvas(wp),
                )
            }
        }

        // Target base / cone ring.
        drawCircle(
            color = accentColor,
            radius = 6.dp.toPx(),
            center = toCanvas(baseMarker),
            style = Stroke(width = 1.75.dp.toPx()),
        )

        // Animated player marker with soft halo and crisp core.
        val dotCenter = toCanvas(playerPos)
        drawCircle(
            color = accentColor.copy(alpha = 0.22f),
            radius = 7.5.dp.toPx(),
            center = dotCenter,
        )
        drawCircle(
            color = accentColor,
            radius = 4.2.dp.toPx(),
            center = dotCenter,
        )
        drawCircle(
            color = tileBg,
            radius = 1.6.dp.toPx(),
            center = dotCenter,
        )
    }
}

@Composable
private fun PoseFigureCanvas(
    figure: PoseFigure,
    modifier: Modifier = Modifier,
) {
    val bodyColor = MaterialTheme.colorScheme.onBackground
    val backLimbColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
    val torsoFill = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.13f)
    val groundColor = MaterialTheme.colorScheme.outlineVariant
    val shadowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val accentColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val pad = 8.dp.toPx()
        val side = min(size.width - pad * 2f, size.height - pad * 2f).coerceAtLeast(1f)
        val originX = (size.width - side) / 2f
        val originY = (size.height - side) / 2f

        fun pt(p: VisualPoint): Offset = Offset(originX + p.x * side, originY + p.y * side)

        // Ground baseline and subtle contact shadow under the stance.
        val groundY = originY + POSE_GROUND_Y * side
        drawLine(
            color = groundColor,
            start = Offset(originX + 0.08f * side, groundY),
            end = Offset(originX + 0.92f * side, groundY),
            strokeWidth = 1.25.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val ankleL = pt(figure.ankleL)
        val ankleR = pt(figure.ankleR)
        val shadowLeft = min(ankleL.x, ankleR.x) - side * 0.06f
        val shadowRight = maxOf(ankleL.x, ankleR.x) + side * 0.06f
        val shadowH = side * 0.028f
        drawOval(
            color = shadowColor,
            topLeft = Offset(shadowLeft, groundY - shadowH / 2f),
            size = Size((shadowRight - shadowLeft).coerceAtLeast(side * 0.18f), shadowH),
        )

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

        val thighW = side * 0.038f
        val shinW = side * 0.031f
        val upperArmW = side * 0.032f
        val forearmW = side * 0.026f
        val torsoStroke = side * 0.028f

        // Non-racket leg and arm behind torso.
        drawLimb(hipL, kneeL, thighW, backLimbColor)
        drawLimb(kneeL, ankleL, shinW, backLimbColor)
        drawLimb(shoulderL, elbowL, upperArmW, backLimbColor)
        drawLimb(elbowL, wristL, forearmW, backLimbColor)

        // Subtle filled torso polygon + shoulder/hip/spine frame.
        val torsoPath = Path().apply {
            moveTo(shoulderL.x, shoulderL.y)
            lineTo(shoulderR.x, shoulderR.y)
            lineTo(hipR.x, hipR.y)
            lineTo(hipL.x, hipL.y)
            close()
        }
        drawPath(torsoPath, color = torsoFill)
        drawLimb(shoulderL, shoulderR, torsoStroke, bodyColor)
        drawLimb(hipL, hipR, torsoStroke, bodyColor)
        val midHip = Offset((hipL.x + hipR.x) / 2f, (hipL.y + hipR.y) / 2f)
        drawLimb(neck, midHip, torsoStroke, bodyColor)

        // Racket-side leg and arm in foreground.
        drawLimb(hipR, kneeR, thighW, bodyColor)
        drawLimb(kneeR, ankleR, shinW, bodyColor)
        drawLimb(shoulderR, elbowR, upperArmW, bodyColor)
        drawLimb(elbowR, wristR, forearmW, bodyColor)

        // Grounded foot ticks extending outward from stance midpoint.
        val midFootX = (ankleL.x + ankleR.x) / 2f
        val footLen = side * 0.038f
        drawLimb(
            ankleL,
            Offset(ankleL.x + if (ankleL.x <= midFootX) -footLen else footLen, ankleL.y),
            shinW * 0.85f,
            backLimbColor,
        )
        drawLimb(
            ankleR,
            Offset(ankleR.x + if (ankleR.x >= midFootX) footLen else -footLen, ankleR.y),
            shinW * 0.85f,
            bodyColor,
        )

        // Solid athletic head silhouette.
        drawCircle(
            color = bodyColor,
            radius = side * 0.058f,
            center = head,
        )

        // Badminton racket shaft + oriented isometric oval head for on-court drills.
        val tipPoint = figure.racketTip
        if (tipPoint != null) {
            val tip = pt(tipPoint)
            val dx = tip.x - wristR.x
            val dy = tip.y - wristR.y
            val len = hypot(dx, dy)
            if (len > 1f) {
                val shaftW = (side * 0.018f).coerceAtLeast(1.5.dp.toPx())
                val shaftEnd = Offset(wristR.x + dx * 0.54f, wristR.y + dy * 0.54f)
                drawLine(
                    color = accentColor,
                    start = wristR,
                    end = shaftEnd,
                    strokeWidth = shaftW,
                    cap = StrokeCap.Round,
                )
                val headCenter = Offset(wristR.x + dx * 0.77f, wristR.y + dy * 0.77f)
                val rx = len * 0.24f
                val ry = len * 0.145f
                val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                withTransform({ rotate(degrees = angleDeg, pivot = headCenter) }) {
                    val ovalTopLeft = Offset(headCenter.x - rx, headCenter.y - ry)
                    val ovalSize = Size(rx * 2f, ry * 2f)
                    drawOval(
                        color = accentColor.copy(alpha = 0.16f),
                        topLeft = ovalTopLeft,
                        size = ovalSize,
                    )
                    drawOval(
                        color = accentColor,
                        topLeft = ovalTopLeft,
                        size = ovalSize,
                        style = Stroke(width = shaftW),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLimb(start: Offset, end: Offset, width: Float, color: Color) {
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}
