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
    /**
     * Fraction of pose frames that produced a position.
     *
     * Worth reading before the heatmap is believed. Every sample stands on
     * the ankles, so coverage is the whole of the quality story: a frame with
     * no confident ankles contributes nothing rather than a guess.
     */
    val coverage: Double get() = if (framesWithPose == 0) 0.0 else samples.size.toDouble() / framesWithPose
}

/** The near player's joints in one frame, in source-video pixels. */
data class PlayerPose(
    val frame: Int,
    /**
     * The frame's container presentation time in seconds. Playback matches
     * on this, never on frame / fps: on a variable-frame-rate source the two
     * disagree and the skeleton would drift off the body.
     */
    val timestamp: Double,
    /** COCO-17 order. */
    val keypoints: List<Point>,
    val confidence: List<Float>,
)

/**
 * The track and the poses, from one pass over the frames.
 *
 * One entry in [poses] per entry in [PlayerTrack.samples], at the same frame:
 * the heatmap's position and the skeleton's joints are the same person in the
 * same frame, and returning them together is what makes that true.
 */
data class NearPlayerSelection(val track: PlayerTrack, val poses: List<PlayerPose>)

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
fun buildNearPlayerTrack(raw: RawInference, keypoints: CourtKeypoints): PlayerTrack =
    selectNearPlayer(raw, keypoints).track

/** [buildNearPlayerTrack], keeping the chosen person's joints as well. */
fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection {
    val selector = NearPlayerSelector(
        keypoints,
        raw.header.videoWidth.toDouble(),
        raw.header.videoHeight.toDouble(),
    )
    // An unusable court is not a thin track, it is no track. Returning an empty
    // one with the reason counted keeps that distinguishable from a match where
    // the player was simply never found.
    if (!selector.usable) {
        return NearPlayerSelection(
            PlayerTrack(emptyList(), 0, mapOf(RejectionReason.BAD_COURT to raw.frames.size)),
            emptyList(),
        )
    }

    val samples = ArrayList<PlayerSample>()
    val poses = ArrayList<PlayerPose>()
    val rejections = mutableMapOf<RejectionReason, Int>()
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a player,
        // so they must not dilute coverage.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val result = selector.select(PoseFrame(frame.frame, frame.persons.map { it.toPosePerson() }))
        val sample = result.sample
        val person = result.person
        if (sample != null && person != null) {
            samples.add(sample)
            poses.add(PlayerPose(frame.frame, frame.timestamp, person.keypoints, person.keypointConfidence))
        } else {
            result.rejection?.let { rejections[it] = (rejections[it] ?: 0) + 1 }
        }
    }
    return NearPlayerSelection(PlayerTrack(samples, framesWithPose, rejections), poses)
}
