package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun FrameStepBar(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    // Step by frame index, with two pieces of slack to survive ExoPlayer's
    // sub-millisecond position truncation:
    //   1. currentPosition is reported in integer ms but frame PTSes are
    //      microsecond-precise. Once a frame renders, position becomes the
    //      frame's PTS rounded DOWN — e.g. 33ms for frame 1 at 30fps (real
    //      33.333ms). Naive floor(pos/frameDurMs) then maps this back to
    //      frame 0, so the next "+1" seeks to frame 1 again — stuck. Adding
    //      half a frame before dividing makes the index robust to that
    //      truncation.
    //   2. Targeting an integer ms just past the frame's PTS guarantees
    //      EXACT seek lands inside that frame's display interval, not on
    //      the boundary with the previous frame.
    fun seekFrames(delta: Int) {
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val frameDurMs = 1000.0 / fps
        val currentFrame = ((player.currentPosition + frameDurMs / 2.0) / frameDurMs).toLong()
        val next = (currentFrame + delta).coerceAtLeast(0L)
        val targetMs = (next * frameDurMs + 1.0).toLong()
        val maxPos = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo(targetMs.coerceIn(0L, maxPos))
    }

    Row(modifier = modifier.fillMaxWidth()) {
        // Backward: tap = -1 frame. Hold = backward seek loop at ~10 steps/sec
        // (the cadence ExoPlayer's exact-seek pipeline can render between).
        TransportButton(
            text = "Previous frame",
            modifier = Modifier.weight(1f),
            onTap = {
                if (player.isPlaying) player.pause()
                seekFrames(-1)
            },
            onHoldTick = { seekFrames(-3) },
            holdTickPeriodMs = 100L,
        )
        // Forward: tap = +1 frame. Hold = real playback so the user sees
        // continuous motion rather than a stack of cancelled seeks.
        TransportButton(
            text = "Next frame",
            modifier = Modifier.weight(1f),
            onTap = {
                if (player.isPlaying) player.pause()
                seekFrames(1)
            },
            onHoldActivate = { player.play() },
            onRelease = { if (player.isPlaying) player.pause() },
        )
    }
}
