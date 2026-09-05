package com.badmintontracker.android.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.HeatmapPanel
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * What one analysed match has to show, reached from a READY row on the
 * Analytics list.
 *
 * The tab row is a pill track with a single tab, because Heatmap is the only
 * thing with anything behind it. Skeleton playback is drawn by SkeletonOverlay,
 * which has no host screen and no caller anywhere in the app; giving it one is
 * product work rather than a redesign, so a second tab here would name a view
 * that does not exist.
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
            HeatmapTabRow()
            HeatmapPanel(entryId = entryId, runner = localAnalysis)
        }
    }
}

/**
 * The pill tab track, holding the one tab that ships.
 *
 * Not clickable, and not Material's TabRow. With a single destination there is
 * nothing to switch to, so a tappable pill would be a control that does
 * nothing; this says which view is on screen instead. Built from the tokens the
 * spec maps onto tabs directly - bgSecondary is the track, pill is the tab -
 * the same way ShuttlButton builds its own pill, so no Material container is
 * involved to resolve a colour of its own.
 */
@Composable
private fun HeatmapTabRow() {
    val trackShape = RoundedCornerShape(ShuttlRadius.pill)
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, trackShape)
            .padding(4.dp),
    ) {
        Text(
            "Heatmap",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ShuttlTheme.extended.onAccent,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, trackShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
