package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.BasePositions
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.analysis.player.basePositions
import com.badmintontracker.shared.local.describeBase
import com.badmintontracker.shared.local.describeRally

/**
 * Where the player played from, rally by rally, on the court.
 *
 * The median position of each rally, which the 2026-09-08 research found to be
 * the most trustworthy number the stored track supports: two pose models agree
 * on it to a centimetre or two. Drawn on the same court as the heatmap so the
 * two read together, then said in words against the two lines the near player
 * plays around.
 *
 * The rally windows are the stored clips', which carry two seconds of pre-roll
 * and one and a half of post-roll around the detected rally. A median does not
 * notice: the player is standing at or near their base during both.
 *
 * The track is resolved exactly as [HeatmapPanel] resolves it, for the same
 * reasons, so the two tabs can never disagree about which run they show.
 */
@Composable
fun BasePositionPanel(
    entryId: String,
    runner: LocalAnalysisRunner,
    modifier: Modifier = Modifier,
) {
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val stored = remember(entryId) { runner.storedTrack(entryId) }
    val source = heatmapSource(done, stored)
    val windows = remember(entryId, done) {
        (done?.clips?.takeIf { it.isNotEmpty() } ?: runner.storedClips(entryId))
            .map { RallyWindow(it.index, it.startSeconds, it.endSeconds) }
    }
    val bases = remember(source, windows) { source?.let { basePositions(it.track, it.fps, windows) } }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val message = when {
            source == null -> "This analysis is no longer loaded. Run it again to see where the player stood."
            source.track.samples.isEmpty() -> whyEmpty(source.track)
            windows.none { it.isBounded } ->
                "The rally windows for this analysis were not kept, so there is nothing to measure per rally. " +
                    "Run the analysis again."
            bases == null || bases.rallies.isEmpty() ->
                "The player was not found for long enough in any rally to say where they stood."
            else -> null
        }
        if (message != null) {
            PanelMessage(message)
            return@Column
        }
        requireNotNull(bases)
        CourtBaseView(bases)
        bases.overall?.let {
            Text(
                "Whole match: ${describeBase(it)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = COURT_GUTTER, end = COURT_GUTTER, top = 18.dp),
            )
        }
        Text(
            "The median position in each rally, from the ankles and the court marks. " +
                "Left and right are the player's own, facing the net.",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 8.dp),
        )
        bases.rallies.forEach { rally ->
            Text(
                describeRally(rally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(COURT_GUTTER))
    }
}

/**
 * The court with one dot per rally and a ring for the whole match.
 *
 * Dots for rallies with bases centimetres apart land on top of each other,
 * and that is the picture: a player whose base does not move draws one spot.
 */
@Composable
private fun CourtBaseView(bases: BasePositions) {
    val courtLine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val dot = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()

    CourtCard {
        drawCourt(marginM = COURT_DRAW_MARGIN_M, lineColor = courtLine)
        val totalW = Court.WIDTH_DOUBLES + 2 * COURT_DRAW_MARGIN_M
        val totalH = Court.LENGTH + 2 * COURT_DRAW_MARGIN_M
        fun at(p: Point) = Offset(
            ((p.x + COURT_DRAW_MARGIN_M) / totalW * size.width).toFloat(),
            ((p.y + COURT_DRAW_MARGIN_M) / totalH * size.height).toFloat(),
        )
        val radius = 5.dp.toPx()
        val ring = 10.dp.toPx()
        bases.rallies.forEach { rally ->
            drawCircle(dot.copy(alpha = 0.8f), radius = radius, center = at(rally.position))
        }
        bases.overall?.let {
            drawCircle(dot, radius = ring, center = at(it), style = Stroke(width = 2.5.dp.toPx()))
        }
        // Numbers last, and clear of the ring, so the whole-match marker cannot
        // sit on top of the one label that says which rally a dot is.
        bases.rallies.forEach { rally ->
            val centre = at(rally.position)
            val measured = measurer.measure(rally.index.toString(), label)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(centre.x + ring + 2.dp.toPx(), centre.y - measured.size.height / 2f),
            )
        }
    }
}
