package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BasePositionPanel
import com.badmintontracker.android.localanalysis.ClipCutter
import com.badmintontracker.android.localanalysis.ExercisesPanel
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.PlayerTrackStore
import com.badmintontracker.android.localanalysis.SkeletonPanel
import com.badmintontracker.android.localanalysis.TrackSource
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.analytics.AnalyticsPanel
import com.badmintontracker.shared.analytics.CourtSideNames
import com.badmintontracker.shared.analytics.availablePanels
import com.badmintontracker.shared.analytics.cloudRallyWindows
import com.badmintontracker.shared.analytics.rallyWindowsFor
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * [sideNames] names the player at each end on the three panels' Near | Far
 * toggle, and supplies a profile's playing hand to the skeleton's metrics.
 */
@Composable
fun AnalysisSection(
    analysisId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    drillProgress: DrillProgressRepository,
    cloudClips: List<RallyClip>,
    /** Who stands at each end (`courtSideNames`); null while the match does not say which end Home was at. */
    sideNames: CourtSideNames?,
    modifier: Modifier = Modifier,
    /** Whether the server holds a cloud pose artifact that can be downloaded on demand. */
    hasCloudPose: Boolean = false,
    /** On-demand downloader storing the cloud pose track into the current account's local store. */
    installCloudPose: (suspend (videoId: String, onProgress: (Float) -> Unit) -> Unit)? = null,
    /** Called once an on-demand cloud pose download succeeds so the match list refreshes its disk probe. */
    onCloudPoseInstalled: () -> Unit = {},
    currentUserId: String? = null,
    otherPartyLabel: String? = null,
    isCoach: Boolean = true,
    onSyncDrills: () -> Unit = {},
) {
    // A run still in memory may carry a track and clips the disk does not yet
    // hold, so both are consulted, the way HeatmapPanel resolves its track.
    // Disk reads (track JSON, clip sidecar, skeleton stat) run off Main.
    val done = localAnalysis.stateFor(analysisId) as? LocalAnalysisState.Done
    var reloadTick by remember(analysisId) { mutableIntStateOf(0) }
    val stored by produceState(StoredAnalysis(), analysisId, done, reloadTick) {
        value = withContext(Dispatchers.IO) {
            StoredAnalysis(
                loaded = true,
                track = localAnalysis.storedTrack(analysisId),
                clips = if (done?.clips?.isNotEmpty() == true) emptyList() else localAnalysis.storedClips(analysisId),
                hasSkeleton = localAnalysis.hasStoredSkeleton(analysisId),
            )
        }
    }
    val hasSkeleton = stored.hasSkeleton
    val storedTrack = stored.track
    val hasTrack = done?.playerTrack?.samples?.isNotEmpty() == true || storedTrack != null

    // On-demand cloud pose download for linked players and second phones:
    // fetches poses.raw once and caches it inside the current account's store.
    var downloadAttempt by remember(analysisId) { mutableIntStateOf(0) }
    var downloading by remember(analysisId) { mutableStateOf(false) }
    var downloadCompleted by remember(analysisId) { mutableStateOf(false) }
    var downloadProgress by remember(analysisId) { mutableFloatStateOf(0f) }
    var downloadError by remember(analysisId) { mutableStateOf<String?>(null) }

    LaunchedEffect(analysisId, stored.loaded, hasTrack, hasCloudPose, downloadAttempt) {
        if (!stored.loaded || hasTrack || !hasCloudPose || installCloudPose == null) return@LaunchedEffect
        if (downloadError != null || downloadCompleted) return@LaunchedEffect
        downloading = true
        downloadProgress = 0f
        try {
            installCloudPose(analysisId) { p -> downloadProgress = p.coerceIn(0f, 1f) }
            downloading = false
            downloadCompleted = true
            reloadTick++
            onCloudPoseInstalled()
        } catch (ce: CancellationException) {
            downloading = false
            throw ce
        } catch (_: Throwable) {
            downloading = false
            downloadError = PlayerInviteCopy.ANALYSIS_DOWNLOAD_FAILED
        }
    }

    val awaitingCloudDownload = hasCloudPose &&
        installCloudPose != null &&
        !hasTrack &&
        downloadError == null &&
        !downloadCompleted

    if (downloading || awaitingCloudDownload) {
        Box(modifier.fillMaxSize().padding(horizontal = MatchPageGutter), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = PlayerInviteCopy.ANALYSIS_DOWNLOADING,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    if (!hasTrack && downloadError != null) {
        Box(modifier.fillMaxSize().padding(horizontal = MatchPageGutter), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = downloadError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                ShuttlButton(
                    text = PlayerInviteCopy.ANALYSIS_RETRY,
                    onClick = {
                        downloadError = null
                        downloadCompleted = false
                        downloadAttempt++
                    },
                    variant = ShuttlButtonVariant.Secondary,
                    compact = true,
                )
            }
        }
        return
    }

    if (stored.loaded && !hasTrack && downloadCompleted) {
        Box(modifier.fillMaxSize().padding(horizontal = MatchPageGutter), contentAlignment = Alignment.Center) {
            Text(
                text = "No pose data was found for this match.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // One source for the whole section. A median taken over one run's rally
    // boundaries and plotted from another run's track is two answers on one
    // court, so whichever run heatmapSource will draw also supplies the windows.
    val windows = remember(analysisId, done, stored, cloudClips) {
        rallyWindowsFor(
            preferCloud = storedTrack?.source == TrackSource.CLOUD,
            cloud = cloudRallyWindows(cloudClips, analysisId),
            device = (done?.clips?.takeIf { it.isNotEmpty() } ?: stored.clips)
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
            AnalyticsPanel.Heatmap -> HeatmapPanel(
                entryId = analysisId,
                runner = localAnalysis,
                windows = windows,
                sideNames = sideNames,
                usePreloadedTrack = true,
                preloadedTrackLoaded = stored.loaded,
                preloadedTrack = storedTrack,
            )
            AnalyticsPanel.Base -> BasePositionPanel(
                entryId = analysisId,
                runner = localAnalysis,
                windows = windows,
                sideNames = sideNames,
                usePreloadedTrack = true,
                preloadedTrackLoaded = stored.loaded,
                preloadedTrack = storedTrack,
            )
            AnalyticsPanel.Skeleton -> SkeletonPanel(
                entryId = analysisId,
                videoUri = videoUri,
                runner = localAnalysis,
                prefs = playbackPrefs,
                racketArmPrefs = racketArmPrefs,
                sideNames = sideNames,
            )
            AnalyticsPanel.Drills -> ExercisesPanel(
                entryId = analysisId,
                runner = localAnalysis,
                windows = windows,
                sideNames = sideNames,
                drillProgress = drillProgress,
                usePreloadedTrack = true,
                preloadedTrackLoaded = stored.loaded,
                preloadedTrack = storedTrack,
                currentUserId = currentUserId,
                otherPartyLabel = otherPartyLabel,
                isCoach = isCoach,
                onSyncDrills = onSyncDrills,
            )
        }
    }
}

private data class StoredAnalysis(
    val loaded: Boolean = false,
    val track: PlayerTrackStore.Stored? = null,
    val clips: List<ClipCutter.Clip> = emptyList(),
    val hasSkeleton: Boolean = false,
)
