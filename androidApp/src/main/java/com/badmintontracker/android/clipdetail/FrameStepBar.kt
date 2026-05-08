package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
    val step: (Int) -> Unit = { dir ->
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val frameMs = (1000f / fps).toLong().coerceAtLeast(1L)
        val maxPos = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + dir * frameMs).coerceIn(0L, maxPos)
        if (player.isPlaying) player.pause()
        player.seekTo(target)
    }

    Row(modifier = modifier.fillMaxWidth()) {
        RepeatingTextButton(
            text = "Previous frame",
            onStep = { step(-1) },
            modifier = Modifier.weight(1f),
        )
        RepeatingTextButton(
            text = "Next frame",
            onStep = { step(+1) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RepeatingTextButton(
    text: String,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    initialDelayMs: Long = 400L,
    repeatPeriodMs: Long = 60L,
) {
    val scope = rememberCoroutineScope()
    val bg = ShuttlTheme.extended.bgTertiary
    val fg = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = modifier
            .background(bg)
            .border(width = 1.dp, color = borderColor)
            .pointerInput(onStep) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        val job = scope.launch {
                            onStep()
                            delay(initialDelayMs)
                            while (isActive) {
                                onStep()
                                delay(repeatPeriodMs)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                    }
                }
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
