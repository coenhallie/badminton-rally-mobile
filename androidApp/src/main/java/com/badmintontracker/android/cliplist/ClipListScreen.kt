package com.badmintontracker.android.cliplist

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.android.localvideo.MatchDetailsSheet
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.shared.analytics.videoRemovalMessage
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchAction
import com.badmintontracker.shared.matchlist.MatchDelete
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListFilter
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.effectiveFilter
import com.badmintontracker.shared.matchlist.passes
import com.badmintontracker.shared.matchlist.showsFilters
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import java.util.Locale
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The drawer's list of matches: one row per match, wherever its video is, with
 * "Shared with me" below it.
 *
 * Every row comes from the shared builder ([ClipListState.matchList]); this
 * screen only draws them and turns their taps, menus and swipes into the
 * callbacks below. Navigation takes ids, which is all the routes take.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    onMatchClick: (videoId: String) -> Unit,
    onScoreMatchClick: (scoreLogId: String) -> Unit,
    /** A file on this phone with nothing else to it: the local player. */
    onLocalClick: (entryId: String) -> Unit = {},
    /** Analyze, or analyze again: court marking, or a resume when the run can resume. */
    onAnalyze: (entryId: String) -> Unit = {},
    onMarkCourt: (scoreLogId: String) -> Unit = {},
    onRetryAttached: (scoreLogId: String) -> Unit = {},
    /** Opens a stored player heatmap, by the id its track is keyed by. */
    onOpenHeatmap: (heatmapId: String) -> Unit = {},
    onOpenLocalClips: (entryId: String) -> Unit = {},
    /** Frees the file and keeps the tracks: see LocalVideoListViewModel.remove. */
    onRemoveFromPhone: (entryId: String) -> Unit = {},
    onLocalResultSeen: (entryId: String) -> Unit = {},
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
    /** The empty state's own "Add new match": Home closes the drawer and opens its add sheet. */
    onAddMatch: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetVideoId by remember { mutableStateOf<String?>(null) }
    var detailsTarget by remember { mutableStateOf<DetailsTarget?>(null) }
    var legendOpen by remember { mutableStateOf(false) }
    // A row the coach asked to delete, waiting on a confirmation: the choice
    // sheet when it has two things to delete, the single dialog otherwise.
    var choiceTarget by remember { mutableStateOf<MatchListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MatchListItem?>(null) }
    var removeTarget by remember { mutableStateOf<MatchListItem?>(null) }

    var filter by rememberSaveable { mutableStateOf(MatchListFilter.ALL) }
    val mine = state.matchList.mine
    val shared = state.matchList.shared
    // Review focus 5: a filter whose chips have gone would go on hiding
    // matches with nothing on screen to undo it.
    LaunchedEffect(showsFilters(mine.size)) {
        if (!showsFilters(mine.size)) filter = MatchListFilter.ALL
    }
    val applied = effectiveFilter(filter, mine.size)
    val visible = mine.filter { it.passes(applied) }

    // The entry is persisted before this runs, so a dismissed sheet, a
    // backgrounded app or a crash never costs the user the video they just took.
    LaunchedEffect(autoDetailsEntryId, state.localEntries) {
        val id = autoDetailsEntryId ?: return@LaunchedEffect
        val entry = state.localEntries.firstOrNull { it.id == id } ?: return@LaunchedEffect
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
    // Standalone videos only: a match's own video fails on its match's row.
    var resultEntry by remember { mutableStateOf<LocalVideoEntry?>(null) }
    LaunchedEffect(state.localEntries) {
        if (resultEntry == null) {
            resultEntry = state.localEntries.firstOrNull {
                it.scoreLogId == null && it.stage == AnalyzeStage.FAILED && !it.resultSeen
            }
        }
    }

    fun open(item: MatchListItem) {
        val entryId = item.entryId
        val scoreLogId = item.scoreLogId
        val videoId = item.videoId
        when {
            item.opensLocalPlayer && entryId != null -> onLocalClick(entryId)
            scoreLogId != null -> onScoreMatchClick(scoreLogId)
            videoId != null -> onMatchClick(videoId)
        }
    }

    fun act(item: MatchListItem) {
        val entryId = item.entryId
        val scoreLogId = item.scoreLogId
        when (item.action) {
            MatchAction.ANALYZE -> entryId?.let(onAnalyze)
            // A scored match's video goes through its own retry, which finds
            // the entry from the match; a video filmed on its own is its entry.
            MatchAction.RETRY -> if (scoreLogId != null) onRetryAttached(scoreLogId) else entryId?.let(onAnalyze)
            MatchAction.MARK_COURT -> scoreLogId?.let(onMarkCourt)
            MatchAction.NONE, MatchAction.RUNNING, MatchAction.PAUSED -> Unit
        }
    }

    fun deleteEverywhere(item: MatchListItem) {
        val videoId = item.videoId
        val scoreLogId = item.scoreLogId
        when (item.delete) {
            MatchDelete.VIDEO_MATCH -> videoId?.let { vm.deleteMatch(it, entryId = item.entryId) }
            MatchDelete.SCORE_MATCH -> scoreLogId?.let(vm::deleteScoreMatch)
            MatchDelete.BOUND_MATCH -> if (videoId != null && scoreLogId != null) vm.deleteBoundMatch(videoId, scoreLogId)
            MatchDelete.LEAVE_SHARE -> videoId?.let(vm::leaveShare)
            MatchDelete.NONE -> Unit
        }
    }

    fun menuFor(item: MatchListItem): List<RowMenuItem> = buildList {
        val entryId = item.entryId
        val videoId = item.videoId
        if (item.canShare && videoId != null) add(RowMenuItem(MatchListCopy.MENU_SHARE) { sheetVideoId = videoId })
        if (item.deviceClipCount > 0 && entryId != null) {
            add(RowMenuItem(MatchListCopy.menuDeviceClips(item.deviceClipCount)) { onOpenLocalClips(entryId) })
        }
        item.heatmapId?.let { id -> add(RowMenuItem(MatchListCopy.MENU_HEATMAP) { onOpenHeatmap(id) }) }
        if (item.canEditDetails) {
            state.localEntries.firstOrNull { it.id == entryId }?.let { entry ->
                add(RowMenuItem(MatchListCopy.MENU_EDIT_DETAILS) { detailsTarget = DetailsTarget(entry, autoOpened = false) })
            }
        }
        if (item.canAnalyzeAgain && entryId != null) add(RowMenuItem(MatchListCopy.MENU_ANALYZE_AGAIN) { onAnalyze(entryId) })
        if (item.canRemoveFromPhone) add(RowMenuItem(MatchListCopy.MENU_REMOVE_FROM_PHONE) { removeTarget = item })
        // Straight to the one confirmation, not the choice sheet: removing the
        // file alone is already its own item just above.
        when (item.delete) {
            MatchDelete.NONE -> Unit
            MatchDelete.LEAVE_SHARE -> add(RowMenuItem(MatchListCopy.MENU_LEAVE_SHARE) { deleteTarget = item })
            else -> add(RowMenuItem(MatchListCopy.MENU_DELETE) { deleteTarget = item })
        }
    }

    fun LazyListScope.rows(items: List<MatchListItem>) {
        items(items, key = { it.key }) { item ->
            val swipe = swipeFor(item)
            MatchListRow(
                item = item,
                media = media,
                onClick = { open(item) },
                onAction = { act(item) },
                menu = menuFor(item),
                swipeLabel = swipe?.label,
                onSwiped = {
                    when (swipe) {
                        Swipe.CHOOSE -> choiceTarget = item
                        Swipe.DELETE, Swipe.LEAVE -> deleteTarget = item
                        Swipe.REMOVE -> removeTarget = item
                        null -> Unit
                    }
                },
            )
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
            if (mine.isEmpty() && shared.isEmpty() && !state.isRefreshing) {
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
                    // panel separated by a gap, so there are no dividers to
                    // draw. "Shared with me" carries its own label padding, so
                    // the gap above it is the label's plus this spacing.
                    contentPadding = PaddingValues(
                        top = DrawerList.listTopInset,
                        bottom = DrawerList.sideMargin,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DrawerList.rowGap),
                ) {
                    item(key = "filters") {
                        MatchFilterRow(
                            showChips = showsFilters(mine.size),
                            selected = applied,
                            onSelect = { filter = it },
                            onLegend = { legendOpen = true },
                        )
                    }
                    rows(visible)
                    if (visible.isEmpty() && mine.isNotEmpty()) {
                        item(key = "filter-empty") {
                            Text(
                                text = if (applied == MatchListFilter.ON_THIS_PHONE) MatchListCopy.EMPTY_ON_THIS_PHONE
                                       else MatchListCopy.EMPTY_NOT_ANALYZED,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = DrawerList.sideMargin, vertical = 12.dp),
                            )
                        }
                    }
                    if (shared.isNotEmpty()) {
                        item(key = "header-shared") { DrawerSectionLabel(MatchListCopy.SHARED_SECTION) }
                        // Never filtered: the chips are about the coach's own
                        // matches, and a shared one is neither on this phone
                        // nor waiting on this coach to analyze it.
                        rows(shared)
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

    if (legendOpen) MatchLegendSheet(onDismiss = { legendOpen = false })

    resultEntry?.let { entry ->
        AnalyzeResultDialog(
            entry = entry,
            onRetry = { resultEntry = null; onLocalResultSeen(entry.id); onAnalyze(entry.id) },
            onDismiss = { resultEntry = null; onLocalResultSeen(entry.id) },
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

    choiceTarget?.let { item ->
        DeleteChoiceSheet(
            item = item,
            onRemoveFromPhone = { choiceTarget = null; item.entryId?.let(onRemoveFromPhone) },
            onDeleteEverywhere = { choiceTarget = null; deleteEverywhere(item) },
            onDismiss = { choiceTarget = null },
        )
    }

    removeTarget?.let { item ->
        ConfirmDialog(
            title = "Remove video?",
            // The stored track, as before, not "has any analysis": the message
            // promises the heatmap stays, which is only true when there is one,
            // and a row whose only analysis is its rallies or its score has none.
            message = videoRemovalMessage(hasAnalysis = item.heatmapId != null),
            confirmLabel = "Remove",
            onConfirm = { removeTarget = null; item.entryId?.let(onRemoveFromPhone) },
            onDismiss = { removeTarget = null },
        )
    }

    deleteTarget?.let { item ->
        val (title, message, confirm) = deleteConfirmation(item.delete) ?: return@let
        ConfirmDialog(
            title = title,
            message = message,
            confirmLabel = confirm,
            onConfirm = { deleteTarget = null; deleteEverywhere(item) },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** [autoOpened] switches the sheet's dismiss label between "Skip" and "Cancel". */
private data class DetailsTarget(val entry: LocalVideoEntry, val autoOpened: Boolean)

/** What a swipe on a row does, and what the revealed label says. */
private enum class Swipe(val label: String) {
    /** Both a file here and a match in the cloud: the choice sheet. */
    CHOOSE(MatchListCopy.SWIPE_DELETE),
    DELETE(MatchListCopy.SWIPE_DELETE),
    LEAVE(MatchListCopy.SWIPE_REMOVE),
    /** Only the file: the "Remove video?" dialog. */
    REMOVE(MatchListCopy.SWIPE_REMOVE),
}

/**
 * Null for a row nothing may be deleted from: a run in flight holds its file,
 * and deleting the match would delete it too.
 */
private fun swipeFor(item: MatchListItem): Swipe? = when {
    item.delete == MatchDelete.LEAVE_SHARE -> Swipe.LEAVE
    item.delete != MatchDelete.NONE && item.canRemoveFromPhone -> Swipe.CHOOSE
    item.delete != MatchDelete.NONE -> Swipe.DELETE
    item.canRemoveFromPhone -> Swipe.REMOVE
    else -> null
}

/**
 * The single confirmation per kind, worded as each was before the list was
 * one list: each says exactly what goes, and the coach who deletes a scored
 * match is told about the points, not about clips it never had.
 */
private fun deleteConfirmation(kind: MatchDelete): Triple<String, String, String>? = when (kind) {
    MatchDelete.VIDEO_MATCH ->
        Triple("Delete match?", "Delete this match and all its rally clips? This can't be undone.", "Delete")
    MatchDelete.SCORE_MATCH ->
        Triple("Delete match?", "Delete this match and every point you scored? This can't be undone.", "Delete")
    MatchDelete.BOUND_MATCH -> Triple(
        "Delete match?",
        "Delete this match, every point you scored and all its rally clips? This can't be undone.",
        "Delete",
    )
    MatchDelete.LEAVE_SHARE -> Triple(
        "Remove shared match?",
        "Remove this shared match from your list? You'll need the owner to share it again.",
        "Remove",
    )
    MatchDelete.NONE -> null
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
