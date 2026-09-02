package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.NearPlayerSelector
import com.badmintontracker.analysis.player.Skeleton

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
 */
@Composable
fun SkeletonOverlay(
    keypoints: List<Point>,
    confidence: List<Float>,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
    minConfidence: Float = NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE,
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
    }
}

private const val LIMB_WIDTH = 4f
private const val JOINT_RADIUS = 4f
private const val ANKLE_RADIUS = 7f
private val LIMB = Color(0xFF00E5FF)
private val ARM = Color(0xFFFFD600)
private val JOINT = Color(0xFFFFFFFF)
private val ANKLE = Color(0xFFFF3D00)
