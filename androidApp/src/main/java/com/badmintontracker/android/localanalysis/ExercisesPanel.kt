package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.analytics.CourtSideNames
import com.badmintontracker.shared.analytics.profileArm
import com.badmintontracker.shared.exercises.ExerciseReport
import com.badmintontracker.shared.exercises.ExerciseReportStatus
import com.badmintontracker.shared.exercises.ExerciseSuggestion
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.MovementSummary
import com.badmintontracker.shared.exercises.adviseExercisesForTrack
import com.badmintontracker.shared.local.ABSENT_METRIC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suggested drills and exercises derived from a completed video analysis.
 *
 * Follows [BasePositionPanel]'s threading, side-toggle placement, and track
 * resolution so all Analysis panels read the exact same run and rally windows.
 */
@Composable
fun ExercisesPanel(
    entryId: String,
    runner: LocalAnalysisRunner,
    windows: List<RallyWindow>,
    sideNames: CourtSideNames?,
    modifier: Modifier = Modifier,
) {
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val measured = produceState<MeasuredExercises?>(null, entryId, done, windows, sideNames) {
        value = withContext(Dispatchers.Default) {
            val source = heatmapSource(done, runner.storedTrack(entryId))
            val reports = source?.tracks?.associate { sideTrack ->
                sideTrack.side to adviseExercisesForTrack(
                    track = sideTrack.track,
                    fps = source.fps,
                    windows = windows,
                    side = sideTrack.side,
                    racketArm = profileArm(sideTrack.side, sideNames),
                )
            } ?: emptyMap()
            MeasuredExercises(source = source, reportsBySide = reports)
        }
    }.value

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (measured == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            return@Column
        }

        val (source, reportsBySide) = measured
        var side by rememberSaveable { mutableStateOf(CourtSide.NEAR) }
        val shown = source?.tracks?.firstOrNull { it.side == side } ?: source?.tracks?.firstOrNull()
        val report = shown?.let { reportsBySide[it.side] }

        if (source != null && source.tracks.size > 1 && shown != null) {
            SideToggle(
                sides = source.tracks.map { it.side },
                selected = shown.side,
                onSelect = { side = it },
                names = sideNames,
                modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 8.dp),
            )
        }

        val message = exercisesMessage(
            track = shown?.track,
            windows = windows,
            report = report,
            measured = true,
        )
        if (message != null) {
            PanelMessage(message)
            return@Column
        }
        requireNotNull(report)

        report.summary?.let { summary ->
            MovementSummaryBlock(summary)
        }

        when (report.status) {
            ExerciseReportStatus.INSUFFICIENT_DATA,
            ExerciseReportStatus.BALANCED,
            -> {
                PanelMessage(report.message ?: ExercisesCopy.NO_COUNTED_RALLIES)
            }
            ExerciseReportStatus.SUGGESTIONS -> {
                val shownSide = shown.side
                var expandedIds by remember(shownSide, report) {
                    mutableStateOf(setOfNotNull(report.suggestions.firstOrNull()?.exercise?.id))
                }
                Text(
                    ExercisesCopy.SUGGESTIONS_HEADING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = COURT_GUTTER,
                        end = COURT_GUTTER,
                        top = 20.dp,
                        bottom = 10.dp,
                    ),
                )
                Column(
                    modifier = Modifier.padding(horizontal = COURT_GUTTER),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    report.suggestions.forEach { suggestion ->
                        val id = suggestion.exercise.id
                        val expanded = id in expandedIds
                        ExerciseCard(
                            suggestion = suggestion,
                            expanded = expanded,
                            onToggle = {
                                expandedIds = if (expanded) expandedIds - id else expandedIds + id
                            },
                        )
                    }
                }
            }
        }

        if (report.status != ExerciseReportStatus.INSUFFICIENT_DATA) {
            Text(
                ExercisesCopy.HONESTY_CAPTION,
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.padding(
                    start = COURT_GUTTER,
                    end = COURT_GUTTER,
                    top = 16.dp,
                ),
            )
        }
        Spacer(Modifier.height(COURT_GUTTER))
    }
}

/**
 * Why the Drills panel cannot render a report yet, or null when [report] is
 * ready to display (including balanced and insufficient-data reports, which
 * still show the movement summary above their own status message).
 */
internal fun exercisesMessage(
    track: PlayerTrack?,
    windows: List<RallyWindow>,
    report: ExerciseReport?,
    measured: Boolean,
): String? = when {
    track == null -> ExercisesCopy.NOT_LOADED
    track.samples.isEmpty() -> whyEmpty(track)
    windows.none { it.isBounded } -> ExercisesCopy.NO_BOUNDED_WINDOWS
    !measured -> null
    report == null -> ExercisesCopy.NO_COUNTED_RALLIES
    else -> null
}

@Composable
private fun MovementSummaryBlock(summary: MovementSummary) {
    Text(
        ExercisesCopy.SUMMARY_HEADING,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = COURT_GUTTER,
            end = COURT_GUTTER,
            top = 8.dp,
            bottom = 10.dp,
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = COURT_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShuttlStatTile(
            value = summary.countedRallies.toString(),
            unit = ExercisesCopy.ralliesUnit(summary.countedRallies),
            label = ExercisesCopy.ralliesCaption(summary.coveragePercent),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            labelMaxLines = 2,
        )
        ShuttlStatTile(
            value = summary.peakSpeedText ?: ABSENT_METRIC,
            unit = if (summary.peakSpeedText != null) " m/s" else "",
            label = ExercisesCopy.PEAK_SPEED_LABEL,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            labelMaxLines = 2,
        )
    }
    Text(
        ExercisesCopy.zoneBreakdown(summary.frontPercent, summary.midPercent, summary.rearPercent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = COURT_GUTTER, end = COURT_GUTTER, top = 10.dp),
    )
}

@Composable
private fun ExerciseCard(
    suggestion: ExerciseSuggestion,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val exercise = suggestion.exercise
    val shape = RoundedCornerShape(ShuttlRadius.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    exercise.category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = ShuttlTheme.extended.accentDark,
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        exercise.prescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expanded) ShuttlIcons.ChevronUp else ShuttlIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                exercise.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                suggestion.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${ExercisesCopy.SETUP_LABEL}: ${exercise.setup}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    exercise.steps.forEachIndexed { index, step ->
                        Text(
                            "${index + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "${ExercisesCopy.CUE_LABEL}: ${exercise.coachingCue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

private data class MeasuredExercises(
    val source: HeatmapSource?,
    val reportsBySide: Map<CourtSide, ExerciseReport>,
)
