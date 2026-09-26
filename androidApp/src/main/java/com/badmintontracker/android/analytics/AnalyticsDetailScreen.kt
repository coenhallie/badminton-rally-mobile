package com.badmintontracker.android.analytics

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localvideo.formatDuration
import com.badmintontracker.android.match.AnalysisSection
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository
import kotlinx.datetime.Instant

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
 * The Analytics tab's wrapper over [AnalysisSection], reached from a READY
 * row on that tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDetailScreen(
    entryId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    /**
     * Every rally clip the cache holds. The rally truth for a CLOUD-analysed
     * video, which cuts nothing on this phone: without these the Base tab could
     * never appear for one, however good its track.
     */
    cloudClips: List<RallyClip>,
    /**
     * The list's name for this match, for when there is no entry to read one
     * from. A cloud run finishing does not cause this - the entry stays at
     * ANALYZED rather than being retired (since f96d78c) - so it is the video
     * having since left the phone (`freeVideo` drops the entry but keeps the
     * track) or a match analysed on another phone entirely.
     */
    fallbackTitle: String? = null,
    onBack: () -> Unit,
) {
    val entry = remember(entryId) { localVideos.get(entryId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { VideoTitle(entry = entry, fallback = fallbackTitle ?: "Analysis") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
    ) { padding ->
        AnalysisSection(
            analysisId = entryId,
            localAnalysis = localAnalysis,
            localVideos = localVideos,
            playbackPrefs = playbackPrefs,
            racketArmPrefs = racketArmPrefs,
            cloudClips = cloudClips,
            modifier = Modifier.padding(padding),
        )
    }
}
