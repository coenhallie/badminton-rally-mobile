package com.badmintontracker.android.cliplist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.android.share.ShareSheet
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchClipsScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    shares: SharesRepository,
    videoId: String,
    themePrefs: ThemePreferenceRepository,
    onBack: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.dismissError()
    }

    val match = (state.ownedMatches + state.sharedMatches).firstOrNull { it.videoId == videoId }
    val clipsForMatch = state.clips
        .filter { it.videoId == videoId }
        .sortedBy { it.rallyIndex }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = match
                        ?.let { "MATCH · ${formatDate(it.latestCreatedAt).uppercase(Locale.ROOT)}" }
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
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (clipsForMatch.isEmpty() && !state.isRefreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No rallies in this match.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(clipsForMatch, key = { it.id }) { clip ->
                        ClipRow(clip, media, onClick = { onClipClick(clip) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ShareSheet(
            videoId = videoId,
            sharesRepository = shares,
            onDismiss = { sheetOpen = false },
        )
    }
}

enum class ClipSort { RallyOrder, MostNotes }

fun sortClips(clips: List<RallyClip>, sort: ClipSort): List<RallyClip> = when (sort) {
    ClipSort.RallyOrder -> clips.sortedBy { it.rallyIndex }
    ClipSort.MostNotes -> clips.sortedWith(
        compareByDescending<RallyClip> { it.annotationCount }.thenBy { it.rallyIndex },
    )
}
