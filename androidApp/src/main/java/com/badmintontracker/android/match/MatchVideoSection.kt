package com.badmintontracker.android.match

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.badmintontracker.android.clipdetail.AddAnnotationSheet
import com.badmintontracker.android.clipdetail.AnnotationRow
import com.badmintontracker.android.clipdetail.FullscreenLabels
import com.badmintontracker.android.clipdetail.NotesHeader
import com.badmintontracker.android.clipdetail.PlaybackErrorOverlay
import com.badmintontracker.android.clipdetail.PlaybackSettingsSheet
import com.badmintontracker.android.clipdetail.ReplayLabelRow
import com.badmintontracker.android.clipdetail.TransportBar
import com.badmintontracker.android.clipdetail.VideoCard
import com.badmintontracker.android.clipdetail.quickTagConfirmation
import com.badmintontracker.android.clipdetail.rememberQuickTagMessage
import com.badmintontracker.android.clipdetail.rememberPlaybackPosition
import com.badmintontracker.android.localvideo.LocalPlayerViewModel
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.frameExactPlayer
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

private const val ANNOTATION_STORAGE_NOTE =
    "Notes are saved on this phone and are removed if you remove the video from the app."

/**
 * The Match section: the full recording on this phone, with its timestamped
 * notes and the label chips, as LocalPlayerScreen showed it.
 *
 * Fullscreen is hoisted because, in fullscreen, the page drops its own bar,
 * status banner and section row and this section fills the screen: only the
 * page can do that. The player stays remembered here in both layouts, and the
 * page calls this from one place in both, so going fullscreen and back keeps
 * the position. Leaving the section disposes it, which releases the player.
 *
 * [analyzeLabel] is "Analyze" or "Retry" under the player while the match asks
 * for one (the row's own action), else null.
 */
@Composable
fun MatchVideoSection(
    vm: LocalPlayerViewModel,
    entry: LocalVideoEntry,
    playbackPrefs: PlaybackPreferenceRepository,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    analyzeLabel: String?,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val annotations by vm.state.collectAsStateWithLifecycle()
    val labelOptions by vm.labelOptions.collectAsStateWithLifecycle()
    val labelErrorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    val player = remember { frameExactPlayer(ctx) }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalAnnotation?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    // The snackbar host is the caller's, passed in so this section can show a
    // playback error through it. Fullscreen draws the video, the transport
    // bar and FullscreenLabels' tag row over a black background; the
    // LaunchedEffect below withholds the message while fullscreen and
    // re-checks it once fullscreen exits, unchanged from LocalPlayerScreen's
    // own behavior before this moved here.
    val tagMessage = rememberQuickTagMessage()

    LaunchedEffect(labelErrorMessage, fullscreen) {
        if (fullscreen) return@LaunchedEffect
        val msg = labelErrorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.errorShown()
    }

    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    FullscreenEffect(fullscreen)

    LaunchedEffect(orientation) {
        onFullscreenChange(orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) {
        // Local content:// grants can be revoked or the file deleted behind our
        // back - without this listener the player just freezes silently.
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

    val tag: (AnnotationLabel) -> Unit = { label ->
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        vm.addAnnotation(positionMs / 1000f, "", label)
        tagMessage.show(quickTagConfirmation(label, positionMs))
    }

    val video: @Composable (Boolean) -> Unit = { isFullscreenVideo ->
        VideoCard(
            player = player,
            position = position,
            onSettingsTap = { showSettings = true },
            fullscreen = isFullscreenVideo,
            onFullscreenToggle = { onFullscreenChange(!fullscreen) },
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

    if (fullscreen) {
        Box(modifier.fillMaxSize().background(Color.Black)) {
            video(true)
            TransportBar(
                player = player,
                prefs = playbackPrefs,
                onSettings = { showSettings = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                overVideo = true,
            )
            FullscreenLabels(labels = labelOptions, prefs = playbackPrefs, message = tagMessage.text, onTag = tag)
        }
    } else {
        Column(modifier.fillMaxSize()) {
            video(false)
            TransportBar(
                player = player,
                prefs = playbackPrefs,
                onSettings = { showSettings = true },
                modifier = Modifier.padding(top = 16.dp),
            )
            analyzeLabel?.let { label ->
                ShuttlButton(
                    text = label,
                    onClick = onAnalyze,
                    variant = ShuttlButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
            }
            ReplayLabelRow(
                labels = labelOptions,
                message = tagMessage.text,
                onTag = tag,
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
