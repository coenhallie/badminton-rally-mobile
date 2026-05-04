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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    onClipClick: (RallyClip) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.dismissError()
    }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clips") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menuOpen = false; vm.signOut() },
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
            if (state.clips.isEmpty() && !state.isRefreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No clips yet. Record one in the desktop app.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.clips, key = { it.id }) { clip ->
                        ClipRow(clip, media, onClick = { onClipClick(clip) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(
    clip: RallyClip,
    media: MediaRepository,
    onClick: () -> Unit,
) {
    val thumbUrl by produceState<String?>(initialValue = null, clip.id) {
        value = runCatching { media.signedThumbnailUrl(clip) }.getOrNull()
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
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
                clip.title ?: "Rally #${clip.rallyIndex + 1}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${clip.durationSeconds}s · ${clip.annotationCount} notes",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
