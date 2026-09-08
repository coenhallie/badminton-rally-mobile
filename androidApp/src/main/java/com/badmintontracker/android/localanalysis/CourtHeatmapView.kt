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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.player.CourtOccupancy
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RejectionReason
import kotlin.math.sqrt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap

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
    // One pixel per cell, scaled up by the GPU rather than drawn as rectangles.
    //
    // Rectangles produced two artefacts that made a smooth field look like
    // data it is not. Each cell was drawn a pixel oversized to avoid hairline
    // gaps, so neighbours double-blended into a bright grid; and every cell
    // with any value at all was floored to 15% opacity, which turned the
    // Gaussian tail into a solid plateau with a hard rectangular edge, as if
    // the player had stood everywhere inside it. Interpolating a bitmap has no
    // seams to cover and lets the tail reach zero.
    val image = remember(grid, peak) { heatImage(grid, peak) }
    // Read here, not in drawCourt: a DrawScope is outside composition and cannot
    // reach the theme.
    //
    // 0.75 is measured, not chosen by eye. Against the real backgrounds the court
    // lines come out at 3.8:1 light and 3.9:1 dark, which clears WCAG 1.4.11's 3:1
    // for a non-text graphical object - and this view's whole argument is that the
    // court IS the frame of reference, so the lines are not decoration. It also
    // lands dark within 4% of the 3.8:1 the old fixed 0x66FFFFFF gave it. A first
    // pass at 0.5 fixed light and quietly took dark down to 2.4:1, which a
    // screenshot cannot catch: you can still see the lines, just not that they got
    // dimmer than they used to be.
    val courtLine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

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
                .aspectRatio((Court.WIDTH_DOUBLES + 2 * COURT_DRAW_MARGIN_M).toFloat() / (Court.LENGTH + 2 * COURT_DRAW_MARGIN_M).toFloat()),
        ) {
            if (image != null) {
                drawImage(
                    image = image,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    // Smooth, so a quarter-metre grid reads as a field rather
                    // than as the blocks it is stored in.
                    filterQuality = FilterQuality.High,
                )
            }
            drawCourt(marginM = COURT_DRAW_MARGIN_M, lineColor = courtLine)
        }

        Text(
            summary(track, occupancy),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * The occupancy field as one small image, one pixel per cell.
 *
 * Alpha rises with occupancy and reaches zero where there is none, so the edge
 * of the data is where the player stopped going rather than where the grid
 * happens to end.
 */
private fun heatImage(grid: List<List<Double>>, peak: Double): ImageBitmap? {
    if (peak <= 0.0 || grid.isEmpty()) return null
    val rows = grid.size
    val columns = grid[0].size
    val pixels = IntArray(rows * columns)
    for (r in 0 until rows) {
        for (c in 0 until columns) {
            // Square root rather than linear: occupancy is heavily peaked
            // around a base position, and a linear ramp renders everywhere
            // else as empty when it is not.
            val t = sqrt(grid[r][c] / peak).toFloat().coerceIn(0f, 1f)
            val colour = lerp(COOL, HOT, t)
            pixels[r * columns + c] = android.graphics.Color.argb(
                (t * 255).toInt(),
                (colour.red * 255).toInt(),
                (colour.green * 255).toInt(),
                (colour.blue * 255).toInt(),
            )
        }
    }
    return android.graphics.Bitmap
        .createBitmap(pixels, columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/**
 * Court lines, in the same metre space as the grid underneath.
 *
 * Drawn from `Court`'s dimensions rather than hand-placed fractions, so the
 * lines and the data cannot drift apart.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCourt(marginM: Double, lineColor: Color) {
    val totalW = Court.WIDTH_DOUBLES + 2 * marginM
    val totalH = Court.LENGTH + 2 * marginM
    fun x(m: Double) = ((m + marginM) / totalW * size.width).toFloat()
    fun y(m: Double) = ((m + marginM) / totalH * size.height).toFloat()

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, width: Float = 1.5f) =
        drawLine(lineColor, Offset(x(x1), y(y1)), Offset(x(x2), y(y2)), strokeWidth = width)

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
 * Coverage is the whole quality story: every sample stands on the ankles, so a
 * frame without confident ankles contributes nothing rather than a guess, and
 * the share of frames that produced a position is what the picture rests on.
 */
private fun summary(track: PlayerTrack, occupancy: CourtOccupancy): String {
    val minutes = occupancy.totalSeconds / 60.0
    val coverage = (track.coverage * 100).toInt()
    return "%.1f min tracked · player found in %d%% of frames".format(minutes, coverage)
}

internal fun whyEmpty(track: PlayerTrack): String = when {
    track.rejections.containsKey(RejectionReason.BAD_COURT) ->
        "The court marks do not fit a badminton court, so positions cannot be trusted. " +
            "Mark the court again and re-run the analysis."
    track.framesWithPose == 0 ->
        "No pose data for this video. It was analysed for rallies only."
    else ->
        "The player was not found on the near court in any of ${track.framesWithPose} frames."
}

/** The court plus the margin the selector accepts, so a lunge past the baseline is drawn where it was. */
internal const val COURT_DRAW_MARGIN_M = 2.0

/**
 * DELIBERATELY raw hex, outside ShuttlPalette, and not a defect to "fix".
 *
 * These two are data, not chrome. They are the endpoints of a scale that
 * encodes occupancy, so what has to stay readable is the GRADIENT BETWEEN
 * them - a viewer reads "here more than there" - not either endpoint's
 * contrast against the surface behind it. A theme-swapped ramp would change
 * what a colour MEANS between light and dark, which is the one thing a scale
 * may not do; the court lines under it are themed precisely because they are
 * chrome and carry no value.
 *
 * The usual objection - that [COOL] is weak on a dark background - cannot
 * arise: [heatImage] sets alpha to the same `t` that drives the lerp, so the
 * cool end is drawn fully transparent and the colour is never shown at the
 * strength where the contrast question would apply.
 *
 * Blue-to-orange rather than the more common green-to-red: it survives the
 * two most common colour-vision deficiencies, and green is already the
 * accent, which would read as an interface colour on top of a court.
 */
private val COOL = Color(0xFF2962FF)
private val HOT = Color(0xFFFF6D00)
