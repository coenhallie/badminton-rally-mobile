package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.PlayerTrack

/** A track to draw and the frame rate it was sampled at, which must travel together. */
internal data class HeatmapSource(val track: PlayerTrack, val fps: Double)

/**
 * Which track a heatmap should draw: the one still in memory, the one on disk, or
 * neither.
 *
 * A finished run is preferred, but only when it actually carries samples. A run
 * that asked for no pose metric completes with an EMPTY PlayerTrack, and
 * LocalAnalysisRunner only writes one to disk when `samples.isNotEmpty()`, so
 * preferring the in-memory run blindly replaced a perfectly good stored heatmap
 * with CourtHeatmapView's "No pose data for this video. It was analysed for
 * rallies only." Court marking seeds its metrics to RALLY_CLIPS alone, so a
 * pose-less run is the DEFAULT: "pose last week, clips today, open the heatmap"
 * is an ordinary coach path, not a corner.
 *
 * Second, quieter consequence, kept deliberately. An empty track also means a
 * pose run that found nobody on the near court, and such a run saves nothing. So
 * a coach who waits on that run now sees the PREVIOUS run's heatmap and summary
 * rather than "The player was not found...". The alternative is discarding a good
 * heatmap because a later run failed, which is worse; the run's own outcome is
 * reported where runs are reported, not here.
 *
 * Returning the pair is the point: the fps belongs to the track it was measured
 * with, and picking each independently paired them by coincidence.
 */
internal fun heatmapSource(
    done: LocalAnalysisState.Done?,
    stored: PlayerTrackStore.Stored?,
): HeatmapSource? = when {
    done != null && done.playerTrack.samples.isNotEmpty() -> HeatmapSource(done.playerTrack, done.fps)
    stored != null -> HeatmapSource(stored.track, stored.fps)
    else -> null
}

/**
 * One entry's court heatmap, or an honest line saying why there is none.
 *
 * Two screens show this: the standalone heatmap route the analysis banner opens
 * when a run finishes, and the Analytics detail's Heatmap tab. They resolve the
 * same track from the same two places, so the resolution lives here once. Two
 * copies is how the banner's path rots while the newer one still looks fine,
 * and the banner's path is how a coach returns to a run that cost half an hour.
 */
@Composable
fun HeatmapPanel(
    entryId: String,
    runner: LocalAnalysisRunner,
    modifier: Modifier = Modifier,
) {
    // In memory if the run is still loaded, from disk otherwise. A pose run costs
    // half an hour, so losing its result to a process death and asking for another
    // one is not an option.
    //
    // The disk is read once per entry even when a run is in memory, because that
    // run may be a pose-less one whose empty track must fall through. This is a
    // detail screen reached by an explicit tap, so it is one parse per opening,
    // not the per-row cost the Analytics list had to avoid.
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val stored = remember(entryId) { runner.storedTrack(entryId) }
    val source = heatmapSource(done, stored)

    // Scrolls because the court is 1.7x taller than it is wide: in landscape, or
    // portrait at a large font scale, it overflows and takes the summary line -
    // which is how much of the map to trust - off the bottom with it. Fixed here
    // rather than on either host so both get it.
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (source == null) {
            // A run's state lives in memory, so it is gone after a process death.
            // Said plainly rather than drawing an empty court, which would read as
            // a player who never moved.
            Text(
                "This analysis is no longer loaded. Run it again to see the heatmap.",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            CourtHeatmapView(track = source.track, fps = source.fps)
        }
    }
}
