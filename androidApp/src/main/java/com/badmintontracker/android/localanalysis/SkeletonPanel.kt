package com.badmintontracker.android.localanalysis

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.analysis.player.nearestPose
import com.badmintontracker.analysis.player.poseToleranceS
import com.badmintontracker.android.R
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.clipdetail.PlaybackControlBar
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether the stored skeleton has been read from disk yet. */
private sealed interface SkeletonLoad {
    data object Loading : SkeletonLoad
    data class Loaded(val stored: SkeletonStore.Stored?) : SkeletonLoad
}

/**
 * The near player's skeleton over the video it was measured on.
 *
 * Two things have to exist: the poses a run kept (only when the skeleton
 * metric was ticked) and the file that run decoded. Either missing is said in
 * one line rather than drawn around, because a skeleton over the wrong frames
 * looks like tracking that is broken, and a coach cannot tell that from
 * tracking that is bad.
 */
@Composable
fun SkeletonPanel(
    entryId: String,
    videoUri: String?,
    runner: LocalAnalysisRunner,
    prefs: PlaybackPreferenceRepository,
    modifier: Modifier = Modifier,
) {
    // SkeletonStore.load parses the whole file, and its own KDoc prices a
    // 30-minute match at 11MB of poses - reading and decoding that on the
    // composition thread would jank the screen open. Loaded off the main
    // thread instead, with a one-line placeholder while it runs.
    val load by produceState<SkeletonLoad>(initialValue = SkeletonLoad.Loading, key1 = entryId) {
        value = SkeletonLoad.Loaded(withContext(Dispatchers.IO) { runner.storedSkeleton(entryId) })
    }
    val source = remember(entryId, videoUri) { videoUri?.let { runner.analysedSource(entryId, it) } }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (val l = load) {
            SkeletonLoad.Loading -> Text("Loading skeleton", modifier = Modifier.padding(16.dp))
            is SkeletonLoad.Loaded -> when {
                l.stored == null -> Text(
                    "No skeleton was kept for this video. Run the analysis again with " +
                        "\"Skeleton playback\" ticked.",
                    modifier = Modifier.padding(16.dp),
                )
                source == null -> Text(
                    "The video this skeleton was measured on is no longer on this phone.",
                    modifier = Modifier.padding(16.dp),
                )
                else -> SkeletonPlayer(source, l.stored, prefs)
            }
        }
    }
}

/**
 * The player, the overlay, and the loop that keeps them on the same frame.
 *
 * The box takes the video's own aspect ratio, as the court-marking screen
 * does, so the player fills it edge to edge and the overlay's letterbox
 * arithmetic reduces to the identity. The position is read once per
 * display frame with `withFrameNanos`, because Media3 has no per-frame
 * position callback and a coach stepping frame by frame needs the joints to
 * move with the frame, not a few hundred milliseconds after it.
 */
@Composable
private fun SkeletonPlayer(
    source: File,
    stored: SkeletonStore.Stored,
    prefs: PlaybackPreferenceRepository,
) {
    if (stored.videoWidth <= 0 || stored.videoHeight <= 0) {
        // Guards aspectRatio() below, which throws on a non-positive ratio.
        // A stored size this broken is another "this skeleton cannot be
        // shown" case, not a crash.
        Text(
            "This skeleton's stored video size is invalid. Run the analysis again to rebuild it.",
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    var playbackError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        // Mirrors LocalPlayerScreen: the analysed file can be moved or
        // deleted behind us, and without a listener the player just
        // freezes silently instead of saying so.
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Couldn't play the analysed video. Run the analysis again to rebuild it."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(source) {
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(source)))
        player.prepare()
        player.playWhenReady = false
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            // Polls once per display frame rather than waiting on a Media3
            // callback, because there is none. This keeps the Recomposer
            // non-idle for as long as this screen is composed, so a Compose
            // UI test of this screen must not wait for idle: it never will.
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }
    val tolerance = remember(stored.fps) { poseToleranceS(stored.fps) }
    val pose = remember(positionMs, stored) { nearestPose(stored.poses, positionMs / 1000.0, tolerance) }

    val error = playbackError
    if (error != null) {
        // In place of the video box: the overlay is inside it and so never
        // draws when this branch runs instead. The transport bars stay -
        // seeking or pausing an errored ExoPlayer is a no-op, not a crash -
        // but the summary line below is skipped explicitly, since it too is
        // about a frame that is not on screen.
        Text(error, modifier = Modifier.padding(16.dp))
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(stored.videoWidth.toFloat() / stored.videoHeight.toFloat())
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { c ->
                    // The layout, not PlayerView(c): it asks for a texture surface, and
                    // only a texture surface lets Compose animate and scroll over the
                    // video. A SurfaceView renders in its own window, so it survives the
                    // exit animation for a frame on top of the next screen (715e22b),
                    // and this one is inside a scroller as well.
                    val view = LayoutInflater.from(c)
                        .inflate(R.layout.clip_player_view, null) as PlayerView
                    view.apply {
                        this.player = player
                        // The bars below own transport; a controller over the
                        // skeleton would sit exactly where the joints are.
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (pose != null) {
                SkeletonOverlay(
                    keypoints = pose.keypoints,
                    confidence = pose.confidence,
                    videoWidth = stored.videoWidth,
                    videoHeight = stored.videoHeight,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    PlaybackControlBar(player = player, prefs = prefs)
    FrameStepBar(player = player)
    if (error == null) {
        Text(
            "Skeleton in ${stored.poses.size} frames" +
                (pose?.let { " · frame ${it.frame}" } ?: " · no skeleton at this frame"),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
