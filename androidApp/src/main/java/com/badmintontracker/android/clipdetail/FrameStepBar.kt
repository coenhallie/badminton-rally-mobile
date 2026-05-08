package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.badmintontracker.android.ui.theme.ShuttlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun FrameStepBar(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    fun frameMs(): Long {
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        return (1000f / fps).toLong().coerceAtLeast(1L)
    }

    fun seekRel(deltaMs: Long) {
        val maxPos = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, maxPos)
        player.seekTo(target)
    }

    Row(modifier = modifier.fillMaxWidth()) {
        // Backward: tap = -1 frame. Hold = backward seek loop at ~10 steps/sec
        // (the cadence ExoPlayer's exact-seek pipeline can render between).
        HoldButton(
            text = "Previous frame",
            modifier = Modifier.weight(1f),
            onTap = {
                if (player.isPlaying) player.pause()
                seekRel(-frameMs())
            },
            onHoldTick = { seekRel(-100L) },
            holdTickPeriodMs = 100L,
        )
        // Forward: tap = +1 frame. Hold = real playback so the user sees
        // continuous motion rather than a stack of cancelled seeks.
        HoldButton(
            text = "Next frame",
            modifier = Modifier.weight(1f),
            onTap = {
                if (player.isPlaying) player.pause()
                seekRel(frameMs())
            },
            onHoldActivate = { player.play() },
            onRelease = { if (player.isPlaying) player.pause() },
        )
    }
}

@Composable
private fun HoldButton(
    text: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    holdActivationDelayMs: Long = 400L,
    onHoldActivate: () -> Unit = {},
    onHoldTick: () -> Unit = {},
    holdTickPeriodMs: Long = 100L,
    onRelease: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val bg = ShuttlTheme.extended.bgTertiary
    val pressedBg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = modifier
            .background(if (pressed) pressedBg else bg)
            .border(width = 1.dp, color = borderColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onTap()
                        val holdJob = scope.launch {
                            delay(holdActivationDelayMs)
                            onHoldActivate()
                            while (isActive) {
                                onHoldTick()
                                delay(holdTickPeriodMs)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            holdJob.cancel()
                            pressed = false
                            onRelease()
                        }
                    },
                )
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
