package com.badmintontracker.android.localvideo.court

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.analytics.CAPABILITY_SHARED_LINE
import com.badmintontracker.shared.analytics.cloudNotes
import com.badmintontracker.shared.analytics.deviceNotes
import com.badmintontracker.shared.analytics.panelCapabilities

/**
 * What each of the two runs produces, opened from the "?" beside "What to
 * analyze".
 *
 * Every string comes from `:shared`, so this sheet and its iOS twin cannot
 * drift: the copy is a promise about what the app does, and the two phones
 * making different promises is the failure the shared analytics rules exist
 * to prevent.
 *
 * Two columns rather than a paragraph. The coach is standing in a sports hall
 * deciding between two buttons, and the question is a comparison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitySheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "What each run produces",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                CAPABILITY_SHARED_LINE,
                style = MaterialTheme.typography.bodyMedium,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(20.dp))
            CapabilityRow("", "In cloud", "On device", header = true)
            panelCapabilities.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CapabilityRow(row.panel.label, row.cloud, row.device)
            }

            Spacer(Modifier.height(24.dp))
            NoteBlock("In cloud", cloudNotes)
            Spacer(Modifier.height(16.dp))
            NoteBlock("On device", deviceNotes)
        }
    }
}

/**
 * One line of the table.
 *
 * The three columns are weighted rather than fixed so the longest cell - "Both
 * players, if the video is on this phone" - wraps instead of pushing the row
 * off the screen at a large font scale.
 */
@Composable
private fun CapabilityRow(what: String, cloud: String, device: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) ShuttlTheme.extended.textTertiary else MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            what,
            style = if (header) style else MaterialTheme.typography.labelMedium,
            color = if (header) color else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(0.8f),
        )
        Text(cloud, style = style, color = color, modifier = Modifier.weight(1.1f))
        Text(device, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NoteBlock(title: String, notes: List<String>) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        notes.forEach { note ->
            // The bullet in its own column rather than inline in the string:
            // a wrapped line has to hang under the text, not run back to the
            // margin and read as a new bullet.
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    "\u2022",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShuttlTheme.extended.textTertiary,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShuttlTheme.extended.textTertiary,
                )
            }
        }
    }
}
