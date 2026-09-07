package com.badmintontracker.android.localanalysis

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.analysis.player.nearestPose
import com.badmintontracker.analysis.player.poseToleranceS
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.clipdetail.PlaybackControlBar
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import java.io.File

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
    val stored = remember(entryId) { runner.storedSkeleton(entryId) }
    val source = remember(entryId, videoUri) { videoUri?.let { runner.analysedSource(entryId, it) } }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            stored == null -> Text(
                "No skeleton was kept for this video. Run the analysis again with " +
                    "\"Skeleton playback\" ticked.",
                modifier = Modifier.padding(16.dp),
            )
            source == null -> Text(
                "The video this skeleton was measured on is no longer on this phone.",
                modifier = Modifier.padding(16.dp),
            )
            else -> SkeletonPlayer(source, stored, prefs)
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
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(source) {
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(source)))
        player.prepare()
        player.playWhenReady = false
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }
    val tolerance = remember(stored.fps) { poseToleranceS(stored.fps) }
    val pose = remember(positionMs, stored) { nearestPose(stored.poses, positionMs / 1000.0, tolerance) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(stored.videoWidth.toFloat() / stored.videoHeight.toFloat())
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
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
    PlaybackControlBar(player = player, prefs = prefs)
    FrameStepBar(player = player)
    Text(
        "Skeleton in ${stored.poses.size} frames" +
            (pose?.let { " · frame ${it.frame}" } ?: " · no skeleton at this frame"),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
