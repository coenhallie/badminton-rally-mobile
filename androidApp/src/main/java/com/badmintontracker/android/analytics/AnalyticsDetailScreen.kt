package com.badmintontracker.android.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner

/**
 * What one analysed match has to show, reached from a READY row on the
 * Analytics list.
 *
 * No tab row, deliberately. The plan called for one, and on the device a single
 * green pill was indistinguishable from a primary button that does nothing when
 * tapped - and in light theme its track (#F5F7F6 on white) was invisible, so
 * even the segmented-control reading was gone. A control that cannot be actuated
 * is worse than a plain heading. Heatmap is the only view with anything behind
 * it: skeleton playback is drawn by SkeletonOverlay, which has no host screen and
 * no caller anywhere in the app. The tab row arrives with the second renderer,
 * which is when it will have something to switch to.
 *
 * The heatmap itself, and the resolution of which track to draw, are
 * [HeatmapPanel]'s - shared with the standalone route the analysis banner
 * opens, so the two cannot drift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDetailScreen(
    entryId: String,
    localAnalysis: LocalAnalysisRunner,
    onBack: () -> Unit,
) {
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
            // Styled as the list's SectionHeader, not as a title: a coach arrives
            // here in one tap from that list, and two treatments of the same thing
            // across those two screens reads as two different kinds of heading.
            Text(
                "HEATMAP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HeatmapPanel(entryId = entryId, runner = localAnalysis)
        }
    }
}
