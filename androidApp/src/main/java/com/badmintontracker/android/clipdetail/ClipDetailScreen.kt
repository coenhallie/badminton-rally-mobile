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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.RallyAnnotation
import kotlinx.coroutines.launch

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
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(a.window, a.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
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
                        controllerShowTimeoutMs = 1500
                        controllerAutoShow = false
                        hideController()
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
            if (!isFullscreen && state.clip != null && state.isOwner) {
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
                FrameStepBar(
                    player = player,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
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
                            onDelete = if (state.isOwner) ({ pendingDelete = a }) else null,
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
            FrameStepBar(
                player = player,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }

    addDialog?.let { ts ->
        AddAnnotationDialog(
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
            text = { Text(if (a.body.isNotBlank()) "\"${a.body}\"" else a.kind?.style()?.label.orEmpty()) },
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

private fun formatTimestamp(seconds: Float): String {
    val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun AnnotationRow(a: RallyAnnotation, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimestamp(a.timestampSeconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        a.kind?.let { kind ->
            val s = kind.style()
            Surface(
                shape = RoundedCornerShape(50),
                color = s.container,
            ) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = s.onContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (a.body.isNotBlank()) {
            Text(
                a.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete annotation")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAnnotationDialog(
    onDismiss: () -> Unit,
    onConfirm: (body: String, kind: AnnotationKind?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<AnnotationKind?>(null) }
    val canAdd = kind != null || body.isNotBlank()

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add annotation", style = MaterialTheme.typography.titleLarge)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KindChip("Good shot",      AnnotationKind.GOOD_SHOT,      kind) { kind = if (kind == it) null else it }
                KindChip("Forced error",   AnnotationKind.FORCED_ERROR,   kind) { kind = if (kind == it) null else it }
                KindChip("Unforced error", AnnotationKind.UNFORCED_ERROR, kind) { kind = if (kind == it) null else it }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Note (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { hideThen { onDismiss() } }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canAdd,
                    onClick = { hideThen { onConfirm(body, kind) } },
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun KindChip(
    label: String,
    target: AnnotationKind,
    selected: AnnotationKind?,
    onClick: (AnnotationKind) -> Unit,
) {
    val s = target.style()
    val isSelected = selected == target
    FilterChip(
        selected = isSelected,
        onClick = { onClick(target) },
        label = { Text(label) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = s.container,
            selectedLabelColor = s.onContainer,
        ),
    )
}
