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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.SkeletonPanel
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository

/** Which renderer the detail is showing. Only offered when both exist. */
internal enum class AnalyticsPanel { Heatmap, Skeleton }

/**
 * What one analysed match has to show, reached from a READY row on the
 * Analytics list.
 *
 * A two-segment control between the heatmap and the skeleton, drawn only when
 * a skeleton is stored for this video. The earlier version of this screen had
 * no tab row, deliberately: with one renderer a single pill read as a primary
 * button that did nothing, and a control that cannot be actuated is worse
 * than a plain heading. Two segments with two things behind them is the case
 * that version was waiting for. Built as the match page builds its facet
 * selector, and gated the same way: both sides must have content.
 *
 * The heatmap itself, and the resolution of which track to draw, are
 * [HeatmapPanel]'s - shared with the standalone route the analysis banner
 * opens, so the two cannot drift. The skeleton is [SkeletonPanel]'s.
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
    var panel by rememberSaveable { mutableStateOf(AnalyticsPanel.Heatmap) }
    val videoUri = remember(entryId) { localVideos.get(entryId)?.uri }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("ANALYSIS") },
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
            if (hasSkeleton) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    listOf(AnalyticsPanel.Heatmap to "Heatmap", AnalyticsPanel.Skeleton to "Skeleton")
                        .forEachIndexed { index, (candidate, label) ->
                            SegmentedButton(
                                selected = panel == candidate,
                                onClick = { panel = candidate },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                }
            } else {
                // Styled as the list's SectionHeader, not as a title: a coach arrives
                // here in one tap from that list, and two treatments of the same thing
                // across those two screens reads as two different kinds of heading.
                Text(
                    "HEATMAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            when (if (hasSkeleton) panel else AnalyticsPanel.Heatmap) {
                AnalyticsPanel.Heatmap -> HeatmapPanel(entryId = entryId, runner = localAnalysis)
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
