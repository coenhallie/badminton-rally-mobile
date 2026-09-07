package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind

/**
 * The selected measurement over the seconds around the playhead.
 *
 * A serve is a curve, not a frame: the elbow angle through a serve is what a
 * coach compares between two serves, and a tile cannot show a curve. Raw
 * values, no smoothing, a gap wherever the joint was absent for more than two
 * frames, a fixed vertical range per kind so the shape does not rescale
 * under the eye. The playhead sits at the centre; tapping or dragging seeks.
 */
@Composable
fun MetricGraph(
    series: List<MetricSample>,
    kind: MetricKind,
    positionS: Double,
    fps: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    windowS: Double = 2.0,
) {
    val line = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.onSurface
    val frame = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val range = kind.range
    val gapS = if (fps > 0) 2.5 / fps else 0.1

    // The drag block below is keyed on windowS alone, and pointerInput keeps
    // the lambda it launched with for as long as its keys hold, so reading
    // positionS directly there would read whatever the playhead was when the
    // graph first appeared: a drag at 0:10 would seek from 0:00. Keying the
    // block on positionS instead is worse, because seeking moves positionS and
    // would cancel the drag on its own first frame. Read both through a state
    // handle instead.
    val currentPositionS by rememberUpdatedState(positionS)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(positionS, windowS) {
                detectTapGestures { tap -> onSeek(positionS + (tap.x / size.width - 0.5) * 2 * windowS) }
            }
            .pointerInput(windowS) {
                var anchorS = currentPositionS
                detectHorizontalDragGestures(
                    onDragStart = { anchorS = currentPositionS },
                    onHorizontalDrag = { change, _ ->
                        anchorS += -(change.positionChange().x / size.width) * 2 * windowS
                        currentOnSeek(anchorS)
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val startS = positionS - windowS
        fun x(t: Double) = (((t - startS) / (2 * windowS)) * w).toFloat()
        fun y(v: Double) = (h - ((v - range.start) / (range.endInclusive - range.start)) * h).toFloat()

        drawRect(color = frame, style = Stroke(width = 1f))

        // Binary search for the first sample in the window; walk to the last.
        var lo = 0
        var hi = series.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].timestamp < startS) lo = mid + 1 else hi = mid
        }
        var prev: MetricSample? = null
        var i = lo
        while (i < series.size && series[i].timestamp <= positionS + windowS) {
            val s = series[i]
            val v = kind.of(s.metrics)
            val pv = prev?.let { kind.of(it.metrics) }
            if (v != null && pv != null && s.timestamp - prev.timestamp <= gapS) {
                drawLine(line, Offset(x(prev.timestamp), y(pv)), Offset(x(s.timestamp), y(v)), strokeWidth = 3f)
            } else if (v != null) {
                drawCircle(line, radius = 2f, center = Offset(x(s.timestamp), y(v)))
            }
            prev = s
            i++
        }

        drawLine(playhead, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2f)

        val style = labelStyle.copy(color = labelColor)
        drawText(measurer, formatMetric(kind, range.endInclusive), topLeft = Offset(4f, 2f), style = style)
        val bottom = measurer.measure(formatMetric(kind, range.start), style)
        drawText(measurer, formatMetric(kind, range.start), topLeft = Offset(4f, h - bottom.size.height - 2f), style = style)
    }
}
