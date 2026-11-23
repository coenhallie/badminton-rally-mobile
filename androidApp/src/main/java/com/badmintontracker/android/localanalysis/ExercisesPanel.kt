package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.contentDescription
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
import com.badmintontracker.shared.analytics.profilePlayerId
import com.badmintontracker.shared.exercises.DrillLibraryFilter
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.exercises.DrillSessionLog
import com.badmintontracker.shared.exercises.Exercise
import com.badmintontracker.shared.exercises.ExerciseCatalog
import com.badmintontracker.shared.exercises.ExerciseReport
import com.badmintontracker.shared.exercises.ExerciseReportStatus
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.MovementSummary
import com.badmintontracker.shared.exercises.adviseExercisesForTrack
import com.badmintontracker.shared.exercises.badgeMapByExercise
import com.badmintontracker.shared.exercises.filterDrillLogs
import com.badmintontracker.shared.exercises.formatClockSeconds
import com.badmintontracker.shared.exercises.relativeDaysLabel
import com.badmintontracker.shared.exercises.trainingOverview
import com.badmintontracker.shared.exercises.trainingOverviewHeadline
import com.badmintontracker.shared.local.ABSENT_METRIC
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suggested drills and exercises derived from a completed video analysis,
 * followed by a collapsible, searchable, lazy-rendered library of all curated
 * drills and player-linked guided session tracking.
 */
@Composable
fun ExercisesPanel(
    entryId: String,
    runner: LocalAnalysisRunner,
    windows: List<RallyWindow>,
    sideNames: CourtSideNames?,
    drillProgress: DrillProgressRepository,
    modifier: Modifier = Modifier,
    /** When true, reuse [preloadedTrack] parsed by AnalysisSection instead of re-reading disk. */
    usePreloadedTrack: Boolean = false,
    preloadedTrackLoaded: Boolean = false,
    preloadedTrack: PlayerTrackStore.Stored? = null,
) {
    val allLogs by drillProgress.logs.collectAsStateWithLifecycle()
    val nowMs = remember(allLogs) { System.currentTimeMillis() }
    var activeLaunch by remember { mutableStateOf<DrillLaunch?>(null) }

    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val measured = produceState<MeasuredExercises?>(
        initialValue = null,
        entryId,
        done,
        windows,
        sideNames,
        usePreloadedTrack,
        preloadedTrackLoaded,
        preloadedTrack,
    ) {
        if (usePreloadedTrack && !preloadedTrackLoaded) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val stored = if (usePreloadedTrack) preloadedTrack else runner.storedTrack(entryId)
            val source = heatmapSource(done, stored)
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

    if (measured == null) {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
        return
    }

    val (source, reportsBySide) = measured
    var side by rememberSaveable { mutableStateOf(CourtSide.NEAR) }
    val shown = source?.tracks?.firstOrNull { it.side == side } ?: source?.tracks?.firstOrNull()
    val report = shown?.let { reportsBySide[it.side] }

    val message = exercisesMessage(
        track = shown?.track,
        windows = windows,
        report = report,
        measured = true,
    )
    if (message != null || report == null || shown == null || source == null) {
        Column(modifier = modifier.fillMaxSize()) {
            if (source != null && source.tracks.size > 1 && shown != null) {
                SideToggle(
                    sides = source.tracks.map { it.side },
                    selected = shown.side,
                    onSelect = { side = it },
                    names = sideNames,
                    modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 8.dp),
                )
            }
            PanelMessage(message ?: ExercisesCopy.NO_COUNTED_RALLIES)
        }
        return
    }

    val shownSide = shown.side
    val shownArm = profileArm(shownSide, sideNames)
    val shownPlayerId = profilePlayerId(shownSide, sideNames)
    val scopedLogs = remember(allLogs, shownPlayerId) { filterDrillLogs(allLogs, shownPlayerId) }
    val badges = remember(scopedLogs, nowMs) { badgeMapByExercise(scopedLogs, nowMs) }

    var suggestionExpandedIds by remember(shownSide, report) {
        mutableStateOf(setOfNotNull(report.suggestions.firstOrNull()?.exercise?.id))
    }
    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf(DrillLibraryFilter.ALL) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var libraryExpandedIds by remember { mutableStateOf(emptySet<String>()) }

    val recentExercises = remember(scopedLogs) { ExerciseCatalog.recentExercises(scopedLogs) }
    val effectiveRecentOnly = recentOnly && recentExercises.isNotEmpty()
    val visibleExercises = remember(selectedFilter, effectiveRecentOnly, recentExercises, searchQuery) {
        val base = if (effectiveRecentOnly) recentExercises else ExerciseCatalog.filtered(selectedFilter)
        ExerciseCatalog.search(base, searchQuery)
    }

    val launchDrill: (Exercise) -> Unit = { ex ->
        activeLaunch = DrillLaunch(
            exercise = ex,
            racketArm = shownArm,
            playerId = shownPlayerId,
            matchId = entryId,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = COURT_GUTTER),
    ) {
        if (source.tracks.size > 1) {
            item(key = "side-toggle") {
                SideToggle(
                    sides = source.tracks.map { it.side },
                    selected = shownSide,
                    onSelect = { side = it },
                    names = sideNames,
                    modifier = Modifier.padding(horizontal = COURT_GUTTER, vertical = 8.dp),
                )
            }
        }

        report.summary?.let { summary ->
            item(key = "movement-summary") {
                MovementSummaryBlock(summary)
            }
        }

        if (scopedLogs.isNotEmpty()) {
            item(key = "training-progress") {
                TrainingProgressStrip(
                    logs = scopedLogs,
                    nowMs = nowMs,
                    onDeleteLog = drillProgress::deleteSession,
                    onRepeatDrill = launchDrill,
                    topPadding = 18.dp,
                )
            }
        }

        when (report.status) {
            ExerciseReportStatus.INSUFFICIENT_DATA,
            ExerciseReportStatus.BALANCED,
            -> {
                item(key = "status-message") {
                    PanelMessage(report.message ?: ExercisesCopy.NO_COUNTED_RALLIES)
                }
            }
            ExerciseReportStatus.SUGGESTIONS -> {
                item(key = "suggestions-heading") {
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
                }
                items(
                    items = report.suggestions,
                    key = { "suggestion-${it.exercise.id}" },
                ) { suggestion ->
                    val id = suggestion.exercise.id
                    val expanded = id in suggestionExpandedIds
                    Box(
                        modifier = Modifier.padding(
                            start = COURT_GUTTER,
                            end = COURT_GUTTER,
                            bottom = 12.dp,
                        ),
                    ) {
                        ExerciseCard(
                            exercise = suggestion.exercise,
                            kicker = suggestion.exercise.category.label.uppercase(),
                            subtitle = suggestion.evidence,
                            progressBadge = badges[id],
                            racketArm = shownArm,
                            expanded = expanded,
                            onToggle = {
                                suggestionExpandedIds =
                                    if (expanded) suggestionExpandedIds - id else suggestionExpandedIds + id
                            },
                            onStartDrill = { launchDrill(suggestion.exercise) },
                        )
                    }
                }
            }
        }

        drillLibraryItems(
            racketArm = shownArm,
            badges = badges,
            initiallyOpen = false,
            libraryOpen = libraryOpen,
            onToggleLibrary = { libraryOpen = !libraryOpen },
            recentCount = recentExercises.size,
            recentOnly = effectiveRecentOnly,
            selectedFilter = selectedFilter,
            onSelectRecent = { recentOnly = true },
            onSelectFilter = {
                recentOnly = false
                selectedFilter = it
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            visibleExercises = visibleExercises,
            expandedIds = libraryExpandedIds,
            onToggleCard = { id ->
                libraryExpandedIds = if (id in libraryExpandedIds) libraryExpandedIds - id else libraryExpandedIds + id
            },
            onStartDrill = launchDrill,
        )

        if (report.status != ExerciseReportStatus.INSUFFICIENT_DATA) {
            item(key = "honesty-caption") {
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
        }
    }

    activeLaunch?.let { launch ->
        DrillSessionDialog(
            exercise = launch.exercise,
            racketArm = launch.racketArm,
            playerId = launch.playerId,
            matchId = launch.matchId,
            drillProgress = drillProgress,
            onDismiss = { activeLaunch = null },
        )
    }
}

/**
 * Standalone Drills tab shown directly on a player's profile page so a player
 * or coach can search, launch, and track any drill mirrored to that player's
 * hand without opening a match first.
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
    val nowMs = remember(allLogs) { System.currentTimeMillis() }
    val badges = remember(playerLogs, nowMs) { badgeMapByExercise(playerLogs, nowMs) }
    var activeDrill by remember { mutableStateOf<Exercise?>(null) }

    var selectedFilter by rememberSaveable { mutableStateOf(DrillLibraryFilter.ALL) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    val recentExercises = remember(playerLogs) { ExerciseCatalog.recentExercises(playerLogs) }
    val effectiveRecentOnly = recentOnly && recentExercises.isNotEmpty()
    val visibleExercises = remember(selectedFilter, effectiveRecentOnly, recentExercises, searchQuery) {
        val base = if (effectiveRecentOnly) recentExercises else ExerciseCatalog.filtered(selectedFilter)
        ExerciseCatalog.search(base, searchQuery)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        if (playerLogs.isNotEmpty()) {
            item(key = "player-training-progress") {
                TrainingProgressStrip(
                    logs = playerLogs,
                    nowMs = nowMs,
                    onDeleteLog = drillProgress::deleteSession,
                    onRepeatDrill = { activeDrill = it },
                    topPadding = 16.dp,
                )
            }
        } else {
            item(key = "player-no-sessions") {
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
        }

        drillLibraryItems(
            racketArm = racketArm,
            badges = badges,
            initiallyOpen = true,
            libraryOpen = true,
            onToggleLibrary = {},
            recentCount = recentExercises.size,
            recentOnly = effectiveRecentOnly,
            selectedFilter = selectedFilter,
            onSelectRecent = { recentOnly = true },
            onSelectFilter = {
                recentOnly = false
                selectedFilter = it
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            visibleExercises = visibleExercises,
            expandedIds = expandedIds,
            onToggleCard = { id ->
                expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
            },
            onStartDrill = { activeDrill = it },
        )
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
    onRepeatDrill: (Exercise) -> Unit,
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
                    val exercise = ExerciseCatalog.byIdOrNull(log.exerciseId)
                    val title = exercise?.title ?: log.exerciseId
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
                        if (exercise != null) {
                            IconButton(
                                onClick = { onRepeatDrill(exercise) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = ShuttlIcons.Play,
                                    contentDescription = "${ExercisesCopy.REPEAT_DRILL_LABEL}: $title",
                                    tint = ShuttlTheme.extended.accentDark,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
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

private fun LazyListScope.drillLibraryItems(
    racketArm: RacketArm?,
    badges: Map<String, String>,
    initiallyOpen: Boolean,
    libraryOpen: Boolean,
    onToggleLibrary: () -> Unit,
    recentCount: Int,
    recentOnly: Boolean,
    selectedFilter: DrillLibraryFilter,
    onSelectRecent: () -> Unit,
    onSelectFilter: (DrillLibraryFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    visibleExercises: List<Exercise>,
    expandedIds: Set<String>,
    onToggleCard: (String) -> Unit,
    onStartDrill: (Exercise) -> Unit,
) {
    item(key = "library-heading") {
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
    }

    if (!initiallyOpen) {
        item(key = "library-toggle") {
            val toggleShape = RoundedCornerShape(ShuttlRadius.large)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = COURT_GUTTER)
                    .clip(toggleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, toggleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, toggleShape)
                    .semantics { stateDescription = if (libraryOpen) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClick = onToggleLibrary)
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
                        ExercisesCopy.librarySubtitle(
                            ExerciseCatalog.onCourtCount,
                            ExerciseCatalog.offCourtCount,
                        ),
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
    }

    if (libraryOpen) {
        item(key = "library-search-and-filters") {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!initiallyOpen) Spacer(Modifier.height(12.dp))
                DrillSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier.padding(horizontal = COURT_GUTTER),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = COURT_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (recentCount > 0) {
                        FilterChipPill(
                            label = "${ExercisesCopy.FILTER_RECENT} ($recentCount)",
                            isSelected = recentOnly,
                            onClick = onSelectRecent,
                        )
                    }
                    ExerciseCatalog.allFilters.forEach { filter ->
                        val isSelected = !recentOnly && filter == selectedFilter
                        FilterChipPill(
                            label = "${filter.label} (${ExerciseCatalog.filterCount(filter)})",
                            isSelected = isSelected,
                            onClick = { onSelectFilter(filter) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (visibleExercises.isEmpty()) {
            item(key = "library-empty-search") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = COURT_GUTTER, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = ExercisesCopy.NO_SEARCH_MATCHES,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = ExercisesCopy.CLEAR_SEARCH,
                            style = MaterialTheme.typography.labelMedium,
                            color = ShuttlTheme.extended.accentDark,
                            modifier = Modifier
                                .clip(RoundedCornerShape(ShuttlRadius.pill))
                                .clickable(role = Role.Button) { onSearchQueryChange("") }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        } else {
            items(
                items = visibleExercises,
                key = { "library-${it.id}" },
            ) { exercise ->
                val expanded = exercise.id in expandedIds
                Box(
                    modifier = Modifier.padding(
                        start = COURT_GUTTER,
                        end = COURT_GUTTER,
                        bottom = 10.dp,
                    ),
                ) {
                    ExerciseCard(
                        exercise = exercise,
                        kicker = "${exercise.category.label} · ${exercise.focus.label}".uppercase(),
                        subtitle = null,
                        progressBadge = badges[exercise.id],
                        racketArm = racketArm,
                        expanded = expanded,
                        onToggle = { onToggleCard(exercise.id) },
                        onStartDrill = { onStartDrill(exercise) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrillSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldShape = RoundedCornerShape(ShuttlRadius.medium)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = {
            Text(
                text = ExercisesCopy.SEARCH_PLACEHOLDER,
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = ExercisesCopy.CLEAR_SEARCH,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            null
        },
        shape = fieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterChipPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
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
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) ShuttlTheme.extended.onAccent
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                    Text(
                        exercise.prescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
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

            Spacer(Modifier.width(8.dp))

            // 1-tap quick launch button directly from the card header.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ShuttlTheme.extended.analysisChip, CircleShape)
                    .semantics {
                        contentDescription = "${ExercisesCopy.QUICK_START_LABEL} ${exercise.title}"
                    }
                    .clickable(role = Role.Button, onClick = onStartDrill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ShuttlIcons.Play,
                    contentDescription = null,
                    tint = ShuttlTheme.extended.accentDark,
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            Icon(
                imageVector = if (expanded) ShuttlIcons.ChevronUp else ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
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

private data class DrillLaunch(
    val exercise: Exercise,
    val racketArm: RacketArm?,
    val playerId: String?,
    val matchId: String?,
)

private data class MeasuredExercises(
    val source: HeatmapSource?,
    val reportsBySide: Map<CourtSide, ExerciseReport>,
)
