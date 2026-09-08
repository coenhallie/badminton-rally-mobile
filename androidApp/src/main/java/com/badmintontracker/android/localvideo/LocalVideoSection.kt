package com.badmintontracker.android.localvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.badmintontracker.android.cliplist.DrawerList
import com.badmintontracker.android.cliplist.DrawerRowContainer
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import kotlinx.datetime.Instant

/**
 * "On this phone" section rendered inside the matches LazyColumn.
 *
 * The mock draws these as filled cards rather than list rows: this is the
 * shortest path from "I just filmed a match" to "analyze it", so it gets the
 * one raised surface in the drawer while the match rows below stay quiet.
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
    onEditDetails: (LocalVideoEntry) -> Unit,
    /**
     * Null when this video has no stored player track.
     *
     * The heatmap used to be reachable only from the banner of the run that
     * produced it, so it vanished with the process even though the track was on
     * disk - half an hour of analysis with no way back to it.
     */
    onOpenHeatmap: ((LocalVideoEntry) -> Unit)? = null,
    hasHeatmap: (LocalVideoEntry) -> Boolean = { false },
    onOpenLocalClips: ((LocalVideoEntry) -> Unit)? = null,
    localClipCount: (LocalVideoEntry) -> Int = { 0 },
) {
    if (rows.isEmpty()) return
    item(key = "header-local") { header("On this phone") }
    items(rows, key = { "local-${it.entry.id}" }) { row ->
        DrawerRowContainer(
            shape = RoundedCornerShape(ShuttlRadius.large),
            fill = ShuttlTheme.extended.bgTertiary,
            // Mid-pipeline rows pass null: removing would delete the file under
            // the active upload, or out from under the device run still
            // decoding it, and swallow the run's outcome.
            swipeLabel = "Remove".takeIf { row.canRemove },
            onSwiped = { onRemoveRequest(row.entry); false },
        ) {
            LocalVideoRowItem(
                row = row,
                onClick = { onRowClick(row.entry) },
                onAnalyze = { onAnalyzeClick(row) },
                onRemove = { onRemoveRequest(row.entry) },
                onEditDetails = { onEditDetails(row.entry) },
                onOpenHeatmap = onOpenHeatmap?.takeIf { hasHeatmap(row.entry) }
                    ?.let { open -> { open(row.entry) } },
                localClips = localClipCount(row.entry).takeIf { it > 0 },
                onOpenLocalClips = onOpenLocalClips?.let { open -> { open(row.entry) } },
            )
        }
    }
}

@Composable
private fun LocalVideoRowItem(
    row: LocalVideoRow,
    onClick: () -> Unit,
    onAnalyze: () -> Unit,
    onRemove: () -> Unit,
    onEditDetails: () -> Unit,
    onOpenHeatmap: (() -> Unit)? = null,
    localClips: Int? = null,
    onOpenLocalClips: (() -> Unit)? = null,
) {
    val entry = row.entry
    var menuOpen by remember { mutableStateOf(false) }
    // The menu renders when either action applies; each item is gated on its
    // own rule, so a mid-pipeline row that can do neither shows no menu at all.
    val hasMenu = row.canRemove || row.canEditDetails

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = DrawerList.rowPaddingH,
                // An IconButton carries 12dp of its own padding inside a 48dp
                // box. Backing that out here is what puts the glyph the same
                // 16dp from the card's edge as the thumbnail on the other side,
                // instead of an optical 28dp that reads as a misaligned card.
                end = if (hasMenu) DrawerList.rowPaddingH - 12.dp else DrawerList.rowPaddingH,
                top = DrawerList.rowPaddingV,
                bottom = DrawerList.rowPaddingV,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = entry.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(DrawerList.thumbWidth, DrawerList.thumbHeight)
                    .clip(RoundedCornerShape(ShuttlRadius.small))
                    // A thumbnail that has not decoded yet would otherwise be a
                    // card-coloured hole; this reads as an empty slot instead.
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(DrawerList.gap))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${row.durationText} · " +
                        formatDate(Instant.fromEpochMilliseconds(entry.addedAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.statusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two, not one: the drawer leaves this column about 118dp
                        // once the thumbnail and the menu have taken theirs. One
                        // line is enough for every label the row shows at the
                        // default font scale; at a larger one this wraps instead
                        // of truncating mid-word.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.isBusy && !row.canAnalyze) {
                // Either pipeline, not just the cloud one: a device run leaves the
                // stage alone, so asking isAnalysisRunning(stage) here left the row
                // showing nothing at all while the phone was analysing it.
                //
                // Settled stages (e.g. ANALYZED) show neither ring nor button -
                // the status text already says what happened.
                // Indeterminate even for a run whose fraction is known: at 16dp a
                // determinate ring spends the first minutes looking like an empty
                // grey circle, which reads as "stalled" rather than "2% done". The
                // motion is what says the phone is working; the number lives on
                // Home's own analysis banner and in the chrome indicator, both of
                // which have room to draw it.
                Spacer(Modifier.width(DrawerList.gap))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            if (hasMenu) {
                // The menu must share a Box with its anchor: DropdownMenu positions
                // itself relative to its parent, not the IconButton.
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Local video menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (localClips != null && onOpenLocalClips != null) {
                            DropdownMenuItem(
                                text = { Text("Clips on this phone ($localClips)") },
                                onClick = { menuOpen = false; onOpenLocalClips() },
                            )
                        }
                        if (onOpenHeatmap != null) {
                            DropdownMenuItem(
                                text = { Text("Player heatmap") },
                                onClick = { menuOpen = false; onOpenHeatmap() },
                            )
                        }
                        if (row.canEditDetails) {
                            DropdownMenuItem(
                                text = { Text("Edit details") },
                                onClick = { menuOpen = false; onEditDetails() },
                            )
                        }
                        if (row.canRemove) {
                            DropdownMenuItem(
                                text = { Text("Remove from app") },
                                onClick = { menuOpen = false; onRemove() },
                            )
                        }
                    }
                }
            }
        }
        if (row.canAnalyze) {
            // Its own line, not the trailing slot the mock draws it in. The
            // mock's card carries a thumbnail, two lines and one pill; this one
            // also carries the overflow menu, and squeezing a pill in beside it
            // left the title about 38dp wide - roughly four characters. Full
            // width instead, which is also how Home states its two primary
            // actions, so the card reads as one clear next step.
            Spacer(Modifier.height(DrawerList.gap))
            ShuttlButton(
                text = row.analyzeLabel,
                onClick = onAnalyze,
                variant = ShuttlButtonVariant.Primary,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // Cancels the menu compensation above, so the pill ends flush
                    // with the thumbnail's left edge and the card's right inset.
                    .padding(end = if (hasMenu) 12.dp else 0.dp),
            )
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
