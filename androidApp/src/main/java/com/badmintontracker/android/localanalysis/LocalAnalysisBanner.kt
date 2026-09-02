package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What the on-device pipeline PRODUCED. Progress lives in the app bar.
 *
 * Exists so the two pipelines can be compared without a debugger attached: an
 * on-device run takes minutes, and "did it work" is otherwise only answerable
 * from logcat. Shows the numbers worth comparing against a cloud run of the
 * same video - rallies found, shuttle visibility, and how long it took - and
 * the clips themselves, playable.
 *
 * It deliberately renders nothing while a run is in flight. A card that pushed
 * the whole list down for the nine minutes an analysis takes cost more screen
 * than the one number it carried, and that number is now in the app bar, where
 * it is visible from every screen rather than only this one.
 */
@Composable
fun LocalAnalysisBanner(runner: LocalAnalysisRunner, modifier: Modifier = Modifier) {
    val states by runner.state.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<ClipCutter.Clip?>(null) }

    playing?.let { clip ->
        LocalClipPlayerDialog(clip = clip, onDismiss = { playing = null })
    }

    // Finished or failed only: in-flight states are the app bar indicator's.
    val settled = states.filterValues {
        it is LocalAnalysisState.Done || it is LocalAnalysisState.Failed
    }
    if (settled.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settled.forEach { (entryId, state) ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("On-device analysis", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    when (state) {
                        is LocalAnalysisState.Done -> {
                            Text(
                                "${state.rallies} rallies, ${state.clips.size} clips, " +
                                    "shuttle in ${state.shuttleVisible}/${state.totalFrames} frames, " +
                                    "${"%.0f".format(state.elapsedSeconds)}s",
                            )
                            // The clips themselves, playable. Counts alone
                            // cannot answer whether local cutting is as good
                            // as the cloud's - that needs watching the two
                            // side by side, which is the whole reason both
                            // pipelines are offered.
                            state.clips.forEach { clip ->
                                TextButton(onClick = { playing = clip }) {
                                    Text(
                                        "Rally ${clip.index}  " +
                                            "${"%.1f".format(clip.startSeconds)}s - " +
                                            "${"%.1f".format(clip.endSeconds)}s  " +
                                            "(${"%.1f".format(clip.endSeconds - clip.startSeconds)}s)",
                                    )
                                }
                            }
                        }
                        is LocalAnalysisState.Failed -> Text(
                            "Failed: ${state.message}",
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Filtered out above; the compiler still wants them.
                        else -> Unit
                    }
                    if (state is LocalAnalysisState.Done || state is LocalAnalysisState.Failed) {
                        TextButton(onClick = { runner.clear(entryId) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}
