package com.badmintontracker.analysis

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.ClipWindow
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.rally.detectRalliesFromShots
import com.badmintontracker.analysis.rally.detectRalliesGradient
import com.badmintontracker.analysis.rally.padRallyWindows
import com.badmintontracker.analysis.rally.refineRallies
import com.badmintontracker.analysis.rally.unionRallies
import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shuttle.ShuttleSample
import com.badmintontracker.analysis.shuttle.buildFilteredTrack

data class Phase1Input(
    val rawShuttle: Map<Int, ShuttleSample>,
    val fps: Double,
    val totalFrames: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoDuration: Double?,
    val keypoints: CourtKeypoints?,
)

data class Phase1Output(
    /** Gradient union shot-gap. Goes into results.json, matching the cloud. */
    val storedRallies: List<Rally>,
    /** The confirmed separation, padded. Drives clip cutting. */
    val clipWindows: List<ClipWindow>,
    val filteredTrack: Map<Int, ShuttleSample>,
)

/**
 * Phase 1: raw shuttle track in, rally list and clip windows out.
 *
 * Two rally sets leave here, exactly as in the cloud worker:
 *   - storedRallies: gradient union raw shot-gap, the "combined" set, so both
 *     timelines remain available to any consumer of results.json
 *   - clipWindows:   filtered shot-gap refined toward raw bounds, then padded.
 *     This is the separation the apps actually see as clips.
 *
 * The two shot-gap runs use different tracks on purpose. The filtered track's
 * noise rejection gives trustworthy splits but trims tails; the raw track has
 * accurate bounds but fabricates rallies in idle windows.
 *
 * One known divergence from the worker, and it lives on the raw side. There,
 * the raw track is what the detection loop wrote into `skeleton_frames`, which
 * is TrackNet's output already gated by a permissive court ROI - x expanded
 * 1.40 about the centroid, and every vertex above the centroid clamped to
 * y = 0. Here `rawShuttle` is taken as given. Feeding this unfiltered TrackNet
 * output is therefore MORE permissive than the cloud, so `refineRallies` can
 * widen bounds toward rally noise the cloud had already discarded. Callers
 * that want cloud parity should apply that ROI before calling in; the golden
 * comparison is what will show whether it matters in practice.
 */
fun runPhase1(input: Phase1Input): Phase1Output {
    val fps = normalizeFps(input.fps).fps
    val filtered = buildFilteredTrack(
        raw = input.rawShuttle,
        fps = fps,
        videoWidth = input.videoWidth,
        videoHeight = input.videoHeight,
        courtCorners = input.keypoints?.corners,
    )
    return runPhase1FromTracks(
        fusionTrack = input.rawShuttle,
        filteredTrack = filtered,
        fps = fps,
        totalFrames = input.totalFrames,
        videoDuration = input.videoDuration,
    )
}

/**
 * Phase 1 from the two tracks it actually consumes, skipping the filter.
 *
 * [runPhase1] is the production path: it derives the filtered track from raw
 * model output. This entry point exists because the cloud persists both tracks
 * and never persists the raw one, so a comparison against recorded cloud
 * output has to supply them rather than recompute them. Feeding the cloud's
 * filtered track back through `buildFilteredTrack` would filter it twice, and
 * on a real capture that silently drops a further 279 of 605 visible positions.
 *
 * The filter itself is verified separately, against the worker's own Python,
 * by ShuttleTrackParityTest.
 */
fun runPhase1FromTracks(
    fusionTrack: Map<Int, ShuttleSample>,
    filteredTrack: Map<Int, ShuttleSample>,
    fps: Double,
    totalFrames: Int,
    videoDuration: Double?,
): Phase1Output {
    val filtered = filteredTrack
    val gradient = detectRalliesGradient(filtered, fps, totalFrames)

    // Both detectors read a frame list, and "no shuttle here" has to mean the
    // same thing in both representations: buildFilteredTrack marks a rejected
    // position INVISIBLE rather than dropping it, and the shot detector reads
    // absence as null. The worker performs the same remap when it builds
    // filtered_frames, so this conversion is part of the port, not glue.
    //
    // The LENGTH of this list matters as much as its contents. The shot
    // detector divides by it to get coverage, and coverage decides whether
    // stride subsampling runs at all - which changes every shot and therefore
    // every rally boundary, not some rounding detail. Using totalFrames is
    // right because the worker's detection loop appends one skeleton_frame per
    // decoded frame with no stride or skip, so its denominator is the same
    // number.
    //
    // Note the worker numbers those frames from 1, not 0: it increments
    // frame_count before using it, so shuttle_positions in a captured
    // results.json is 1-based. Indexing from 0 here is deliberate and is
    // divergence 3 in the design register.
    fun frames(track: Map<Int, ShuttleSample>): List<FrameSample> =
        (0 until totalFrames).map { f ->
            FrameSample(f, f / fps, track[f]?.takeIf { it.visible })
        }

    val rawShotGap = detectRalliesFromShots(frames(fusionTrack), fps)
    val filteredShotGap = detectRalliesFromShots(frames(filtered), fps)

    val stored = unionRallies(gradient, rawShotGap, fps)
    val clipRallies = refineRallies(filteredShotGap, rawShotGap, fps)
        .ifEmpty { stored }

    return Phase1Output(
        storedRallies = stored,
        clipWindows = padRallyWindows(clipRallies, videoDuration),
        filteredTrack = filtered,
    )
}
