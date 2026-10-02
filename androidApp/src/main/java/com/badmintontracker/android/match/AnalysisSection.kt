package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BasePositionPanel
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.SkeletonPanel
import com.badmintontracker.android.localanalysis.TrackSource
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.analytics.AnalyticsPanel
import com.badmintontracker.shared.analytics.availablePanels
import com.badmintontracker.shared.analytics.cloudRallyWindows
import com.badmintontracker.shared.analytics.rallyWindowsFor
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository

/**
 * The Analysis section: what one analysed match has to show, on the match
 * page's Analysis toggle.
 *
 * A pill tab row between the heatmap, the per-rally base position and the
 * skeleton, drawn only when more than one of them has content. The analysis
 * screen this section replaced first had no tab row, deliberately: with one
 * renderer a single pill read as a primary button that did nothing, and a
 * control that cannot be actuated is worse than a plain heading. Gated the
 * same way the match page gates its section row: a tab must have something
 * behind it.
 *
 * The heatmap itself, and the resolution of which track to draw, are
 * [HeatmapPanel]'s. The base position is [BasePositionPanel]'s, on the same
 * track. The skeleton is [SkeletonPanel]'s.
 *
 * Which panels exist is `availablePanels`, and the rally windows come from
 * whichever run the heatmap draws (CloudAnalysisPanelsTest pins the two
 * together).
 *
 * [analysisId] is `analysisId(item)`: the heatmap's own id, else the entry.
 */
@Composable
fun AnalysisSection(
    analysisId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    cloudClips: List<RallyClip>,
    modifier: Modifier = Modifier,
) {
    val hasSkeleton = remember(analysisId) { localAnalysis.hasStoredSkeleton(analysisId) }
    // A run still in memory may carry a track and clips the disk does not yet
    // hold, so both are consulted, the way HeatmapPanel resolves its track.
    val done = localAnalysis.stateFor(analysisId) as? LocalAnalysisState.Done
    val storedTrack = remember(analysisId, done) { localAnalysis.storedTrack(analysisId) }
    val hasTrack = done?.playerTrack?.samples?.isNotEmpty() == true || storedTrack != null
    // One source for the whole section. A median taken over one run's rally
    // boundaries and plotted from another run's track is two answers on one
    // court, so whichever run heatmapSource will draw also supplies the windows.
    val windows = remember(analysisId, done, storedTrack, cloudClips) {
        rallyWindowsFor(
            preferCloud = storedTrack?.source == TrackSource.CLOUD,
            cloud = cloudRallyWindows(cloudClips, analysisId),
            device = (done?.clips?.takeIf { it.isNotEmpty() } ?: localAnalysis.storedClips(analysisId))
                .map { RallyWindow(it.index, it.startSeconds, it.endSeconds) },
        )
    }
    val hasBoundedClips = windows.any { it.isBounded }
    val entry = remember(analysisId) { localVideos.get(analysisId) }
    val videoUri = entry?.uri
    // A cloud analysis lands on a phone that never held the footage, so the
    // skeleton tab has nothing to overlay. The heatmap and base read only the
    // stored track and are unaffected.
    val panels = availablePanels(hasTrack, hasBoundedClips, hasSkeleton, hasVideo = videoUri != null)
    var chosen by rememberSaveable { mutableStateOf(AnalyticsPanel.Heatmap) }
    val panel = if (chosen in panels) chosen else AnalyticsPanel.Heatmap

    Column(modifier.fillMaxSize()) {
        if (panels.size > 1) {
            ShuttlPillTabs(
                labels = panels.map { it.label },
                selectedIndex = panels.indexOf(panel),
                onSelect = { chosen = panels[it] },
                modifier = Modifier.padding(start = MatchPageGutter, end = MatchPageGutter, bottom = 16.dp),
                // Secondary: this row is Analysis's own, under the page's
                // section row, and must not read as its peer.
                secondary = true,
                groupLabel = MatchPageCopy.ANALYSIS_PANELS,
            )
        } else {
            // Styled as a small uppercase section label, not as a title: the
            // label sits under the page's own section row, and two treatments
            // of the same thing there reads as two different kinds of heading.
            Text(
                MatchPageCopy.HEATMAP_HEADING,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MatchPageGutter, end = MatchPageGutter, bottom = 12.dp),
            )
        }
        when (panel) {
            AnalyticsPanel.Heatmap ->
                HeatmapPanel(entryId = analysisId, runner = localAnalysis, windows = windows)
            AnalyticsPanel.Base ->
                BasePositionPanel(entryId = analysisId, runner = localAnalysis, windows = windows)
            AnalyticsPanel.Skeleton -> SkeletonPanel(
                entryId = analysisId,
                videoUri = videoUri,
                runner = localAnalysis,
                prefs = playbackPrefs,
                racketArmPrefs = racketArmPrefs,
            )
        }
    }
}
