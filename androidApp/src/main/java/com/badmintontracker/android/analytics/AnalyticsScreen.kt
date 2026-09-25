package com.badmintontracker.android.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.cliplist.MatchStatusLine
import com.badmintontracker.android.cliplist.hasStatusLine
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.analytics.opensAnalytics
import com.badmintontracker.shared.matchlist.MatchListItem

/**
 * Which of the drawer's own two sections a row belongs to, in display order.
 *
 * Two, not the drawer's earlier three: the shared builder already folds a
 * match's local video and its cloud clips into one [MatchListItem] regardless
 * of where the file is, so "on this phone" versus "my matches" was never a
 * distinction between two different *matches* here, only between two ways the
 * same row's [MatchStatusLine] draws its where-glyphs. [label] is the section
 * header text, or null for [MINE], which draws none - the drawer does not
 * label its own matches either, only the shared ones.
 */
enum class AnalyticsGroup(val label: String?) {
    MINE(null),
    SHARED("Shared with me"),
}

/**
 * What an `ANALYSABLE` row's control should show, in place of a live "Analyze"
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
     * A run is under way. [phase] is the shared label for that stage, so this
     * row and the drawer's row for the same video say the same words:
     * `deviceWorkLabel` for the device run, `cloudAnalysisStatus` for the cloud
     * one.
     */
    data class InProgress(val phase: String) : AnalyseAffordance

    /**
     * The last attempt for this row ended in a failure, from either pipeline.
     * [reason] is that pipeline's own message.
     */
    data class Failed(val reason: String) : AnalyseAffordance
}

/**
 * One row of the Analytics list, one per [MatchListItem] in the shared match
 * list. [entryId] is null only when [state] is NOT_ON_DEVICE with no video to
 * point at (a score-only match, or a shared match) - inert either way.
 *
 * [title] and [subtitle] are [item]'s own `title`/`detail`, carried as their
 * own fields rather than read off [item] at each use because that is what the
 * pinned tests already assert on; [item] itself is kept alongside them for
 * everything else a row draws that the older, screen-specific fields never
 * had to (the status line, the players line).
 */
data class AnalyticsRow(
    val key: String,
    val entryId: String?,
    val group: AnalyticsGroup,
    val title: String,
    val subtitle: String,
    val state: AnalyticsRowState,
    val affordance: AnalyseAffordance = AnalyseAffordance.Ready,
    val item: MatchListItem,
)

/**
 * The coach's Analytics list: the same rows the drawer lists, in the same
 * order, one row per match whether its video is on this phone, in the cloud or
 * both - "Shared with me" is the only section either screen still labels.
 *
 * [onOpenDetail] fires for a READY row, opening its analysis, which shows the
 * heatmap; [onAnalyse] fires for an ANALYSABLE row's button whether it reads
 * "Analyze" or "Retry", and the caller decides which of the two it is: a failed
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
    val legend = analyticsLegend(rows)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
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
                // No action here: matches are added on Home, and this screen's
                // only way there is its back arrow, which the body names.
                ShuttlEmptyState(
                    icon = ShuttlIcons.ChartColumn,
                    title = "No matches yet",
                    body = "Add a match on Home and it will be listed here, ready to analyze.",
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = GUTTER),
        ) {
            // The mock's headline over the list, then the one line that says
            // what the rows offer.
            item(key = "headline") {
                Text(
                    "Pick a match",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 16.dp),
                )
            }
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
                            // about to draw a chart. Both targets named on
                            // purpose: court marking offers cloud AND on device
                            // (CourtMarkingScreen), and it is the on-device run
                            // that writes the track a row needs to turn READY, so
                            // copy naming only the cloud would steer a coach away
                            // from the dot this same legend explains.
                            "Analyze cuts a video into rallies, on this phone or in the cloud."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp),
                    )
                }
            }
            AnalyticsGroup.entries.forEach { group ->
                val groupRows = rows.filter { it.group == group }
                if (groupRows.isNotEmpty()) {
                    group.label?.let { label -> item(key = "header-${group.name}") { SectionHeader(label) } }
                    items(groupRows, key = { it.key }) { row ->
                        AnalyticsRowItem(
                            row = row,
                            showNotOnDeviceSubtitle = legend.showsNotOnDeviceSubtitle,
                            onClick = { onOpenDetail(row) },
                            onAnalyse = { onAnalyse(row) },
                        )
                    }
                }
            }
        }
    }
}

/** The page gutter the mock lays every card in. */
private val GUTTER = 24.dp

@Composable
private fun DotLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Analyzed on this phone - tap to view",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
        )
    }
}

/**
 * A section's label: 12sp, secondary, sentence case - the same label the
 * drawer draws for its own shared section
 * ([com.badmintontracker.android.cliplist.DrawerSectionLabel]), and today the
 * only label either screen draws: "Shared with me".
 *
 * It used to be the uppercase tracked `labelSmall` this app's older lists draw.
 * The mock has it as plain 12px secondary text and carries no uppercase
 * anywhere, so that was a Material holdover from before the redesign - and it
 * had followed this layout across to iPhone as well.
 *
 * Not DrawerSectionLabel itself: that bakes in the drawer's own 4dp label gap,
 * and this screen states its spacing here. The two want one shared component;
 * see the note in DrawerList.kt.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 18.dp, bottom = 10.dp),
    )
}

/**
 * One match, on the mock's card: title over subtitle, and on the right the
 * availability dot for a READY row, the Analyze pill for an ANALYSABLE one,
 * nothing for a match that is not on this phone.
 */
@Composable
private fun AnalyticsRowItem(
    row: AnalyticsRow,
    showNotOnDeviceSubtitle: Boolean,
    onClick: () -> Unit,
    onAnalyse: () -> Unit,
) {
    val ready = opensAnalytics(row.state)
    val shape = RoundedCornerShape(ShuttlRadius.large)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GUTTER, end = GUTTER, bottom = 8.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .let { if (ready) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Absent until somebody is named, the same rule the drawer's own
            // row follows (MatchListRow.kt) - "Home vs Away" on every row would
            // say nothing.
            row.item.playersLine?.let { players ->
                Text(
                    players,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShuttlTheme.extended.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.item.hasStatusLine) {
                MatchStatusLine(row.item, Modifier.padding(top = 6.dp))
            }
            if (row.state == AnalyticsRowState.NOT_ON_DEVICE && showNotOnDeviceSubtitle) {
                Text(
                    "Not on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShuttlTheme.extended.textTertiary,
                )
            }
            // Ready says nothing here, and InProgress does not repeat itself
            // either: the status line above already reports the same run, from
            // the very fields (item.action/statusText) this row's own
            // affordance draws its wording from. Failed is the one case the
            // status line cannot speak for - MatchStatusLine never shows an
            // error string, on purpose - so this stays the row's only account
            // of *why* it failed.
            if (row.state == AnalyticsRowState.ANALYSABLE) {
                val failure = row.affordance as? AnalyseAffordance.Failed
                failure?.let {
                    Text(
                        it.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (ready) {
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (row.state == AnalyticsRowState.ANALYSABLE) {
            Spacer(Modifier.width(16.dp))
            when (row.affordance) {
                is AnalyseAffordance.InProgress ->
                    // The spinner goes inside the pill, using ShuttlButton's own
                    // `loading`, rather than beside it: a bare indicator would
                    // collapse the slot to 16dp and swap a pill for something
                    // much narrower every time a run starts. One control in all
                    // three states leaves the slot a pill throughout; what still
                    // varies is the label plus the 14dp ring and its 8dp spacer
                    // while a run is in flight. Exact parity would need a
                    // reserved width, and a hardcoded one cannot be checked
                    // without a device and would clip a one-line, no-wrap label
                    // at large font scales.
                    ShuttlButton(
                        text = "Analyze",
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
                    text = "Analyze",
                    onClick = onAnalyse,
                    variant = ShuttlButtonVariant.Primary,
                    compact = true,
                )
            }
        }
    }
}
