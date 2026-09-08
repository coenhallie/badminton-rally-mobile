package com.badmintontracker.android.clipdetail

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.R
import com.badmintontracker.android.localvideo.formatDuration
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlVideoOverlay
import com.badmintontracker.shared.prefs.PlaybackOptions

/** How far a pinch can take the frame. Enough to fill the card with one player at the far baseline. */
private const val MAX_ZOOM = 5f

/** What a double tap zooms to. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Where the playhead is and how long the media is, in milliseconds; duration is zero until the player knows it. */
data class PlaybackPosition(val positionMs: Long, val durationMs: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The player's position, read once per display frame.
 *
 * Polled rather than waited on, because Media3 has no per-frame position
 * callback, and a coach stepping frame by frame needs the timecode, the
 * progress bar and any overlay to move with the frame, not a few hundred
 * milliseconds after it. This keeps the Recomposer non-idle for as long as
 * the caller is composed, so a Compose UI test of such a screen must not wait
 * for idle: it never will.
 */
@Composable
fun rememberPlaybackPosition(player: ExoPlayer): State<PlaybackPosition> {
    val state = remember(player) { mutableStateOf(PlaybackPosition(0L, 0L)) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            state.value = PlaybackPosition(player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
        }
    }
    return state
}

/**
 * The video on the mock's card: rounded, in the page gutter, with the
 * timecode chip top left, the speed chip top right, and the progress bar
 * along the bottom edge, which also scrubs.
 *
 * Media3's own controller is off. Its play button, time bar and settings
 * gear would sit on top of whatever [overlay] draws, and the transport under
 * the card owns playback anyway; scrubbing moves to the bar, whose touch
 * strip is taller than the three pixels it draws. In [fullscreen] the card
 * loses its gutter and corners and fills whatever the caller gives it; the
 * chips stay, and the corner-bracket chip beside the speed leaves again.
 * The frame pinches to zoom and pans once zoomed, up to [MAX_ZOOM]; a double
 * tap zooms in on the spot, or out again.
 *
 * [aspectRatio] is the video's own when the caller knows it (the skeleton
 * view reads it from the stored file); otherwise it is read from the player
 * once the first frame decodes, with 16:9 until then. Portrait videos get a
 * square card and sit pillarboxed in it rather than a card taller than the
 * screen.
 */
@Composable
fun VideoCard(
    player: ExoPlayer,
    position: PlaybackPosition,
    speed: Float,
    onSpeedTap: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    fullscreen: Boolean = false,
    onFullscreenToggle: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    error: (@Composable BoxScope.() -> Unit)? = null,
) {
    var measured by remember(player) { mutableStateOf<Float?>(null) }
    if (aspectRatio == null) {
        DisposableEffect(player) {
            fun sizeOf(size: VideoSize): Float? =
                if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height * size.pixelWidthHeightRatio else null
            measured = sizeOf(player.videoSize)
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    measured = sizeOf(videoSize)
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    val ratio = (aspectRatio ?: measured ?: (16f / 9f)).coerceAtLeast(1f)

    val shape = RoundedCornerShape(ShuttlRadius.large)
    val frame = if (fullscreen) {
        modifier.fillMaxSize()
    } else {
        modifier.padding(horizontal = 24.dp).fillMaxWidth().aspectRatio(ratio).clip(shape)
    }

    // Pinch to zoom, drag to pan once zoomed, double-tap to zoom in on a
    // spot or back out. The frame and whatever is drawn over it scale
    // together, so a skeleton stays on its joints; the chips and the bar
    // stay put. Offsets are in pixels about the centre, clamped so the
    // frame's edge never comes inside the card's. Reset when the card
    // changes size, since an offset for one size is nonsense at another.
    var scale by remember(player) { mutableFloatStateOf(1f) }
    var offset by remember(player) { mutableStateOf(Offset.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    fun clamped(candidate: Offset, atScale: Float): Offset {
        val maxX = contentSize.width * (atScale - 1f) / 2f
        val maxY = contentSize.height * (atScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = clamped(offset * (next / scale) + panChange, next)
        scale = next
    }
    LaunchedEffect(fullscreen) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(modifier = frame.background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { contentSize = it }
                // A one-finger drag pans only once zoomed; at 1x it stays a
                // scroll of the page the card sits in.
                .transformable(transform, canPan = { scale > 1f }, lockRotationOnZoomPan = true)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // The tapped spot stays under the finger:
                                // a point t from the centre lands at
                                // t * s + offset, so offset = t * (1 - s).
                                val fromCentre = tap - Offset(size.width / 2f, size.height / 2f)
                                scale = DOUBLE_TAP_ZOOM
                                offset = clamped(fromCentre * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                AndroidView(
                    factory = { c ->
                        // The layout, not PlayerView(c): it asks for a texture surface, and
                        // only a texture surface lets Compose animate, scroll and scale
                        // the video. A SurfaceView renders in its own window, so it survives
                        // the exit animation for a frame on top of the next screen (715e22b).
                        val view = LayoutInflater.from(c).inflate(R.layout.clip_player_view, null) as PlayerView
                        view.apply {
                            this.player = player
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                overlay()
            }
        }
        OverlayChip(
            text = formatDuration(position.positionMs),
            color = ShuttlVideoOverlay.text,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            OverlayChip(
                text = PlaybackOptions.formatSpeed(speed),
                color = ShuttlVideoOverlay.accentText,
                onClick = onSpeedTap,
            )
            if (onFullscreenToggle != null) {
                OverlayChip(
                    icon = if (fullscreen) ShuttlIcons.Minimize else ShuttlIcons.Maximize,
                    contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                    onClick = onFullscreenToggle,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        ScrubBar(position = position, onSeek = { fraction ->
            val duration = player.duration.takeIf { it > 0 } ?: return@ScrubBar
            player.seekTo((fraction * duration).toLong().coerceIn(0L, duration))
        }, modifier = Modifier.align(Alignment.BottomStart))
        error?.invoke(this)
    }
}

/**
 * The three-pixel progress bar, with a touch strip eight times taller so it
 * can be scrubbed. While a finger is down the bar follows the finger and the
 * seek lands on release; a tap seeks straight away.
 */
@Composable
private fun ScrubBar(position: PlaybackPosition, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val fraction = if (dragging) dragFraction else position.fraction
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { tap -> seek((tap.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        dragFraction = (start.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        seek(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(ShuttlVideoOverlay.track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(ShuttlVideoOverlay.progress))
        }
    }
}

/** A small pill over the video: the mock's `rgba(11,12,13,.7)` scrim with an 11 sp label, or one glyph. */
@Composable
private fun OverlayChip(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    color: Color = ShuttlVideoOverlay.text,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(ShuttlVideoOverlay.scrim, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
        if (icon != null) {
            // Sized to the label's cap height, so the two chips sit at one height.
            Icon(icon, contentDescription = contentDescription, tint = color, modifier = Modifier.size(14.dp))
        }
    }
}
