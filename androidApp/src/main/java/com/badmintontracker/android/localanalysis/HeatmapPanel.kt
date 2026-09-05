package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    // In memory if the run is still loaded, from disk otherwise.
    // A pose run costs half an hour, so losing its result to a
    // process death and asking for another one is not an option.
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val stored = remember(entryId) {
        if (done != null) null else runner.storedTrack(entryId)
    }
    val track = done?.playerTrack ?: stored?.track
    val trackFps = done?.fps ?: stored?.fps ?: 0.0

    Column(modifier = modifier.fillMaxSize()) {
        if (track == null) {
            // A run's state lives in memory, so it is gone
            // after a process death. Said plainly rather
            // than drawing an empty court, which would read
            // as a player who never moved.
            Text(
                "This analysis is no longer loaded. Run it again to see the heatmap.",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            CourtHeatmapView(track = track, fps = trackFps)
        }
    }
}
