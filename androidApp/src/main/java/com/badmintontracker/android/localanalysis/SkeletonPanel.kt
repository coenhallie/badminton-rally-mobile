package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.maxResidualM
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.NearPlayerSelector
import com.badmintontracker.analysis.player.nearestPose
import com.badmintontracker.analysis.player.poseMetrics
import com.badmintontracker.analysis.player.poseToleranceS
import com.badmintontracker.android.clipdetail.PlaybackSettingsSheet
import com.badmintontracker.android.clipdetail.TransportBar
import com.badmintontracker.android.clipdetail.VideoCard
import com.badmintontracker.android.clipdetail.rememberPlaybackPosition
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.prefs.PlaybackOptions
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The page gutter the mock lays every card in. */
private val GUTTER = 24.dp

/** Whether the stored skeleton has been read from disk yet. */
private sealed interface SkeletonLoad {
    data object Loading : SkeletonLoad
    data class Loaded(
        val stored: SkeletonStore.Stored?,
        /** The resolved court fit from the file's marks; non-null only when [courtFit] is OK. */
        val homography: Matrix3x3?,
        val courtFit: CourtFit,
        /** One entry per pose, in pose order, for the graph. */
        val series: List<MetricSample>,
    ) : SkeletonLoad
}

/**
 * The near player's skeleton over the video it was measured on.
 *
 * Two things have to exist: the poses a run kept (only when the skeleton
 * metric was ticked) and the file that run decoded. Either missing is said in
 * one line rather than drawn around, because a skeleton over the wrong frames
 * looks like tracking that is broken, and a coach cannot tell that from
 * tracking that is bad.
 *
 * Under the video sit the transport, the strip and the graph: one tile per
 * measurement of the frame on screen, and the selected tile's kind drawn on
 * the skeleton as an arc and plotted over the two seconds either side of the
 * playhead. Nothing here is stored - every number is computed from the poses
 * in the file at view time, so a metric added later needs no re-run, and the
 * two court-plane tiles appear only when the file carries marks that fit.
 */
@Composable
fun SkeletonPanel(
    entryId: String,
    videoUri: String?,
    runner: LocalAnalysisRunner,
    prefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    modifier: Modifier = Modifier,
) {
    // SkeletonStore.load parses the whole file, and its own KDoc prices a
    // 30-minute match at 11MB of poses - reading and decoding that on the
    // composition thread would jank the screen open. Loaded off the main
    // thread instead, with a one-line placeholder while it runs.
    // The metrics for every pose are computed here too, off the main thread
    // and once per file: the graph needs the whole series, and a court fit
    // whose marks miss by more than the selector's own gate is dropped rather
    // than turned into a stance in metres that is metres wrong.
    val load by produceState<SkeletonLoad>(initialValue = SkeletonLoad.Loading, key1 = entryId) {
        value = withContext(Dispatchers.IO) {
            val stored = runner.storedSkeleton(entryId)
            val marks = stored?.marks
            val homography = marks?.let { m ->
                m.homography()?.takeIf { h ->
                    val residual = m.maxResidualM(h)
                    residual != null && residual <= NearPlayerSelector.MAX_COURT_RESIDUAL_M
                }
            }
            val courtFit = when {
                marks == null -> CourtFit.NONE
                homography == null -> CourtFit.BAD
                else -> CourtFit.OK
            }
            val series = stored?.poses?.map {
                MetricSample(it.timestamp, poseMetrics(it.keypoints, it.confidence, homography))
            } ?: emptyList()
            SkeletonLoad.Loaded(stored, homography, courtFit, series)
        }
    }
    val source = remember(entryId, videoUri) { videoUri?.let { runner.analysedSource(entryId, it) } }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (val l = load) {
            SkeletonLoad.Loading -> PanelMessage("Loading skeleton")
            is SkeletonLoad.Loaded -> when {
                l.stored == null -> PanelMessage(
                    "No skeleton was kept for this video. Run the analysis again with " +
                        "\"Skeleton playback\" ticked.",
                )
                source == null -> PanelMessage("The video this skeleton was measured on is no longer on this phone.")
                else -> SkeletonPlayer(
                    source = source,
                    stored = l.stored,
                    homography = l.homography,
                    courtFit = l.courtFit,
                    series = l.series,
                    prefs = prefs,
                    racketArmPrefs = racketArmPrefs,
                    entryId = entryId,
                )
            }
        }
    }
}

/** One line in the gutter, for a panel that has nothing to draw and says why. */
@Composable
internal fun PanelMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GUTTER, vertical = 16.dp),
    )
}

/**
 * The player, the overlay, and the loop that keeps them on the same frame.
 *
 * The card takes the video's own stored aspect ratio, so the player fills it
 * edge to edge and the overlay's letterbox arithmetic reduces to the
 * identity. The position comes from [rememberPlaybackPosition], read once per
 * display frame so the joints move with the frame.
 */
@Composable
private fun SkeletonPlayer(
    source: File,
    stored: SkeletonStore.Stored,
    homography: Matrix3x3?,
    courtFit: CourtFit,
    series: List<MetricSample>,
    prefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    entryId: String,
) {
    if (stored.videoWidth <= 0 || stored.videoHeight <= 0) {
        // Guards aspectRatio() below, which throws on a non-positive ratio.
        // A stored size this broken is another "this skeleton cannot be
        // shown" case, not a crash.
        PanelMessage("This skeleton's stored video size is invalid. Run the analysis again to rebuild it.")
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
                playbackError = "Couldn't play the analyzed video. Run the analysis again to rebuild it."
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

    val position by rememberPlaybackPosition(player)
    val positionMs = position.positionMs
    val durationMs = position.durationMs
    val tolerance = remember(stored.fps) { poseToleranceS(stored.fps) }
    val pose = remember(positionMs, stored) { nearestPose(stored.poses, positionMs / 1000.0, tolerance) }

    val hasCourt = homography != null
    var racketArm by remember(entryId) { mutableStateOf(racketArmPrefs.racketArm(entryId)) }
    var chosen by rememberSaveable(entryId) {
        mutableStateOf(if (hasCourt) MetricKind.STANCE else MetricKind.ELBOW_RIGHT)
    }
    val visible = visibleKinds(hasCourt, racketArm)
    // The coach's choice held to the tiles on screen: the racket-arm control can
    // hide the chosen kind, and the graph and the arc must never show a kind with
    // no tile. Derived, not written back, so composition stays a read; the choice
    // itself is kept, so putting the arm back brings the tile back selected.
    val selected = if (chosen in visible) chosen else visible.first()
    val metrics = remember(pose, homography) { pose?.let { poseMetrics(it.keypoints, it.confidence, homography) } }
    val expanded by prefs.metricsExpanded.collectAsStateWithLifecycle()
    val speed by prefs.speed.collectAsStateWithLifecycle()
    val skipSeconds by prefs.skipSeconds.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val error = playbackError
    if (error != null) {
        // In place of the video card: the overlay is inside it and so never
        // draws when this branch runs instead. The transport stays -
        // seeking or pausing an errored ExoPlayer is a no-op, not a crash -
        // but everything under it is skipped explicitly, since it is all
        // about a frame that is not on screen.
        PanelMessage(error)
    } else {
        VideoCard(
            player = player,
            position = position,
            speed = speed,
            onSpeedTap = { showSettings = true },
            aspectRatio = stored.videoWidth.toFloat() / stored.videoHeight.toFloat(),
        ) {
            if (pose != null) {
                SkeletonOverlay(
                    keypoints = pose.keypoints,
                    confidence = pose.confidence,
                    videoWidth = stored.videoWidth,
                    videoHeight = stored.videoHeight,
                    modifier = Modifier.fillMaxSize(),
                    highlight = selected.highlight(),
                )
            }
        }
    }
    TransportBar(
        player = player,
        prefs = prefs,
        onSettings = { showSettings = true },
        modifier = Modifier.padding(top = 16.dp),
    )
    if (showSettings) {
        PlaybackSettingsSheet(
            skipSeconds = skipSeconds,
            speed = speed,
            onSkipSeconds = prefs::setSkipSeconds,
            onSpeed = prefs::setSpeed,
            onDismiss = { showSettings = false },
        )
    }
    if (error == null) {
        // The frame is the one fact read per frame; the speed sits opposite
        // it, as the mock lays the line out, and opens the same sheet the
        // transport's hold gesture does.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = GUTTER, end = GUTTER, top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                skeletonFooter(pose?.frame),
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${PlaybackOptions.formatSpeed(speed)} speed",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = { showSettings = true })
                    .padding(vertical = 4.dp),
            )
        }
        MetricsStrip(
            metrics = metrics,
            hasCourt = hasCourt,
            racketArm = racketArm,
            onRacketArm = { arm ->
                racketArm = arm
                racketArmPrefs.setRacketArm(entryId, arm)
            },
            selected = selected,
            onSelect = { chosen = it },
            expanded = expanded,
            onExpanded = prefs::setMetricsExpanded,
            detail = skeletonDetail(stored.poses.size, courtFit),
            modifier = Modifier.padding(top = 10.dp),
        )
        MetricGraph(
            series = series,
            kind = selected,
            label = metricLabel(selected, racketArm),
            positionS = positionMs / 1000.0,
            fps = stored.fps,
            durationS = if (durationMs > 0) durationMs / 1000.0 else Double.POSITIVE_INFINITY,
            onSeek = { seconds ->
                if (player.isPlaying) player.pause()
                player.seekTo(
                    (seconds * 1000).toLong()
                        .coerceIn(0L, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE),
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        // A court warning is shown here, not only in the expanded detail: a
        // coach looking for the Stance tile should not have to open the grid
        // to learn why it is not there.
        courtWarning(courtFit)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                modifier = Modifier.padding(horizontal = GUTTER, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(GUTTER))
    }
}
