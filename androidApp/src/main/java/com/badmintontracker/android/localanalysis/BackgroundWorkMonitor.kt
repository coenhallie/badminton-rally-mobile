package com.badmintontracker.android.localanalysis

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.BackgroundWork
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.backgroundWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the app is working on, for the chrome indicator.
 *
 * Application-scoped rather than a ViewModel: a run outlives every screen that
 * can show it, and the indicator appears on most of them, so tying this to any
 * one `ViewModelStoreOwner` would restart the collection as the user navigates.
 *
 * Takes flows rather than the two coordinators: it needs nothing else from
 * them, and depending on the objects would drag a Context and a Supabase client
 * into a class whose entire job is a fold over three streams.
 *
 * It only assembles inputs. The rules for what they mean live in
 * [backgroundWork] in shared code, where iOS will reach them too and where they
 * are tested without a device.
 */
class BackgroundWorkMonitor(
    entries: StateFlow<List<LocalVideoEntry>>,
    progress: StateFlow<Map<String, AnalyzeProgress>>,
    device: StateFlow<Map<String, LocalAnalysisState>>,
    scope: CoroutineScope,
) {
    // Kept apart so neither source can clear the other's badge: the same video
    // can have failed in the cloud and be running on the device.
    private val cloudFailures = MutableStateFlow<Set<String>>(emptySet())
    private val deviceFailures = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            // Transitions in both directions. An entry already FAILED when
            // collection starts failed in an earlier run of the app, and its row
            // already carries the failure and the Retry that clears it; badging
            // it here would put a permanent mark on the chrome. Equally, a badge
            // that only ever accumulates is the same bug: retry and succeed, and
            // the dot would outlive the failure with nothing left to click.
            var seen = emptyMap<String, AnalyzeStage>()
            entries.collect { current ->
                val stages = current.associate { it.id to it.stage }
                val failed = stages.filter { (id, stage) ->
                    val before = seen[id]
                    stage == AnalyzeStage.FAILED && before != null && before != AnalyzeStage.FAILED
                }.keys
                val resolved = seen.keys.filter { id ->
                    // Retried into any other stage, or removed from the library.
                    val now = stages[id]
                    now == null || (seen[id] == AnalyzeStage.FAILED && now != AnalyzeStage.FAILED)
                }.toSet()
                seen = stages
                if (failed.isNotEmpty() || resolved.isNotEmpty()) {
                    cloudFailures.update { (it + failed) - resolved }
                }
            }
        }
        scope.launch {
            // Assigned, not accumulated: the runner replaces a Failed state the
            // moment a retry starts, so this clears itself.
            device.collect { states ->
                deviceFailures.value = states.filterValues { it is LocalAnalysisState.Failed }.keys
            }
        }
    }

    private val sessionFailures = combine(cloudFailures, deviceFailures) { cloud, dev -> cloud + dev }

    val work: StateFlow<BackgroundWork?> = combine(
        entries,
        progress,
        device,
        sessionFailures,
    ) { current, progressMap, deviceMap, failures ->
        backgroundWork(
            current,
            progressMap,
            deviceMap.mapNotNull { (id, state) -> toDeviceWork(id, state) },
            failures,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
