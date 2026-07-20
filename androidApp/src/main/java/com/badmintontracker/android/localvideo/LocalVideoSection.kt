package com.badmintontracker.android.localvideo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.SwipeToRemoveRow
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import kotlinx.datetime.Instant
import java.util.Locale

/**
 * "On this phone" section rendered inside the matches LazyColumn.
 *
 * [onRemoveRequest] asks the host screen to confirm; actual removal happens
 * there, so swipes must not dismiss the row.
 */
fun LazyListScope.localVideoSection(
    rows: List<LocalVideoRow>,
    header: @Composable (String) -> Unit,
    onRowClick: (LocalVideoEntry) -> Unit,
    onAnalyzeClick: (LocalVideoRow) -> Unit,
    onRemoveRequest: (LocalVideoEntry) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header-local") { header("On this phone") }
    items(rows, key = { "local-${it.entry.id}" }) { row ->
        val rowItem = @Composable {
            LocalVideoRowItem(
                row = row,
                onClick = { onRowClick(row.entry) },
                onAnalyze = { onAnalyzeClick(row) },
                onRemove = { onRemoveRequest(row.entry) },
            )
        }
        if (row.canRemove) {
            SwipeToRemoveRow(
                label = "Remove",
                onSwiped = { onRemoveRequest(row.entry); false },
            ) {
                rowItem()
            }
        } else {
            // Mid-pipeline: removing would delete the file under the active
            // upload and swallow the run's outcome.
            rowItem()
        }
        HorizontalDivider()
    }
}

@Composable
private fun LocalVideoRowItem(
    row: LocalVideoRow,
    onClick: () -> Unit,
    onAnalyze: () -> Unit,
    onRemove: () -> Unit,
) {
    val entry = row.entry
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.uri,
            contentDescription = null,
            modifier = Modifier.size(96.dp, 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${row.durationText} · ${formatDate(Instant.fromEpochMilliseconds(entry.addedAtEpochMs))}"
                    .uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.statusText?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (row.canAnalyze) {
            ShuttlButton(
                text = row.analyzeLabel,
                onClick = onAnalyze,
                variant = ShuttlButtonVariant.Primary,
                compact = true,
            )
        } else if (isAnalysisRunning(entry.stage)) {
            // Settled stages (e.g. ANALYZED) show neither button nor spinner —
            // the status text already says what happened.
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        if (row.canRemove) {
            // The menu must share a Box with its anchor: DropdownMenu positions
            // itself relative to its parent, not the IconButton.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Local video menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from app") },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
    }
}

/** Modal shown when a local video's analysis ends in a failure or a no-rallies result. */
@Composable
fun AnalyzeResultDialog(
    entry: LocalVideoEntry,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = entry.failureMessage ?: "Unknown error"
    val noRallies = message.contains("no rallies", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (noRallies) "No rallies found" else "Analysis failed") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onRetry) { Text("Retry") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
