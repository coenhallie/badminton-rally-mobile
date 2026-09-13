package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.repo.CloudPoseRepository

/**
 * Fetches one video's cloud pose artifact and turns it into a stored analysis.
 *
 * The cloud sibling of [LocalAnalysisCoordinator], and the same shape: the
 * platform supplies the capabilities (the network, and the stores behind
 * `save`) and this owns the sequencing, so the whole path runs in CI against a
 * fake with no phone and no Supabase.
 *
 * What it does NOT do is detect a rally. [poseOnlyAnalysis] has nowhere to put
 * one and [CloudPoseOutcome] has no field for one; rally_clips is the rally
 * truth for a cloud video.
 */
class CloudPoseCoordinator(
    private val repository: CloudPoseRepository,
    private val log: (String) -> Unit = {},
) {

    /**
     * @param save called once, with the finished analysis, before this
     *   returns. A lambda rather than a store because the stores are platform
     *   types; the platform decides where an artifact lands, this decides when
     *   there is one to land.
     * @return `false` when the cloud has no artifact for this video, which is
     *   an ordinary outcome and not a failure: every video analysed before
     *   this shipped is in that state, and so is one whose artifact upload
     *   failed on the worker.
     */
    suspend fun install(
        videoId: String,
        onProgress: (Float) -> Unit = {},
        save: (CloudPoseOutcome) -> Unit,
    ): Result<Boolean> = runCatching {
        // The marks ride on the artifact, from the same videos row. Not taken
        // as a parameter: a match uploaded from another of this coach's
        // phones has no LocalVideoEntry here and so no local copy of them,
        // and that match is exactly what READY_NO_VIDEO exists for.
        val artifact = repository.artifact(videoId).getOrThrow() ?: return@runCatching false
        log("cloud pose: ${artifact.frameCount} frames at ${artifact.storagePath}")

        // Clamped below 1.0 by the repository: completion is this
        // coordinator's to report, after the selection that follows the
        // transfer, not the transfer's when the bytes land.
        val bytes = repository.download(artifact) { onProgress(it.coerceAtMost(0.999f)) }.getOrThrow()

        // Throws on a bad magic or a truncated stream, deliberately, and the
        // throw becomes a failed Result here rather than a video that quietly
        // has no heatmap. See RawInferenceCodec's Reader.take.
        val raw = RawInferenceCodec.decode(bytes)
        val outcome = poseOnlyAnalysis(raw, artifact.keypoints)
        outcome.selections.forEach { selection ->
            log(
                "cloud pose ${selection.side}: ${selection.track.samples.size} samples over " +
                    "${selection.track.framesWithPose} pose frames",
            )
        }

        save(outcome)
        onProgress(1f)
        true
    }
}
