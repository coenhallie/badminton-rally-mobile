package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.camera.CameraMotion
import com.badmintontracker.analysis.camera.CameraSample
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import io.kotest.matchers.doubles.plusOrMinus
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

    @Test
    fun a_panned_frame_finds_the_player_where_they_stand() {
        // The camera panned 80 px up after marking: the person's pixels moved
        // by the same shift.
        val standing = Point(3.0, 11.0) // court metres, near half
        val person = personAt(standing).movedBy(PAN)
        val raw = rawOf(
            listOf(frameWith(person, timestamp = 0.5)),
            camera = CameraMotion.of(0.1, listOf(CameraSample(0.1, IDENTITY), CameraSample(0.5, PAN))),
        )

        val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }

        near.track.samples.single().courtPosition.x shouldBe (3.0 plusOrMinus 0.01)
        near.track.samples.single().courtPosition.y shouldBe (11.0 plusOrMinus 0.01)
        near.poses.single().camera shouldBe PAN
        // The pose keeps the frame's own pixels, for the overlay.
        near.poses.single().keypoints shouldBe person.keypoints.map { Point(it.x.toDouble(), it.y.toDouble()) }
    }

    @Test
    fun without_the_camera_the_same_frame_is_lost_to_the_far_side_or_placed_wrongly() {
        // The control for the test above: the same moved pixels read through
        // the fixed marks, which is what every frame got before the camera
        // was followed.
        val person = personAt(Point(3.0, 11.0)).movedBy(PAN)

        val near = selectPlayers(rawOf(listOf(frameWith(person, timestamp = 0.5)), camera = null), keypoints)
            .first { it.side == CourtSide.NEAR }

        val s = near.track.samples.singleOrNull()
        (s == null || kotlin.math.abs(s.courtPosition.y - 11.0) > 0.5) shouldBe true
    }

    @Test
    fun a_frame_whose_camera_is_unknown_is_camera_lost_on_both_sides() {
        // 4.9 s from the only sample: past every blend and hold, so the
        // camera is not known here and the fixed marks must not stand in.
        val raw = rawOf(
            listOf(frameWith(personAt(Point(3.0, 11.0)), timestamp = 5.0)),
            camera = CameraMotion.of(0.1, listOf(CameraSample(0.1, IDENTITY))),
        )

        selectPlayers(raw, keypoints).forEach {
            it.track.samples shouldBe emptyList()
            it.track.rejections[RejectionReason.CAMERA_LOST] shouldBe 1
            it.track.framesWithPose shouldBe 1
        }
    }

    @Test
    fun a_camera_with_no_way_back_to_the_marking_frame_is_camera_lost_too() {
        // A singular matrix cannot be inverted, so the frame's people have no
        // marking-frame pixels. Falling back to the fixed marks here would be
        // the wrong answer design §4.4 rules out for an unknown camera.
        val singular = listOf(listOf(1.0, 0.0, 0.0), listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 1.0))
        val raw = rawOf(
            listOf(frameWith(personAt(Point(3.0, 11.0)), timestamp = 0.5)),
            camera = CameraMotion.of(0.1, listOf(CameraSample(0.5, singular))),
        )

        selectPlayers(raw, keypoints).forEach {
            it.track.samples shouldBe emptyList()
            it.track.rejections[RejectionReason.CAMERA_LOST] shouldBe 1
        }
    }

    @Test
    fun no_camera_section_is_todays_selection_exactly() {
        val frames = listOf(frameWith(personAt(Point(3.0, 11.0)), timestamp = 0.5))

        selectPlayers(rawOf(frames, camera = null), keypoints).first().poses.single().camera shouldBe null
    }

    @Test
    fun new_reasons_are_appended_so_no_ordinal_moves() {
        // Appended, so no stored or logged ordinal of an existing reason moves.
        RejectionReason.BAD_COURT.ordinal shouldBe 5
        RejectionReason.CAMERA_LOST.ordinal shouldBe 6
        RejectionReason.entries.last() shouldBe RejectionReason.NOT_ON_PATH
    }

    @Test
    fun the_other_player_reaching_across_the_net_does_not_take_the_far_side() {
        // IMG_5892 frames 1375-1378: the near player walks to the net and her
        // ankles cross it, more confidently detected than the far player. Each
        // side keeps its player over time (design §5), not the most confident
        // person of each frame.
        val frames = (1..20).map { f ->
            val far = personAt(Point(3.0, 2.0 + 0.01 * f), confidence = 0.6f)
            val atTheNet = personAt(Point(4.5, 6.4), confidence = 0.95f)
            RawFrame(f, f / 30.0, null, emptyList(), if (f in 8..11) listOf(atTheNet, far) else listOf(far))
        }

        val far = selectPlayers(inference(*frames.toTypedArray()), keypoints).first { it.side == CourtSide.FAR }

        far.track.samples.size shouldBe 20
        far.track.samples.all { it.courtPosition.y < 3.0 } shouldBe true
        far.poses.size shouldBe 20
    }

    @Test
    fun the_near_player_crossing_the_net_while_the_far_player_is_hidden_stays_off_the_far_track() {
        // IMG_5892 frames 1376-1380, audited: she walks up to the net, her
        // ankles cross it, and the far player behind her is not detected, so
        // for four frames she is the only person on the far side.
        val frames = (1..60).map { f ->
            val people = when {
                f <= 20 -> listOf(personAt(Point(1.0, 7.8 - 0.05 * f)), personAt(Point(3.0, 2.0)))
                f <= 24 -> listOf(personAt(Point(1.0, 6.55)))
                else -> listOf(personAt(Point(1.0, 7.0 + 0.02 * (f - 24))), personAt(Point(3.0, 2.0)))
            }
            RawFrame(f, f / 30.0, null, emptyList(), people)
        }

        val selections = selectPlayers(inference(*frames.toTypedArray()), keypoints)
        val far = selections.first { it.side == CourtSide.FAR }

        far.track.samples.none { it.frame in 21..24 } shouldBe true
        far.track.samples.size shouldBe 56
        far.track.rejections[RejectionReason.NOT_ON_PATH] shouldBe 4
        far.poses.size shouldBe far.track.samples.size
    }

    @Test
    fun a_one_frame_jump_to_someone_else_is_not_on_the_path() {
        val frames = (1..20).map { f ->
            val who = if (f == 10) personAt(Point(5.5, 5.5), confidence = 0.99f) else personAt(Point(3.0, 2.0))
            RawFrame(f, f / 30.0, null, emptyList(), listOf(who))
        }

        val far = selectPlayers(inference(*frames.toTypedArray()), keypoints).first { it.side == CourtSide.FAR }

        far.track.samples.size shouldBe 19
        far.track.samples.none { it.frame == 10 } shouldBe true
        far.track.rejections[RejectionReason.NOT_ON_PATH] shouldBe 1
        // Every pose frame is either a sample or a counted rejection.
        (far.track.samples.size + far.track.rejections.values.sum()) shouldBe far.track.framesWithPose
    }

    /**
     * A plausible figure whose ankle midpoint stands on [court], in the
     * marking frame's pixels: the court point taken back through the marks'
     * own fit, so the test and the selector agree on the geometry by
     * construction.
     */
    private fun personAt(court: Point, confidence: Float = 0.9f): RawPerson {
        val pixel = invert3x3(keypoints.homography()!!)!!.apply(court.x, court.y)!!
        return person(pixel.x, pixel.y, confidence = confidence)
    }

    /** This person as a camera [m] (marking-frame pixels to frame pixels) would see them. */
    private fun RawPerson.movedBy(m: Matrix3x3): RawPerson {
        fun moved(x: Float, y: Float): Point = m.apply(x.toDouble(), y.toDouble())!!
        val topLeft = moved(box.x1, box.y1)
        val bottomRight = moved(box.x2, box.y2)
        return RawPerson(
            box = box.copy(
                x1 = topLeft.x.toFloat(), y1 = topLeft.y.toFloat(),
                x2 = bottomRight.x.toFloat(), y2 = bottomRight.y.toFloat(),
            ),
            keypoints = keypoints.map { k ->
                val p = moved(k.x, k.y)
                RawKeypoint(p.x.toFloat(), p.y.toFloat(), k.confidence)
            },
        )
    }

    private fun frameWith(person: RawPerson, timestamp: Double) =
        RawFrame(0, timestamp, null, emptyList(), listOf(person))

    private fun rawOf(frames: List<RawFrame>, camera: CameraMotion?) =
        inference(*frames.toTypedArray()).copy(camera = camera)

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

        val IDENTITY: Matrix3x3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))

        /** The camera panned so everything sits 80 px higher in the frame than when it was marked. */
        val PAN: Matrix3x3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, -80.0), listOf(0.0, 0.0, 1.0))
    }
}
