package com.badmintontracker.android.localanalysis

import android.content.Context
import android.net.Uri
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.shared.local.AnalysisMetric
import com.badmintontracker.shared.local.DeviceThroughputRepository
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
        /** Empty unless a metric that needs pose was asked for. */
        val playerTrack: PlayerTrack,
        val fps: Double,
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
    private val throughput: DeviceThroughputRepository,
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

    private val tracks = PlayerTrackStore(context.filesDir)

    /** The track from an earlier run, for a screen opened after this one died. */
    fun storedTrack(entryId: String): PlayerTrackStore.Stored? = tracks.load(entryId)

    private val states = MutableStateFlow<Map<String, LocalAnalysisState>>(emptyMap())
    val state: StateFlow<Map<String, LocalAnalysisState>> = states

    fun stateFor(entryId: String): LocalAnalysisState =
        states.value[entryId] ?: LocalAnalysisState.Idle

    fun start(
        entryId: String,
        videoUri: String,
        keypoints: CourtKeypoints,
        metrics: Set<AnalysisMetric> = setOf(AnalysisMetric.RALLY_CLIPS),
    ) {
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
                val wantsPose = metrics.any { it.needsPose }
                val inferenceStarted = System.currentTimeMillis()
                val outcome = LocalAnalysisCoordinator(
                    engine = AndroidLocalInferenceEngine(
                        context = context,
                        // Loaded only when asked for: pose roughly doubles the
                        // run, so a user who wanted clips must not pay for it.
                        poseModelPath = if (wantsPose) {
                            ModelCatalog.path(context, Model.POSE)
                        } else {
                            null
                        },
                    ),
                    log = log,
                ).analyze(local.path, keypoints) { f ->
                    set(entryId, LocalAnalysisState.Analysing(f))
                }
                val inferenceMs = (System.currentTimeMillis() - inferenceStarted).toDouble()
                val result = outcome.getOrElse {
                    set(entryId, LocalAnalysisState.Failed(it.message ?: "analysis failed"))
                    return@launch
                }

                // What this phone actually managed, so the next estimate is
                // its own rather than a reference device's.
                val frames = result.result.totalFrames
                if (frames > 0) {
                    val perFrame = inferenceMs / frames
                    throughput.record(
                        // Pose and base share one decode pass, so the split is
                        // the ratio the two models were measured at rather than
                        // something this run can separate.
                        baseMsPerFrame = if (wantsPose) perFrame * BASE_SHARE else perFrame,
                        poseMsPerFrame = if (wantsPose) perFrame * (1 - BASE_SHARE) else null,
                        cutMsPerClipSecond = null,
                    )
                }

                // Written before the clips are cut, which is minutes of work:
                // a track that survived the analysis should not be lost to a
                // failure in the step after it.
                if (result.playerTrack.samples.isNotEmpty()) {
                    tracks.save(entryId, result.playerTrack, result.result.fps)
                }

                val windows = if (AnalysisMetric.RALLY_CLIPS in metrics) result.clipWindows else emptyList()
                set(entryId, LocalAnalysisState.Cutting(0, windows.size))
                if (windows.isNotEmpty()) LocalAnalysisService.start(context, "Cutting ${windows.size} clips")
                val dir = File(context.filesDir, "local-clips/$entryId").apply { mkdirs() }
                val clips = if (windows.isEmpty()) emptyList() else ClipCutter().cut(local, windows, dir)

                set(
                    entryId,
                    LocalAnalysisState.Done(
                        rallies = result.result.rallies.size,
                        shuttleVisible = result.result.shuttlePositions.count { it.value.visible },
                        totalFrames = result.result.totalFrames,
                        clips = clips,
                        elapsedSeconds = (System.currentTimeMillis() - started) / 1000.0,
                        playerTrack = result.playerTrack,
                        fps = result.result.fps,
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

    private companion object {
        /**
         * Base's share of a combined run, from the measured 235ms against
         * pose's 230ms. One timer cannot separate two models sharing a decode
         * pass, so the split is applied rather than observed.
         */
        const val BASE_SHARE = 0.51
    }

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
