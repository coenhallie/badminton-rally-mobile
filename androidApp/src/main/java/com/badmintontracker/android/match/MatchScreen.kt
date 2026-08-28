package com.badmintontracker.android.match

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummaryViewModel
import com.badmintontracker.android.cliplist.MatchSummarySheet
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.cliplist.topRallyName
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import java.util.Locale

/**
 * One match, however it was made: today this renders only the rallies facet,
 * the points facet (score events, set summary) lands in the same list in the
 * next task. [summaryVm] is nullable because a match with no video has no
 * clips to summarise - see the caller for how the effective video id is
 * resolved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(
    vm: ClipListViewModel,
    summaryVm: MatchSummaryViewModel?,
    media: MediaRepository,
    shares: SharesRepository,
    themePrefs: ThemePreferenceRepository,
    scoreLogId: String?,
    videoId: String?,
    onBack: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ClipSort.RallyOrder) }
    val summary by summaryVm?.summary?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MatchLabelSummary?>(null) }
    var summarySheetOpen by remember { mutableStateOf(false) }

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

    val match = (state.ownedMatches + state.sharedMatches).firstOrNull { it.videoId == videoId }
    val clipsForMatch = sortClips(state.clips.filter { it.videoId == videoId }, sort)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = match
                        ?.let {
                            it.title?.uppercase(Locale.ROOT)
                                ?: "MATCH · ${formatDate(it.latestCreatedAt).uppercase(Locale.ROOT)}"
                        }
                        ?: "RALLIES"
                    Text(
                        titleText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
                    if (match?.isOwned == true) {
                        IconButton(onClick = { sheetOpen = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Share match")
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
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { vm.refresh(); summaryVm?.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
