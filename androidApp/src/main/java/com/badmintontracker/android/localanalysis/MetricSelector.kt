package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
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
 *
 * One card per option, in the mock's shape: the name and its cost on the left,
 * a mark on the right, and the accent around the card while it is on. The
 * caller heads the page; this draws the choices, the total and where the total
 * came from.
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

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnalysisMetric.entries.forEach { metric ->
            // The cost OF THIS OPTION, measured as what turning it off would
            // save rather than what it would cost alone. Pose is shared, so
            // quoting a standalone price would promise a saving that turning
            // one of the two pose options off does not deliver.
            val without = estimateAnalysis(frames, fps, selected - metric, throughput)
            val marginal = total.fastSeconds - without.fastSeconds
            val on = metric in selected

            MetricCard(
                title = metric.title(),
                detail = metric.detail(marginal, on),
                selected = on,
                onToggle = { onToggle(metric) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Estimated time",
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (selected.isEmpty()) "Nothing selected" else total.describe(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            if (throughput.measured) {
                "Based on how fast this phone ran your last analysis."
            } else {
                // Said plainly rather than hidden: the first estimate on an
                // unmeasured phone comes from a different one.
                "First estimate, from a reference phone. It corrects itself after one run."
            },
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
        )
        // The estimate and the choice above it both price the on-device run.
        // The cloud's cost is someone else's GPU and its worker decides its own
        // stages, so laying the two buttons under one list of options would
        // otherwise imply a control over the cloud run that does not exist.
        Text(
            "A cloud run decides its own stages and takes as long as it takes.",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    detail: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.large)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
            )
        }
        Spacer(Modifier.size(16.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(ShuttlRadius.pill))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                )
                .border(
                    width = if (selected) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(ShuttlRadius.pill),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = ShuttlTheme.extended.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
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
        // as a bug. Only a pose option can be free for that reason: clip
        // cutting is its own pass, and on a short video it rounds to nothing
        // without any pose pass running at all, which is what this said before.
        marginalSeconds < 1.0 && needsPose -> "no extra time, the pose pass is already running"
        marginalSeconds < 1.0 -> "no measurable extra time"
        minutes < 1.0 -> "adds under a minute"
        else -> "adds about ${minutes.toInt()} min"
    }
    return "$base · $cost"
}
