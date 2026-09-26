package com.badmintontracker.android.match

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.MatchSummarySheet
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.cliplist.topRallyName
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.scoring.AttachIntent
import com.badmintontracker.android.scoring.EditMatchDetailsDialog
import com.badmintontracker.android.scoring.MatchDetailsDraft
import com.badmintontracker.android.scoring.detailsDraft
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.shareText
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchAction
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.matchpage.MatchPageSection
import com.badmintontracker.shared.matchpage.analysisId
import com.badmintontracker.shared.matchpage.matchPageItem
import com.badmintontracker.shared.matchpage.matchPageSections
import com.badmintontracker.shared.matchpage.shownSection
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.AttachStatus
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MatchVideoAction
import com.badmintontracker.shared.scoring.VIDEO_MATCH_TITLE_CAP
import com.badmintontracker.shared.scoring.exportMatchText
import com.badmintontracker.shared.scoring.matchDetailsLine
import com.badmintontracker.shared.scoring.matchVideoPrompt
import com.badmintontracker.shared.scoring.playersLine

/**
 * One match, however it was made, and every way into it: a row's tap on the
 * Matches tab opens this page whatever the match has. It shows the match's
 * sections (Points, Match, Rallies, Analysis), decided by the shared
 * [matchPageSections] rule from the list's own row, with a toggle row between
 * them when there is more than one. [summaryVm] is nullable because a match
 * with no cloud video has no clips to summarise - see the caller for how the
 * effective video id is resolved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(
    vm: ClipListViewModel,
    matchVm: MatchViewModel,
    summaryVm: MatchSummaryViewModel?,
    media: MediaRepository,
    shares: SharesRepository,
    scoreLogId: String?,
    videoId: String?,
    /** A video only on this phone has no cloud id and no score log: this is how the page finds it. */
    entryId: String?,
    /** The section to open on, from a finished run's View; null opens the default. */
    initialSection: MatchPageSection?,
    /** The intent carried by [com.badmintontracker.android.nav.Route.Match]'s `attach`
     *  argument - "Import" or "Record" chosen on the board's finish prompt, or null. */
    attach: String?,
    onBack: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
    onScore: () -> Unit,
    onAddVideo: (AttachIntent) -> Unit,
    onMarkCourt: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAnalyzeEntry: (entryId: String) -> Unit,
    /**
     * The three section bodies, built by AuthGate, which holds the runner and
     * the repositories they read. Slots rather than parameters so this screen
     * does not take five more dependencies to pass straight through.
     */
    matchSection: @Composable (entry: LocalVideoEntry, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit, snackbar: SnackbarHostState, analyzeLabel: String?) -> Unit,
    analysisSection: @Composable (analysisId: String) -> Unit,
    deviceRalliesSection: @Composable (entryId: String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val matchState by matchVm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var addVideoMenuOpen by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ClipSort.RallyOrder) }
    val summary by summaryVm?.summary?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MatchLabelSummary?>(null) }
    var summarySheetOpen by remember { mutableStateOf(false) }
    // Which of the two video gestures is awaiting its confirm, or null. Both are
    // irreversible, and both are behind the same dialog - see MatchVideoDialog.
    var videoAction by remember { mutableStateOf<MatchVideoAction?>(null) }
    var editingDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // A summary that goes away while its sheet is open must close the sheet, not
    // leave the flag standing to reopen it when the next summary arrives.
    LaunchedEffect(summary) {
        if (summary?.isEmpty != false) summarySheetOpen = false
    }

    // Covers the case the clip-set trigger cannot see: a note added inside
    // ClipDetail and then a back press. Keyed on summaryVm because the effect
    // captures its block once per key: a page opened on a video only on this
    // phone has none, and gains one when a cloud run creates the video.
    LifecycleResumeEffect(summaryVm) {
        summaryVm?.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.dismissError()
    }

    // Its own channel, not the clip store's: removing this match's video is the
    // one thing this page does that can fail on its own.
    LaunchedEffect(matchState.error) {
        val err = matchState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        matchVm.dismissError()
    }

    // rememberSaveable, not remember: court marking's exit does a bare
    // nav.popBackStack() back to this same destination, whose `attach` argument
    // is still set, so a plain `remember` flag (reset by that recomposition, and
    // lost across process death besides) let the picker reopen unprompted on
    // every return from court marking. Mirrors iOS's MatchView.attachHandled.
    var attachHandled by rememberSaveable { mutableStateOf(false) }

    // The picker is plumbed to this screen and nowhere else, so "attach a video to
    // this match" has one implementation and two entry points: this button, and the
    // prompt the board raises when a match finishes. Consumed once per push of this
    // destination, not once per recomposition - see attachHandled above.
    LaunchedEffect(attach) {
        if (attachHandled) return@LaunchedEffect
        when (attach) {
            "Import" -> { attachHandled = true; onAddVideo(AttachIntent.Import) }
            "Record" -> { attachHandled = true; onAddVideo(AttachIntent.Record) }
            else -> Unit
        }
    }

    val match = (state.ownedMatches + state.sharedMatches).firstOrNull { it.videoId == videoId }
    val clipsForMatch = sortClips(state.clips.filter { it.videoId == videoId }, sort)
    val log = matchState.log

    // The row this page is for, from the same list the Matches screen draws,
    // and the sections it has by the one shared rule: the chips on that row
    // are the toggles here. See matchPageSections.
    val matchItem = state.matchList.matchPageItem(scoreLogId, videoId, entryId)
    val sections = matchItem?.let(::matchPageSections).orEmpty()
    // Saveable, held for the life of this page and never cleared: a section
    // can go while on screen (the file removed from the phone, a re-analysis
    // clearing the clips),
    // and shownSection falls back without forgetting what the coach picked.
    // Saveable rather than remembered also keeps the choice across a return
    // from court marking or any other pushed destination.
    var chosenSection by rememberSaveable { mutableStateOf(initialSection) }
    val section = shownSection(chosenSection, sections)
    // The section first shown counts as chosen. Without this, a section
    // appearing moves the page: a video only on this phone opens on Match
    // alone, and when the run started from under its player lands, Rallies
    // becomes the default and the player is released mid-watch. The app does
    // not change the screen on its own (a finished run is announced, not
    // opened), and a section arriving is the same kind of jump.
    LaunchedEffect(section) {
        if (chosenSection == null && section != null) chosenSection = section
    }
    var videoFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = videoFullscreen && section == MatchPageSection.MATCH
    val entry = matchItem?.entryId?.let { id -> state.localEntries.firstOrNull { it.id == id } }
    // Under the player only for a video with no score log. A scored match's
    // video asks through AttachStatusBanner already; two buttons for one
    // question is the kind of drift the shared rule exists to stop.
    val analyzeLabel = matchItem?.takeIf { it.scoreLogId == null }?.action
        ?.takeIf { it == MatchAction.ANALYZE || it == MatchAction.RETRY }
        ?.let(MatchListCopy::actionLabel)

    Scaffold(
        topBar = {
            // Fullscreen video owns the whole screen: see MatchVideoSection.
            if (!fullscreen) TopAppBar(
                title = {
                    when {
                        // The list's own name for this match, so the page and
                        // the row one tap back cannot read as two matches.
                        matchItem != null -> Text(matchItem.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Until the row arrives (the first frame after a push):
                        // a match with a video owns its title, whether or not it
                        // also has a score log.
                        match != null -> Text(
                            match.title ?: "Match · ${formatDate(match.latestCreatedAt)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Score-only: same title as the screen this page replaced.
                        log != null -> Text(log.title, maxLines = 1)
                        // Nothing to name: a match that is gone, or the frame
                        // before its row arrives. The body says which.
                        else -> Unit
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // First in the bar so it keeps its place as each screen's own
                    // actions come and go.
                    BackgroundWorkAction()
                    if (match?.isOwned == true) {
                        IconButton(onClick = { sheetOpen = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share match")
                        }
                    }
                    if (matchState.canAddVideo) {
                        IconButton(onClick = { addVideoMenuOpen = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add video")
                        }
                        DropdownMenu(
                            expanded = addVideoMenuOpen,
                            onDismissRequest = { addVideoMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import video") },
                                onClick = { addVideoMenuOpen = false; onAddVideo(AttachIntent.Import) },
                            )
                            DropdownMenuItem(
                                text = { Text("Record video") },
                                onClick = { addVideoMenuOpen = false; onAddVideo(AttachIntent.Record) },
                            )
                        }
                    }
                    // A scored match, or a filmed one that is the coach's own: both
                    // have details to edit. A video only on this phone that can be
                    // analyzed again has that to offer. A shared match has none of
                    // these, so it gets no menu at all.
                    if (log != null || match?.isOwned == true || matchItem?.canAnalyzeAgain == true) {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Match options")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            // First, because it is the one a coach comes here for:
                            // the new match form asks for none of this, and a video
                            // is uploaded with a name at most.
                            if (log != null || match?.isOwned == true) {
                                DropdownMenuItem(
                                    text = { Text("Edit details") },
                                    onClick = { overflowOpen = false; editingDetails = true },
                                )
                            }
                            if (log != null) {
                                DropdownMenuItem(
                                    text = { Text("Export as text") },
                                    onClick = {
                                        overflowOpen = false
                                        shareText(context, log.title, exportMatchText(log))
                                    },
                                )
                            }
                            // The row's own menu item, here too: the same entry,
                            // through the same resume-or-mark-court decision.
                            matchItem?.takeIf { it.canAnalyzeAgain }?.entryId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(MatchListCopy.MENU_ANALYZE_AGAIN) },
                                    onClick = { overflowOpen = false; onAnalyzeEntry(id) },
                                )
                            }
                            // Offered whenever this match has a video and nothing
                            // is in flight for it - not only when the analysis
                            // disappointed. A video that found no rallies is the
                            // likeliest reason to want another one, but gating on
                            // that would leave the "Finishing up…" dead end with
                            // no action on it at all. See the 2026-08-29 design.
                            if (matchState.canRemoveVideo) {
                                DropdownMenuItem(
                                    text = { Text("Change video") },
                                    onClick = { overflowOpen = false; videoAction = MatchVideoAction.CHANGE },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove video") },
                                    onClick = { overflowOpen = false; videoAction = MatchVideoAction.REMOVE },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (matchItem == null) {
            // Gone (deleted elsewhere, a share left, or the file removed from
            // a match that was only on this phone), or not listed yet. Only
            // the first is worth words. The list is Home's, so once it has
            // finished one refresh (refreshGeneration counts completed ones,
            // failed included) a row it lacks is not coming: the list holds
            // everything it knows about. Before that, only the lookups that
            // need no network can tell.
            val gone = state.refreshGeneration > 0 ||
                (scoreLogId != null && log == null && match == null) ||
                (scoreLogId == null && videoId == null && entryId != null && state.localEntries.none { it.id == entryId })
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (gone) Text(MatchPageCopy.MISSING)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(if (fullscreen) PaddingValues() else padding)) {
            if (!fullscreen) {
                // Same status, same actions as the match list's own row - see
                // AttachStatusBanner. Without this the page was blind about an
                // attach in progress while simultaneously still offering "Add
                // video", because canAddVideo used to close only on a videoId.
                matchState.attach?.let { attach ->
                    AttachStatusBanner(attach = attach, onMarkCourt = onMarkCourt, onRetry = onRetry)
                }
                // A row over one section is a control that does nothing, and a
                // match with one section must look like a page, not a picker.
                if (sections.size > 1 && section != null) {
                    ShuttlPillTabs(
                        labels = sections.map { it.label },
                        selectedIndex = sections.indexOf(section),
                        onSelect = { chosenSection = sections[it] },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            // One call site for every section, in both layouts: the Match
            // section's player lives inside it, and moving the call would
            // rebuild the player on every trip into fullscreen and back.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SectionBody(
                    section = section,
                    refreshable = videoId != null,
                    isRefreshing = state.isRefreshing,
                    onRefresh = { vm.refresh(); summaryVm?.refresh() },
                ) {
                    when (section) {
                        MatchPageSection.POINTS -> LazyColumn(Modifier.fillMaxSize()) {
                            if (log != null) pointsFacet(
                                log = log, card = matchState.card, tally = matchState.tally,
                                points = matchState.match?.points.orEmpty(), onScore = onScore,
                            )
                        }
                        MatchPageSection.MATCH -> entry?.let {
                            matchSection(it, fullscreen, { on -> videoFullscreen = on }, snackbarHostState, analyzeLabel)
                        }
                        MatchPageSection.RALLIES -> {
                            // Rallies a device run cut and nothing in the cloud:
                            // those clips never leave the phone, so they are
                            // drawn from its disk rather than from rally_clips.
                            val deviceEntryId = matchItem.entryId
                            if (clipsForMatch.isEmpty() && matchItem.deviceClipCount > 0 && deviceEntryId != null) {
                                deviceRalliesSection(deviceEntryId)
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    // Only rallies are reordered, so the sort
                                    // lives with them; one rally has no order.
                                    if (clipsForMatch.size > 1) item(key = "sort") { RallySortRow(sort) { sort = it } }
                                    ralliesFacet(
                                        clips = clipsForMatch,
                                        summary = summary,
                                        matchTitle = match?.title,
                                        // The score log's details when there is one, the
                                        // video's own otherwise: a scored match that was later
                                        // filmed keeps the log as its record.
                                        details = matchDetailsLine(
                                            playersLine = log?.playersLine() ?: match?.playersLine,
                                            format = log?.format ?: match?.format,
                                        ),
                                        description = match?.description,
                                        media = media,
                                        isRefreshing = state.isRefreshing,
                                        onSummaryClick = { summarySheetOpen = true },
                                        onClipClick = onClipClick,
                                    )
                                }
                            }
                        }
                        MatchPageSection.ANALYSIS -> analysisId(matchItem)?.let { analysisSection(it) }
                        null -> Unit
                    }
                }
            }
        }
    }

    if (editingDetails) {
        when {
            log != null -> EditMatchDetailsDialog(
                initial = log.detailsDraft(),
                titleCap = MAX_MATCH_TITLE,
                onDismiss = { editingDetails = false },
                onSave = { draft ->
                    matchVm.updateDetails(draft.title, draft.format, draft.homePlayers, draft.awayPlayers)
                    editingDetails = false
                },
            )
            match != null -> EditMatchDetailsDialog(
                // Every field the video carries, the description included: it was
                // typed at upload and this is the only place it can change since.
                initial = MatchDetailsDraft(
                    title = match.title.orEmpty(),
                    description = match.description.orEmpty(),
                    format = match.format,
                    homePlayers = match.homePlayers,
                    awayPlayers = match.awayPlayers,
                ),
                titleCap = VIDEO_MATCH_TITLE_CAP,
                onDismiss = { editingDetails = false },
                onSave = { draft ->
                    matchVm.updateVideoDetails(
                        videoId = match.videoId,
                        title = draft.title,
                        description = draft.description.orEmpty(),
                        format = draft.format,
                        homePlayers = draft.homePlayers,
                        awayPlayers = draft.awayPlayers,
                    )
                    editingDetails = false
                },
            )
        }
    }

    videoAction?.let { action ->
        MatchVideoDialog(
            action = action,
            hasServerVideo = matchState.hasServerVideo,
            onDismiss = { videoAction = null },
            // Change is remove, then the picker this page already owns: once
            // removal lands the match has no video in either sense, so nothing
            // about the attach path changes. The source is chosen here rather
            // than in a second dialog afterwards.
            onConfirm = { intent ->
                videoAction = null
                matchVm.removeVideo(onRemoved = { intent?.let(onAddVideo) })
            },
        )
    }

    if (sheetOpen && videoId != null) {
        ShareSheet(
            videoId = videoId,
            sharesRepository = shares,
            onDismiss = { sheetOpen = false },
        )
    }

    val sheetSummary = summary
    if (summarySheetOpen && sheetSummary != null && !sheetSummary.isEmpty) {
        MatchSummarySheet(
            summary = sheetSummary,
            topRallyName = sheetSummary.topRally?.let {
                topRallyName(it, clipsForMatch, match?.title)
            },
            onTopRallyClick = {
                summarySheetOpen = false
                sheetSummary.topRally
                    ?.let { top -> clipsForMatch.firstOrNull { it.id == top.clipId } }
                    ?.let(onClipClick)
            },
            onDismiss = { summarySheetOpen = false },
        )
    }
}

/**
 * The confirm for both video gestures. What it says is built in shared
 * ([matchVideoPrompt]) rather than here, for the same reason [AttachStatus]'s
 * text is: two platforms writing the same sentence are two chances to write it
 * differently. It has to say the points survive, which is the opposite of the
 * match list's bound-match confirm, so that wording could not be reused.
 *
 * [onConfirm] carries the source for a change (null for a plain removal), chosen
 * on this dialog rather than in a second one after the video is already gone.
 * The three-button shape - Cancel and Record sharing the dismiss slot - is the
 * board's own "Add the video?" prompt, in `ScoringScreen.kt`.
 */
@Composable
private fun MatchVideoDialog(
    action: MatchVideoAction,
    hasServerVideo: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AttachIntent?) -> Unit,
) {
    val prompt = matchVideoPrompt(action, hasServerVideo)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = { Text(prompt.body) },
        confirmButton = {
            when (action) {
                MatchVideoAction.CHANGE ->
                    TextButton(onClick = { onConfirm(AttachIntent.Import) }) { Text("Import video") }
                MatchVideoAction.REMOVE ->
                    TextButton(onClick = { onConfirm(null) }) { Text("Remove") }
            }
        },
        // Cancel leftmost, then the two sources: commitment escalates left to
        // right, so the way out of a destructive confirm is not sitting between
        // two buttons that both go through with it.
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (action == MatchVideoAction.CHANGE) {
                    TextButton(onClick = { onConfirm(AttachIntent.Record) }) { Text("Record") }
                }
            }
        },
    )
}

/**
 * What the match list's row says about this match's video, repeated on the match
 * page itself: §4.9 of the 2026-08-28 design requires the page to say so and offer
 * the same actions, not leave the coach reading a page that says nothing while
 * "Add video" is also gone with no explanation. Same text, same three actions
 * (Mark court / Retry / a spinner) as the Matches tab's row for this match
 * ([com.badmintontracker.android.cliplist.MatchListRow]), both derived from the
 * one shared attach status so the two cannot say different things.
 */
@Composable
private fun AttachStatusBanner(
    attach: AttachStatus,
    onMarkCourt: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = attach.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (attach.kind == AttachKind.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        when (attach.kind) {
            AttachKind.COURT_NOT_MARKED ->
                ShuttlButton(text = "Mark court", onClick = onMarkCourt,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.FAILED ->
                ShuttlButton(text = "Retry", onClick = onRetry,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP ->
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
        }
    }
}

/**
 * Pull to refresh around the sections that show cloud data (Points for a
 * filmed match, Rallies), and nothing around the others: pulling on the Match
 * section's notes or on a heatmap refreshing the clip cache would be a
 * gesture that does nothing visible. A score-only match has no video to
 * refresh, as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionBody(
    section: MatchPageSection?,
    refreshable: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (refreshable && (section == MatchPageSection.POINTS || section == MatchPageSection.RALLIES)) {
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { content() }
    } else {
        content()
    }
}

/**
 * The rally sort, at the top of the Rallies section: it only reorders rallies,
 * so it lives with them rather than in a top bar every section shares.
 */
@Composable
private fun RallySortRow(sort: ClipSort, onSort: (ClipSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        // The menu shares a Box with its anchor: DropdownMenu positions itself
        // relative to its parent.
        val current = if (sort == ClipSort.RallyOrder) MatchPageCopy.SORT_RALLY_ORDER else MatchPageCopy.SORT_MOST_NOTES
        Box {
            TextButton(
                onClick = { open = true },
                shape = RoundedCornerShape(ShuttlRadius.pill),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
                // What the control does and the order it is on: the description
                // replaces the visible text for TalkBack, so it carries both.
                modifier = Modifier.semantics { contentDescription = "${MatchPageCopy.SORT}, $current" },
            ) {
                Icon(ShuttlIcons.ListOrdered, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(current, style = MaterialTheme.typography.labelMedium)
            }
            // What MenuDefaults resolves to, set explicitly: the theme maps
            // surfaceContainer to a palette token. The items likewise.
            val itemColors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                listOf(ClipSort.RallyOrder to MatchPageCopy.SORT_RALLY_ORDER, ClipSort.MostNotes to MatchPageCopy.SORT_MOST_NOTES)
                    .forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { if (sort == value) Icon(Icons.Default.Check, contentDescription = null) },
                            onClick = { onSort(value); open = false },
                            colors = itemColors,
                        )
                    }
            }
        }
    }
}
