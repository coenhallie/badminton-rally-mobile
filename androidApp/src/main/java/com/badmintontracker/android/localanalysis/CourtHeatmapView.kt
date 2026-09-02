package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.player.CourtOccupancy
import com.badmintontracker.analysis.player.PlayerTrack

/**
 * Where the player spent the match, drawn on the court rather than on the video.
 *
 * The court is the frame of reference on purpose. The web app bins into
 * video-normalised pixels, which ties the picture to where the camera stood: two
 * matches cannot be compared, and because perspective makes a pixel near the
 * camera cover less court than one at the far baseline, a fixed pixel blur is a
 * spatially varying blur in the real world. Drawn in metres, one cell is one
 * cell everywhere.
 *
 * Aspect ratio comes from the real court, so the drawing cannot silently stretch
 * and misrepresent how far the player actually ranged.
 */
@Composable
fun CourtHeatmapView(
    track: PlayerTrack,
    fps: Double,
    modifier: Modifier = Modifier,
    cellSizeM: Double = CourtOccupancy.DEFAULT_CELL_SIZE_M,
) {
    val occupancy = remember(track, fps, cellSizeM) {
        CourtOccupancy(cellSizeM).also { it.addAll(track.samples, fps) }
    }
    val grid = remember(occupancy) { occupancy.smoothed() }
    val peak = remember(grid) { grid.flatten().maxOrNull() ?: 0.0 }

    Column(modifier) {
        if (track.samples.isEmpty()) {
            Text(
                whyEmpty(track),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                // The court plus the margin the selector accepts, so a player
                // lunging past the baseline is drawn where they were rather
                // than clamped onto the line.
                .aspectRatio((Court.WIDTH_DOUBLES + 2 * MARGIN_M).toFloat() / (Court.LENGTH + 2 * MARGIN_M).toFloat()),
        ) {
            val cellW = size.width / occupancy.columns
            val cellH = size.height / occupancy.rows

            if (peak > 0.0) {
                grid.forEachIndexed { row, cells ->
                    cells.forEachIndexed { column, seconds ->
                        if (seconds <= 0.0) return@forEachIndexed
                        // Square root rather than linear: occupancy is heavily
                        // peaked around a base position, and a linear ramp
                        // renders everywhere else as empty when it is not.
                        val t = kotlin.math.sqrt(seconds / peak).toFloat()
                        drawRect(
                            color = lerp(COOL, HOT, t).copy(alpha = 0.15f + 0.85f * t),
                            topLeft = Offset(column * cellW, row * cellH),
                            size = Size(cellW + 1f, cellH + 1f),
                        )
                    }
                }
            }
            drawCourt(marginM = MARGIN_M)
        }

        Text(
            summary(track, occupancy),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Court lines, in the same metre space as the grid underneath.
 *
 * Drawn from `Court`'s dimensions rather than hand-placed fractions, so the
 * lines and the data cannot drift apart.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCourt(marginM: Double) {
    val totalW = Court.WIDTH_DOUBLES + 2 * marginM
    val totalH = Court.LENGTH + 2 * marginM
    fun x(m: Double) = ((m + marginM) / totalW * size.width).toFloat()
    fun y(m: Double) = ((m + marginM) / totalH * size.height).toFloat()

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, width: Float = 1.5f) =
        drawLine(LINE, Offset(x(x1), y(y1)), Offset(x(x2), y(y2)), strokeWidth = width)

    // Outer doubles court.
    line(0.0, 0.0, Court.WIDTH_DOUBLES, 0.0)
    line(0.0, Court.LENGTH, Court.WIDTH_DOUBLES, Court.LENGTH)
    line(0.0, 0.0, 0.0, Court.LENGTH)
    line(Court.WIDTH_DOUBLES, 0.0, Court.WIDTH_DOUBLES, Court.LENGTH)

    // Net, heavier because it is the one line that orients the whole picture.
    line(0.0, Court.LENGTH / 2, Court.WIDTH_DOUBLES, Court.LENGTH / 2, width = 3f)

    // Singles sidelines.
    val inset = (Court.WIDTH_DOUBLES - Court.WIDTH_SINGLES) / 2
    line(inset, 0.0, inset, Court.LENGTH)
    line(Court.WIDTH_DOUBLES - inset, 0.0, Court.WIDTH_DOUBLES - inset, Court.LENGTH)

    // Short service lines, one either side of the net.
    line(0.0, Court.LENGTH / 2 - Court.SERVICE_LINE, Court.WIDTH_DOUBLES, Court.LENGTH / 2 - Court.SERVICE_LINE)
    line(0.0, Court.LENGTH / 2 + Court.SERVICE_LINE, Court.WIDTH_DOUBLES, Court.LENGTH / 2 + Court.SERVICE_LINE)

    // Centre line, on the near half only: this pipeline tracks one player.
    line(Court.WIDTH_DOUBLES / 2, Court.LENGTH / 2 + Court.SERVICE_LINE, Court.WIDTH_DOUBLES / 2, Court.LENGTH)
}

/**
 * Says how much of the map to trust, rather than only how much there is.
 *
 * The ankle percentage is here because it changes what the picture means: a hip
 * sits about a metre above the court plane and projects long, so a track built
 * largely on hips is pushed away from the camera.
 */
private fun summary(track: PlayerTrack, occupancy: CourtOccupancy): String {
    val minutes = occupancy.totalSeconds / 60.0
    val coverage = (track.coverage * 100).toInt()
    val ankles = (track.ankleFraction * 100).toInt()
    return "%.1f min tracked · found in %d%% of frames · %d%% on ankles".format(minutes, coverage, ankles)
}

private fun whyEmpty(track: PlayerTrack): String = when {
    track.framesWithPose == 0 ->
        "No pose data for this video. It was analysed for rallies only."
    else ->
        "The player was not found on the near court in any of ${track.framesWithPose} frames."
}

private const val MARGIN_M = 2.0
private val COOL = Color(0xFF2962FF)
private val HOT = Color(0xFFFF6D00)
private val LINE = Color(0x66FFFFFF)
