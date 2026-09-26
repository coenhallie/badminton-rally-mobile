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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.android.home.MatchesEmptyState
import com.badmintontracker.android.localvideo.MatchDetailsSheet
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchAction
import com.badmintontracker.shared.matchlist.MatchConfirmation
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
 * The match list: one row per match, wherever its video is, with
 * "Shared with me" below it.
 *
 * Every row comes from the shared builder ([ClipListState.matchList]); this
 * screen only draws them and turns their taps, menus and swipes into the
 * callbacks below. A tap hands the whole row up: the match page is found by
 * whichever of its ids the list still has, so the caller needs them all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    /** Every row opens its match page, whatever the match has: see matchPageSections. */
    onOpenMatch: (MatchListItem) -> Unit,
    /** Analyze, or analyze again: court marking, or a resume when the run can resume. */
    onAnalyze: (entryId: String) -> Unit = {},
    /** Court marking for this entry: a scored match's video that has not been marked yet. */
    onMarkCourt: (entryId: String) -> Unit = {},
    /** Frees the file and keeps the tracks: see LocalVideoListViewModel.remove. */
    onRemoveFromPhone: (entryId: String) -> Unit = {},
    onLocalResultSeen: (entryId: String) -> Unit = {},
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit = { _, _, _ -> },
    /** Entry just imported or recorded: its details sheet opens once, unprompted. */
    autoDetailsEntryId: String? = null,
    onAutoDetailsShown: () -> Unit = {},
    /** Hoisted to HomeScreen: the Add button reads it, and it is not inside the list. */
    listState: LazyListState,
    /** HomeScreen's one host, so a snackbar sits above the Add button, not under it. */
    snackbarHostState: SnackbarHostState,
    /**
     * Whether any sheet or dialog this list owns is open. HomeScreen folds it
     * into "nothing above the list", which gates a finished run's snackbar:
     * without it a run could offer View over a half-typed details sheet.
     */
    onOverlayChange: (Boolean) -> Unit = {},
    /** The empty state's hero ticker stops while the add sheet covers it. */
    heroPaused: Boolean = false,
    /** Room at the bottom so the last row and its menu scroll clear of the gesture bar and the Add button. */
    contentPadding: PaddingValues = PaddingValues(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sheetVideoId by remember { mutableStateOf<String?>(null) }
    var detailsTarget by remember { mutableStateOf<DetailsTarget?>(null) }
    var legendOpen by remember { mutableStateOf(false) }
    // A row the coach asked to delete, waiting on a confirmation: the choice
    // sheet when it has two things to delete, the single dialog otherwise.
    // Held by key and looked up in the list as it is now (itemWithKey), not
    // as the item the gesture saw: the list is rebuilt under an open dialog
    // (stored results land, a run settles, clips sync), and the dialog has to
    // say what removing or deleting the row does as it is drawn now.
    var choiceKey by remember { mutableStateOf<String?>(null) }
    var deleteKey by remember { mutableStateOf<String?>(null) }
    var removeKey by remember { mutableStateOf<String?>(null) }
    val choiceTarget = choiceKey?.let { state.matchList.confirmationTarget(it, MatchConfirmation.CHOICE) }
    val deleteTarget = deleteKey?.let { state.matchList.confirmationTarget(it, MatchConfirmation.DELETE) }
    val removeTarget = removeKey?.let { state.matchList.confirmationTarget(it, MatchConfirmation.REMOVE) }
    // A row that left the list, or no longer offers what its dialog confirms,
    // takes the confirmation with it for good, rather than leaving a key that
    // would reopen the dialog if the row came back. iOS clears its keys on the
    // same rule.
    LaunchedEffect(choiceTarget, deleteTarget, removeTarget) {
        if (choiceTarget == null) choiceKey = null
        if (deleteTarget == null) deleteKey = null
        if (removeTarget == null) removeKey = null
    }

    var filter by rememberSaveable { mutableStateOf(MatchListFilter.ALL) }
    val mine = state.matchList.mine
    val shared = state.matchList.shared
    // Review focus 5: a filter whose chips have gone would go on hiding
    // matches with nothing on screen to undo it. Reset on the chips going
    // away, not on their being absent: the first frame draws the view model's
    // empty initial state, and resetting against that would wipe a filter the
    // coach chose before a rotation or a process restore. effectiveFilter
    // already stops a filter applying while its chips are hidden; this makes
    // the choice itself forget once they go.
    val chipsShown by rememberUpdatedState(showsFilters(mine.size))
    LaunchedEffect(Unit) {
        var wereShown = false
        snapshotFlow { chipsShown }.collect { shown ->
            if (wereShown && !shown) filter = MatchListFilter.ALL
            wereShown = shown
        }
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

    val overlayOpen = sheetVideoId != null || detailsTarget != null || legendOpen ||
        choiceTarget != null || deleteTarget != null || removeTarget != null || resultEntry != null
    LaunchedEffect(overlayOpen) { onOverlayChange(overlayOpen) }

    fun act(item: MatchListItem) {
        val entryId = item.entryId
        when (item.action) {
            // Every action goes by the row's own entry, never by looking it up
            // again from the match: the builder may have joined the entry to its
            // log by the log's video id, which a scoreLogId lookup would miss,
            // leaving a button that does nothing under a status that says why
            // it is there. Retry is Analyze: the caller's resume-or-mark-court
            // decision is the same one either way.
            MatchAction.ANALYZE, MatchAction.RETRY -> entryId?.let(onAnalyze)
            MatchAction.MARK_COURT -> entryId?.let(onMarkCourt)
            MatchAction.NONE, MatchAction.RUNNING, MatchAction.PAUSED -> Unit
        }
    }

    fun deleteEverywhere(item: MatchListItem) {
        when (val call = deleteEverywhereFor(item)) {
            is DeleteEverywhere.VideoMatch -> vm.deleteMatch(call.videoId, entryId = call.entryId)
            is DeleteEverywhere.ScoreMatch -> vm.deleteScoreMatch(call.scoreLogId, entryId = call.entryId)
            is DeleteEverywhere.BoundMatch -> vm.deleteBoundMatch(call.videoId, call.scoreLogId, entryId = call.entryId)
            is DeleteEverywhere.LeaveShare -> vm.leaveShare(call.videoId)
            null -> Unit
        }
    }

    fun menuFor(item: MatchListItem): List<RowMenuItem> = buildList {
        val entryId = item.entryId
        val videoId = item.videoId
        if (item.canShare && videoId != null) add(RowMenuItem(MatchListCopy.MENU_SHARE) { sheetVideoId = videoId })
        if (item.canEditDetails) {
            state.localEntries.firstOrNull { it.id == entryId }?.let { entry ->
                add(RowMenuItem(MatchListCopy.MENU_EDIT_DETAILS) { detailsTarget = DetailsTarget(entry, autoOpened = false) })
            }
        }
        if (item.canAnalyzeAgain && entryId != null) add(RowMenuItem(MatchListCopy.MENU_ANALYZE_AGAIN) { onAnalyze(entryId) })
        if (item.canRemoveFromPhone) add(RowMenuItem(MatchListCopy.MENU_REMOVE_FROM_PHONE) { removeKey = item.key })
        // Straight to the one confirmation, not the choice sheet: removing the
        // file alone is already its own item just above.
        when (item.delete) {
            MatchDelete.NONE -> Unit
            MatchDelete.LEAVE_SHARE -> add(RowMenuItem(MatchListCopy.MENU_LEAVE_SHARE) { deleteKey = item.key })
            else -> add(RowMenuItem(MatchListCopy.MENU_DELETE) { deleteKey = item.key })
        }
    }

    fun LazyListScope.rows(items: List<MatchListItem>) {
        items(items, key = { it.key }) { item ->
            val swipe = swipeFor(item)
            MatchListRow(
                item = item,
                media = media,
                onClick = { onOpenMatch(item) },
                onAction = { act(item) },
                menu = menuFor(item),
                swipeLabel = swipe?.label,
                refreshGeneration = state.refreshGeneration,
                onSwiped = {
                    when (swipe) {
                        Swipe.CHOOSE -> choiceKey = item.key
                        Swipe.DELETE, Swipe.LEAVE -> deleteKey = item.key
                        Swipe.REMOVE -> removeKey = item.key
                        null -> Unit
                    }
                },
            )
        }
    }

    // No Scaffold: HomeScreen owns the Scaffold, the title bar, the Add button
    // and the one snackbar host. This is its content, not a screen of its own,
    // so a plain Box is all it needs to lay its own two states (empty, list) on
    // top of pull to refresh.
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (mine.isEmpty() && shared.isEmpty() && !state.isRefreshing) {
                // Scrollable even though nothing scrolls: PullToRefreshBox only
                // sees the gesture through a nested-scroll child, so without
                // this a pull on the empty list did nothing. Top-aligned, not
                // centred: the hero sits at a fixed offset so the cycling line
                // does not shift the layout.
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    MatchesEmptyState(isPaused = heroPaused)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // An inset list, not a full-bleed one: rows are cards on the
                    // panel separated by a gap, so there are no dividers to
                    // draw. "Shared with me" carries its own label padding, so
                    // the gap above it is the label's plus this spacing.
                    contentPadding = PaddingValues(
                        top = DrawerList.listTopInset,
                        bottom = DrawerList.sideMargin + contentPadding.calculateBottomPadding(),
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
        // No drawer to open on close: the coach is already looking at the new
        // row, on the list, whether this sheet auto-opened or the coach
        // opened it from a row's menu.
        fun close() {
            detailsTarget = null
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
            onRemoveFromPhone = { choiceKey = null; item.entryId?.let(onRemoveFromPhone) },
            onDeleteEverywhere = { choiceKey = null; deleteEverywhere(item) },
            onDismiss = { choiceKey = null },
        )
    }

    removeTarget?.let { item ->
        ConfirmDialog(
            title = MatchListCopy.REMOVE_VIDEO_TITLE,
            // Per row: what goes and what stays depends on whether the match
            // has a cloud copy, which is the only thing that lists a kept
            // track again once the file is gone.
            message = MatchListCopy.removeFromPhoneDetail(item),
            confirmLabel = MatchListCopy.CONFIRM_REMOVE,
            onConfirm = { removeKey = null; item.entryId?.let(onRemoveFromPhone) },
            onDismiss = { removeKey = null },
        )
    }

    deleteTarget?.let { item ->
        val title = MatchListCopy.deleteTitle(item.delete) ?: return@let
        val confirm = MatchListCopy.deleteConfirmLabel(item.delete) ?: return@let
        // The file goes too whenever the row has one: a delete is only offered
        // on such a row once the file is free to discard.
        val message = MatchListCopy.deleteMessage(item.delete, onThisPhone = item.entryId != null) ?: return@let
        ConfirmDialog(
            title = title,
            message = message,
            confirmLabel = confirm,
            onConfirm = { deleteKey = null; deleteEverywhere(item) },
            onDismiss = { deleteKey = null },
        )
    }
}

/** [autoOpened] switches the sheet's dismiss label between "Skip" and "Cancel". */
private data class DetailsTarget(val entry: LocalVideoEntry, val autoOpened: Boolean)

/**
 * What a swipe on a row does, and what the revealed label says. Internal, not
 * private: RowGesturesTest drives it directly, as iOS's RowSwipeTests does.
 */
internal enum class Swipe(val label: String) {
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
internal fun swipeFor(item: MatchListItem): Swipe? = when {
    item.delete == MatchDelete.LEAVE_SHARE -> Swipe.LEAVE
    item.delete != MatchDelete.NONE && item.canRemoveFromPhone -> Swipe.CHOOSE
    item.delete != MatchDelete.NONE -> Swipe.DELETE
    item.canRemoveFromPhone -> Swipe.REMOVE
    else -> null
}

/** The view model call a row's delete everywhere makes, with the ids it passes. */
internal sealed interface DeleteEverywhere {
    data class VideoMatch(val videoId: String, val entryId: String?) : DeleteEverywhere
    data class ScoreMatch(val scoreLogId: String, val entryId: String?) : DeleteEverywhere
    data class BoundMatch(val videoId: String, val scoreLogId: String, val entryId: String?) : DeleteEverywhere
    data class LeaveShare(val videoId: String) : DeleteEverywhere
}

/**
 * What deleting [item] everywhere calls. Always with the row's own entry, never
 * one looked up again from the match: the builder may have joined the entry to
 * its log by the log's video id, which a scoreLogId lookup would miss, and the
 * file would survive as a phone-only row of the match just deleted. Apart from
 * the screen so RowGesturesTest can hold it to that.
 */
internal fun deleteEverywhereFor(item: MatchListItem): DeleteEverywhere? {
    val videoId = item.videoId
    val scoreLogId = item.scoreLogId
    return when (item.delete) {
        MatchDelete.VIDEO_MATCH -> videoId?.let { DeleteEverywhere.VideoMatch(it, item.entryId) }
        MatchDelete.SCORE_MATCH -> scoreLogId?.let { DeleteEverywhere.ScoreMatch(it, item.entryId) }
        MatchDelete.BOUND_MATCH -> if (videoId != null && scoreLogId != null) {
            DeleteEverywhere.BoundMatch(videoId, scoreLogId, item.entryId)
        } else {
            null
        }
        MatchDelete.LEAVE_SHARE -> videoId?.let(DeleteEverywhere::LeaveShare)
        MatchDelete.NONE -> null
    }
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * "Sep 8, 2026". Used by [com.badmintontracker.android.match.MatchScreen]'s own
 * headline; the match list reads the shared builder's own `formatMatchDate`
 * instead (`MatchListItem.detail`), which is where `matchRowPrimary` and
 * `matchRowSecondary` used to draw its headline and sub-line before they moved
 * onto `MatchListItem`.
 */
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
