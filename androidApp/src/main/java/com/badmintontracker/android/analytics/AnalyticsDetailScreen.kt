package com.badmintontracker.android.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.BasePositionPanel
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.SkeletonPanel
import com.badmintontracker.android.localvideo.formatDuration
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository
import kotlinx.datetime.Instant

/** Which renderer the detail is showing. A segment is offered only when it has content. */
internal enum class AnalyticsPanel(val label: String) { Heatmap("Heatmap"), Base("Base"), Skeleton("Skeleton") }

/**
 * Which panels this entry can show, in tab order. The heatmap is always first
 * and always present: it is the screen's reason to exist, and it says why it is
 * empty itself. Base needs a track with samples and rally windows with bounds,
 * which is what [BasePositionPanel] measures from; skeleton needs a stored
 * skeleton.
 */
internal fun availablePanels(hasTrack: Boolean, hasBoundedClips: Boolean, hasSkeleton: Boolean): List<AnalyticsPanel> =
    buildList {
        add(AnalyticsPanel.Heatmap)
        if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Base)
        if (hasSkeleton) add(AnalyticsPanel.Skeleton)
    }

/**
 * The bar's two lines for one entry: the match's name, then its length and
 * the day it was added, as the mock heads the page ("Vitidsarn vs Axelsen"
 * over "Rally 12 of 46 · Aug 21"). The mock's second line names a rally;
 * this screen shows the whole video, so its second line says which video.
 */
internal fun detailSubtitle(entry: LocalVideoEntry): String =
    "${formatDuration(entry.durationMs)} · ${formatDate(Instant.fromEpochMilliseconds(entry.addedAtEpochMs))}"

/**
 * The bar's two lines for a video: the match's name over its length and date,
 * or [fallback] alone when the video is no longer on the phone.
 */
@Composable
fun VideoTitle(entry: LocalVideoEntry?, fallback: String) {
    Column {
        Text(
            // The list's own naming: the user's title, else the file name.
            entry?.title ?: entry?.displayName ?: fallback,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry != null) {
            Text(
                detailSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What one analysed match has to show, reached from a READY row on the
 * Analytics list.
 *
 * A pill tab row between the heatmap, the per-rally base position and the
 * skeleton, drawn only when more than one of them has content. The earlier
 * version of this screen had no tab row, deliberately: with one renderer a
 * single pill read as a primary button that did nothing, and a control that
 * cannot be actuated is worse than a plain heading. Gated the same way the
 * match page gates its facet selector: a tab must have something behind it.
 *
 * The heatmap itself, and the resolution of which track to draw, are
 * [HeatmapPanel]'s - shared with the standalone route the analysis banner
 * opens, so the two cannot drift. The base position is [BasePositionPanel]'s,
 * on the same track. The skeleton is [SkeletonPanel]'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDetailScreen(
    entryId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    onBack: () -> Unit,
) {
    val hasSkeleton = remember(entryId) { localAnalysis.hasStoredSkeleton(entryId) }
    // A run still in memory may carry a track and clips the disk does not yet
    // hold, so both are consulted, the way HeatmapPanel resolves its track.
    val done = localAnalysis.stateFor(entryId) as? LocalAnalysisState.Done
    val hasTrack = remember(entryId, done) {
        done?.playerTrack?.samples?.isNotEmpty() == true || localAnalysis.hasStoredTrack(entryId)
    }
    val hasBoundedClips = remember(entryId, done) {
        (done?.clips?.takeIf { it.isNotEmpty() } ?: localAnalysis.storedClips(entryId))
            .any { it.endSeconds > it.startSeconds }
    }
    val panels = availablePanels(hasTrack, hasBoundedClips, hasSkeleton)
    var chosen by rememberSaveable { mutableStateOf(AnalyticsPanel.Heatmap) }
    val panel = if (chosen in panels) chosen else AnalyticsPanel.Heatmap
    val entry = remember(entryId) { localVideos.get(entryId) }
    val videoUri = entry?.uri

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { VideoTitle(entry = entry, fallback = "Analysis") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (panels.size > 1) {
                ShuttlPillTabs(
                    labels = panels.map { it.label },
                    selectedIndex = panels.indexOf(panel),
                    onSelect = { chosen = panels[it] },
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                )
            } else {
                // Styled as the list's SectionHeader, not as a title: a coach arrives
                // here in one tap from that list, and two treatments of the same thing
                // across those two screens reads as two different kinds of heading.
                Text(
                    "HEATMAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            when (panel) {
                AnalyticsPanel.Heatmap -> HeatmapPanel(entryId = entryId, runner = localAnalysis)
                AnalyticsPanel.Base -> BasePositionPanel(entryId = entryId, runner = localAnalysis)
                AnalyticsPanel.Skeleton -> SkeletonPanel(
                    entryId = entryId,
                    videoUri = videoUri,
                    runner = localAnalysis,
                    prefs = playbackPrefs,
                    racketArmPrefs = racketArmPrefs,
                )
            }
        }
    }
}
