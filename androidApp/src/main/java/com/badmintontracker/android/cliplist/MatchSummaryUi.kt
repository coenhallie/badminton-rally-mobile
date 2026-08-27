package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.clipdetail.LabelBadge
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelCount
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.stripLabelName

/** Two chips plus an overflow count is what fits beside the chevron at 360dp. */
private const val STRIP_CHIP_LIMIT = 2

/**
 * The match's top labels, above the rally list. The whole row is one target:
 * a chip that looks tappable but does not filter would promise something this
 * screen does not do.
 */
@Composable
fun MatchLabelStrip(
    summary: MatchLabelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = summary.labels.take(STRIP_CHIP_LIMIT)
    val overflow = summary.labels.size - shown.size
    val description = "Match summary, ${labelledNotes(summary.labelledNoteCount)}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { LabelCountChip(it) }
        if (overflow > 0) {
            Text(
                "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One label and how many times it was used. Internal rather than private because
 * the courtside tally on the match page renders the same thing, and the two
 * summaries have to read as the same thing.
 */
@Composable
internal fun LabelCountChip(label: LabelCount) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelBadge(name = stripLabelName(label.name), colorKey = label.colorKey)
        Text(
            label.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full breakdown. [topRallyName] is resolved by the caller, which holds the
 * clips, so this stays a pure rendering of the summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchSummarySheet(
    summary: MatchLabelSummary,
    topRallyName: String?,
    onTopRallyClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                labelledNotes(summary.labelledNoteCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            summary.labels.forEach { label ->
                LabelShareRow(label)
                Spacer(Modifier.height(12.dp))
            }
            val top = summary.topRally
            if (top != null && topRallyName != null) {
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTopRallyClick)
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Most labelled · $topRallyName · ${labelledNotes(top.labelCount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelShareRow(label: LabelCount) {
    val swatch = LabelColor.from(label.colorKey)
    val barColor = swatch?.let { Color(it.background.toInt()) } ?: MaterialTheme.colorScheme.primary
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelBadge(name = label.name, colorKey = label.colorKey)
            Spacer(Modifier.weight(1f))
            Text(
                "${label.count}   ${label.sharePercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    // A label rounding to 0% still gets a visible sliver: the
                    // percentage stays honest, the bar stays present.
                    .fillMaxWidth((label.sharePercent / 100f).coerceIn(0.01f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
    }
}

/** "1 labelled note" / "12 labelled notes". Never "notes": see MatchLabelSummary. */
internal fun labelledNotes(count: Int): String =
    "$count labelled ${if (count == 1) "note" else "notes"}"
