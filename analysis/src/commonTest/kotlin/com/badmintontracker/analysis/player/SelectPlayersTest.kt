package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * Both players out of one pass, from the gates that already decided one.
 *
 * The cloud runs a large pose model on a GPU and tracks both players well;
 * the phone runs a nano-class model and reaches 44% far-player coverage
 * against 93% near (see NearPlayerSelector's own KDoc). So this exists for
 * the cloud path, and the near half has to stay bit-identical to what the
 * device path already ships. selectNearPlayer is defined as this file's
 * near half, so that identity holds by construction; the regression
 * evidence that the partition did not move it is the four pre-existing
 * classes (NearPlayerSelectionTest, PlayerTrackTest, NearPlayerSelectorTest,
 * DevicePoseDumpTest) passing unchanged.
 */
class SelectPlayersTest {

    // A court whose marks fit, borrowed from NearPlayerSelectionTest's shape:
    // a wide camera behind the near baseline, net across the middle.
    // Corpus video 743d7fb1's marks, as the cloud stored them. Lifted
    // verbatim from NearPlayerSelectionTest rather than invented: two test
    // files describing two different cameras is how a partition test passes
    // while the real one fails. The net line sits at y ~ 664, so the near
    // half is BELOW it in the frame and the far half above.
    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    /**
     * The same marks with the top two corners swapped.
     *
     * The documented failure shape: well-placed marks fit to 0.37m at worst
     * on the corpus and a mis-ordered corner fits to 3.5m, which is past
     * MAX_COURT_RESIDUAL_M of 1.0. Chosen over a degenerate all-zero set
     * because a set that cannot produce a homography at all takes a
     * different branch, and the branch that matters is the one a coach
     * reaches by clicking corners in the wrong order.
     */
    private val swappedCorners = keypoints.copy(
        topLeft = keypoints.topRight,
        topRight = keypoints.topLeft,
    )

    private fun person(ankleX: Double, ankleY: Double, confidence: Float): RawPerson {
        // A plausible figure standing at (ankleX, ankleY): shoulders and hips
        // above the ankles at a scale the court supports, so plausibleScale
        // passes rather than rejecting for a torso that is too long.
        val k = MutableList(Coco.COUNT) { RawKeypoint(ankleX.toFloat(), ankleY.toFloat(), 0.9f) }
        val torso = 60f
        k[Coco.LEFT_SHOULDER] = RawKeypoint(ankleX.toFloat() - 12f, ankleY.toFloat() - torso * 2, 0.9f)
        k[Coco.RIGHT_SHOULDER] = RawKeypoint(ankleX.toFloat() + 12f, ankleY.toFloat() - torso * 2, 0.9f)
        k[Coco.LEFT_HIP] = RawKeypoint(ankleX.toFloat() - 10f, ankleY.toFloat() - torso, 0.9f)
        k[Coco.RIGHT_HIP] = RawKeypoint(ankleX.toFloat() + 10f, ankleY.toFloat() - torso, 0.9f)
        k[Coco.LEFT_ANKLE] = RawKeypoint(ankleX.toFloat() - 8f, ankleY.toFloat(), 0.9f)
        k[Coco.RIGHT_ANKLE] = RawKeypoint(ankleX.toFloat() + 8f, ankleY.toFloat(), 0.9f)
        return RawPerson(
            box = RawBox(0, confidence, ankleX.toFloat() - 30f, ankleY.toFloat() - 200f,
                         ankleX.toFloat() + 30f, ankleY.toFloat()),
            keypoints = k,
        )
    }

    private fun inference(vararg frames: RawFrame) = RawInference(
        header = RawHeader(
            version = 1, fps = 30.0, totalFrames = frames.size,
            videoWidth = 1920, videoHeight = 1080, modelVersion = "cloud:test",
        ),
        frames = frames.toList(),
    )

    @Test
    fun the_near_half_is_exactly_what_select_near_player_returns() {
        // Pins the delegation: selectNearPlayer is defined as
        // selectPlayers(...).first { NEAR }, so this assertion holds by
        // construction and cannot by itself catch a regression in the
        // partition. The actual regression evidence is the four pre-existing
        // classes (NearPlayerSelectionTest, PlayerTrackTest,
        // NearPlayerSelectorTest, DevicePoseDumpTest) passing unchanged -
        // they exercise selectNearPlayer's behaviour directly, and this test
        // only guards against the delegation itself being undone.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
            RawFrame(1, 1.0 / 30.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
        )

        val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }
        val legacy = selectNearPlayer(raw, keypoints)

        near.track shouldBe legacy.track
        near.poses shouldBe legacy.poses
    }

    @Test
    fun a_frame_with_a_player_on_each_side_fills_both_tracks() {
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        selections[0].track.samples.size shouldBe 1
        selections[1].track.samples.size shouldBe 1
        // Different people, so different court positions. Equal positions here
        // would mean one detection was handed to both sides.
        selections[0].track.samples[0].courtPosition shouldNotBe
            selections[1].track.samples[0].courtPosition
    }

    @Test
    fun the_poses_belong_to_the_player_whose_track_they_sit_beside() {
        // The invariant NearPlayerSelection.kt states for one player, now for
        // two: the heatmap's position and the skeleton's joints are the same
        // person in the same frame. Crossed sides here would draw the far
        // player's skeleton over the near player's heatmap.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.forEach { it.poses.size shouldBe it.track.samples.size }
        // The near player's ankles are lower in the frame (larger y) than the
        // far player's, because the camera sits behind the near baseline.
        val nearAnkleY = selections[0].poses[0].keypoints[Coco.LEFT_ANKLE].y
        val farAnkleY = selections[1].poses[0].keypoints[Coco.LEFT_ANKLE].y
        (nearAnkleY > farAnkleY) shouldBe true
    }

    @Test
    fun a_side_with_nobody_on_it_gets_an_empty_track_not_a_missing_one() {
        // Empty rather than absent, for LocalAnalysisOutcome's reason: a match
        // played on one half and a match where the far player was never found
        // are different facts, and PlayerTrack already distinguishes them
        // through framesWithPose and its rejection counts.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.size shouldBe 2
        selections[1].side shouldBe CourtSide.FAR
        selections[1].track.samples shouldBe emptyList()
        selections[1].track.framesWithPose shouldBe 1
        selections[1].track.rejections[RejectionReason.WRONG_SIDE] shouldBe 1
    }

    @Test
    fun frames_the_model_never_ran_on_dilute_neither_side() {
        // framesWithPose is the denominator of coverage, and a frame with no
        // detections at all is not a failure to find anybody.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), emptyList()),
            RawFrame(1, 1.0 / 30.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
        )

        selectPlayers(raw, keypoints).forEach { it.track.framesWithPose shouldBe 1 }
    }

    @Test
    fun unusable_marks_produce_two_empty_tracks_and_say_why() {
        // Not one empty track and one absent: the marks are about the court,
        // not about either player, so the failure applies to both equally.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, swappedCorners)

        selections.size shouldBe 2
        selections.forEach {
            it.track.samples shouldBe emptyList()
            it.track.rejections[RejectionReason.BAD_COURT] shouldBe 1
        }
    }

    @Test
    fun the_higher_confidence_detection_wins_within_one_side() {
        // Unchanged from the near-only selector, and worth pinning: two
        // detections on one side is the ordinary case when the model finds a
        // player and a line judge behind them.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), listOf(
                person(NEAR_X, NEAR_Y, confidence = 0.4f),
                person(NEAR_X + 40, NEAR_Y, confidence = 0.95f),
            )),
        )

        val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }

        near.track.samples.size shouldBe 1
        near.poses[0].keypoints[Coco.LEFT_ANKLE].x shouldBe (NEAR_X + 40 - 8)
    }

    private fun nearPlayer() = person(NEAR_X, NEAR_Y, confidence = 0.9f)
    private fun farPlayer() = person(FAR_X, FAR_Y, confidence = 0.8f)

    private companion object {
        // Pixel positions either side of the marked net line. Chosen from the
        // same geometry NearPlayerSelectionTest uses, so both files describe
        // one camera rather than two imagined ones.
        const val NEAR_X = 960.0
        const val NEAR_Y = 900.0
        const val FAR_X = 960.0

        // 420.0 (the pixel row roughly the same distance above the net line as
        // NEAR_Y is below it) projects to court y = -3.44m: 1.44m past the far
        // baseline's OUT_OF_COURT_MARGIN_M of 2.0, because the marked far
        // baseline corners sit at y ~ 481-485, above 420. 550.0 sits below
        // those corners and projects to court y ~ 2.97, x ~ 3.0: inside the
        // court, in the rear half between the far baseline and the short
        // service line.
        const val FAR_Y = 550.0
    }
}
