package com.badmintontracker.android.clipdetail

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.shared.model.RallyAnnotation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    vm: ClipDetailViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val orientation = LocalConfiguration.current.orientation
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val snackbar = remember { SnackbarHostState() }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<RallyAnnotation?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    LaunchedEffect(isFullscreen, activity) {
        val a = activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (isFullscreen) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

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
        player.playWhenReady = true
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    LaunchedEffect(state.actionError) {
        val msg = state.actionError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearActionError()
    }

    val playerSurface: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier = modifier) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        setFullscreenButtonClickListener { isFullscreen = !isFullscreen }
                    }
                },
                update = { it.setFullscreenButtonState(isFullscreen) },
                modifier = Modifier.fillMaxSize(),
            )
            if (state.error != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        ShuttlButton(
                            text = "Retry",
                            onClick = vm::onManualRetry,
                            variant = ShuttlButtonVariant.Primary,
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(state.clip?.title ?: state.clip?.let { "Rally #${it.rallyIndex}" } ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isFullscreen && state.clip != null) {
                FloatingActionButton(onClick = {
                    val ms = player.currentPosition.coerceAtLeast(0L)
                    addDialog = ms / 1000f
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add annotation")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFullscreen) {
                playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }

            if (state.annotations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No annotations on this clip.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.annotations, key = { it.id }) { a ->
                        AnnotationRow(
                            a = a,
                            onClick = { vm.onAnnotationTap(a) },
                            onDelete = { pendingDelete = a },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            playerSurface(Modifier.fillMaxSize())
        }
    }

    addDialog?.let { ts ->
        AddAnnotationDialog(
            onDismiss = { addDialog = null },
            onConfirm = { body ->
                vm.addAnnotation(ts, body)
                addDialog = null
            },
        )
    }

    pendingDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete annotation?") },
            text = { Text("\"${a.body}\"") },
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

@Composable
private fun AnnotationRow(a: RallyAnnotation, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            a.body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete annotation")
        }
    }
}

@Composable
private fun AddAnnotationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add annotation") },
        text = {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Note") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = body.isNotBlank(),
                onClick = { onConfirm(body) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
