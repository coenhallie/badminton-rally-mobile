package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.NearPlayerSelector
import com.badmintontracker.analysis.player.Skeleton
import kotlin.math.atan2

/**
 * What the overlay emphasises for the selected tile: the arc of an angle, the
 * line between the ankles, or a tilt line with the horizontal it is measured from.
 */
sealed interface PoseHighlight {
    data class Angle(val a: Int, val vertex: Int, val c: Int) : PoseHighlight
    data object Stance : PoseHighlight
    data class Tilt(val left: Int, val right: Int) : PoseHighlight
}

fun MetricKind.highlight(): PoseHighlight? {
    val joints = angleJoints
    val line = lineJoints
    return when {
        joints != null -> PoseHighlight.Angle(joints.first, joints.second, joints.third)
        line != null -> PoseHighlight.Tilt(line.first, line.second)
        this == MetricKind.STANCE -> PoseHighlight.Stance
        else -> null
    }
}

/**
 * One player's pose, drawn over the video frame it came from.
 *
 * Keypoints arrive in SOURCE-VIDEO pixels, as everything downstream of the
 * platform layer does, so this maps them into whatever box the video is being
 * displayed in. Taking the displayed size as a parameter rather than assuming
 * the canvas matches the video is what keeps the drawing correct when the player
 * is letterboxed inside its container, which it usually is.
 *
 * Low-confidence joints are omitted rather than drawn faintly. A limb drawn to a
 * keypoint the model is unsure of puts an arm through the figure's chest, which
 * a viewer reads as broken tracking rather than as uncertainty.
 *
 * [highlight] puts the number and the geometry it came from on the same pixels.
 * It is drawn last so it sits over the limbs, and it is omitted when any joint
 * it needs is below [minConfidence], the same rule the limbs follow.
 */
@Composable
fun SkeletonOverlay(
    keypoints: List<Point>,
    confidence: List<Float>,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
    minConfidence: Float = NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE,
    highlight: PoseHighlight? = null,
) {
    if (keypoints.size < Coco.COUNT || videoWidth <= 0 || videoHeight <= 0) return

    Canvas(modifier = modifier) {
        // The video is fitted inside this box, so the same letterboxing the
        // player applies has to be applied here or the skeleton drifts off the
        // body at any aspect ratio but the exact one.
        val scale = minOf(size.width / videoWidth, size.height / videoHeight)
        val offsetX = (size.width - videoWidth * scale) / 2f
        val offsetY = (size.height - videoHeight * scale) / 2f
        fun at(index: Int) = Offset(
            offsetX + (keypoints[index].x * scale).toFloat(),
            offsetY + (keypoints[index].y * scale).toFloat(),
        )

        Skeleton.EDGES.forEach { edge ->
            if (!Skeleton.edgeVisible(confidence, edge, minConfidence)) return@forEach
            drawLine(
                color = if (edge in Skeleton.ARM_EDGES) ARM else LIMB,
                start = at(edge.first),
                end = at(edge.second),
                strokeWidth = LIMB_WIDTH,
            )
        }

        for (k in 0 until Coco.COUNT) {
            if (confidence.getOrElse(k) { 0f } < minConfidence) continue
            // The ankles are marked apart because they are the measurement: the
            // heatmap is built from their midpoint, so seeing them land on the
            // feet is how the projection is checked by eye.
            val ankle = k == Coco.LEFT_ANKLE || k == Coco.RIGHT_ANKLE
            drawCircle(
                color = if (ankle) ANKLE else JOINT,
                radius = if (ankle) ANKLE_RADIUS else JOINT_RADIUS,
                center = at(k),
            )
        }

        when (highlight) {
            is PoseHighlight.Angle -> {
                val (a, v, c) = highlight
                val visible = listOf(a, v, c).all { confidence.getOrElse(it) { 0f } >= minConfidence }
                if (visible) {
                    val centre = at(v)
                    val ua = at(a) - centre
                    val uc = at(c) - centre
                    if (ua.getDistance() > 0f && uc.getDistance() > 0f) {
                        val startDeg = Math.toDegrees(atan2(ua.y.toDouble(), ua.x.toDouble())).toFloat()
                        val endDeg = Math.toDegrees(atan2(uc.y.toDouble(), uc.x.toDouble())).toFloat()
                        // The short way round: the interior angle, never its reflex.
                        var sweep = endDeg - startDeg
                        while (sweep > 180f) sweep -= 360f
                        while (sweep < -180f) sweep += 360f
                        drawArc(
                            color = HIGHLIGHT,
                            startAngle = startDeg,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(centre.x - ARC_RADIUS, centre.y - ARC_RADIUS),
                            size = Size(ARC_RADIUS * 2, ARC_RADIUS * 2),
                            style = Stroke(width = HIGHLIGHT_WIDTH),
                        )
                    }
                }
            }
            PoseHighlight.Stance -> {
                if (confidence.getOrElse(Coco.LEFT_ANKLE) { 0f } >= minConfidence &&
                    confidence.getOrElse(Coco.RIGHT_ANKLE) { 0f } >= minConfidence
                ) {
                    drawLine(HIGHLIGHT, at(Coco.LEFT_ANKLE), at(Coco.RIGHT_ANKLE), strokeWidth = HIGHLIGHT_WIDTH)
                }
            }
            is PoseHighlight.Tilt -> {
                val (l, r) = highlight
                if (confidence.getOrElse(l) { 0f } >= minConfidence && confidence.getOrElse(r) { 0f } >= minConfidence) {
                    val a = at(l)
                    val b = at(r)
                    // The horizontal the tilt is measured from, dashed and as
                    // long as the line, so the angle between the two is the
                    // number on the tile.
                    val half = (b - a).getDistance() / 2f
                    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    drawLine(
                        HIGHLIGHT.copy(alpha = 0.6f),
                        Offset(mid.x - half, mid.y),
                        Offset(mid.x + half, mid.y),
                        strokeWidth = HIGHLIGHT_WIDTH * 0.6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                    drawLine(HIGHLIGHT, a, b, strokeWidth = HIGHLIGHT_WIDTH)
                }
            }
            null -> Unit
        }
    }
}

private const val LIMB_WIDTH = 4f
private const val JOINT_RADIUS = 4f
private const val ANKLE_RADIUS = 7f
private const val ARC_RADIUS = 22f
private const val HIGHLIGHT_WIDTH = 3f
private val LIMB = Color(0xFF00E5FF)
private val ARM = Color(0xFFFFD600)
private val JOINT = Color(0xFFFFFFFF)
private val ANKLE = Color(0xFFFF3D00)
private val HIGHLIGHT = Color(0xFFE040FB)
