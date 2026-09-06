package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawPerson

/**
 * The near player's path across the match, and how well it is known.
 *
 * The counts travel with the samples rather than being logged and dropped. A
 * track of 400 samples means something different when it came from 420 frames
 * than from 4000, and a heatmap drawn from the second while described as the
 * first is the kind of wrong that looks completely healthy.
 */
data class PlayerTrack(
    val samples: List<PlayerSample>,
    val framesWithPose: Int,
    val rejections: Map<RejectionReason, Int>,
) {
    /** Fraction of pose frames that produced a position. */
    val coverage: Double get() = if (framesWithPose == 0) 0.0 else samples.size.toDouble() / framesWithPose

    /**
     * Fraction of samples standing on ankles rather than hips.
     *
     * Worth reading before the heatmap is believed: a hip is about a metre
     * above the court plane and projects long, so a track built mostly on hips
     * is biased away from the camera by more than the gap between any two pose
     * model sizes.
     */
    val ankleFraction: Double
        get() = if (samples.isEmpty()) 0.0 else samples.count { it.onAnkles }.toDouble() / samples.size
}

private fun RawPerson.toPosePerson(): PosePerson = PosePerson(
    boxConfidence = box.confidence,
    keypoints = keypoints.map { Point(it.x.toDouble(), it.y.toDouble()) },
    keypointConfidence = keypoints.map { it.confidence },
)

/**
 * Builds the near player's track from raw model output.
 *
 * Pure, so the whole of Phase 2's judgement is testable without a phone: the
 * platform layer's only job was to put keypoints in [RawInference].
 */
fun buildNearPlayerTrack(raw: RawInference, keypoints: CourtKeypoints): PlayerTrack {
    val selector = NearPlayerSelector(
        keypoints,
        raw.header.videoWidth.toDouble(),
        raw.header.videoHeight.toDouble(),
    )
    // An unusable court is not a thin track, it is no track. Returning an empty
    // one with the reason counted keeps that distinguishable from a match where
    // the player was simply never found.
    if (!selector.usable) {
        return PlayerTrack(emptyList(), 0, mapOf(RejectionReason.NO_PEOPLE to raw.frames.size))
    }

    val samples = ArrayList<PlayerSample>()
    val rejections = mutableMapOf<RejectionReason, Int>()
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a player,
        // so they must not dilute coverage.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val result = selector.select(PoseFrame(frame.frame, frame.persons.map { it.toPosePerson() }))
        val sample = result.sample
        if (sample != null) {
            samples.add(sample)
        } else {
            result.rejection?.let { rejections[it] = (rejections[it] ?: 0) + 1 }
        }
    }
    return PlayerTrack(samples, framesWithPose, rejections)
}
