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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.R
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant

/**
 * Plays a local (not yet analyzed) recording straight from its content:// URI,
 * with the same frame-step and fullscreen behavior as analyzed clips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(
    entry: LocalVideoEntry,
    canAnalyze: Boolean,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.EXACT)
        }
    }
    var isFullscreen by remember { mutableStateOf(false) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    FullscreenEffect(isFullscreen)

    LaunchedEffect(orientation) {
        isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(entry.uri) {
        player.setMediaItem(MediaItem.fromUri(entry.uri))
        player.prepare()
        player.playWhenReady = false
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
                    title = {
                        Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
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
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFullscreen) {
                playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                FrameStepBar(
                    player = player,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
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
}
