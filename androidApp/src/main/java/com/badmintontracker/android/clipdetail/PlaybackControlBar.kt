package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.badmintontracker.shared.playback.SkipMath
import com.badmintontracker.shared.prefs.PlaybackOptions
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

/**
 * Skip back / speed / skip forward, sitting directly above [FrameStepBar].
 *
 * The skip buttons are ours rather than PlayerView's built-in ones because
 * ExoPlayer bakes its seek increments in at Builder time: a preference change
 * could only reach them by rebuilding the player and losing the playhead.
 */
@Composable
fun PlaybackControlBar(
    player: ExoPlayer,
    prefs: PlaybackPreferenceRepository,
    modifier: Modifier = Modifier,
) {
    val skipSeconds by prefs.skipSeconds.collectAsStateWithLifecycle()
    val speed by prefs.speed.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    // Keyed on the player too: the preference is app-wide, so a speed chosen on
    // another clip has to be applied as soon as this player exists, not only
    // when the user next changes it.
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }

    fun skip(direction: Int) {
        player.seekTo(
            SkipMath.targetMs(
                positionMs = player.currentPosition,
                deltaSeconds = direction * skipSeconds,
                durationMs = player.duration,
            )
        )
    }

    Row(modifier = modifier.fillMaxWidth()) {
        TransportButton(
            text = "◀◀ ${skipSeconds}s",
            contentDescription = "Skip back $skipSeconds seconds",
            modifier = Modifier.weight(1f),
            onTap = { skip(-1) },
            onHoldActivate = { showSettings = true },
        )
        TransportButton(
            text = PlaybackOptions.formatSpeed(speed),
            contentDescription = "Playback settings, speed ${PlaybackOptions.formatSpeed(speed)}",
            modifier = Modifier.weight(1f),
            onTap = { showSettings = true },
        )
        TransportButton(
            text = "${skipSeconds}s ▶▶",
            contentDescription = "Skip forward $skipSeconds seconds",
            modifier = Modifier.weight(1f),
            onTap = { skip(1) },
            onHoldActivate = { showSettings = true },
        )
    }

    if (showSettings) {
        PlaybackSettingsSheet(
            skipSeconds = skipSeconds,
            speed = speed,
            onSkipSeconds = prefs::setSkipSeconds,
            onSpeed = prefs::setSpeed,
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSettingsSheet(
    skipSeconds: Int,
    speed: Float,
    onSkipSeconds: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Unset, this resolves M3's surfaceContainerLow, which ShuttlColors.kt
        // never sets. See AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Playback", style = MaterialTheme.typography.titleLarge)

            Text("Speed", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackOptions.speedOptions.forEach { option ->
                    FilterChip(
                        selected = option == speed,
                        onClick = { onSpeed(option) },
                        label = { Text(PlaybackOptions.formatSpeed(option)) },
                    )
                }
            }

            Text("Skip interval", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackOptions.skipSecondsOptions.forEach { option ->
                    FilterChip(
                        selected = option == skipSeconds,
                        onClick = { onSkipSeconds(option) },
                        label = { Text("${option}s") },
                    )
                }
            }
        }
    }
}
