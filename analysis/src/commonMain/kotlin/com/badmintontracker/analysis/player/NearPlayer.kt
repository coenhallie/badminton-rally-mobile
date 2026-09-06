package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.validNetLine

/** COCO-17 indices, the layout every YOLO pose model emits. */
object Coco {
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16
    const val COUNT = 17
}

/** One detected person in one frame, in video pixels. */
data class PosePerson(
    val boxConfidence: Float,
    val keypoints: List<Point>,
    val keypointConfidence: List<Float>,
)

data class PoseFrame(val frame: Int, val people: List<PosePerson>)

/** One accepted position, in court metres. */
data class PlayerSample(
    val frame: Int,
    val courtPosition: Point,
    /**
     * Whether this came from the ankles or fell back to the hips.
     *
     * Carried rather than discarded because a hip sits about a metre above the
     * court plane and therefore projects long. The error it introduces is
     * larger than the gap between any two pose model sizes, so quality
     * reporting needs to be able to see how much of a track is built on it.
     */
    val onAnkles: Boolean,
)

/** Why a frame produced no sample, kept so a thin track can be explained. */
enum class RejectionReason { NO_PEOPLE, NO_GROUND_POINT, WRONG_SIDE, OFF_COURT }

/**
 * Picks the player nearest the camera out of one frame of pose detections.
 *
 * Near only, on purpose. Measurement on an S23 put nano's far-player coverage at
 * 44% of frames against 93% near, and even the largest model reaches only about
 * 70% far. Tracking one player is what makes the smallest model viable rather
 * than a compromise, and it drops the half of the data that was unreliable
 * whatever the model.
 *
 * Three gates in order, cheapest first, each rejecting something different: a
 * person with no usable ground point, a person on the far side, and a person who
 * is not on the court at all. The third is not optional - the far side of the
 * net line contains the crowd, and on real footage it rejected more detections
 * than it kept.
 */
class NearPlayerSelector(
    private val keypoints: CourtKeypoints,
    private val frameWidth: Double,
    private val frameHeight: Double,
    private val minKeypointConfidence: Float = MIN_KEYPOINT_CONFIDENCE,
) {
    private val homography: Matrix3x3? = keypoints.homography()

    /**
     * Whether the net line can be trusted to decide sides.
     *
     * A degenerate line silently classifies every point in the frame as one
     * side, which switches player identity off entirely rather than failing.
     */
    val netLineUsable: Boolean =
        validNetLine(keypoints.netLeft, keypoints.netRight, frameWidth, frameHeight)

    val usable: Boolean get() = homography != null && netLineUsable

    fun select(frame: PoseFrame): Result {
        if (homography == null || !netLineUsable) return Result(null, RejectionReason.NO_PEOPLE)
        if (frame.people.isEmpty()) return Result(null, RejectionReason.NO_PEOPLE)

        var best: PlayerSample? = null
        var bestConfidence = Float.NEGATIVE_INFINITY
        var reason = RejectionReason.NO_GROUND_POINT

        for (person in frame.people) {
            val ground = groundPoint(person) ?: continue
            if (isFarSide(ground.first)) {
                reason = worse(reason, RejectionReason.WRONG_SIDE)
                continue
            }
            // Nullable: the projection has no answer for a point on the
            // horizon line, where the perspective divisor vanishes.
            val court = homography.apply(ground.first.x, ground.first.y)
            if (court == null || !onCourt(court)) {
                reason = worse(reason, RejectionReason.OFF_COURT)
                continue
            }
            if (person.boxConfidence > bestConfidence) {
                bestConfidence = person.boxConfidence
                best = PlayerSample(frame.frame, court, ground.second)
            }
        }
        return Result(best, if (best == null) reason else null)
    }

    /**
     * The point on the court plane this person is standing on.
     *
     * Ankles first because the homography maps the ground; the hips are a
     * documented fallback rather than an equal alternative.
     */
    private fun groundPoint(person: PosePerson): Pair<Point, Boolean>? {
        if (person.keypoints.size < Coco.COUNT || person.keypointConfidence.size < Coco.COUNT) return null
        fun midpoint(a: Int, b: Int): Point? {
            if (person.keypointConfidence[a] < minKeypointConfidence) return null
            if (person.keypointConfidence[b] < minKeypointConfidence) return null
            val p = person.keypoints[a]
            val q = person.keypoints[b]
            return Point((p.x + q.x) / 2.0, (p.y + q.y) / 2.0)
        }
        midpoint(Coco.LEFT_ANKLE, Coco.RIGHT_ANKLE)?.let { return it to true }
        midpoint(Coco.LEFT_HIP, Coco.RIGHT_HIP)?.let { return it to false }
        return null
    }

    /**
     * Side of the net, by the net's y interpolated at this x.
     *
     * Not a pixel midline: on an angled camera a midline misclassifies play near
     * the net, and the net line is already marked by hand.
     */
    private fun isFarSide(point: Point): Boolean {
        val left = keypoints.netLeft
        val right = keypoints.netRight
        val span = right.x - left.x
        if (span == 0.0) return point.y < (left.y + right.y) / 2.0
        val t = (point.x - left.x) / span
        return point.y < left.y + t * (right.y - left.y)
    }

    private fun onCourt(court: Point): Boolean =
        court.x >= -OUT_OF_COURT_MARGIN_M &&
            court.x <= Court.WIDTH_DOUBLES + OUT_OF_COURT_MARGIN_M &&
            court.y >= -OUT_OF_COURT_MARGIN_M &&
            court.y <= Court.LENGTH + OUT_OF_COURT_MARGIN_M

    /** Report the gate the person got furthest through, which is the informative one. */
    private fun worse(current: RejectionReason, candidate: RejectionReason): RejectionReason =
        if (candidate.ordinal > current.ordinal) candidate else current

    data class Result(val sample: PlayerSample?, val rejection: RejectionReason?)

    companion object {
        /** Ultralytics' own keypoint visibility threshold. */
        const val MIN_KEYPOINT_CONFIDENCE = 0.5f

        /**
         * How far outside the court a player may legitimately be, in metres.
         *
         * Players lunge past the baseline and wide of the tramlines; spectators,
         * officials and the next court over do not come this close.
         */
        const val OUT_OF_COURT_MARGIN_M = 2.0
    }
}
