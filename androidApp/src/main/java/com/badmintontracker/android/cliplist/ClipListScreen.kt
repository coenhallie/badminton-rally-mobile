package com.badmintontracker.android.cliplist

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.android.localvideo.AnalyzeResultDialog
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.android.localvideo.localVideoSection
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    shares: SharesRepository,
    themePrefs: ThemePreferenceRepository,
    onMatchClick: (MatchSummary) -> Unit,
    localRows: List<LocalVideoRow> = emptyList(),
    intakeError: String? = null,
    onIntakeErrorShown: () -> Unit = {},
    onLocalClick: (LocalVideoEntry) -> Unit = {},
    onLocalAnalyze: (LocalVideoRow) -> Unit = {},
    onLocalRemove: (LocalVideoEntry) -> Unit = {},
    onLocalResultSeen: (LocalVideoEntry) -> Unit = {},
    onRecord: () -> Unit = {},
    onImport: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetVideoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.dismissError()
    }

    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    // Auto-show the result modal once per failure. `resultSeen` is persisted on the
    // entry, so dismissing it survives navigation and relaunch; a later retry that
    // fails again resets the flag (in AnalyzeCoordinator.fail) and shows it anew.
    var resultDialog by remember { mutableStateOf<LocalVideoRow?>(null) }
    LaunchedEffect(localRows) {
        if (resultDialog == null) {
            resultDialog = localRows.firstOrNull {
                it.entry.stage == AnalyzeStage.FAILED && !it.entry.resultSeen
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MATCHES",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                    )
                },
                actions = {
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add video")
                    }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Record video") },
                            onClick = { addMenuOpen = false; onRecord() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import video") },
                            onClick = { addMenuOpen = false; onImport() },
                        )
                    }
                    ThemeToggleButton(
                        mode = themeMode,
                        onToggle = themePrefs::toggle,
                    )
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menuOpen = false; vm.signOut() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.ownedMatches.isEmpty() && state.sharedMatches.isEmpty() &&
                localRows.isEmpty() && !state.isRefreshing
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matches yet. Record one with the + button above.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    localVideoSection(
                        rows = localRows,
                        header = { SectionHeader(it) },
                        onRowClick = onLocalClick,
                        onAnalyzeClick = onLocalAnalyze,
                        onRemove = onLocalRemove,
                    )
                    if (state.ownedMatches.isNotEmpty()) {
                        item(key = "header-owned") { SectionHeader("My matches") }
                        items(state.ownedMatches, key = { "owned-${it.videoId}" }) { match ->
                            MatchRow(
                                match = match,
                                media = media,
                                onClick = { onMatchClick(match) },
                                onShareClick = { sheetVideoId = match.videoId },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (state.sharedMatches.isNotEmpty()) {
                        item(key = "header-shared") { SectionHeader("Shared with me") }
                        items(state.sharedMatches, key = { "shared-${it.videoId}" }) { match ->
                            MatchRow(
                                match = match,
                                media = media,
                                onClick = { onMatchClick(match) },
                                onShareClick = null,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    sheetVideoId?.let { vid ->
        ShareSheet(
            videoId = vid,
            sharesRepository = shares,
            onDismiss = { sheetVideoId = null },
        )
    }

    resultDialog?.let { row ->
        AnalyzeResultDialog(
            entry = row.entry,
            onRetry = { resultDialog = null; onLocalResultSeen(row.entry); onLocalAnalyze(row) },
            onDismiss = { resultDialog = null; onLocalResultSeen(row.entry) },
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
private fun MatchRow(
    match: MatchSummary,
    media: MediaRepository,
    onClick: () -> Unit,
    onShareClick: (() -> Unit)?,
) {
    val thumbUrl by produceState<String?>(initialValue = null, match.videoId) {
        value = runCatching { media.signedThumbnailUrl(match.coverClip) }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbUrl,
            contentDescription = null,
            modifier = Modifier.size(96.dp, 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Match · ${formatDate(match.latestCreatedAt)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${match.rallyCount} ${if (match.rallyCount == 1) "rally" else "rallies"}".uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (match.sharerEmail != null) {
                Text(
                    text = "Shared by ${match.sharerEmail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShareClick != null) {
            IconButton(onClick = onShareClick) {
                Icon(Icons.Default.Share, contentDescription = "Share match")
            }
        }
    }
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun formatDate(instant: Instant): String {
    val ldt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${MONTHS[ldt.monthNumber - 1]} ${ldt.dayOfMonth}, ${ldt.year}"
}

@Composable
internal fun ClipRow(
    clip: RallyClip,
    media: MediaRepository,
    onClick: () -> Unit,
) {
    val thumbUrl by produceState<String?>(initialValue = null, clip.id) {
        value = runCatching { media.signedThumbnailUrl(clip) }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbUrl,
            contentDescription = null,
            modifier = Modifier.size(96.dp, 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.title ?: "Rally #${clip.rallyIndex}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${clip.durationSeconds}s · ${clip.annotationCount} notes".uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
