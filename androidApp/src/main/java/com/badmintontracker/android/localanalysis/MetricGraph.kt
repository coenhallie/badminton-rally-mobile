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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind

/**
 * What to draw for one metric over one window: a polyline per run of samples
 * that are present and close together, and a point per sample that joins
 * neither neighbour. Coordinates are (timestamp, value), not pixels.
 */
data class GraphSegments(
    val polylines: List<List<Pair<Double, Double>>>,
    val points: List<Pair<Double, Double>>,
) {
    companion object {
        val EMPTY = GraphSegments(emptyList(), emptyList())
    }
}

/**
 * What to draw for [kind] between [startS] and [endS].
 *
 * The walk starts one sample before the window and ends one sample after it,
 * so a run that straddles an edge is drawn as a line crossing that edge
 * instead of stopping short of it; the caller clips to the box. Values are
 * never coerced into the kind's range: a clipped line says the value left the
 * range, a coerced one would say it sat on the boundary.
 *
 * A lone sample becomes a point only when it is inside the window. One that
 * falls outside it is there to anchor a crossing line, and drawing it as a
 * point would put a mark outside the window the caller asked for.
 */
internal fun graphSegments(
    series: List<MetricSample>,
    kind: MetricKind,
    startS: Double,
    endS: Double,
    gapS: Double,
): GraphSegments {
    if (series.isEmpty()) return GraphSegments.EMPTY

    // First sample at or after the window's left edge.
    var lo = 0
    var hi = series.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (series[mid].timestamp < startS) lo = mid + 1 else hi = mid
    }

    val polylines = mutableListOf<List<Pair<Double, Double>>>()
    val points = mutableListOf<Pair<Double, Double>>()
    var run = mutableListOf<Pair<Double, Double>>()

    fun flush() {
        if (run.size >= 2) {
            polylines += run.toList()
        } else if (run.size == 1) {
            val point = run[0]
            if (point.first >= startS && point.first <= endS) points += point
        }
        run = mutableListOf()
    }

    var i = maxOf(0, lo - 1)
    var prev: MetricSample? = null
    while (i < series.size) {
        val s = series[i]
        val value = kind.of(s.metrics)
        val prevValue = prev?.let { kind.of(it.metrics) }
        val joinsPrev = value != null && prevValue != null && s.timestamp - prev.timestamp <= gapS
        if (value == null) {
            flush()
        } else {
            if (!joinsPrev) flush()
            run += s.timestamp to value
        }
        prev = s
        // Consume exactly one sample past the right edge, then stop.
        if (s.timestamp > endS) break
        i++
    }
    flush()

    return GraphSegments(polylines, points)
}

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

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(windowS) {
                detectTapGestures { tap ->
                    currentOnSeek(currentPositionS + (tap.x / size.width - 0.5) * 2 * windowS)
                }
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
        val endS = positionS + windowS
        fun x(t: Double) = (((t - startS) / (2 * windowS)) * w).toFloat()
        fun y(v: Double) = (h - ((v - range.start) / (range.endInclusive - range.start)) * h).toFloat()

        drawRect(color = frame, style = Stroke(width = 1f))

        val segments = graphSegments(series, kind, startS, endS, gapS)
        // Values outside the kind's range are real (a lunge behind the service
        // line reads under -1 m), and Canvas does not clip on its own, so the
        // curve is cut at the box edges rather than drawn over the tiles.
        clipRect {
            segments.polylines.forEach { polyline ->
                for (n in 1 until polyline.size) {
                    val (t0, v0) = polyline[n - 1]
                    val (t1, v1) = polyline[n]
                    drawLine(line, Offset(x(t0), y(v0)), Offset(x(t1), y(v1)), strokeWidth = 3f)
                }
            }
            segments.points.forEach { (t, v) ->
                drawCircle(line, radius = 2f, center = Offset(x(t), y(v)))
            }
        }

        drawLine(playhead, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2f)

        val style = labelStyle.copy(color = labelColor)
        drawText(measurer, formatMetric(kind, range.endInclusive), topLeft = Offset(4f, 2f), style = style)
        val bottom = measurer.measure(formatMetric(kind, range.start), style)
        drawText(measurer, formatMetric(kind, range.start), topLeft = Offset(4f, h - bottom.size.height - 2f), style = style)
    }
}
