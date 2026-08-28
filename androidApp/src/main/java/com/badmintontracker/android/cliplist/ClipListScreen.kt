package com.badmintontracker.android.cliplist

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.localvideo.AnalyzeResultDialog
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.android.localvideo.MatchDetailsSheet
import com.badmintontracker.android.localvideo.localVideoSection
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.SwipeToRemoveRow
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.ScoreMatchCard
import java.util.Locale
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    shares: SharesRepository,
    themePrefs: ThemePreferenceRepository,
    onMatchClick: (MatchSummary) -> Unit,
    onScoreMatchClick: (ScoreMatchCard) -> Unit,
    onNewMatch: () -> Unit,
    localRows: List<LocalVideoRow> = emptyList(),
    intakeError: String? = null,
    onIntakeErrorShown: () -> Unit = {},
    onLocalClick: (LocalVideoEntry) -> Unit = {},
    onLocalAnalyze: (LocalVideoRow) -> Unit = {},
    onLocalRemove: (LocalVideoEntry) -> Unit = {},
    onLocalResultSeen: (LocalVideoEntry) -> Unit = {},
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit = { _, _, _ -> },
    /** Entry just imported or recorded: its details sheet opens once, unprompted. */
    autoDetailsEntryId: String? = null,
    onAutoDetailsShown: () -> Unit = {},
    onRecord: () -> Unit = {},
    onImport: () -> Unit = {},
    onLabels: () -> Unit = {},
    onAttachedMarkCourt: (String) -> Unit = {},
    onAttachedRetry: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetVideoId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<MatchSummary?>(null) }
    var deleteScoreTarget by remember { mutableStateOf<ScoreMatchCard?>(null) }
    var deleteBoundMatchTarget by remember { mutableStateOf<MatchRow.Score?>(null) }
    var leaveShareTarget by remember { mutableStateOf<MatchSummary?>(null) }
    var localRemoveTarget by remember { mutableStateOf<LocalVideoEntry?>(null) }
    var detailsTarget by remember { mutableStateOf<DetailsTarget?>(null) }

    // A video picked for a match is represented by that match's row (MatchRow.Score.video).
    // Listing it again here under "On this phone" would be the same match twice.
    val standaloneRows = localRows.filter { it.entry.scoreLogId == null }

    // The entry is persisted before this runs, so a dismissed sheet, a
    // backgrounded app or a crash never costs the user the video they just took.
    LaunchedEffect(autoDetailsEntryId, localRows) {
        val id = autoDetailsEntryId ?: return@LaunchedEffect
        val entry = localRows.firstOrNull { it.entry.id == id }?.entry ?: return@LaunchedEffect
        detailsTarget = DetailsTarget(entry, autoOpened = true)
        onAutoDetailsShown()
    }

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
            resultDialog = standaloneRows.firstOrNull {
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
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New match") },
                            onClick = { addMenuOpen = false; onNewMatch() },
                        )
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
                            text = { Text("Labels") },
                            onClick = { menuOpen = false; onLabels() },
                        )
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
            if (state.ownedRows.isEmpty() && state.sharedMatches.isEmpty() &&
                standaloneRows.isEmpty() && !state.isRefreshing
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matches yet. Score one or record a video with the + button above.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    localVideoSection(
                        rows = standaloneRows,
                        header = { SectionHeader(it) },
                        onRowClick = onLocalClick,
                        onAnalyzeClick = onLocalAnalyze,
                        onRemoveRequest = { localRemoveTarget = it },
                        onEditDetails = { detailsTarget = DetailsTarget(it, autoOpened = false) },
                    )
                    if (state.ownedRows.isNotEmpty()) {
                        item(key = "header-owned") { SectionHeader("My matches") }
                        items(state.ownedRows, key = { it.key }) { row ->
                            SwipeToRemoveRow(
                                label = "Delete",
                                onSwiped = {
                                    when (row) {
                                        is MatchRow.Video -> deleteTarget = row.match
                                        is MatchRow.Score ->
                                            // A row with clips deletes two things, not one:
                                            // the video and the score log. That case gets its
                                            // own confirmation so the wording can say so.
                                            if (row.video != null) deleteBoundMatchTarget = row
                                            else deleteScoreTarget = row.card
                                    }
                                    false
                                },
                            ) {
                                when (row) {
                                    is MatchRow.Video -> VideoMatchRow(
                                        match = row.match,
                                        media = media,
                                        onClick = { onMatchClick(row.match) },
                                        onShareClick = { sheetVideoId = row.match.videoId },
                                    )
                                    is MatchRow.Score -> ScoreMatchRow(
                                        row = row,
                                        media = media,
                                        onClick = { onScoreMatchClick(row.card) },
                                        onShareClick = row.video?.let { { sheetVideoId = it.videoId } },
                                        onMarkCourt = { onAttachedMarkCourt(row.card.scoreLogId) },
                                        onRetry = { onAttachedRetry(row.card.scoreLogId) },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.sharedMatches.isNotEmpty()) {
                        item(key = "header-shared") { SectionHeader("Shared with me") }
                        items(state.sharedMatches, key = { "shared-${it.videoId}" }) { match ->
                            SwipeToRemoveRow(
                                label = "Remove",
                                onSwiped = { leaveShareTarget = match; false },
                            ) {
                                VideoMatchRow(
                                    match = match,
                                    media = media,
                                    onClick = { onMatchClick(match) },
                                    onShareClick = null,
                                )
                            }
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

    detailsTarget?.let { target ->
        MatchDetailsSheet(
            initialTitle = target.entry.title,
            initialDescription = target.entry.description,
            autoOpened = target.autoOpened,
            onDismiss = { detailsTarget = null },
            onSave = { title, description ->
                onLocalDetailsSaved(target.entry.id, title, description)
                detailsTarget = null
            },
        )
    }

    localRemoveTarget?.let { entry ->
        ConfirmDialog(
            title = "Remove video?",
            message = "Remove this video and its notes from the app? The video itself stays on your phone.",
            confirmLabel = "Remove",
            onConfirm = { onLocalRemove(entry); localRemoveTarget = null },
            onDismiss = { localRemoveTarget = null },
        )
    }

    deleteTarget?.let { match ->
        ConfirmDialog(
            title = "Delete match?",
            message = "Delete this match and all its rally clips? This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteMatch(match.videoId); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }

    deleteScoreTarget?.let { card ->
        ConfirmDialog(
            title = "Delete match?",
            // Different wording from the video match on purpose: there are no clips
            // to lose here, and the thing the coach would actually miss is the tags.
            message = "Delete this match and every point you scored? This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteScoreMatch(card.scoreLogId); deleteScoreTarget = null },
            onDismiss = { deleteScoreTarget = null },
        )
    }

    deleteBoundMatchTarget?.let { row ->
        val videoId = row.video?.videoId
        ConfirmDialog(
            title = "Delete match?",
            // A bound match's delete removes two things at once: the clips and the
            // scored points. Neither of the two wordings above says both.
            message = "Delete this match, every point you scored and all its rally clips? This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                if (videoId != null) vm.deleteBoundMatch(videoId, row.card.scoreLogId)
                deleteBoundMatchTarget = null
            },
            onDismiss = { deleteBoundMatchTarget = null },
        )
    }

    leaveShareTarget?.let { match ->
        ConfirmDialog(
            title = "Remove shared match?",
            message = "Remove this shared match from your list? You'll need the owner to share it again.",
            confirmLabel = "Remove",
            onConfirm = { vm.leaveShare(match.videoId); leaveShareTarget = null },
            onDismiss = { leaveShareTarget = null },
        )
    }
}

/** [autoOpened] switches the sheet's dismiss label between "Skip" and "Cancel". */
private data class DetailsTarget(val entry: LocalVideoEntry, val autoOpened: Boolean)

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
private fun VideoMatchRow(
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
                matchRowPrimary(match),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                matchRowSecondary(match),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            match.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/**
 * Headline for a match row. A named match leads with its name; an unnamed one
 * keeps the original date headline.
 */
internal fun matchRowPrimary(match: MatchSummary): String =
    match.title ?: "Match · ${formatDate(match.latestCreatedAt)}"

/**
 * Sub-line for a match row. When the name takes the headline the date moves
 * down here, so it is never lost from the list.
 */
internal fun matchRowSecondary(match: MatchSummary): String {
    val rallies = "${match.rallyCount} ${if (match.rallyCount == 1) "rally" else "rallies"}"
        .uppercase(Locale.ROOT)
    return if (match.title != null) {
        "$rallies · ${formatDate(match.latestCreatedAt).uppercase(Locale.ROOT)}"
    } else {
        rallies
    }
}

internal fun formatDate(instant: Instant): String {
    val ldt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${MONTHS[ldt.monthNumber - 1]} ${ldt.dayOfMonth}, ${ldt.year}"
}

@Composable
internal fun ClipRow(
    clip: RallyClip,
    media: MediaRepository,
    onClick: () -> Unit,
    matchTitle: String? = null,
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
                clipRowTitle(clip, matchTitle),
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

/**
 * A match scored courtside, in the same shape as [VideoMatchRow] so the two kinds
 * line up down the list. The cover slot is the same 96x54 box: a thumbnail once
 * the row's video has produced clips, the placeholder icon otherwise. A row 4dp
 * shorter than its neighbour reads as a bug.
 */
@Composable
private fun ScoreMatchRow(
    row: MatchRow.Score,
    media: MediaRepository,
    onClick: () -> Unit,
    onShareClick: (() -> Unit)?,
    onMarkCourt: () -> Unit,
    onRetry: () -> Unit,
) {
    val card = row.card
    val thumbUrl by produceState<String?>(initialValue = null, row.video?.videoId) {
        val cover = row.video?.coverClip ?: return@produceState
        value = runCatching { media.signedThumbnailUrl(cover) }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Matches VideoMatchRow's vertical padding: the two rows can now show
            // the identical 96x54 thumbnail and must not differ by a few dp.
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbUrl != null) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                modifier = Modifier.size(96.dp, 54.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .background(ShuttlTheme.extended.bgTertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${card.scoreLine.uppercase()} · ${formatDate(Instant.fromEpochMilliseconds(card.createdAtEpochMs)).uppercase()}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.55.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = card.playersLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.attach?.let { attach ->
                Text(
                    text = attach.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attach.kind == AttachKind.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (row.attach?.kind) {
            AttachKind.COURT_NOT_MARKED ->
                ShuttlButton(text = "Mark court", onClick = onMarkCourt,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.FAILED ->
                ShuttlButton(text = "Retry", onClick = onRetry,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP ->
                // Boxed to the same 48dp the IconButton below occupies, so the trailing
                // slot doesn't shift width when the state flips between the two.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            // Sharing needs a video: match_shares is keyed on video_id. Present but
            // disabled with the reason until there is one, rather than absent.
            null -> IconButton(onClick = { onShareClick?.invoke() }, enabled = onShareClick != null) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = if (onShareClick != null) "Share match"
                                         else "Add a video to share this match",
                )
            }
        }
    }
}
