package com.badmintontracker.android.localanalysis

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.R
import com.badmintontracker.android.clipdetail.PlaybackErrorOverlay
import com.badmintontracker.android.ui.theme.ShuttlRadius

/**
 * Plays one locally cut clip, under [title].
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
fun LocalClipPlayerDialog(clip: ClipCutter.Clip, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipUri = remember(clip.file.path) { android.net.Uri.fromFile(clip.file) }
    val player = remember(clip.file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clipUri))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    var measuredRatio by remember(player) { mutableStateOf<Float?>(null) }
    var playbackError by remember(player) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        fun ratioOf(size: VideoSize): Float? =
            if (size.width > 0 && size.height > 0) {
                (size.width.toFloat() / size.height) * size.pixelWidthHeightRatio
            } else {
                null
            }
        measuredRatio = ratioOf(player.videoSize)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                ratioOf(videoSize)?.let { measuredRatio = it }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Couldn't play this clip. The file may have been removed."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleStartEffect(player) {
        onStopOrDispose { player.pause() }
    }

    val aspectRatio = (measuredRatio ?: (16f / 9f)).coerceIn(0.5f, 3f)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The caller's name for the clip, the one on the row that
                // opened it; it had printed its own bounds to two decimals
                // under a row that said something else.
                Text(title, style = MaterialTheme.typography.labelLarge)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(aspectRatio)
                            .clip(RoundedCornerShape(ShuttlRadius.medium))
                            .background(Color.Black),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                // TextureView-backed layout (same as VideoCard) so dialog
                                // animations never leave a SurfaceView ghost frame behind.
                                (LayoutInflater.from(ctx).inflate(R.layout.clip_player_view, null) as PlayerView).apply {
                                    this.player = player
                                }
                            },
                            onRelease = { it.player = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                        playbackError?.let { message ->
                            PlaybackErrorOverlay(
                                message = message,
                                onRetry = {
                                    playbackError = null
                                    player.setMediaItem(MediaItem.fromUri(clipUri))
                                    player.prepare()
                                    player.playWhenReady = true
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
