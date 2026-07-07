package com.badmintontracker.android.localvideo

import androidx.compose.foundation.clickable
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
import kotlinx.datetime.Instant
import java.util.Locale

/** "On this phone" section rendered inside the matches LazyColumn. */
fun LazyListScope.localVideoSection(
    rows: List<LocalVideoRow>,
    header: @Composable (String) -> Unit,
    onRowClick: (LocalVideoEntry) -> Unit,
    onAnalyzeClick: (LocalVideoRow) -> Unit,
    onRemove: (LocalVideoEntry) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header-local") { header("On this phone") }
    items(rows, key = { "local-${it.entry.id}" }) { row ->
        LocalVideoRowItem(
            row = row,
            onClick = { onRowClick(row.entry) },
            onAnalyze = { onAnalyzeClick(row) },
            onRemove = { onRemove(row.entry) },
        )
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
    var errorDialogOpen by remember { mutableStateOf(false) }

    if (errorDialogOpen) {
        AlertDialog(
            onDismissRequest = { errorDialogOpen = false },
            title = {
                Text("Analysis failed" + (entry.failedStep?.let { " (${it.label()})" } ?: ""))
            },
            text = { Text(entry.failureMessage ?: "Unknown error") },
            confirmButton = {
                TextButton(onClick = { errorDialogOpen = false; onAnalyze() }) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = { errorDialogOpen = false }) { Text("Close") }
            },
        )
    }

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
                val failed = entry.stage == AnalyzeStage.FAILED
                Text(
                    text = if (failed) "$status · tap for details" else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (failed) {
                        Modifier.clickable { errorDialogOpen = true }
                    } else {
                        Modifier
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (row.canAnalyze) {
            ShuttlButton(
                text = "Analyze",
                onClick = onAnalyze,
                variant = ShuttlButtonVariant.Primary,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
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

private fun AnalyzeStep.label(): String = when (this) {
    AnalyzeStep.UPLOAD -> "upload"
    AnalyzeStep.CREATE_ROW -> "registration"
    AnalyzeStep.KEYPOINTS -> "court points"
    AnalyzeStep.TRIGGER -> "start"
    AnalyzeStep.PROCESSING -> "processing"
}
