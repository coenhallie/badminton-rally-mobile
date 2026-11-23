package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.BasePositions
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.analysis.player.basePositions
import com.badmintontracker.shared.analytics.CourtSideNames
import com.badmintontracker.shared.local.describeBase
import com.badmintontracker.shared.local.describeRally
import com.badmintontracker.shared.local.RallyMark
import com.badmintontracker.shared.local.placeRallyLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the player played from, rally by rally, on the court.
 *
 * The median position of each rally, which the 2026-09-08 research found to be
 * the most trustworthy number the stored track supports: two pose models agree
 * on it to a centimetre or two. Drawn on the same court as the heatmap so the
 * two read together, then said in words against the two lines the near player
 * plays around.
 *
 * The rally windows are the clips', which carry two seconds of pre-roll and one
 * and a half of post-roll around the detected rally. A median does not notice:
 * the player is standing at or near their base during both. For a device run
 * they are the clips this phone cut; for a cloud run they are `rally_clips`,
 * which is that video's rally truth and what the coach's annotations hang off.
 *
 * The track is resolved exactly as [HeatmapPanel] resolves it, for the same
 * reasons, so the two tabs can never disagree about which run they show.
 */
@Composable
fun BasePositionPanel(
    entryId: String,
    runner: LocalAnalysisRunner,
    /**
     * The rallies to measure over, resolved by the caller alongside the track.
     *
     * Passed in rather than read here: a cloud analysis cuts no clips on this
     * phone and takes its rallies from `rally_clips`, and the screen that
     * decides which track to draw is the only place that can keep the two on
     * one run. See `rallyWindowsFor`.
     */
    windows: List<RallyWindow>,
    /** Who stands at each end, for the Near | Far toggle; null while the match does not say. */
    sideNames: CourtSideNames?,
    modifier: Modifier = Modifier,
    /** When true, reuse [preloadedTrack] parsed by AnalysisSection instead of re-reading disk. */
    usePreloadedTrack: Boolean = false,
    preloadedTrackLoaded: Boolean = false,
    preloadedTrack: PlayerTrackStore.Stored? = null,
) {
    // Measured off the thread drawing the frame, reusing AnalysisSection's
    // already-parsed track JSON when supplied so tab switches stay instant.
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val measured = produceState<Measured?>(
        initialValue = null,
        entryId,
        done,
        windows,
        usePreloadedTrack,
        preloadedTrackLoaded,
        preloadedTrack,
    ) {
        if (usePreloadedTrack && !preloadedTrackLoaded) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val stored = if (usePreloadedTrack) preloadedTrack else runner.storedTrack(entryId)
            val source = heatmapSource(done, stored)
            // Measured for every side here, off the main thread, rather than
            // recomputed when the toggle moves: the medians are cheap next to
            // the parse above, and doing it on selection would put them on the
            // thread drawing the frame.
            Measured(
                source, windows,
                source?.tracks?.associate {
                    it.side to basePositions(it.track, source.fps, windows)
                } ?: emptyMap(),
            )
        }
    }.value

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The frames between the panel appearing and the measurement landing:
        // show a subtle centered indicator rather than a blank screen or the
        // empty/missing messages below.
        if (measured == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            return@Column
        }
        val (source, windows, basesBySide) = measured
        var side by rememberSaveable { mutableStateOf(CourtSide.NEAR) }
        val shown = source?.tracks?.firstOrNull { it.side == side } ?: source?.tracks?.firstOrNull()
        val bases = shown?.let { basesBySide[it.side] }
        if (source != null && source.tracks.size > 1 && shown != null) {
            SideToggle(
                sides = source.tracks.map { it.side },
                selected = shown.side,
                onSelect = { side = it },
                names = sideNames,
                modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 8.dp),
            )
        }
        val message = when {
            source == null || shown == null ->
                "This analysis is no longer loaded. Run it again to see where the player stood."
            shown.track.samples.isEmpty() -> whyEmpty(shown.track)
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
        // The sentences measure from the shown player's own lines.
        val shownSide = shown?.side ?: CourtSide.NEAR
        CourtBaseView(bases)
        bases.overall?.let {
            Text(
                "Whole match: ${describeBase(it, shownSide)}",
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
                describeRally(rally, shownSide),
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
    val dotFill = dot.copy(alpha = 0.8f)
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val radiusPx = with(density) { 5.dp.toPx() }
    val ringPx = with(density) { 10.dp.toPx() }
    val ringStrokePx = with(density) { 2.5.dp.toPx() }
    val gapPx = with(density) { 2.dp.toPx() }
    val ringStroke = remember(ringStrokePx) { Stroke(width = ringStrokePx) }

    // Pre-measure every rally number and pre-compute label placement outside
    // Canvas.onDraw so draw passes allocate nothing and never run text layout.
    val texts = remember(bases.rallies, label, measurer) {
        bases.rallies.map { measurer.measure(it.index.toString(), label) }
    }
    val spots = remember(bases, texts, canvasSize, radiusPx, ringPx, ringStrokePx, gapPx) {
        if (canvasSize == IntSize.Zero) {
            emptyList()
        } else {
            val totalW = Court.WIDTH_DOUBLES + 2 * COURT_DRAW_MARGIN_M
            val totalH = Court.LENGTH + 2 * COURT_DRAW_MARGIN_M
            val w = canvasSize.width.toFloat()
            val h = canvasSize.height.toFloat()
            fun atX(x: Double) = ((x + COURT_DRAW_MARGIN_M) / totalW * w).toFloat()
            fun atY(y: Double) = ((y + COURT_DRAW_MARGIN_M) / totalH * h).toFloat()
            val overall = bases.overall
            placeRallyLabels(
                marks = bases.rallies.mapIndexed { i, rally ->
                    RallyMark(
                        atX(rally.position.x),
                        atY(rally.position.y),
                        texts[i].size.width.toFloat(),
                        texts[i].size.height.toFloat(),
                    )
                },
                dotRadius = radiusPx,
                gap = gapPx,
                ringX = overall?.let { atX(it.x) } ?: 0f,
                ringY = overall?.let { atY(it.y) } ?: 0f,
                // The stroke straddles the radius, so the outer edge is half a
                // line width past it.
                ringReach = if (overall == null) 0f else ringPx + ringStrokePx / 2f,
                canvasWidth = w,
                canvasHeight = h,
            )
        }
    }

    CourtCard(onCanvasSizeChanged = { canvasSize = it }) {
        drawCourt(marginM = COURT_DRAW_MARGIN_M, lineColor = courtLine)
        val totalW = Court.WIDTH_DOUBLES + 2 * COURT_DRAW_MARGIN_M
        val totalH = Court.LENGTH + 2 * COURT_DRAW_MARGIN_M
        fun at(p: Point) = Offset(
            ((p.x + COURT_DRAW_MARGIN_M) / totalW * size.width).toFloat(),
            ((p.y + COURT_DRAW_MARGIN_M) / totalH * size.height).toFloat(),
        )
        bases.rallies.forEach { rally ->
            drawCircle(dotFill, radius = radiusPx, center = at(rally.position))
        }
        bases.overall?.let {
            drawCircle(dot, radius = ringPx, center = at(it), style = ringStroke)
        }
        // Numbers last, each beside its own dot and clear of every other
        // number, dot and the whole-match marker, so none of them sits on the
        // one label that says which rally a dot is.
        spots.forEach { spot ->
            drawText(textLayoutResult = texts[spot.mark], topLeft = Offset(spot.left, spot.top))
        }
    }
}

/**
 * A finished measurement. Null in place of one of these means it is still
 * running, which is a blank frame rather than any of the panel's messages.
 */
private data class Measured(
    val source: HeatmapSource?,
    val windows: List<RallyWindow>,
    /** One entry per side the analysis found somebody on. */
    val basesBySide: Map<CourtSide, BasePositions>,
)
