package com.badmintontracker.android.localvideo

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.R
import com.badmintontracker.android.clipdetail.AddAnnotationSheet
import com.badmintontracker.android.clipdetail.AnnotationRow
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant

private const val ANNOTATION_STORAGE_NOTE =
    "Annotations are saved on this phone and are removed if you remove the video from the app."

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
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val annotations by vm.state.collectAsStateWithLifecycle()
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    var isFullscreen by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalAnnotation?>(null) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    FullscreenEffect(isFullscreen)

    LaunchedEffect(orientation) {
        isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(entry.uri) {
        player.setMediaItem(MediaItem.fromUri(entry.uri))
        player.prepare()
        player.playWhenReady = false
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    val playerSurface: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            factory = { c ->
                val view = LayoutInflater.from(c)
                    .inflate(R.layout.clip_player_view, null) as PlayerView
                view.apply {
                    this.player = player
                    setFullscreenButtonClickListener { isFullscreen = !isFullscreen }
                    controllerShowTimeoutMs = 1500
                    controllerAutoShow = false
                    hideController()
                }
            },
            update = { it.setFullscreenButtonState(isFullscreen) },
            modifier = modifier,
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (canAnalyze) {
                            ShuttlButton(
                                text = "Analyze",
                                onClick = onAnalyze,
                                variant = ShuttlButtonVariant.Primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isFullscreen) {
                FloatingActionButton(onClick = {
                    addDialog = (player.currentPosition.coerceAtLeast(0L)) / 1000f
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add annotation")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFullscreen) {
                playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                FrameStepBar(player = player, modifier = Modifier.padding(vertical = 8.dp))

                if (annotations.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ANNOTATION_STORAGE_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        ANNOTATION_STORAGE_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(annotations, key = { it.id }) { a ->
                            AnnotationRow(
                                timestampSeconds = a.timestampSeconds,
                                body = a.body,
                                kind = a.kind,
                                onClick = { vm.onAnnotationTap(a) },
                                onDelete = { pendingDelete = a },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            playerSurface(Modifier.fillMaxSize())
            FrameStepBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }

    addDialog?.let { ts ->
        AddAnnotationSheet(
            onDismiss = { addDialog = null },
            onConfirm = { body, kind ->
                vm.addAnnotation(ts, body, kind)
                addDialog = null
            },
        )
    }

    pendingDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete annotation?") },
            text = { Text(if (a.body.isNotBlank()) "\"${a.body}\"" else "This annotation") },
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
