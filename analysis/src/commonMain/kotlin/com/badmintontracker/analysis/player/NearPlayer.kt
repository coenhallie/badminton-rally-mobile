package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.maxResidualM
import com.badmintontracker.analysis.geometry.validNetLine

/** COCO-17 indices, the layout every YOLO pose model emits. */
object Coco {
    const val NOSE = 0
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
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

/**
 * One accepted position, in court metres.
 *
 * Always the ankle midpoint. There used to be a hip fallback here, flagged so
 * quality reporting could see how much of a track stood on it; it was measured
 * on 2026-09-07 and removed. See [NearPlayerSelector.groundPoint].
 */
data class PlayerSample(
    val frame: Int,
    val courtPosition: Point,
)

/**
 * Why a frame produced no sample, kept so a thin track can be explained.
 *
 * Ordered by how far through the gates a person got, because [NearPlayerSelector]
 * reports the furthest gate reached. [BAD_COURT] is outside that order: it is
 * about the marks, not about any person, and applies to every frame at once.
 */
enum class RejectionReason { NO_PEOPLE, NO_GROUND_POINT, WRONG_SIDE, OFF_COURT, BAD_COURT }

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

    /**
     * How badly the marks fit a court, in metres; null when there is no fit.
     *
     * Well-placed marks on the three corpus videos fit to between 0.17m and
     * 0.37m at the worst point. A corner clicked in the wrong order or a
     * service line marked on the wrong side of the net shows up as metres,
     * and a homography built on it puts the player metres from where they
     * stand while every downstream count looks healthy.
     */
    val courtFitResidualM: Double? = homography?.let { keypoints.maxResidualM(it) }

    /** False when the marks cannot be trusted; see [courtFitResidualM]. */
    val courtUsable: Boolean =
        courtFitResidualM != null && courtFitResidualM <= MAX_COURT_RESIDUAL_M

    val usable: Boolean get() = courtUsable && netLineUsable

    fun select(frame: PoseFrame): Result {
        if (homography == null || !usable) return Result(null, RejectionReason.BAD_COURT)
        if (frame.people.isEmpty()) return Result(null, RejectionReason.NO_PEOPLE)

        var best: PlayerSample? = null
        var bestConfidence = Float.NEGATIVE_INFINITY
        var bestPerson: PosePerson? = null
        var reason = RejectionReason.NO_GROUND_POINT

        for (person in frame.people) {
            val ground = groundPoint(person) ?: continue
            if (isFarSide(ground)) {
                reason = worse(reason, RejectionReason.WRONG_SIDE)
                continue
            }
            // Nullable: the projection has no answer for a point on the
            // horizon line, where the perspective divisor vanishes.
            val court = homography.apply(ground.x, ground.y)
            if (court == null || !onCourt(court)) {
                reason = worse(reason, RejectionReason.OFF_COURT)
                continue
            }
            if (person.boxConfidence > bestConfidence) {
                bestConfidence = person.boxConfidence
                best = PlayerSample(frame.frame, court)
                bestPerson = person
            }
        }
        return Result(best, if (best == null) reason else null, bestPerson)
    }

    /**
     * The point on the court plane this person is standing on: the ankle
     * midpoint, or nothing.
     *
     * The homography maps the ground plane, so only a point on the ground
     * projects correctly. The hips used to stand in when the ankles were not
     * confident, on the belief that they sit "about a metre" above the court
     * and project a little long. Measured against confident ankles on the two
     * corpus videos with the deployed nano model (2026-09-07, 150 frames each,
     * near player, court centimetres):
     *
     * | fallback                    | median   | p90      |
     * |-----------------------------|----------|----------|
     * | hip midpoint                | 173-291  | 343-346  |
     * | box bottom centre           | 53-81    | 92-107   |
     * | hip + 1.3 x torso vector    | 24-37    | 58-102   |
     *
     * against an ankle precision of about 9cm. The hip is not a metre off, it
     * is two to three, and 22% of on-court detections had no confident ankles,
     * so a fifth of the map was being drawn three metres from the player. The
     * two better estimates are still an order of magnitude worse than an
     * ankle, and they were measured on frames where the ankles were visible,
     * which are exactly not the lunges and net-dives where a fallback would
     * be used. A missing sample costs coverage, which the summary reports; a
     * wrong one costs the map its meaning.
     */
    private fun groundPoint(person: PosePerson): Point? {
        if (person.keypoints.size < Coco.COUNT || person.keypointConfidence.size < Coco.COUNT) return null
        if (person.keypointConfidence[Coco.LEFT_ANKLE] < minKeypointConfidence) return null
        if (person.keypointConfidence[Coco.RIGHT_ANKLE] < minKeypointConfidence) return null
        val p = person.keypoints[Coco.LEFT_ANKLE]
        val q = person.keypoints[Coco.RIGHT_ANKLE]
        return Point((p.x + q.x) / 2.0, (p.y + q.y) / 2.0)
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

    /**
     * [person] is the detection [sample] was taken from, so a caller that
     * wants the joints as well as the court position gets the same person
     * the heatmap got, by construction rather than by a second pass.
     */
    data class Result(
        val sample: PlayerSample?,
        val rejection: RejectionReason?,
        val person: PosePerson? = null,
    )

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

        /**
         * The worst reprojection residual a set of court marks may have and
         * still be used, in metres.
         *
         * Well-placed marks fit to 0.37m at worst on the corpus; a swapped
         * pair or a mis-ordered corner fits to 3.5m. One metre sits between
         * the two with room on both sides, and is also the scale at which a
         * heatmap stops meaning anything: a coach reads it at a stride.
         */
        const val MAX_COURT_RESIDUAL_M = 1.0
    }
}
