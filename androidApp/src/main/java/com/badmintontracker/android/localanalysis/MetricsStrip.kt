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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.shared.prefs.RacketArm

/**
 * Every tile is this wide, so the columns line up between the rows the
 * FlowRow wraps into. A minimum width would let the widest label push its own
 * tile out and leave the rows ragged.
 *
 * Measured on the emulator at 420 dpi, not guessed: the longest label,
 * "Behind line", renders 60.6 dp wide at labelSmall, so with 10 dp of padding
 * each side a tile needs 80.6 dp; the longest value, "-0.50 m", needs about
 * 71 dp the same way. 88 dp clears both and still fits four tiles and their
 * 8 dp gaps across a 412 dp phone (376 dp of the 387 dp available).
 */
private val TILE_WIDTH = 88.dp

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

@Composable
private fun MetricTile(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(TILE_WIDTH),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
