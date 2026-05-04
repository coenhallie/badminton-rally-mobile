package com.badmintontracker.android.clipdetail

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.badmintontracker.shared.model.RallyAnnotation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    vm: ClipDetailViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.clip?.title ?: state.clip?.let { "Rally #${it.rallyIndex + 1}" } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(
                    factory = { PlayerView(ctx).apply { this.player = player } },
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
                            Button(onClick = vm::onManualRetry) { Text("Retry") }
                        }
                    }
                }
            }

            if (state.annotations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No annotations on this clip.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.annotations, key = { it.id }) { a ->
                        AnnotationRow(a, onClick = { vm.onAnnotationTap(a) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(a: RallyAnnotation, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%.1fs".format(a.timestampSeconds), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(12.dp))
        Text(a.body, style = MaterialTheme.typography.bodyMedium)
    }
}
