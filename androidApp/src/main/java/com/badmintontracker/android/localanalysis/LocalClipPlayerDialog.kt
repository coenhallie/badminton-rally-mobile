package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Plays one locally cut clip.
 *
 * Exists so the two pipelines can be judged on what they actually produce.
 * Rally counts can match while the clips are cut at the wrong moment, and the
 * only way to see that is to watch them - the cloud's clips are already
 * playable in the app, so the local ones need to be too or the comparison is
 * numbers against video.
 *
 * Loops, because a rally is a few seconds long and judging a boundary usually
 * takes more than one viewing.
 */
@Composable
fun LocalClipPlayerDialog(clip: ClipCutter.Clip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(clip.file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip.file)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Rally ${clip.index}  ${"%.2f".format(clip.startSeconds)}s - " +
                        "${"%.2f".format(clip.endSeconds)}s",
                    style = MaterialTheme.typography.labelLarge,
                )
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
