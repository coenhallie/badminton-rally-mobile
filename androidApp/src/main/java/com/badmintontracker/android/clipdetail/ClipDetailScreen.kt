package com.badmintontracker.android.clipdetail

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlVideoOverlay
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    vm: ClipDetailViewModel,
    playbackPrefs: PlaybackPreferenceRepository,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.EXACT)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<RallyAnnotation?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    FullscreenEffect(isFullscreen)

    LaunchedEffect(orientation) {
        isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { vm.onPlayerError() }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state.signedClipUrl) {
        val url = state.signedClipUrl ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = false
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    LaunchedEffect(state.actionError) {
        val msg = state.actionError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearActionError()
    }

    val position by rememberPlaybackPosition(player)
    val speed by playbackPrefs.speed.collectAsStateWithLifecycle()
    val skipSeconds by playbackPrefs.skipSeconds.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val canAddNote = state.clip != null && state.isOwner
    val addNote: (() -> Unit)? = if (canAddNote) ({ addDialog = player.currentPosition.coerceAtLeast(0L) / 1000f }) else null

    val video: @Composable (Boolean) -> Unit = { fullscreen ->
        VideoCard(
            player = player,
            position = position,
            speed = speed,
            onSpeedTap = { showSettings = true },
            fullscreen = fullscreen,
            onFullscreenToggle = { isFullscreen = !isFullscreen },
            error = state.error?.let { message ->
                {
                    PlaybackErrorOverlay(message = message, onRetry = vm::onManualRetry)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(state.displayTitle ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    // Only on the non-fullscreen bar: fullscreen playback is
                    // deliberately chrome-free.
                    actions = { BackgroundWorkAction() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFullscreen) {
                video(false)
                TransportBar(
                    player = player,
                    prefs = playbackPrefs,
                    onSettings = { showSettings = true },
                    modifier = Modifier.padding(top = 16.dp),
                )
                NotesHeader(caption = "Notes", onAdd = addNote, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.annotations.isEmpty()) {
                Text(
                    "No notes on this clip.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.annotations, key = { it.id }) { a ->
                        AnnotationRow(
                            timestampSeconds = a.timestampSeconds,
                            body = a.body,
                            labelName = a.labelName,
                            labelColor = a.labelColor,
                            onClick = { vm.onAnnotationTap(a) },
                            onDelete = if (state.isOwner) ({ pendingDelete = a }) else null,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            video(true)
            TransportBar(
                player = player,
                prefs = playbackPrefs,
                onSettings = { showSettings = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }

    if (showSettings) {
        PlaybackSettingsSheet(
            skipSeconds = skipSeconds,
            speed = speed,
            onSkipSeconds = playbackPrefs::setSkipSeconds,
            onSpeed = playbackPrefs::setSpeed,
            onDismiss = { showSettings = false },
        )
    }

    addDialog?.let { ts ->
        AddAnnotationSheet(
            labels = state.labels,
            onDismiss = { addDialog = null },
            onConfirm = { body, label ->
                vm.addAnnotation(ts, body, label)
                addDialog = null
            },
        )
    }

    pendingDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete note?") },
            text = { Text(if (a.body.isNotBlank()) "\"${a.body}\"" else a.labelName.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAnnotation(a.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * What the card shows in place of the frame when playback failed: the
 * message, and a retry. Over the card rather than instead of it, so the
 * transport under it stays where it was.
 */
@Composable
internal fun PlaybackErrorOverlay(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = ShuttlVideoOverlay.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        ShuttlButton(text = "Retry", onClick = onRetry, variant = ShuttlButtonVariant.Primary, compact = true)
    }
}
