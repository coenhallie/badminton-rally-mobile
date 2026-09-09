package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.local.MetricSample
import com.badmintontracker.shared.local.graphSegments
import com.badmintontracker.shared.local.metricRangeLabel

/**
 * The selected measurement over the seconds around the playhead, on a card.
 *
 * A serve is a curve, not a frame: the elbow angle through a serve is what a
 * coach compares between two serves, and a tile cannot show a curve. Raw
 * values, no smoothing, a gap wherever the joint was absent for more than two
 * frames, a fixed vertical range per kind so the shape does not rescale
 * under the eye. The card's header names the measurement and says the range
 * once; the plot itself is the mock's bare line with the playhead at its
 * centre, no frame and no axis labels. Tapping or dragging seeks.
 *
 * [durationS] is the clip's length in seconds, or [Double.POSITIVE_INFINITY]
 * while the player does not know it yet. A drag clamps to it, so dragging past
 * an end does not bank travel that has to be given back before the playhead
 * moves again.
 */
@Composable
fun MetricGraph(
    series: List<MetricSample>,
    kind: MetricKind,
    label: String,
    positionS: Double,
    fps: Double,
    durationS: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    windowS: Double = 2.0,
) {
    val line = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.onBackground
    val gapS = if (fps > 0) 2.5 / fps else 0.1

    // Both gesture blocks are keyed on windowS alone, which never changes in
    // practice, and pointerInput keeps the lambda it launched with for as long
    // as its keys hold. So neither block may read positionS or onSeek
    // directly: it would read whatever they were when the graph first
    // appeared, and a drag at 0:10 would seek from 0:00. Keying on positionS
    // instead looks like the fix and is worse: playback moves positionS every
    // frame, which would tear the block down and cancel a gesture under the
    // finger. Read through a state handle and keep the blocks long-lived.
    val currentPositionS by rememberUpdatedState(positionS)
    val currentOnSeek by rememberUpdatedState(onSeek)
    // Read through a handle for the same reason: the duration is unknown on
    // the first composition of a clip and arrives later.
    val currentDurationS by rememberUpdatedState(durationS)

    val shape = RoundedCornerShape(ShuttlRadius.large)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "$label around this frame",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                metricRangeLabel(kind),
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(72.dp)
                .pointerInput(windowS) {
                    detectTapGestures { tap ->
                        currentOnSeek(currentPositionS + (tap.x / size.width - 0.5) * 2 * windowS)
                    }
                }
                .pointerInput(windowS) {
                    // The anchor is clamped, not just the seek: an unclamped anchor
                    // would keep accumulating past the clip's end and the drag back
                    // would spend its first centimetres undoing that instead of
                    // moving the playhead.
                    var anchorS = currentPositionS
                    detectHorizontalDragGestures(
                        onDragStart = { anchorS = currentPositionS },
                        onHorizontalDrag = { change, _ ->
                            anchorS = (anchorS - (change.positionChange().x / size.width) * 2 * windowS)
                                .coerceIn(0.0, maxOf(0.0, currentDurationS))
                            currentOnSeek(anchorS)
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val startS = positionS - windowS
            val endS = positionS + windowS
            fun x(t: Double) = (((t - startS) / (2 * windowS)) * w).toFloat()
            fun y(v: Double) = (h - ((v - kind.rangeStart) / (kind.rangeEnd - kind.rangeStart)) * h).toFloat()

            val segments = graphSegments(series, kind, startS, endS, gapS)
            val stroke = 1.6.dp.toPx()
            // Values outside the kind's range are real (a lunge behind the service
            // line reads under -1 m), and Canvas does not clip on its own, so the
            // curve is cut at the box edges rather than drawn over the header.
            clipRect {
                segments.polylines.forEach { polyline ->
                    for (n in 1 until polyline.size) {
                        val a = polyline[n - 1]
                        val b = polyline[n]
                        drawLine(
                            line,
                            Offset(x(a.timestamp), y(a.value)),
                            Offset(x(b.timestamp), y(b.value)),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                segments.points.forEach { point ->
                    drawCircle(line, radius = stroke, center = Offset(x(point.timestamp), y(point.value)))
                }
            }

            drawLine(playhead, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 1.dp.toPx())
        }
    }
}
