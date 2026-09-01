package com.badmintontracker.android.localanalysis

import android.content.Context
import android.net.Uri
import com.badmintontracker.shared.local.LocalAnalysisCoordinator
import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where a local analysis has got to, per video. */
sealed interface LocalAnalysisState {
    data object Idle : LocalAnalysisState
    data class Preparing(val message: String) : LocalAnalysisState
    data class Analysing(val fraction: Float) : LocalAnalysisState
    data class Cutting(val done: Int, val total: Int) : LocalAnalysisState
    data class Done(
        val rallies: Int,
        val shuttleVisible: Int,
        val totalFrames: Int,
        val clips: List<ClipCutter.Clip>,
        val elapsedSeconds: Double,
    ) : LocalAnalysisState
    data class Failed(val message: String) : LocalAnalysisState
}

/**
 * Runs the on-device pipeline for one video and keeps its progress.
 *
 * Held by the application rather than a ViewModel because an analysis outlives
 * the screen that starts it - at roughly 235ms a frame a one-minute clip takes
 * about seven minutes, and navigating away must not cancel it.
 *
 * Deliberately not merged into `AnalyzeCoordinator`. That drives the cloud
 * path, and the whole point of running both is that they stay separable enough
 * to compare.
 */
class LocalAnalysisRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    /**
     * Which analyses are in flight.
     *
     * Tracked separately from [states] because the state is what the UI reads
     * and it moves through Preparing, Analysing and Cutting; "is anything
     * running" is a different question and the service's lifetime depends on
     * getting it right rather than on matching a particular state.
     */
    private val running = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val states = MutableStateFlow<Map<String, LocalAnalysisState>>(emptyMap())
    val state: StateFlow<Map<String, LocalAnalysisState>> = states

    fun stateFor(entryId: String): LocalAnalysisState =
        states.value[entryId] ?: LocalAnalysisState.Idle

    fun start(entryId: String, videoUri: String, keypoints: CourtKeypoints) {
        if (running.contains(entryId)) return
        running.add(entryId)
        // Held for as long as anything is in flight, and released in a finally
        // so a crash cannot strand a wake lock until reboot.
        LocalAnalysisService.start(context, "Preparing")
        scope.launch(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            try {
                set(entryId, LocalAnalysisState.Preparing("Copying video"))
                // MediaExtractor and MediaMetadataRetriever both want a real
                // path, and a gallery pick is a content:// URI whose backing
                // file the app cannot open directly.
                val local = materialise(videoUri, entryId)

                set(entryId, LocalAnalysisState.Analysing(0f))
                LocalAnalysisService.start(context, "Analysing")
                val outcome = LocalAnalysisCoordinator(
                    engine = AndroidLocalInferenceEngine(context),
                    log = log,
                ).analyze(local.path, keypoints) { f ->
                    set(entryId, LocalAnalysisState.Analysing(f))
                }
                val result = outcome.getOrElse {
                    set(entryId, LocalAnalysisState.Failed(it.message ?: "analysis failed"))
                    return@launch
                }

                val windows = result.clipWindows
                set(entryId, LocalAnalysisState.Cutting(0, windows.size))
                LocalAnalysisService.start(context, "Cutting ${windows.size} clips")
                val dir = File(context.filesDir, "local-clips/$entryId").apply { mkdirs() }
                val clips = ClipCutter().cut(local, windows, dir)

                set(
                    entryId,
                    LocalAnalysisState.Done(
                        rallies = result.result.rallies.size,
                        shuttleVisible = result.result.shuttlePositions.count { it.value.visible },
                        totalFrames = result.result.totalFrames,
                        clips = clips,
                        elapsedSeconds = (System.currentTimeMillis() - started) / 1000.0,
                    ),
                )
                log("local analysis done: ${clips.size} clips in ${(System.currentTimeMillis() - started) / 1000}s")
            } catch (t: Throwable) {
                set(entryId, LocalAnalysisState.Failed(t.message ?: t::class.simpleName ?: "failed"))
                log("local analysis failed: $t")
            } finally {
                running.remove(entryId)
                // Only when nothing is left: two videos analysed back to back
                // share one service, and stopping on the first would drop the
                // wake lock under the second.
                if (running.isEmpty()) LocalAnalysisService.stop(context)
            }
        }
    }

    fun clear(entryId: String) = states.update { it - entryId }

    private fun set(entryId: String, s: LocalAnalysisState) =
        states.update { it + (entryId to s) }

    /** Copy a content:// video into app storage so the media APIs can open it. */
    private fun materialise(videoUri: String, entryId: String): File {
        val direct = File(videoUri.removePrefix("file://"))
        if (direct.isFile && direct.canRead()) return direct
        val out = File(context.filesDir, "local-sources/$entryId.mp4")
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        context.contentResolver.openInputStream(Uri.parse(videoUri))
            ?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("Video file is missing or access was revoked")
        return out
    }
}
