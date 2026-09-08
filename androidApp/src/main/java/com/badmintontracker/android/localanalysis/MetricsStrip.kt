package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.shared.prefs.RacketArm

/** The tile's own padding, counted into the measured width. */
private val TILE_PADDING = 10.dp

/**
 * The per-frame measurements under the skeleton video.
 *
 * One tile per visible kind, label over value, absent shown as a dash so the
 * layout never jumps and a stale number is never left on screen. Tapping a
 * tile selects it for the graph and the overlay's arc. The caption says once
 * what every angle tile means; the racket-arm control is the one thing the
 * pipeline cannot know and the coach can.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricsStrip(
    metrics: PoseMetrics?,
    hasCourt: Boolean,
    racketArm: RacketArm?,
    onRacketArm: (RacketArm?) -> Unit,
    selected: MetricKind,
    onSelect: (MetricKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileWidth = rememberTileWidth()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleKinds(hasCourt, racketArm).forEach { kind ->
                MetricTile(
                    label = metricLabel(kind, racketArm),
                    value = formatMetric(kind, metrics?.let { kind.of(it) }),
                    selected = kind == selected,
                    width = tileWidth,
                    onClick = { onSelect(kind) },
                )
            }
        }
        // The caption and the control get a row each. Side by side they fit a
        // 412 dp phone only by touching: the caption ends on the pixel the
        // first segment starts.
        Text(
            "Angles as seen by the camera",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp)) {
            listOf(RacketArm.LEFT to "Left arm", RacketArm.RIGHT to "Right arm", null to "Both")
                .forEachIndexed { index, (arm, label) ->
                    SegmentedButton(
                        selected = racketArm == arm,
                        onClick = { onRacketArm(arm) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
        }
    }
}

/**
 * The width every tile shares, wide enough for the longest text any tile can
 * hold at the current density and font scale.
 *
 * One width for all of them, so the columns line up between the rows the
 * FlowRow wraps into; a minimum width would let the widest label push its own
 * tile out and leave the rows ragged. A fixed dp figure cannot do it: it holds
 * at one font scale and wraps "Behind line" onto two lines at the next system
 * step up, which makes that one tile taller and the row ragged the other way.
 *
 * So it is measured, not guessed: the widest label over every [MetricKind]
 * against the widest value the formatter can produce, through the same text
 * styles the tile draws with, plus the tile's own padding. Every kind, not
 * only the visible ones, so the width does not jump when a video has no court
 * marks or when the racket arm is chosen and the arm labels lose their side.
 *
 * Measured on a 412 dp phone at 420 dpi: at font scale 1.0 the binding string
 * is the "Behind line" label and a tile comes out at 81 dp, so four tiles and
 * their 8 dp gaps take 347 dp of the 388 dp the strip's side padding leaves;
 * at 1.3 a tile is 99 dp and three fit a row. The 88 dp this replaced rounded
 * the same label bound up for headroom the measurement does not need. At a
 * larger font scale the tiles grow with the text and the FlowRow wraps into
 * more rows, which is the intended outcome.
 */
@Composable
private fun rememberTileWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    return remember(density.density, density.fontScale, labelStyle, valueStyle) {
        // Built through the formatter rather than typed out, so the widest
        // value cannot drift away from what the tiles actually show.
        val widest = MetricKind.entries.map { metricLabel(it, racketArm = null) to labelStyle } +
            listOf(
                formatMetric(MetricKind.BEHIND_LINE, -0.5) to valueStyle,
                formatMetric(MetricKind.LEAN, 180.0) to valueStyle,
            )
        val textPx = widest.maxOf { (text, style) -> measurer.measure(text, style).size.width }
        with(density) { textPx.toDp() } + TILE_PADDING * 2
    }
}

@Composable
private fun MetricTile(label: String, value: String, selected: Boolean, width: Dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(width),
    ) {
        Column(modifier = Modifier.padding(horizontal = TILE_PADDING, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
