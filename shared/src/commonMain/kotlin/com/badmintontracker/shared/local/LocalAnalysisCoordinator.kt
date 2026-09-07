package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.PlayerPose
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RejectionReason
import com.badmintontracker.analysis.player.selectNearPlayer
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.rally.ClipWindow
import com.badmintontracker.analysis.result.AnalysisResult
import com.badmintontracker.analysis.result.fromPhase1
import com.badmintontracker.analysis.runPhase1FromTracks
import com.badmintontracker.analysis.shuttle.ShuttleDetection
import com.badmintontracker.analysis.shuttle.ShuttleSample
import com.badmintontracker.analysis.shuttle.buildFilteredTrack
import com.badmintontracker.analysis.shuttle.buildFusionTrack
import com.badmintontracker.analysis.normalizeFps
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis

data class LocalAnalysisOutcome(
    val result: AnalysisResult,
    val clipWindows: List<ClipWindow>,
    /**
     * The near player's path, or an empty track when pose did not run.
     *
     * Empty rather than null: a Phase 1 run and a Phase 2 run that found
     * nobody are different facts, and [PlayerTrack] already distinguishes them
     * through framesWithPose and its rejection counts. A null would collapse
     * the two into "no data" at exactly the point a coach asks why the heatmap
     * is blank.
     */
    val playerTrack: PlayerTrack,
    /**
     * The same player's joints, one per sample, in source pixels. Always
     * computed: the selection produces them for free. Whether they are KEPT
     * is the runner's decision, from the metrics the coach asked for.
     */
    val poses: List<PlayerPose>,
    /** What [poses] are measured in, so a renderer can fit them to a display box. */
    val videoWidth: Int,
    val videoHeight: Int,
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
        val fps = normalizeFps(header.fps).fps
        val corners = keypoints.toAnalysis().corners

        // RawInference carries the UNFILTERED TrackNet peak, because the cloud
        // derives two different tracks from it and they disagree about which
        // frames carry a shuttle.
        val trackNet = raw.frames.associate { frame ->
            frame.frame to (
                frame.shuttle
                    ?.let { ShuttleSample(it.x.toDouble(), it.y.toDouble(), it.visible) }
                    ?: ShuttleSample.INVISIBLE
                )
        }
        val detections = raw.frames
            .filter { it.boxes.isNotEmpty() }
            .associate { frame ->
                frame.frame to frame.boxes.map {
                    ShuttleDetection(
                        x = ((it.x1 + it.x2) / 2).toDouble(),
                        y = ((it.y1 + it.y2) / 2).toDouble(),
                        confidence = it.confidence.toDouble(),
                    )
                }
            }

        // The gradient detector reads the filtered track, built from raw
        // TrackNet; the shot-gap detector reads the TrackNet/YOLO fusion.
        // Feeding either one to both is the mistake this split exists to
        // avoid - on a real capture the two differ by 4,355 frames against
        // 3,015.
        val filtered = buildFilteredTrack(
            raw = trackNet,
            fps = fps,
            videoWidth = header.videoWidth,
            videoHeight = header.videoHeight,
            courtCorners = corners,
        )
        val fusion = buildFusionTrack(
            trackNet = trackNet,
            detections = detections,
            fps = fps,
            videoWidth = header.videoWidth,
            videoHeight = header.videoHeight,
            courtCorners = corners,
            totalFrames = header.totalFrames,
        )

        val output = runPhase1FromTracks(
            fusionTrack = fusion,
            filteredTrack = filtered,
            fps = fps,
            totalFrames = header.totalFrames,
            // The container duration the cloud pads with is persisted nowhere,
            // so totalFrames / fps is the closest available value. See
            // Phase1PipelineTest for what that costs.
            videoDuration = durationSeconds(header.totalFrames, fps),
        )

        // Empty unless the engine was given a pose model, since Phase 1 frames
        // carry no persons at all.
        val selection = selectNearPlayer(raw, keypoints.toAnalysis())
        val playerTrack = selection.track
        if (playerTrack.rejections.containsKey(RejectionReason.BAD_COURT)) {
            log("near player: the court marks do not fit a court; no positions taken")
        } else if (playerTrack.framesWithPose > 0) {
            log(
                "near player: ${playerTrack.samples.size} samples over " +
                    "${playerTrack.framesWithPose} pose frames",
            )
        }

        return LocalAnalysisOutcome(
            playerTrack = playerTrack,
            poses = selection.poses,
            videoWidth = header.videoWidth,
            videoHeight = header.videoHeight,
            result = AnalysisResult.fromPhase1(
                output = output,
                fps = fps,
                totalFrames = header.totalFrames,
                durationSeconds = durationSeconds(header.totalFrames, fps),
                filename = videoPath.substringAfterLast('/'),
            ),
            clipWindows = output.clipWindows,
        )
    }

    private fun durationSeconds(totalFrames: Int, fps: Double): Double =
        if (fps > 0) totalFrames / fps else 0.0
}
