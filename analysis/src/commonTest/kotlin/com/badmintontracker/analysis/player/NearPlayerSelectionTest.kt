package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The skeleton and the heatmap are one selection. Whatever the selector picks
 * for the court position is the person whose joints are kept, at the same
 * frame, with the frame's own timestamp.
 */
class NearPlayerSelectionTest {

    // Corpus video 743d7fb1's marks, as the cloud stored them.
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

    private fun header(fps: Double = 25.0) = RawHeader(1, fps, 100, 1920, 1080, "test")

    /** A person standing at ([x],[y]) with a distinctive nose so the joints can be told apart. */
    private fun person(x: Double, y: Double, confidence: Float, noseX: Double): RawPerson {
        val kps = MutableList(Coco.COUNT) { RawKeypoint(x.toFloat(), (y - 100).toFloat(), 0.9f) }
        kps[0] = RawKeypoint(noseX.toFloat(), (y - 180).toFloat(), 0.9f)
        kps[Coco.LEFT_ANKLE] = RawKeypoint((x - 10).toFloat(), y.toFloat(), 0.9f)
        kps[Coco.RIGHT_ANKLE] = RawKeypoint((x + 10).toFloat(), y.toFloat(), 0.9f)
        return RawPerson(RawBox(0, confidence, 0f, 0f, 1f, 1f), kps)
    }

    private fun frame(index: Int, fps: Double, persons: List<RawPerson>, timestamp: Double = index / fps) =
        RawFrame(index, timestamp, null, emptyList(), persons)

    @Test
    fun every_sample_has_a_pose_at_the_same_frame_and_no_others() {
        val raw = RawInference(
            header(),
            listOf(
                frame(0, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 111.0))),
                frame(1, 25.0, emptyList()),                                          // pose never ran
                frame(2, 25.0, listOf(person(966.0, 550.0, 0.9f, noseX = 222.0))),   // far side, rejected
                frame(3, 25.0, listOf(person(1200.0, 950.0, 0.8f, noseX = 333.0))),
            ),
        )
        val selection = selectNearPlayer(raw, keypoints)
        assertEquals(listOf(0, 3), selection.track.samples.map { it.frame })
        assertEquals(listOf(0, 3), selection.poses.map { it.frame })
        assertEquals(selection.track, buildNearPlayerTrack(raw, keypoints))
    }

    @Test
    fun the_pose_is_the_chosen_persons_joints() {
        // Two on the near court; the more confident wins the sample, so the
        // pose must be that person's joints, not the first person's.
        val raw = RawInference(
            header(),
            listOf(
                frame(
                    0, 25.0,
                    listOf(
                        person(700.0, 900.0, 0.4f, noseX = 111.0),
                        person(1200.0, 950.0, 0.95f, noseX = 999.0),
                    ),
                ),
            ),
        )
        val pose = selectNearPlayer(raw, keypoints).poses.single()
        assertEquals(999.0, pose.keypoints[0].x)
        assertEquals(Coco.COUNT, pose.keypoints.size)
        assertEquals(Coco.COUNT, pose.confidence.size)
        assertTrue(pose.confidence.all { it == 0.9f })
    }

    @Test
    fun the_pose_carries_the_frames_own_timestamp_not_frame_over_fps() {
        // A variable-frame-rate source: frame 3 is late. The pose must say
        // when the frame was actually shown, which is what playback matches on.
        val raw = RawInference(
            header(fps = 25.0),
            listOf(frame(3, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 1.0)), timestamp = 0.2)),
        )
        assertEquals(0.2, selectNearPlayer(raw, keypoints).poses.single().timestamp)
    }

    @Test
    fun a_bad_court_yields_no_poses_either() {
        val raw = RawInference(header(), listOf(frame(0, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 1.0)))))
        val scrambled = keypoints.copy(topLeft = keypoints.bottomRight, bottomRight = keypoints.topLeft)
        val selection = selectNearPlayer(raw, scrambled)
        assertTrue(selection.poses.isEmpty())
        assertEquals(1, selection.track.rejections[RejectionReason.BAD_COURT])
    }
}
