package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.local.AnalysisMetric
import com.badmintontracker.shared.local.DeviceThroughput
import com.badmintontracker.shared.local.estimateAnalysis

/**
 * What to compute, and what it will cost in time.
 *
 * Pose roughly doubles an analysis, and an eight-minute video is hours rather
 * than minutes, so which metrics are wanted is the user's decision and not a
 * default to be discovered afterwards. The estimate is shown per option, next
 * to the option, because a total alone does not tell anyone which switch to
 * turn off.
 */
@Composable
fun MetricSelector(
    frames: Int,
    fps: Double,
    selected: Set<AnalysisMetric>,
    throughput: DeviceThroughput,
    onToggle: (AnalysisMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = estimateAnalysis(frames, fps, selected, throughput)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What to analyse", style = MaterialTheme.typography.titleSmall)

        AnalysisMetric.entries.forEach { metric ->
            // The cost OF THIS OPTION, measured as what turning it off would
            // save rather than what it would cost alone. Pose is shared, so
            // quoting a standalone price would promise a saving that turning
            // one of the two pose options off does not deliver.
            val without = estimateAnalysis(frames, fps, selected - metric, throughput)
            val marginal = total.fastSeconds - without.fastSeconds

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(metric) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = metric in selected, onCheckedChange = { onToggle(metric) })
                Column(Modifier.weight(1f)) {
                    Text(metric.title(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        metric.detail(marginal, metric in selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            if (selected.isEmpty()) "Nothing selected" else "Estimated ${total.describe()}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (throughput.measured) {
                "Based on how fast this phone ran your last analysis."
            } else {
                // Said plainly rather than hidden: the first estimate on an
                // unmeasured phone comes from a different one.
                "First estimate, from a reference phone. It corrects itself after one run."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AnalysisMetric.title(): String = when (this) {
    AnalysisMetric.RALLY_CLIPS -> "Rally clips"
    AnalysisMetric.PLAYER_MOVEMENT -> "Player movement and heatmap"
    AnalysisMetric.SKELETON_PLAYBACK -> "Skeleton playback"
}

private fun AnalysisMetric.detail(marginalSeconds: Double, isSelected: Boolean): String {
    val base = when (this) {
        AnalysisMetric.RALLY_CLIPS -> "Each rally cut into its own playable clip"
        AnalysisMetric.PLAYER_MOVEMENT -> "Where the near player spent the match"
        AnalysisMetric.SKELETON_PLAYBACK -> "Every joint kept, to draw over the video"
    }
    if (!isSelected) return base
    val minutes = marginalSeconds / 60.0
    val cost = when {
        // Zero marginal cost is the honest answer when another selected option
        // already pays for the same pose pass, and saying so stops it reading
        // as a bug.
        marginalSeconds < 1.0 -> "no extra time, the pose pass is already running"
        minutes < 1.0 -> "adds under a minute"
        else -> "adds about ${minutes.toInt()} min"
    }
    return "$base · $cost"
}
