package com.badmintontracker.android.localvideo

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.badmintontracker.android.clipdetail.AddAnnotationSheet
import com.badmintontracker.android.clipdetail.AnnotationRow
import com.badmintontracker.android.clipdetail.NotesHeader
import com.badmintontracker.android.clipdetail.PlaybackErrorOverlay
import com.badmintontracker.android.clipdetail.PlaybackSettingsSheet
import com.badmintontracker.android.clipdetail.TransportBar
import com.badmintontracker.android.clipdetail.VideoCard
import com.badmintontracker.android.clipdetail.rememberPlaybackPosition
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

private const val ANNOTATION_STORAGE_NOTE =
    "Notes are saved on this phone and are removed if you remove the video from the app."

/**
 * Plays a local recording from its content:// URI with the same frame-step and
 * fullscreen behavior as analyzed clips, plus on-phone timestamped annotations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(
    vm: LocalPlayerViewModel,
    entry: LocalVideoEntry,
    canAnalyze: Boolean,
    playbackPrefs: PlaybackPreferenceRepository,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val annotations by vm.state.collectAsStateWithLifecycle()
    val labelOptions by vm.labelOptions.collectAsStateWithLifecycle()
    val labelErrorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    var isFullscreen by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalAnnotation?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // The snackbar host lives on the Scaffold, which fullscreen playback
    // overlays with an opaque Box - showing it while isFullscreen is true
    // would burn the message invisibly. Hold it and re-check when fullscreen
    // exits instead of firing once and losing it.
    LaunchedEffect(labelErrorMessage, isFullscreen) {
        if (isFullscreen) return@LaunchedEffect
        val msg = labelErrorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.errorShown()
    }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    FullscreenEffect(isFullscreen)

    LaunchedEffect(orientation) {
        isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) {
        // Local content:// grants can be revoked or the file deleted behind our
        // back — without this listener the player just freezes silently.
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Couldn't play this video. The file may have been moved or deleted."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(entry.uri) {
        player.setMediaItem(MediaItem.fromUri(entry.uri))
        player.prepare()
        player.playWhenReady = false
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    val position by rememberPlaybackPosition(player)
    val speed by playbackPrefs.speed.collectAsStateWithLifecycle()
    val skipSeconds by playbackPrefs.skipSeconds.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val video: @Composable (Boolean) -> Unit = { fullscreen ->
        VideoCard(
            player = player,
            position = position,
            speed = speed,
            onSpeedTap = { showSettings = true },
            fullscreen = fullscreen,
            onFullscreenToggle = { isFullscreen = !isFullscreen },
            error = playbackError?.let { message ->
                {
                    PlaybackErrorOverlay(
                        message = message,
                        onRetry = {
                            playbackError = null
                            player.setMediaItem(MediaItem.fromUri(entry.uri))
                            player.prepare()
                        },
                    )
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            entry.title ?: entry.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (canAnalyze) {
                            ShuttlButton(
                                text = analyzeButtonLabel(entry.stage),
                                onClick = onAnalyze,
                                variant = ShuttlButtonVariant.Primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // Starting a run from here returns to this screen, so this
                        // is where the run has to be visible. Every other screen
                        // that can start one already carries this.
                        BackgroundWorkAction()
                    },
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
                entry.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp),
                    )
                }
                // The caption doubles as the notes' heading: where they live is
                // the one thing a coach needs to know before writing one.
                NotesHeader(
                    caption = ANNOTATION_STORAGE_NOTE,
                    onAdd = { addDialog = player.currentPosition.coerceAtLeast(0L) / 1000f },
                    modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(annotations, key = { it.id }) { a ->
                        AnnotationRow(
                            timestampSeconds = a.timestampSeconds,
                            body = a.body,
                            labelName = a.labelName,
                            labelColor = a.labelColor,
                            onClick = { vm.onAnnotationTap(a) },
                            onDelete = { pendingDelete = a },
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
            labels = labelOptions,
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
            text = { Text(if (a.body.isNotBlank()) "\"${a.body}\"" else "This note") },
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
