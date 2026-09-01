package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * What the on-device pipeline is doing, and what it produced.
 *
 * Exists so the two pipelines can be compared without a debugger attached: an
 * on-device run takes minutes, and "did it work" is otherwise only answerable
 * from logcat. Shows the numbers worth comparing against a cloud run of the
 * same video - rallies found, shuttle visibility, and how long it took.
 */
@Composable
fun LocalAnalysisBanner(runner: LocalAnalysisRunner, modifier: Modifier = Modifier) {
    val states by runner.state.collectAsStateWithLifecycle()
    if (states.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        states.forEach { (entryId, state) ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("On-device analysis", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    when (state) {
                        is LocalAnalysisState.Idle -> Text("Idle")
                        is LocalAnalysisState.Preparing -> {
                            Text(state.message)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is LocalAnalysisState.Analysing -> {
                            Text("Analysing ${(state.fraction * 100).toInt()}%")
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        is LocalAnalysisState.Cutting -> {
                            Text("Cutting clips ${state.done}/${state.total}")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is LocalAnalysisState.Done -> Text(
                            "${state.rallies} rallies, ${state.clips.size} clips, " +
                                "shuttle in ${state.shuttleVisible}/${state.totalFrames} frames, " +
                                "${"%.0f".format(state.elapsedSeconds)}s",
                        )
                        is LocalAnalysisState.Failed -> Text(
                            "Failed: ${state.message}",
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state is LocalAnalysisState.Done || state is LocalAnalysisState.Failed) {
                        TextButton(onClick = { runner.clear(entryId) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}
