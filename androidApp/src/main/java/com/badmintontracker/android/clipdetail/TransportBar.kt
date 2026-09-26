package com.badmintontracker.android.clipdetail

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlVideoOverlay
import com.badmintontracker.shared.playback.SkipMath
import com.badmintontracker.shared.playback.TransportRowLayout
import com.badmintontracker.shared.playback.TransportRowMetrics
import com.badmintontracker.shared.prefs.PlaybackOptions
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

/**
 * Every player's transport, in one centred row as the mock draws it: a
 * skip-back pill, a frame-back circle, the accent play circle, a
 * frame-forward circle, a skip-forward pill. The playback speed is read out in
 * the row's trailing gutter and opens the settings sheet when tapped.
 *
 * Shared by the clip player, the local video player and the skeleton view,
 * so playback is driven the same way everywhere. The skip interval and the
 * speed are the app-wide preferences; the speed is applied here, so a speed
 * chosen on another clip takes effect the moment this player exists. Holding
 * a skip pill opens the settings sheet through [onSettings]; holding the
 * frame-back circle steps quickly; holding the frame-forward circle plays
 * until released.
 */
@Composable
fun TransportBar(
    player: ExoPlayer,
    prefs: PlaybackPreferenceRepository,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** True in fullscreen, where the row sits on the frame rather than on the page: the readout has no surface of its own, so it has to be lettered for whatever is behind it. */
    overVideo: Boolean = false,
) {
    val skipSeconds by prefs.skipSeconds.collectAsStateWithLifecycle()
    val speed by prefs.speed.collectAsStateWithLifecycle()
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }

    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun skip(direction: Int) {
        player.seekTo(
            SkipMath.targetMs(
                positionMs = player.currentPosition,
                deltaSeconds = direction * skipSeconds,
                durationMs = player.duration,
            ),
        )
    }

    // The speed is drawn in the trailing gutter rather than as a sixth control, and
    // the leading gutter matches it, which is what keeps play in the middle of the
    // screen. How the five controls give way to make that room is the shared
    // TransportRowLayout's decision, from measured text rather than a guess, so a
    // large font scale and a narrow phone are both accounted for.
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val pillStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
    val readoutStyle = MaterialTheme.typography.labelMedium
    fun widthOf(text: String) = with(density) { measurer.measure(text, pillStyle).size.width.toDp().value }
    val pillLabelWidth = widthOf(TransportRowLayout.pillLabel(skipSeconds, forward = true, showPillIcons = true))
    val signedPillLabelWidth = maxOf(
        widthOf(TransportRowLayout.pillLabel(skipSeconds, forward = true, showPillIcons = false)),
        widthOf(TransportRowLayout.pillLabel(skipSeconds, forward = false, showPillIcons = false)),
    )
    // The widest the readout gets, not what it says now: the row must not re-space
    // itself when the speed changes.
    val readoutWidth = with(density) {
        PlaybackOptions.speedOptions.maxOf { measurer.measure(PlaybackOptions.formatSpeed(it), readoutStyle).size.width }
            .toDp().value
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val metrics = TransportRowLayout.fit(maxWidth.value, readoutWidth, pillLabelWidth, signedPillLabelWidth)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.gutter.dp),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportControls(player, playing, skipSeconds, metrics, ::skip, onSettings)
        }
        SpeedReadout(
            text = PlaybackOptions.formatSpeed(speed),
            onClick = onSettings,
            color = if (overVideo) ShuttlVideoOverlay.text else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterEnd).width(metrics.gutter.dp),
        )
    }
}

/**
 * The current speed, in the row's trailing gutter. Tappable, to the same sheet the
 * gear on the video opens: it is the number a coach looks at before changing it.
 * The touch target is the whole gutter and the row's height, not the few pixels
 * the text covers.
 */
@Composable
private fun SpeedReadout(text: String, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(ShuttlRadius.pill))
            .clickable(role = Role.Button, onClickLabel = "Playback settings", onClick = onClick)
            .semantics { contentDescription = "Speed $text" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun RowScope.TransportControls(
    player: ExoPlayer,
    playing: Boolean,
    skipSeconds: Int,
    metrics: TransportRowMetrics,
    skip: (Int) -> Unit,
    onSettings: () -> Unit,
) {
        SkipPill(
            icon = ShuttlIcons.ChevronsLeft,
            label = TransportRowLayout.pillLabel(skipSeconds, forward = false, showPillIcons = metrics.showPillIcons),
            metrics = metrics,
            iconFirst = true,
            contentDescription = "Skip back $skipSeconds seconds",
            onTap = { skip(-1) },
            onHoldActivate = onSettings,
            modifier = Modifier.weight(1f, fill = false),
        )
        RoundButton(
            icon = ShuttlIcons.StepBack,
            contentDescription = "Previous frame",
            onTap = {
                if (player.isPlaying) player.pause()
                player.seekFrames(-1)
            },
            onHoldTick = { player.seekFrames(-3) },
            holdTickPeriodMs = 100L,
        )
        RoundButton(
            icon = if (playing) ShuttlIcons.Pause else ShuttlIcons.Play,
            contentDescription = if (playing) "Pause" else "Play",
            accent = true,
            size = 60.dp,
            onTap = {
                when {
                    player.isPlaying -> player.pause()
                    // At the end, play means from the start; otherwise play()
                    // sits on the last frame and does nothing.
                    player.playbackState == Player.STATE_ENDED -> {
                        player.seekTo(0L)
                        player.play()
                    }
                    else -> player.play()
                }
            },
        )
        RoundButton(
            icon = ShuttlIcons.StepForward,
            contentDescription = "Next frame",
            onTap = {
                if (player.isPlaying) player.pause()
                player.seekFrames(1)
            },
            onHoldActivate = { player.play() },
            onRelease = { if (player.isPlaying) player.pause() },
        )
        SkipPill(
            icon = ShuttlIcons.ChevronsRight,
            label = TransportRowLayout.pillLabel(skipSeconds, forward = true, showPillIcons = metrics.showPillIcons),
            metrics = metrics,
            iconFirst = false,
            contentDescription = "Skip forward $skipSeconds seconds",
            onTap = { skip(1) },
            onHoldActivate = onSettings,
            modifier = Modifier.weight(1f, fill = false),
        )
}

/**
 * A 48 dp pill with a double chevron and the skip interval, lettered in the secondary
 * text colour. On a narrow screen [metrics] takes the chevron away and the direction
 * is carried by the sign: "-10s" and "+10s".
 */
@Composable
private fun SkipPill(
    icon: ImageVector,
    label: String,
    metrics: TransportRowMetrics,
    iconFirst: Boolean,
    contentDescription: String,
    onTap: () -> Unit,
    onHoldActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .height(48.dp)
            .transportSurface(RoundedCornerShape(ShuttlRadius.pill), pressed, accent = false)
            .then(pressAndHold(onPressedChange = { pressed = it }, onTap = onTap, onHoldActivate = onHoldActivate))
            .semantics { this.contentDescription = contentDescription; role = Role.Button }
            .padding(horizontal = metrics.pillPadding.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconFirst && metrics.showPillIcons) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
        if (!iconFirst && metrics.showPillIcons) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

/** A circle holding one glyph: a 48 dp secondary one for a frame step, the 60 dp accent one for play. */
@Composable
private fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onTap: () -> Unit,
    accent: Boolean = false,
    size: Dp = 48.dp,
    onHoldActivate: () -> Unit = {},
    onHoldTick: (() -> Unit)? = null,
    holdTickPeriodMs: Long = 100L,
    onRelease: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    val tint = if (accent) ShuttlTheme.extended.onAccent else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(size)
            .transportSurface(CircleShape, pressed, accent)
            .then(
                pressAndHold(
                    onPressedChange = { pressed = it },
                    onTap = onTap,
                    onHoldActivate = onHoldActivate,
                    onHoldTick = onHoldTick,
                    holdTickPeriodMs = holdTickPeriodMs,
                    onRelease = onRelease,
                ),
            )
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (accent) 18.dp else 14.dp))
    }
}

/**
 * The fill of one transport control. Secondary controls sit on the card
 * colour and lift to the raised colour while pressed; the play control is the
 * accent and darkens to the accent's pressed tone.
 */
@Composable
private fun Modifier.transportSurface(shape: Shape, pressed: Boolean, accent: Boolean): Modifier {
    val fill: Color = when {
        accent && pressed -> ShuttlTheme.extended.accentDark
        accent -> MaterialTheme.colorScheme.primary
        pressed -> ShuttlTheme.extended.bgTertiary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    return clip(shape).background(fill, shape)
}

/**
 * Step the playhead by whole frames.
 *
 * Step by frame index, with two pieces of slack to survive ExoPlayer's
 * sub-millisecond position truncation:
 *   1. currentPosition is reported in integer ms but frame PTSes are
 *      microsecond-precise. Once a frame renders, position becomes the
 *      frame's PTS rounded DOWN - e.g. 33ms for frame 1 at 30fps (real
 *      33.333ms). Naive floor(pos/frameDurMs) then maps this back to
 *      frame 0, so the next "+1" seeks to frame 1 again - stuck. Adding
 *      half a frame before dividing makes the index robust to that
 *      truncation.
 *   2. Targeting an integer ms just past the frame's PTS guarantees
 *      EXACT seek lands inside that frame's display interval, not on
 *      the boundary with the previous frame.
 */
// videoFormat is an unstable media3 API; it is the only source of the real
// frame rate the player has.
@OptIn(UnstableApi::class)
internal fun ExoPlayer.seekFrames(delta: Int) {
    val fps = videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
    val frameDurMs = 1000.0 / fps
    val currentFrame = ((currentPosition + frameDurMs / 2.0) / frameDurMs).toLong()
    val next = (currentFrame + delta).coerceAtLeast(0L)
    val targetMs = (next * frameDurMs + 1.0).toLong()
    val maxPos = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
    seekTo(targetMs.coerceIn(0L, maxPos))
}
