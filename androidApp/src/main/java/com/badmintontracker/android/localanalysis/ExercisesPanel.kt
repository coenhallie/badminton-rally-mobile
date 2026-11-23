package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.analytics.CourtSideNames
import com.badmintontracker.shared.analytics.profileArm
import com.badmintontracker.shared.exercises.DrillLibraryFilter
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.exercises.DrillSessionLog
import com.badmintontracker.shared.exercises.Exercise
import com.badmintontracker.shared.exercises.ExerciseCatalog
import com.badmintontracker.shared.exercises.ExerciseCategory
import com.badmintontracker.shared.exercises.ExerciseReport
import com.badmintontracker.shared.exercises.ExerciseReportStatus
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.MovementSummary
import com.badmintontracker.shared.exercises.adviseExercisesForTrack
import com.badmintontracker.shared.exercises.exerciseProgressBadge
import com.badmintontracker.shared.exercises.filterDrillLogs
import com.badmintontracker.shared.exercises.formatClockSeconds
import com.badmintontracker.shared.exercises.progressForExercise
import com.badmintontracker.shared.exercises.relativeDaysLabel
import com.badmintontracker.shared.exercises.trainingOverview
import com.badmintontracker.shared.exercises.trainingOverviewHeadline
import com.badmintontracker.shared.local.ABSENT_METRIC
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suggested drills and exercises derived from a completed video analysis,
 * followed by a collapsible, filterable library of all curated drills and
 * guided session tracking.
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
    drillProgress: DrillProgressRepository,
    modifier: Modifier = Modifier,
) {
    val allLogs by drillProgress.logs.collectAsStateWithLifecycle()
    val nowMs = System.currentTimeMillis()
    var activeDrill by remember { mutableStateOf<Exercise?>(null) }
    var activeArmForSession by remember { mutableStateOf<RacketArm?>(null) }

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
        val shownSide = shown.side
        val shownArm = profileArm(shownSide, sideNames)

        report.summary?.let { summary ->
            MovementSummaryBlock(summary)
        }

        if (allLogs.isNotEmpty()) {
            TrainingProgressStrip(
                logs = allLogs,
                nowMs = nowMs,
                onDeleteLog = drillProgress::deleteSession,
                topPadding = 18.dp,
            )
        }

        when (report.status) {
            ExerciseReportStatus.INSUFFICIENT_DATA,
            ExerciseReportStatus.BALANCED,
            -> {
                PanelMessage(report.message ?: ExercisesCopy.NO_COUNTED_RALLIES)
            }
            ExerciseReportStatus.SUGGESTIONS -> {
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
                        val badge = exerciseProgressBadge(
                            progressForExercise(allLogs, id, nowMs),
                            nowMs,
                        )
                        ExerciseCard(
                            exercise = suggestion.exercise,
                            kicker = suggestion.exercise.category.label.uppercase(),
                            subtitle = suggestion.evidence,
                            progressBadge = badge,
                            racketArm = shownArm,
                            expanded = expanded,
                            onToggle = {
                                expandedIds = if (expanded) expandedIds - id else expandedIds + id
                            },
                            onStartDrill = {
                                activeArmForSession = shownArm
                                activeDrill = suggestion.exercise
                            },
                        )
                    }
                }
            }
        }

        DrillLibrarySection(
            racketArm = shownArm,
            logs = allLogs,
            nowMs = nowMs,
            initiallyOpen = false,
            onStartDrill = { ex ->
                activeArmForSession = shownArm
                activeDrill = ex
            },
        )

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

    activeDrill?.let { ex ->
        DrillSessionDialog(
            exercise = ex,
            racketArm = activeArmForSession,
            playerId = null,
            matchId = entryId,
            drillProgress = drillProgress,
            onDismiss = { activeDrill = null },
        )
    }
}

/**
 * Standalone Drills tab shown directly on a player's profile page so a player
 * or coach can launch any drill, mirror animations to that player's hand, and
 * review their per-player training log anytime without opening a match first.
 */
@Composable
fun PlayerDrillsTab(
    playerId: String,
    racketArm: RacketArm?,
    drillProgress: DrillProgressRepository,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val allLogs by drillProgress.logs.collectAsStateWithLifecycle()
    val playerLogs = remember(allLogs, playerId) { filterDrillLogs(allLogs, playerId) }
    val nowMs = System.currentTimeMillis()
    var activeDrill by remember { mutableStateOf<Exercise?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (playerLogs.isNotEmpty()) {
            TrainingProgressStrip(
                logs = playerLogs,
                nowMs = nowMs,
                onDeleteLog = drillProgress::deleteSession,
                topPadding = 16.dp,
            )
        } else {
            Text(
                text = ExercisesCopy.NO_SESSIONS_YET,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = COURT_GUTTER,
                    end = COURT_GUTTER,
                    top = 16.dp,
                ),
            )
        }

        DrillLibrarySection(
            racketArm = racketArm,
            logs = playerLogs,
            nowMs = nowMs,
            initiallyOpen = true,
            onStartDrill = { activeDrill = it },
        )

        Spacer(Modifier.height(bottomPadding))
    }

    activeDrill?.let { ex ->
        DrillSessionDialog(
            exercise = ex,
            racketArm = racketArm,
            playerId = playerId,
            matchId = null,
            drillProgress = drillProgress,
            onDismiss = { activeDrill = null },
        )
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
private fun TrainingProgressStrip(
    logs: List<DrillSessionLog>,
    nowMs: Long,
    onDeleteLog: (String) -> Unit,
    topPadding: Dp,
) {
    val overview = remember(logs, nowMs) { trainingOverview(logs, nowMs) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }

    Text(
        ExercisesCopy.TRAINING_LOG_HEADING,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = COURT_GUTTER,
            end = COURT_GUTTER,
            top = topPadding,
            bottom = 10.dp,
        ),
    )

    val shape = RoundedCornerShape(ShuttlRadius.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = COURT_GUTTER)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { historyOpen = !historyOpen }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trainingOverviewHeadline(overview),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${overview.uniqueDrillsTried} ${if (overview.uniqueDrillsTried == 1) "drill" else "drills"} practiced · tap for log",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (historyOpen) ShuttlIcons.ChevronUp else ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }

        if (historyOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                logs.take(8).forEach { log ->
                    val title = remember(log.exerciseId) {
                        runCatching { ExerciseCatalog.byId(log.exerciseId).title }.getOrDefault(log.exerciseId)
                    }
                    val ago = relativeDaysLabel(log.completedAtEpochMs, nowMs)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = buildString {
                                append("$ago · ${log.setsCompleted}/${log.setsTarget} sets · ${formatClockSeconds(log.durationSeconds)} · ${log.effort.label}")
                                if (log.note.isNotBlank()) append(" · ${log.note}")
                            }
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteLog(log.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete session",
                                tint = ShuttlTheme.extended.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
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
private fun DrillLibrarySection(
    racketArm: RacketArm?,
    logs: List<DrillSessionLog>,
    nowMs: Long,
    initiallyOpen: Boolean,
    onStartDrill: (Exercise) -> Unit,
) {
    var libraryOpen by rememberSaveable(initiallyOpen) { mutableStateOf(initiallyOpen) }
    var selectedFilter by rememberSaveable { mutableStateOf(DrillLibraryFilter.ALL) }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    val onCourtCount = remember { ExerciseCatalog.all.count { it.category == ExerciseCategory.ON_COURT } }
    val offCourtCount = remember { ExerciseCatalog.all.count { it.category == ExerciseCategory.OFF_COURT } }
    val visibleExercises = remember(selectedFilter) { ExerciseCatalog.filtered(selectedFilter) }

    Text(
        ExercisesCopy.LIBRARY_HEADING,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = COURT_GUTTER,
            end = COURT_GUTTER,
            top = 20.dp,
            bottom = 10.dp,
        ),
    )

    if (!initiallyOpen) {
        val toggleShape = RoundedCornerShape(ShuttlRadius.large)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = COURT_GUTTER)
                .clip(toggleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, toggleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, toggleShape)
                .semantics { stateDescription = if (libraryOpen) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = { libraryOpen = !libraryOpen })
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ExercisesCopy.browseLibraryLabel(ExerciseCatalog.all.size, libraryOpen),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    ExercisesCopy.librarySubtitle(onCourtCount, offCourtCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (libraryOpen) ShuttlIcons.ChevronUp else ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (libraryOpen) {
        if (!initiallyOpen) Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = COURT_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExerciseCatalog.allFilters.forEach { filter ->
                val isSelected = filter == selectedFilter
                val count = remember(filter) { ExerciseCatalog.filtered(filter).size }
                val pillShape = RoundedCornerShape(ShuttlRadius.pill)
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            pillShape,
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = pillShape,
                        )
                        .semantics { selected = isSelected }
                        .clickable(role = Role.Tab, onClick = { selectedFilter = filter })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "${filter.label} ($count)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) ShuttlTheme.extended.onAccent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.padding(horizontal = COURT_GUTTER),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            visibleExercises.forEach { exercise ->
                val expanded = exercise.id in expandedIds
                val badge = exerciseProgressBadge(
                    progressForExercise(logs, exercise.id, nowMs),
                    nowMs,
                )
                ExerciseCard(
                    exercise = exercise,
                    kicker = "${exercise.category.label} · ${exercise.focus.label}".uppercase(),
                    subtitle = null,
                    progressBadge = badge,
                    racketArm = racketArm,
                    expanded = expanded,
                    onToggle = {
                        expandedIds = if (expanded) expandedIds - exercise.id else expandedIds + exercise.id
                    },
                    onStartDrill = { onStartDrill(exercise) },
                )
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: Exercise,
    kicker: String,
    subtitle: String?,
    progressBadge: String?,
    racketArm: RacketArm?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStartDrill: () -> Unit,
) {
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
                    kicker,
                    style = MaterialTheme.typography.labelSmall,
                    color = ShuttlTheme.extended.accentDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        exercise.prescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    exercise.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (progressBadge != null) {
                    val badgeShape = RoundedCornerShape(ShuttlRadius.pill)
                    Box(
                        modifier = Modifier
                            .clip(badgeShape)
                            .background(ShuttlTheme.extended.analysisChip, badgeShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = progressBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = ShuttlTheme.extended.analysisGlyph,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExerciseVisualView(
                    exerciseId = exercise.id,
                    racketArm = racketArm,
                )
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
                Spacer(Modifier.height(2.dp))
                ShuttlButton(
                    text = ExercisesCopy.START_DRILL,
                    onClick = onStartDrill,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private data class MeasuredExercises(
    val source: HeatmapSource?,
    val reportsBySide: Map<CourtSide, ExerciseReport>,
)
