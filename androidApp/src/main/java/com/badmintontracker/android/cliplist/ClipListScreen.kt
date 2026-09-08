package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.android.localvideo.AnalyzeResultDialog
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.android.localvideo.MatchDetailsSheet
import com.badmintontracker.android.localvideo.localVideoSection
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
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
    shares: SharesRepository,
    onMatchClick: (MatchSummary) -> Unit,
    onScoreMatchClick: (ScoreMatchCard) -> Unit,
    /** Opens a stored player heatmap; null hides the menu entry entirely. */
    onOpenHeatmap: ((com.badmintontracker.shared.localvideo.LocalVideoEntry) -> Unit)? = null,
    hasHeatmap: (com.badmintontracker.shared.localvideo.LocalVideoEntry) -> Boolean = { false },
    onOpenLocalClips: ((com.badmintontracker.shared.localvideo.LocalVideoEntry) -> Unit)? = null,
    localClipCount: (com.badmintontracker.shared.localvideo.LocalVideoEntry) -> Int = { 0 },
    localRows: List<LocalVideoRow> = emptyList(),
    onLocalClick: (LocalVideoEntry) -> Unit = {},
    onLocalAnalyze: (LocalVideoRow) -> Unit = {},
    onLocalRemove: (LocalVideoEntry) -> Unit = {},
    onLocalResultSeen: (LocalVideoEntry) -> Unit = {},
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit = { _, _, _ -> },
    /** Entry just imported or recorded: its details sheet opens once, unprompted. */
    autoDetailsEntryId: String? = null,
    onAutoDetailsShown: () -> Unit = {},
    /**
     * That same sheet closing again, however it was closed: saved, skipped, or
     * swiped away. Home opens the drawer on this, so the video the coach just
     * named is on screen instead of a Home screen that looks untouched. Fired
     * only for the auto-opened sheet - an "Edit details" from the row menu
     * already happens with the drawer open and in front of the coach.
     */
    onAutoDetailsClosed: () -> Unit = {},
    onAttachedMarkCourt: (String) -> Unit = {},
    onAttachedRetry: (String) -> Unit = {},
    /** The empty state's own "Add new match": Home closes the drawer and opens its add sheet. */
    onAddMatch: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
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

    // No Scaffold: Home owns the bar now, and this content sits inside the
    // drawer's slot rather than at the top of a screen. A plain Box in place
    // of Scaffold keeps the one behaviour Scaffold was providing here -
    // pinning the snackbar host to the bottom of this content - without
    // dragging its bar/FAB/insets machinery along for a screen that has none
    // of those anymore.
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.ownedRows.isEmpty() && state.sharedMatches.isEmpty() &&
                standaloneRows.isEmpty() && !state.isRefreshing
            ) {
                // Scrollable even though nothing scrolls: PullToRefreshBox only
                // sees the gesture through a nested-scroll child, so without
                // this a pull on the empty list did nothing.
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    // The control itself, not directions to it: this list sits
                    // behind the drawer, and "tap Add new match on Home" sent a
                    // first-time user back out to find a button they had not
                    // seen yet.
                    ShuttlEmptyState(
                        icon = ShuttlIcons.Trophy,
                        title = "No matches yet",
                        body = "Record, import or score a match and it will show up here.",
                        action = {
                            ShuttlButton(
                                text = "Add new match",
                                onClick = onAddMatch,
                                leadingIcon = Icons.Default.Add,
                                compact = true,
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // An inset list, not a full-bleed one: rows are cards on the
                    // panel separated by a gap, so there are no dividers left to
                    // draw. The top inset plus a section label's own top padding
                    // is the mock's 26px from the drawer header to the first
                    // label; between two sections that gap becomes 28px, which
                    // is this spacing added to the same label padding.
                    contentPadding = PaddingValues(
                        top = DrawerList.listTopInset,
                        bottom = DrawerList.sideMargin,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DrawerList.rowGap),
                ) {
                    localVideoSection(
                        rows = standaloneRows,
                        header = { DrawerSectionLabel(it) },
                        onRowClick = onLocalClick,
                        onAnalyzeClick = onLocalAnalyze,
                        onRemoveRequest = { localRemoveTarget = it },
                        onEditDetails = { detailsTarget = DetailsTarget(it, autoOpened = false) },
                        onOpenHeatmap = onOpenHeatmap,
                        hasHeatmap = hasHeatmap,
                        onOpenLocalClips = onOpenLocalClips,
                        localClipCount = localClipCount,
                    )
                    if (state.ownedRows.isNotEmpty()) {
                        item(key = "header-owned") { DrawerSectionLabel("My matches") }
                        items(state.ownedRows, key = { it.key }) { row ->
                            MatchRowContainer(
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
                                        onClick = { onMatchClick(row.match) },
                                        onShareClick = { sheetVideoId = row.match.videoId },
                                    )
                                    is MatchRow.Score -> ScoreMatchRow(
                                        row = row,
                                        onClick = { onScoreMatchClick(row.card) },
                                        onShareClick = row.video?.let { { sheetVideoId = it.videoId } },
                                        onMarkCourt = { onAttachedMarkCourt(row.card.scoreLogId) },
                                        onRetry = { onAttachedRetry(row.card.scoreLogId) },
                                    )
                                }
                            }
                        }
                    }
                    if (state.sharedMatches.isNotEmpty()) {
                        item(key = "header-shared") { DrawerSectionLabel("Shared with me") }
                        items(state.sharedMatches, key = { "shared-${it.videoId}" }) { match ->
                            MatchRowContainer(
                                label = "Remove",
                                onSwiped = { leaveShareTarget = match; false },
                            ) {
                                VideoMatchRow(
                                    match = match,
                                    onClick = { onMatchClick(match) },
                                    onShareClick = null,
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
        fun close() {
            detailsTarget = null
            if (target.autoOpened) onAutoDetailsClosed()
        }
        MatchDetailsSheet(
            initialTitle = target.entry.title,
            initialDescription = target.entry.description,
            autoOpened = target.autoOpened,
            onDismiss = ::close,
            onSave = { title, description ->
                onLocalDetailsSaved(target.entry.id, title, description)
                close()
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

/**
 * A match row's inset box.
 *
 * Quieter than the "On this phone" card above it, and deliberately so: the mock
 * gives the drawer exactly one raised surface, on the section that leads
 * somewhere new. These rows are transparent until they are pressed, so the fill
 * behind the swipe reveal is the panel's own colour.
 */
@Composable
private fun MatchRowContainer(
    label: String,
    onSwiped: () -> Boolean,
    content: @Composable () -> Unit,
) {
    DrawerRowContainer(
        shape = RoundedCornerShape(ShuttlRadius.medium),
        fill = MaterialTheme.colorScheme.background,
        swipeLabel = label,
        onSwiped = onSwiped,
        content = content,
    )
}

@Composable
private fun VideoMatchRow(
    match: MatchSummary,
    onClick: () -> Unit,
    onShareClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = DrawerList.rowPaddingH,
                // See LocalVideoRowItem: backs out an IconButton's own 12dp so
                // the glyph lands the same distance from the row's edge as the
                // text does on the other side.
                end = if (onShareClick != null) DrawerList.rowPaddingH - 12.dp
                      else DrawerList.rowPaddingH,
                top = DrawerList.rowPaddingV,
                bottom = DrawerList.rowPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            Spacer(Modifier.width(DrawerList.gap))
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
 *
 * Sentence case, not the uppercase this used to return: the drawer's redesign
 * carries no uppercase anywhere. Analytics still wants the old look and applies
 * `.uppercase()` at its own call site in AnalyticsRows.kt, so the casing is a
 * screen's choice rather than something baked into the string.
 */
internal fun matchRowSecondary(match: MatchSummary): String {
    val rallies = "${match.rallyCount} ${if (match.rallyCount == 1) "rally" else "rallies"}"
    return if (match.title != null) {
        "$rallies · ${formatDate(match.latestCreatedAt)}"
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
 * line up down the list: the same insets, the same three text lines, and a
 * trailing slot the same width whichever control is in it. A row a few dp
 * shorter than its neighbour reads as a bug.
 */
@Composable
private fun ScoreMatchRow(
    row: MatchRow.Score,
    onClick: () -> Unit,
    onShareClick: (() -> Unit)?,
    onMarkCourt: () -> Unit,
    onRetry: () -> Unit,
) {
    val card = row.card
    // Only the share control is an IconButton with 12dp of padding of its own;
    // the pills and the ring are drawn at their own size and need the row's
    // full inset. See LocalVideoRowItem for what this is backing out.
    val trailingIsIconButton = row.attach == null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = DrawerList.rowPaddingH,
                end = if (trailingIsIconButton) DrawerList.rowPaddingH - 12.dp
                      else DrawerList.rowPaddingH,
                top = DrawerList.rowPaddingV,
                bottom = DrawerList.rowPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${card.scoreLine} · " +
                    formatDate(Instant.fromEpochMilliseconds(card.createdAtEpochMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        Spacer(Modifier.width(DrawerList.gap))
        when (row.attach?.kind) {
            AttachKind.COURT_NOT_MARKED ->
                ShuttlButton(text = "Mark court", onClick = onMarkCourt,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.FAILED ->
                ShuttlButton(text = "Retry", onClick = onRetry,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP ->
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
