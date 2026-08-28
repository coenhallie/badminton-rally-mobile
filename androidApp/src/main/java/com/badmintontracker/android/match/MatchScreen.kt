package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.MatchSummarySheet
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.cliplist.topRallyName
import com.badmintontracker.android.scoring.AttachIntent
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.shareText
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.scoring.exportMatchText
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

    // The picker is plumbed to this screen and nowhere else, so "attach a video to
    // this match" has one implementation and two entry points: this button, and the
    // prompt the board raises when a match finishes.
    LaunchedEffect(attach) {
        when (attach) {
            "Import" -> onAddVideo(AttachIntent.Import)
            "Record" -> onAddVideo(AttachIntent.Record)
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
    var chosenFacet by remember { mutableStateOf(if (hasPoints) Facet.Points else Facet.Rallies) }
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
