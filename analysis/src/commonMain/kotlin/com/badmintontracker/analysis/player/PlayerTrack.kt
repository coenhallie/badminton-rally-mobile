package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.analysis.normalizeFps
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
    /**
     * This frame's camera, marking-frame pixels to these keypoints' pixels,
     * when the pass followed the camera; null when it did not. A court
     * reading of these keypoints goes through `courtFitAt(h, camera)`.
     */
    val camera: Matrix3x3? = null,
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
 *
 * When [RawInference.camera] is present the marks describe the marking frame
 * only, so each frame's people are taken back into it through that frame's
 * camera before the unchanged gates run (design §5.2). The poses keep the
 * frame's own pixels, which is what the overlay draws on, and carry the camera
 * that relates the two. A frame whose camera is unknown is [RejectionReason.CAMERA_LOST]
 * on both sides rather than read through the fixed marks. With no camera the
 * selection is exactly what it was before the camera was followed.
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

    val rejections = CourtSide.entries.associateWith { mutableMapOf<RejectionReason, Int>() }
    // Per side, the frames where somebody passed the gates, for the path to
    // choose among once every frame has been read.
    val seen = CourtSide.entries.associateWith { ArrayList<SeenFrame>() }
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a
        // player, so they must not dilute coverage on either side.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val camera = raw.camera
        val m = camera?.at(frame.timestamp)
        // Null both when the camera is unknown here and when its matrix cannot
        // be inverted: a camera with no way back to the marking frame is the
        // same fact as not knowing where it was.
        val back = m?.let { invert3x3(it) }
        if (camera != null && back == null) {
            for (side in CourtSide.entries) {
                val counts = rejections.getValue(side)
                counts[RejectionReason.CAMERA_LOST] = (counts[RejectionReason.CAMERA_LOST] ?: 0) + 1
            }
            continue
        }
        val originals = frame.persons.map { it.toPosePerson() }
        // A joint the inverse sends to the horizon line has no marking-frame
        // pixel. NaN rather than a dropped joint keeps COCO order, and the
        // gates read a NaN ankle as no ground point and a NaN torso as
        // unmeasurable.
        val inMarkingFrame = if (back == null) originals else originals.map { p ->
            p.copy(keypoints = p.keypoints.map { k -> back.apply(k.x, k.y) ?: Point(Double.NaN, Double.NaN) })
        }
        val poseFrame = PoseFrame(frame.frame, inMarkingFrame)
        for (side in CourtSide.entries) {
            val found = selector.candidates(poseFrame, side)
            if (found.passed.isNotEmpty()) {
                seen.getValue(side).add(SeenFrame(frame.frame, frame.timestamp, m, originals, found.passed))
            } else {
                found.rejection?.let { r ->
                    val counts = rejections.getValue(side)
                    counts[r] = (counts[r] ?: 0) + 1
                }
            }
        }
    }

    val fps = normalizeFps(raw.header.fps).fps
    // Each side's path first, then each side's pieces that are the other
    // side's player reaching across the net (crossedFrom), judged against the
    // other side's path.
    val paths = CourtSide.entries.associateWith { side ->
        val frames = seen.getValue(side)
        val chosen = chooseSidePath(
            frames.map { f ->
                f.passed.map { p ->
                    SideCandidate(
                        p.sample.frame, p.sample.courtPosition, p.person.boxConfidence,
                        outsideLinesM(p.sample.courtPosition, side),
                    )
                }
            },
            fps,
        )
        frames.mapIndexed { i, f -> f to f.passed.getOrNull(chosen[i]) }
    }
    return CourtSide.entries.map { side ->
        val own = paths.getValue(side)
        val otherSide = if (side == CourtSide.NEAR) CourtSide.FAR else CourtSide.NEAR
        val crossed = crossedFrom(
            own.mapNotNull { it.second?.sample },
            paths.getValue(otherSide).mapNotNull { it.second?.sample },
            fps,
        )
        val samples = ArrayList<PlayerSample>()
        val poses = ArrayList<PlayerPose>()
        val counts = rejections.getValue(side)
        for ((f, pick) in own) {
            if (pick == null || pick.sample.frame in crossed) {
                counts[RejectionReason.NOT_ON_PATH] = (counts[RejectionReason.NOT_ON_PATH] ?: 0) + 1
                continue
            }
            val original = f.originals[pick.personIndex]
            samples.add(pick.sample)
            poses.add(PlayerPose(f.frame, f.timestamp, original.keypoints, original.keypointConfidence, f.camera))
        }
        PlayerSelection(side, PlayerTrack(samples, framesWithPose, counts), poses)
    }
}

/** One frame where somebody passed the gates on a side, kept until the path has chosen. */
private class SeenFrame(
    val frame: Int,
    val timestamp: Double,
    val camera: Matrix3x3?,
    /** The frame's people in its own pixels, which is what a pose keeps. */
    val originals: List<PosePerson>,
    val passed: List<NearPlayerSelector.Passed>,
)

/** [buildNearPlayerTrack], keeping the chosen person's joints as well. */
fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection {
    // The near half of [selectPlayers], so the two can never disagree about
    // the player every device run on every phone already has stored.
    val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }
    return NearPlayerSelection(near.track, near.poses)
}
