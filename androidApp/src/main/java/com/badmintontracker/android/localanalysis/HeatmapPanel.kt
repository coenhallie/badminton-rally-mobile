package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The tracks to draw and the frame rate they were sampled at, which must
 * travel together.
 *
 * A list because a cloud analysis carries both players. Returning the pair is
 * still the point: the fps belongs to the tracks it was measured with, and
 * picking each independently paired them by coincidence.
 */
internal data class HeatmapSource(val tracks: List<PlayerTrackStore.SideTrack>, val fps: Double)

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
    // A device run produces the near player only, so this branch is always a
    // single track. The empty check is unchanged and load-bearing: see the
    // KDoc above and HeatmapSourceTest.
    done != null && done.playerTrack.samples.isNotEmpty() ->
        HeatmapSource(listOf(PlayerTrackStore.SideTrack(CourtSide.NEAR, done.playerTrack)), done.fps)
    // Only sides anyone was actually found on. A toggle whose second option
    // draws an empty court is a control that cannot usefully be actuated.
    stored != null -> stored.tracks.filter { it.track.samples.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.let { HeatmapSource(it, stored.fps) }
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
    //
    // One parse, and not on the thread drawing the frame: `remember` runs its
    // block during composition, so the megabyte of track this reads was parsed
    // on the main thread and the tab opened frozen for as long as it took.
    // iOS reads it the same way, in AnalyticsDetailView.reload.
    val done = runner.stateFor(entryId) as? LocalAnalysisState.Done
    val loaded = produceState<Loaded?>(null, entryId, done) {
        value = withContext(Dispatchers.Default) {
            Loaded(heatmapSource(done, runner.storedTrack(entryId)))
        }
    }.value

    // Scrolls because the court is 1.7x taller than it is wide: in landscape, or
    // portrait at a large font scale, it overflows and takes the summary line -
    // which is how much of the map to trust - off the bottom with it. Fixed here
    // rather than on either host so both get it.
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val source = loaded?.source
        when {
            // The frames between the panel appearing and the read landing.
            // Blank, and deliberately not the line below: "no longer loaded"
            // would flash over every single opening of a perfectly good
            // heatmap.
            loaded == null -> Unit
            // A run's state lives in memory, so it is gone after a process death.
            // Said plainly rather than drawing an empty court, which would read as
            // a player who never moved.
            source == null ->
                PanelMessage("This analysis is no longer loaded. Run it again to see the heatmap.")
            else -> {
                // Held by side rather than by index: a redraw that reordered
                // or dropped a side would otherwise silently swap which
                // player the court is showing.
                var side by rememberSaveable { mutableStateOf(CourtSide.NEAR) }
                val shown = source.tracks.firstOrNull { it.side == side } ?: source.tracks.first()
                if (source.tracks.size > 1) {
                    SideToggle(
                        sides = source.tracks.map { it.side },
                        selected = shown.side,
                        onSelect = { side = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                CourtHeatmapView(track = shown.track, fps = source.fps)
            }
        }
    }
}

/**
 * Near or Far, shown only where both were found.
 *
 * Camera-relative labels. `videos.player_labels` carries the coach's own names
 * and Phase 2's thumbnails; using them is out of scope (spec 10), and a label
 * that said "Anna" on a track the gates assigned by net side would be claiming
 * an identification this pipeline does not make.
 */
@Composable
internal fun SideToggle(
    sides: List<CourtSide>,
    selected: CourtSide,
    onSelect: (CourtSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    ShuttlPillTabs(
        labels = sides.map { if (it == CourtSide.NEAR) "Near" else "Far" },
        selectedIndex = sides.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(sides[it]) },
        modifier = modifier,
    )
}

/**
 * A finished read. Null in place of one of these means the read is still
 * running, which is a blank frame rather than a message.
 */
private data class Loaded(val source: HeatmapSource?)
