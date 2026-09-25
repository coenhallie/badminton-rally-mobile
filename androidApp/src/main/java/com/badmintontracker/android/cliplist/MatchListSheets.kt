package com.badmintontracker.android.cliplist

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListItem
import kotlinx.coroutines.launch

/**
 * The glyph beside each [MatchListCopy.LEGEND] row, by position: the two
 * where-glyphs first, then the analyses in the order the status line draws
 * them. Built from the same icons the status line uses so the legend cannot
 * name a glyph the rows do not draw.
 */
private val legendGlyphs: List<Pair<ImageVector, Boolean>> = listOf(
    ShuttlIcons.Smartphone to true,
    ShuttlIcons.Cloud to true,
) + MatchAnalysis.entries.map { it.icon() to false }

/** Names the status line's glyphs. The match page says the same things in words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchLegendSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Pinned rather than left to surfaceContainerLow (the same bgSecondary
        // value today): see AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(MatchListCopy.LEGEND_TITLE, style = MaterialTheme.typography.titleLarge)
            MatchListCopy.LEGEND.zip(legendGlyphs).forEach { (copy, glyph) ->
                val (label, detail) = copy
                val (icon, isWhere) = glyph
                Row(verticalAlignment = Alignment.Top) {
                    // One 18dp slot for both kinds, so the labels line up down
                    // the sheet whether the glyph beside them is bare or chipped.
                    Box(Modifier.padding(top = 1.dp).size(18.dp), contentAlignment = Alignment.Center) {
                        if (isWhere) WhereGlyph(icon) else AnalysisChip(icon)
                    }
                    Spacer(Modifier.width(DrawerList.gap))
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * A swipe on a row that has both a file on this phone and a match in the
 * cloud: two different things to delete, so the coach picks. The option that
 * cannot lose data is first. The menu does not come here: it lists "Remove
 * video from this phone" and "Delete match" as two items already.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteChoiceSheet(
    item: MatchListItem,
    onRemoveFromPhone: () -> Unit,
    onDeleteEverywhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            ChoiceCard(
                label = MatchListCopy.CHOICE_REMOVE,
                detail = MatchListCopy.removeFromPhoneDetail(item),
                labelColor = MaterialTheme.colorScheme.onSurface,
                onClick = { hideThen(onRemoveFromPhone) },
            )
            ChoiceCard(
                label = MatchListCopy.CHOICE_DELETE,
                detail = MatchListCopy.choiceDeleteDetail(item.delete, onThisPhone = item.entryId != null),
                labelColor = MaterialTheme.colorScheme.error,
                onClick = { hideThen(onDeleteEverywhere) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { hideThen(onDismiss) }) { Text(MatchListCopy.CHOICE_CANCEL) }
            }
        }
    }
}

@Composable
private fun ChoiceCard(label: String, detail: String, labelColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ShuttlRadius.medium)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Modal shown when a local video's analysis ends in a failure or a no-rallies result. */
@Composable
internal fun AnalyzeResultDialog(
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
