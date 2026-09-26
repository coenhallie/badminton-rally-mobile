package com.badmintontracker.android.localvideo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.match.MatchVideoSection
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository

/**
 * Plays a local recording from its content:// URI with the same frame-step and
 * fullscreen behavior as analyzed clips, plus on-phone timestamped annotations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(
    vm: LocalPlayerViewModel,
    entry: LocalVideoEntry,
    canAnalyze: Boolean,
    playbackPrefs: PlaybackPreferenceRepository,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
) {
    var isFullscreen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            entry.title ?: entry.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (canAnalyze) {
                            ShuttlButton(
                                text = analyzeButtonLabel(entry.stage),
                                onClick = onAnalyze,
                                variant = ShuttlButtonVariant.Primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // Starting a run from here returns to this screen, so this
                        // is where the run has to be visible. Every other screen
                        // that can start one already carries this.
                        BackgroundWorkAction()
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MatchVideoSection(
            vm = vm,
            entry = entry,
            playbackPrefs = playbackPrefs,
            fullscreen = isFullscreen,
            onFullscreenChange = { isFullscreen = it },
            snackbar = snackbar,
            analyzeLabel = null,
            onAnalyze = onAnalyze,
            modifier = Modifier.padding(if (isFullscreen) PaddingValues() else padding),
        )
    }
}
