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

/**
 * One player's whole story from a run: which half they were on, where they
 * stood, and their joints.
 *
 * [poses] holds one entry per entry in `track.samples`, at the same frame, for
 * the reason [NearPlayerSelection] states: the heatmap's position and the
 * skeleton's joints are the same person in the same frame, and returning them
 * together is what makes that true.
 */
data class PlayerSelection(
    val side: CourtSide,
    val track: PlayerTrack,
    val poses: List<PlayerPose>,
)

/**
 * Both players' tracks and poses from one pass over the frames.
 *
 * Always two entries, NEAR then FAR, even when a side was never occupied: an
 * empty track and an absent one are different facts, and [PlayerTrack] already
 * tells them apart through framesWithPose and its rejection counts. A caller
 * that wants only the near player should call [selectNearPlayer], which is
 * this function's near half and nothing else.
 *
 * One pass, not two: a second pass over a 54,000-frame stream to find the
 * other player would double the work and, worse, would let the two halves
 * disagree about framesWithPose if the loop ever grew a condition.
 */
fun selectPlayers(raw: RawInference, keypoints: CourtKeypoints): List<PlayerSelection> {
    val selector = NearPlayerSelector(
        keypoints,
        raw.header.videoWidth.toDouble(),
        raw.header.videoHeight.toDouble(),
    )
    // An unusable court is not a thin track, it is no track, and it is a fact
    // about the marks rather than about either player - so both sides carry it.
    if (!selector.usable) {
        return CourtSide.entries.map { side ->
            PlayerSelection(
                side,
                PlayerTrack(emptyList(), 0, mapOf(RejectionReason.BAD_COURT to raw.frames.size)),
                emptyList(),
            )
        }
    }

    val samples = CourtSide.entries.associateWith { ArrayList<PlayerSample>() }
    val poses = CourtSide.entries.associateWith { ArrayList<PlayerPose>() }
    val rejections = CourtSide.entries.associateWith { mutableMapOf<RejectionReason, Int>() }
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a
        // player, so they must not dilute coverage on either side.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val poseFrame = PoseFrame(frame.frame, frame.persons.map { it.toPosePerson() })
        for (side in CourtSide.entries) {
            val result = selector.select(poseFrame, side)
            val sample = result.sample
            val person = result.person
            if (sample != null && person != null) {
                samples.getValue(side).add(sample)
                poses.getValue(side).add(
                    PlayerPose(frame.frame, frame.timestamp, person.keypoints, person.keypointConfidence),
                )
            } else {
                result.rejection?.let { r ->
                    val counts = rejections.getValue(side)
                    counts[r] = (counts[r] ?: 0) + 1
                }
            }
        }
    }

    return CourtSide.entries.map { side ->
        PlayerSelection(
            side,
            PlayerTrack(samples.getValue(side), framesWithPose, rejections.getValue(side)),
            poses.getValue(side),
        )
    }
}

/** [buildNearPlayerTrack], keeping the chosen person's joints as well. */
fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection {
    // The near half of [selectPlayers], so the two can never disagree about
    // the player every device run on every phone already has stored.
    val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }
    return NearPlayerSelection(near.track, near.poses)
}
