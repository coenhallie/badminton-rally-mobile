package com.badmintontracker.shared.local

import com.badmintontracker.analysis.Phase1Input
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.rally.ClipWindow
import com.badmintontracker.analysis.result.AnalysisResult
import com.badmintontracker.analysis.result.fromPhase1
import com.badmintontracker.analysis.runPhase1
import com.badmintontracker.analysis.shuttle.ShuttleSample
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis

data class LocalAnalysisOutcome(
    val result: AnalysisResult,
    val clipWindows: List<ClipWindow>,
)

/**
 * Runs a local analysis: raw model output in, results and clip windows out.
 *
 * The local sibling of AnalyzeCoordinator, and deliberately narrower than it.
 * This does no I/O and no networking - it takes a RawInference from the
 * injected engine, calls runPhase1, and returns. Uploading is a separate
 * concern, which is what keeps the entire computation path testable in CI
 * against a fake engine, with no device and no Supabase.
 */
class LocalAnalysisCoordinator(
    private val engine: LocalInferenceEngine,
    private val log: (String) -> Unit = {},
) {

    /**
     * @param keypoints the WIRE type, as the court-marking screen produces it
     *   and as videos.manual_court_keypoints stores it. Converted to the
     *   compute type here so exactly one conversion site exists.
     * @return failure rather than a thrown exception when the engine fails, so
     *   a missing model or an unreadable video is an outcome the caller can
     *   render rather than a crash.
     */
    suspend fun analyze(
        videoPath: String,
        keypoints: CourtKeypoints,
        onProgress: (Float) -> Unit = {},
    ): Result<LocalAnalysisOutcome> = runCatching {
        // Clamped below 1.0: completion is this coordinator's to report, after
        // the analysis that follows inference, not the engine's when decoding
        // stops.
        val raw = engine.run(videoPath) { onProgress(it.coerceAtMost(0.999f)) }
        log("inference produced ${raw.frames.size} frames")

        val outcome = analyse(raw, keypoints, videoPath)
        onProgress(1f)
        outcome
    }

    private fun analyse(
        raw: RawInference,
        keypoints: CourtKeypoints,
        videoPath: String,
    ): LocalAnalysisOutcome {
        val header = raw.header
        val shuttle = raw.frames.associate { frame ->
            frame.frame to (
                frame.shuttle
                    ?.let { ShuttleSample(it.x.toDouble(), it.y.toDouble(), it.visible) }
                    ?: ShuttleSample.INVISIBLE
                )
        }

        val output = runPhase1(
            Phase1Input(
                rawShuttle = shuttle,
                fps = header.fps,
                totalFrames = header.totalFrames,
                videoWidth = header.videoWidth,
                videoHeight = header.videoHeight,
                // The container duration the cloud pads with is persisted
                // nowhere, so totalFrames / fps is the closest available
                // value. See Phase1PipelineTest for what that costs.
                videoDuration = durationSeconds(header.totalFrames, header.fps),
                keypoints = keypoints.toAnalysis(),
            )
        )

        return LocalAnalysisOutcome(
            result = AnalysisResult.fromPhase1(
                output = output,
                fps = header.fps,
                totalFrames = header.totalFrames,
                durationSeconds = durationSeconds(header.totalFrames, header.fps),
                filename = videoPath.substringAfterLast('/'),
            ),
            clipWindows = output.clipWindows,
        )
    }

    private fun durationSeconds(totalFrames: Int, fps: Double): Double =
        if (fps > 0) totalFrames / fps else 0.0
}
