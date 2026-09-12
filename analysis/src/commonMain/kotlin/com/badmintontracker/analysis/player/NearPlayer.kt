package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.maxResidualM
import com.badmintontracker.analysis.geometry.metresPerPixelAt
import com.badmintontracker.analysis.geometry.validNetLine
import kotlin.math.sqrt

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
enum class RejectionReason {
    NO_PEOPLE,
    NO_GROUND_POINT,
    WRONG_SIDE,
    OFF_COURT,

    /**
     * On the court, but at a pixel scale no player at that depth can have: a
     * close-up from another camera. See [NearPlayerSelector.MAX_TORSO_M].
     */
    WRONG_SCALE,
    BAD_COURT,
}

/**
 * Which half of the court a player stands on.
 *
 * Decided by the marked net line, never by a pixel midline: on an angled
 * camera a midline misclassifies play at the net, and the net is already
 * marked by hand. See [NearPlayerSelector.isFarSide].
 *
 * NEAR is the half the camera sits behind. It is the only half a phone can
 * track reliably - nano reaches 44% coverage on the far player against 93%
 * near - so a device run fills NEAR and leaves FAR empty, while a cloud run
 * on a large model fills both.
 */
enum class CourtSide { NEAR, FAR }

/**
 * Picks the player nearest the camera out of one frame of pose detections.
 *
 * Near only, on purpose. Measurement on an S23 put nano's far-player coverage at
 * 44% of frames against 93% near, and even the largest model reaches only about
 * 70% far. Tracking one player is what makes the smallest model viable rather
 * than a compromise, and it drops the half of the data that was unreliable
 * whatever the model.
 *
 * Four gates in order, cheapest first, each rejecting something different: a
 * person with no usable ground point, a person on the far side, a person who
 * is not on the court at all, and a person whose torso is too long for the
 * court scale where they stand. The third is not optional - the far side of
 * the net line contains the crowd, and on real footage it rejected more
 * detections than it kept. The fourth catches a close-up from another camera,
 * which lands on the court at a scale no player there could have; see
 * [plausibleScale].
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

    /** The near player, which is what every caller wanted before there were two. */
    fun select(frame: PoseFrame): Result = select(frame, CourtSide.NEAR)

    /**
     * The best candidate on [side], or the furthest gate anyone on that side
     * reached.
     *
     * Every gate is the same for both sides. Only the side test changed, from
     * a rejection of the far half to a partition between the two, so a track
     * taken for NEAR here is the same track this returned before FAR existed.
     */
    fun select(frame: PoseFrame, side: CourtSide): Result {
        if (homography == null || !usable) return Result(null, RejectionReason.BAD_COURT)
        if (frame.people.isEmpty()) return Result(null, RejectionReason.NO_PEOPLE)

        var best: PlayerSample? = null
        var bestConfidence = Float.NEGATIVE_INFINITY
        var bestPerson: PosePerson? = null
        var reason = RejectionReason.NO_GROUND_POINT

        for (person in frame.people) {
            val ground = groundPoint(person) ?: continue
            if (sideOf(ground) != side) {
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
            if (!plausibleScale(person, ground)) {
                reason = worse(reason, RejectionReason.WRONG_SCALE)
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
     * Whether the person is the size a person at their ground point would be.
     *
     * The torso (shoulder midpoint to hip midpoint) in pixels, times the
     * court's metres per pixel at the ankles. Measured on the two corpus
     * videos (2026-09-07): no real frame exceeded 0.8m; broadcast close-ups
     * from another camera, which the marks do not describe, read 0.8 to
     * 1.4m and were being drawn onto the heatmap as on-court positions. A
     * torso is only ever foreshortened by lean or camera tilt, so a single
     * upper bound is the whole test. Shoulders or hips below the confidence
     * threshold, or a scale the homography cannot give, pass: an unmeasurable
     * torso is not evidence.
     *
     * What it does not catch, said plainly, because the bound sits above the
     * bottom of the close-up range rather than below it:
     *
     *  - A close-up reading between 0.8 and 0.9m passes. 2eabfc01's intro
     *    animation reads 0.8 to 0.95m across its frames, so some of them get
     *    through. The bound is set where a real player never reaches, not
     *    where every close-up starts, because rejecting real frames costs the
     *    map coverage it cannot get back.
     *  - A cut to a different wide camera, where the figure is at a plausible
     *    scale. Nothing about the size gives it away; only the marks would,
     *    and they describe one camera.
     *  - A close-up whose shoulders or hips are below the confidence
     *    threshold. It is unmeasurable, so it passes by the rule above.
     *
     * And what it rejects that is real: a jump. Metres per pixel grows with
     * distance from the camera, so ankles off the floor project further up the
     * court than the player stands, at a larger scale, and the same torso
     * measures longer. An airborne frame can therefore fail the gate. The
     * heatmap does not mind, because that frame's ground point was wrong for
     * exactly the same reason.
     */
    private fun plausibleScale(person: PosePerson, ground: Point): Boolean {
        val c = person.keypointConfidence
        val needed = listOf(Coco.LEFT_SHOULDER, Coco.RIGHT_SHOULDER, Coco.LEFT_HIP, Coco.RIGHT_HIP)
        if (needed.any { c[it] < minKeypointConfidence }) return true
        val h = homography ?: return true
        val metresPerPixel = h.metresPerPixelAt(ground) ?: return true
        val k = person.keypoints
        val sx = (k[Coco.LEFT_SHOULDER].x + k[Coco.RIGHT_SHOULDER].x) / 2.0
        val sy = (k[Coco.LEFT_SHOULDER].y + k[Coco.RIGHT_SHOULDER].y) / 2.0
        val hx = (k[Coco.LEFT_HIP].x + k[Coco.RIGHT_HIP].x) / 2.0
        val hy = (k[Coco.LEFT_HIP].y + k[Coco.RIGHT_HIP].y) / 2.0
        val torsoM = sqrt((sx - hx) * (sx - hx) + (sy - hy) * (sy - hy)) * metresPerPixel
        return torsoM <= MAX_TORSO_M
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

    private fun sideOf(point: Point): CourtSide =
        if (isFarSide(point)) CourtSide.FAR else CourtSide.NEAR

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

        /**
         * The longest a torso can measure on the court scale and still be a
         * player at that depth, metres.
         *
         * Above the 0.8m no real corpus frame reached, so it never rejects a
         * player standing on the court, and below the bulk of the close-up
         * range. It is not a clean separator: a close-up reading 0.8 to 0.9m
         * passes, and a jump can measure past it. See [plausibleScale].
         */
        const val MAX_TORSO_M = 0.9
    }
}
