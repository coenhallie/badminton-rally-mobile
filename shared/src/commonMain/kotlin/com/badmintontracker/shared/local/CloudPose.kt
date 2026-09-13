package com.badmintontracker.shared.local

import com.badmintontracker.analysis.normalizeFps
import com.badmintontracker.analysis.player.PlayerSelection
import com.badmintontracker.analysis.player.selectPlayers
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis
import com.badmintontracker.analysis.geometry.CourtKeypoints as AnalysisCourtKeypoints

/**
 * What one cloud analysis leaves on the phone.
 *
 * Deliberately narrower than [LocalAnalysisOutcome], and the difference IS the
 * design. There is no AnalysisResult here and no clip window, because
 * rally_clips is the rally truth for a cloud video: three rally lists already
 * exist for one video and disagree (see the 2026-08-31 pipeline reference,
 * 8.9), and a fourth computed on the phone would move boundaries under clips a
 * coach has already annotated.
 *
 * A type with no rally in it is a stronger guarantee than a comment asking
 * nobody to read one.
 */
data class CloudPoseOutcome(
    /** Both players, NEAR first, each with its track and its joints. */
    val selections: List<PlayerSelection>,
    val fps: Double,
    /** What the poses are measured in, so a renderer can fit them to a display box. */
    val videoWidth: Int,
    val videoHeight: Int,
    /**
     * The court these positions were projected through.
     *
     * Carried out rather than left to the caller to remember, for
     * SkeletonStore's reason: a reading in metres needs the homography these
     * marks fit, and a file that carries what it needs cannot be paired with
     * the wrong marks.
     */
    val marks: AnalysisCourtKeypoints,
)

/**
 * Runs the player selection over a pose stream the cloud produced.
 *
 * The cloud's whole contribution is the stream. Which player is which, which
 * half of the court they are on, whether a detection stands on a plausible
 * ground point at a plausible scale, and where that puts them in court metres
 * are all decided here, by the same [selectPlayers] a device run uses, from
 * the same court marks. Nothing about this function knows the poses came from
 * an A10G rather than an NPU, which is the point: one implementation, and the
 * only thing that crossed a language boundary was model output.
 *
 * @param keypoints the WIRE type, as the court-marking screen produces it and
 *   as videos.manual_court_keypoints stores it. Converted to the compute type
 *   here so exactly one conversion site exists, matching
 *   [LocalAnalysisCoordinator.analyse].
 */
fun poseOnlyAnalysis(raw: RawInference, keypoints: CourtKeypoints): CloudPoseOutcome {
    val marks = keypoints.toAnalysis()
    return CloudPoseOutcome(
        selections = selectPlayers(raw, marks),
        // The same normalisation every other path applies. A container the
        // prober could not read arrives as a zero, and it divides into every
        // occupancy figure the heatmap draws.
        fps = normalizeFps(raw.header.fps).fps,
        videoWidth = raw.header.videoWidth,
        videoHeight = raw.header.videoHeight,
        marks = marks,
    )
}
