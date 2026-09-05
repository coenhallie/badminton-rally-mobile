package com.badmintontracker.android.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.analytics.AnalyticsRowState
import java.util.Locale

/** Which of the drawer's own sections a row belongs to, in display order. */
enum class AnalyticsGroup(val label: String) {
    LOCAL_VIDEOS("On this phone"),
    OWNED_MATCHES("My matches"),
    SHARED("Shared with me"),
}

/**
 * What an `ANALYSABLE` row's control should show, in place of a live "Analyse"
 * button, when one of the two pipelines is already busy with its video.
 *
 * Three cases rather than either pipeline's own state: the on-device run
 * ([com.badmintontracker.android.localanalysis.LocalAnalysisState]) and the
 * cloud one ([com.badmintontracker.shared.localvideo.AnalyzeStage]) both feed
 * it, and a row has one control to show for both. See [affordanceFor] for which
 * of the two speaks.
 */
sealed interface AnalyseAffordance {
    /** Nothing is running yet (or the last run finished with no track saved). */
    data object Ready : AnalyseAffordance

    /**
     * A run is under way. [phase] names it: Preparing, Analysing or Cutting for
     * the device run, Uploading or Processing for the cloud one.
     */
    data class InProgress(val phase: String) : AnalyseAffordance

    /**
     * The last attempt for this row ended in a failure, from either pipeline.
     * [reason] is that pipeline's own message.
     */
    data class Failed(val reason: String) : AnalyseAffordance
}

/** One row of the Analytics list. [entryId] is null only when [state] is NOT_ON_DEVICE
 * with no video to point at (a score-only match, or a shared match) - inert either way. */
data class AnalyticsRow(
    val key: String,
    val entryId: String?,
    val group: AnalyticsGroup,
    val title: String,
    val subtitle: String,
    val state: AnalyticsRowState,
    val affordance: AnalyseAffordance = AnalyseAffordance.Ready,
)

/**
 * The coach's Analytics list: one section per group mirroring the drawer's own
 * grouping (local videos, owned matches, shared), so the same match shows up in
 * the same place twice.
 *
 * [onOpenDetail] fires for a READY row, opening its analysis, which shows the
 * heatmap; [onAnalyse] fires for an ANALYSABLE row's button whether it reads
 * "Analyse" or "Retry", and the caller decides which of the two it is: a failed
 * cloud run with its court points already saved resumes from the step that
 * failed, and everything else goes to court marking. NOT_ON_DEVICE rows call
 * neither.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    rows: List<AnalyticsRow>,
    onOpenDetail: (AnalyticsRow) -> Unit,
    onAnalyse: (AnalyticsRow) -> Unit,
    onBack: () -> Unit,
) {
    // Every row inert for the same reason: nothing on this list has ever
    // touched this phone. Repeating "Not on this phone" down the whole list
    // would say the same thing as many times as there are rows.
    val legend = analyticsLegend(rows)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("ANALYTICS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No matches yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "explainer") {
                when (legend) {
                    AnalyticsLegend.SILENT -> Unit
                    AnalyticsLegend.DOT -> DotLegend()
                    AnalyticsLegend.NOTHING_ON_THIS_PHONE, AnalyticsLegend.ANALYSE_BUTTON -> Text(
                        if (legend == AnalyticsLegend.NOTHING_ON_THIS_PHONE) {
                            "None of these matches are on this phone yet."
                        } else {
                            // What the button actually does, said plainly, so a
                            // screen called Analytics does not look like it is
                            // about to draw a chart.
                            "Analyse sends a video to the cloud and cuts it into rallies."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            AnalyticsGroup.entries.forEach { group ->
                val groupRows = rows.filter { it.group == group }
                if (groupRows.isNotEmpty()) {
                    item(key = "header-${group.name}") { SectionHeader(group.label) }
                    items(groupRows, key = { it.key }) { row ->
                        AnalyticsRowItem(
                            row = row,
                            showNotOnDeviceSubtitle = legend != AnalyticsLegend.NOTHING_ON_THIS_PHONE,
                            onClick = { onOpenDetail(row) },
                            onAnalyse = { onAnalyse(row) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DotLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Analysed on this phone - tap to view",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun AnalyticsRowItem(
    row: AnalyticsRow,
    showNotOnDeviceSubtitle: Boolean,
    onClick: () -> Unit,
    onAnalyse: () -> Unit,
) {
    val ready = row.state == AnalyticsRowState.READY
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (ready) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp), contentAlignment = Alignment.Center) {
            if (ready) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.state == AnalyticsRowState.NOT_ON_DEVICE && showNotOnDeviceSubtitle) {
                Text(
                    "Not on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShuttlTheme.extended.textTertiary,
                )
            }
            if (row.state == AnalyticsRowState.ANALYSABLE) {
                when (val a = row.affordance) {
                    is AnalyseAffordance.InProgress -> Text(
                        a.phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    is AnalyseAffordance.Failed -> Text(
                        a.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnalyseAffordance.Ready -> Unit
                }
            }
        }
        if (row.state == AnalyticsRowState.ANALYSABLE) {
            Spacer(Modifier.width(8.dp))
            when (row.affordance) {
                is AnalyseAffordance.InProgress ->
                    // The spinner goes inside the pill, using ShuttlButton's own
                    // `loading`, rather than beside it. ScoreMatchRow boxes its
                    // indicator to 48dp for a reason this row does not have
                    // (ClipListScreen.kt:556-557: to match the IconButton it
                    // swaps places with), and a bare indicator like
                    // LocalVideoSection.kt:164 would collapse the slot to 16dp.
                    // Either would swap a pill for something much narrower every
                    // time a run starts. One control in all three states leaves
                    // the slot a pill throughout; what still varies is the label
                    // (as "Mark court" and "Retry" already do on a ScoreMatchRow)
                    // plus the 14dp ring and its 8dp spacer while a run is in
                    // flight. Exact parity would need a reserved width, and a
                    // hardcoded one cannot be checked without a device and would
                    // clip a one-line, no-wrap label at large font scales.
                    ShuttlButton(
                        text = "Analyse",
                        onClick = onAnalyse,
                        variant = ShuttlButtonVariant.Primary,
                        enabled = false,
                        loading = true,
                        compact = true,
                    )
                is AnalyseAffordance.Failed -> ShuttlButton(
                    text = "Retry",
                    onClick = onAnalyse,
                    variant = ShuttlButtonVariant.Primary,
                    compact = true,
                )
                AnalyseAffordance.Ready -> ShuttlButton(
                    text = "Analyse",
                    onClick = onAnalyse,
                    variant = ShuttlButtonVariant.Primary,
                    compact = true,
                )
            }
        }
    }
}
