package com.badmintontracker.android.match

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.MatchSummarySheet
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.cliplist.topRallyName
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.scoring.AttachIntent
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.shareText
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.AttachStatus
import com.badmintontracker.shared.scoring.MatchVideoAction
import com.badmintontracker.shared.scoring.exportMatchText
import com.badmintontracker.shared.scoring.matchVideoPrompt
import java.util.Locale

/** Which half of a match page is on screen. Only meaningful when the match has both. */
private enum class Facet { Points, Rallies }

/**
 * One match, however it was made: a video-first or shared match shows only the
 * rallies facet, a scored match with no video shows only the points facet, and a
 * match with both gets a selector between them. [summaryVm] is nullable because a
 * match with no video has no clips to summarise - see the caller for how the
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
    themePrefs: ThemePreferenceRepository,
    scoreLogId: String?,
    videoId: String?,
    /** The intent carried by [com.badmintontracker.android.nav.Route.Match]'s `attach`
     *  argument - "Import" or "Record" chosen on the board's finish prompt, or null. */
    attach: String?,
    onBack: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
    onScore: () -> Unit,
    onAddVideo: (AttachIntent) -> Unit,
    onMarkCourt: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val matchState by matchVm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var addVideoMenuOpen by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ClipSort.RallyOrder) }
    val summary by summaryVm?.summary?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MatchLabelSummary?>(null) }
    var summarySheetOpen by remember { mutableStateOf(false) }
    // Which of the two video gestures is awaiting its confirm, or null. Both are
    // irreversible, and both are behind the same dialog - see MatchVideoDialog.
    var videoAction by remember { mutableStateOf<MatchVideoAction?>(null) }
    val context = LocalContext.current

    // A summary that goes away while its sheet is open must close the sheet, not
    // leave the flag standing to reopen it when the next summary arrives.
    LaunchedEffect(summary) {
        if (summary?.isEmpty != false) summarySheetOpen = false
    }

    // Covers the case the clip-set trigger cannot see: a note added inside
    // ClipDetail and then a back press.
    LifecycleResumeEffect(Unit) {
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

    // Shown only when the match has both. A selector over one facet is a control
    // that does nothing, and a match that has only rallies must look exactly like
    // it did before this page existed.
    val hasPoints = log != null
    val hasRallies = clipsForMatch.isNotEmpty()
    // Held for the life of this composable, not re-keyed on hasPoints/hasRallies:
    // clipsForMatch briefly empties during a refresh, and re-keying on that would
    // silently snap a reader on the rallies facet back to Points mid-read.
    // rememberSaveable, not remember: returning from court marking (or any other
    // pushed destination) must not snap a reader on the rallies facet back to
    // Points. Matches iOS, where @State survives that return for free.
    var chosenFacet by rememberSaveable { mutableStateOf(if (hasPoints) Facet.Points else Facet.Rallies) }
    // What actually renders: the user's choice when it is still available, else
    // whichever facet the match currently has.
    val facet = when {
        chosenFacet == Facet.Points && hasPoints -> Facet.Points
        chosenFacet == Facet.Rallies && hasRallies -> Facet.Rallies
        hasPoints -> Facet.Points
        else -> Facet.Rallies
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        // A match with a video - own its title, whether or not it
                        // also has a score log. Matches the rallies-only screen this
                        // page replaced.
                        match != null -> {
                            val titleText = match.title?.uppercase(Locale.ROOT)
                                ?: "MATCH · ${formatDate(match.latestCreatedAt).uppercase(Locale.ROOT)}"
                            Text(
                                titleText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                            )
                        }
                        // Score-only: same title as the screen this page replaced.
                        log != null -> Text(log.title, maxLines = 1)
                        else -> Text(
                            "RALLIES",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                        )
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
                    // The sort order only means something while rallies are on screen.
                    if (facet == Facet.Rallies) {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rally order") },
                                leadingIcon = {
                                    if (sort == ClipSort.RallyOrder) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = { sort = ClipSort.RallyOrder; sortMenuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Most notes") },
                                leadingIcon = {
                                    if (sort == ClipSort.MostNotes) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = { sort = ClipSort.MostNotes; sortMenuOpen = false },
                            )
                        }
                    }
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
                    if (log != null) {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Match options")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export as text") },
                                onClick = {
                                    overflowOpen = false
                                    shareText(context, log.title, exportMatchText(log))
                                },
                            )
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
                    ThemeToggleButton(
                        mode = themeMode,
                        onToggle = themePrefs::toggle,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // The score log this page was opened for is gone - deleted elsewhere while
        // it was open, or never synced to this device. A video-first match with no
        // log at all is not this state: `hasPoints` is false for it and it falls
        // straight through to the rallies facet below, same as before this page
        // existed.
        if (scoreLogId != null && log == null && match == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This match is no longer on this phone.")
            }
            return@Scaffold
        }

        // A pull gesture that spins and refreshes nothing is worse than no gesture:
        // a score-only match has no video, so neither vm (clips) nor summaryVm
        // (null here) has anything for onRefresh to touch. Only a video-backed
        // match - the same condition that gives it a summaryVm - keeps the old
        // MatchClipsScreen's pull-to-refresh.
        val pageBody: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.fillMaxSize()) {
                // Same status, same actions as the match list's own row - see
                // AttachStatusBanner. Without this the page was blind about an
                // attach in progress while simultaneously still offering "Add
                // video", because canAddVideo used to close only on a videoId.
                matchState.attach?.let { attach ->
                    AttachStatusBanner(attach = attach, onMarkCourt = onMarkCourt, onRetry = onRetry)
                }
                if (hasPoints && hasRallies) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        listOf(Facet.Points to "Points", Facet.Rallies to "Rallies")
                            .forEachIndexed { index, (candidate, label) ->
                                SegmentedButton(
                                    selected = facet == candidate,
                                    onClick = { chosenFacet = candidate },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(label) }
                            }
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (facet == Facet.Points && log != null) {
                        pointsFacet(
                            log = log,
                            card = matchState.card,
                            tally = matchState.tally,
                            points = matchState.match?.points.orEmpty(),
                            onScore = onScore,
                        )
                    } else {
                        ralliesFacet(
                            clips = clipsForMatch,
                            summary = summary,
                            matchTitle = match?.title,
                            description = match?.description,
                            media = media,
                            isRefreshing = state.isRefreshing,
                            onSummaryClick = { summarySheetOpen = true },
                            onClipClick = onClipClick,
                        )
                    }
                }
            }
        }

        if (videoId != null) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh(); summaryVm?.refresh() },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                pageBody(Modifier)
            }
        } else {
            pageBody(Modifier.padding(padding))
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
 * (Mark court / Retry / a spinner) as [ScoreMatchRow] in ClipListScreen.kt - see
 * that file's doc comment for why sharing the derivation matters.
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
