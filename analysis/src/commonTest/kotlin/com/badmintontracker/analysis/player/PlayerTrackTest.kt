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

class PlayerTrackTest {

    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        // Marked with "near" meaning near the camera for the service lines and
        // near the top for the centre points, as the cloud stored them. The
        // fit resolves each pair by pixel, so both readings are fine.
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    private fun header() = RawHeader(1, 30.0, 100, 1920, 1080, "test")

    private fun person(x: Double, y: Double, confidence: Float = 0.9f): RawPerson {
        val kps = MutableList(Coco.COUNT) { RawKeypoint(x.toFloat(), (y - 100).toFloat(), 0.9f) }
        kps[Coco.LEFT_ANKLE] = RawKeypoint((x - 10).toFloat(), y.toFloat(), 0.9f)
        kps[Coco.RIGHT_ANKLE] = RawKeypoint((x + 10).toFloat(), y.toFloat(), 0.9f)
        kps[Coco.LEFT_HIP] = RawKeypoint((x - 10).toFloat(), (y - 90).toFloat(), 0.9f)
        kps[Coco.RIGHT_HIP] = RawKeypoint((x + 10).toFloat(), (y - 90).toFloat(), 0.9f)
        return RawPerson(RawBox(0, confidence, 0f, 0f, 1f, 1f), kps)
    }

    private fun frame(index: Int, persons: List<RawPerson>) =
        RawFrame(index, index / 30.0, null, emptyList(), persons)

    @Test
    fun a_near_player_in_every_frame_gives_full_coverage() {
        val raw = RawInference(header(), (0 until 10).map { frame(it, listOf(person(966.0, 900.0))) })
        val track = buildNearPlayerTrack(raw, keypoints)
        assertEquals(10, track.samples.size)
        assertEquals(10, track.framesWithPose)
        assertEquals(1.0, track.coverage)
    }

    @Test
    fun frames_the_pose_model_never_ran_on_do_not_dilute_coverage() {
        // Stage 1 frames carry no persons at all. Counting them as failures to
        // find a player would report a healthy track as half broken, and would
        // make coverage depend on how often pose was run rather than on how
        // often it worked.
        val raw = RawInference(
            header(),
            listOf(
                frame(0, listOf(person(966.0, 900.0))),
                frame(1, emptyList()),
                frame(2, emptyList()),
                frame(3, listOf(person(966.0, 900.0))),
            ),
        )
        val track = buildNearPlayerTrack(raw, keypoints)
        assertEquals(2, track.framesWithPose)
        assertEquals(1.0, track.coverage)
    }

    @Test
    fun rejections_are_counted_so_a_thin_track_can_be_explained() {
        val raw = RawInference(
            header(),
            listOf(
                frame(0, listOf(person(966.0, 900.0))),   // near, accepted
                frame(1, listOf(person(966.0, 550.0))),   // far side
                frame(2, listOf(person(150.0, 700.0))),   // off court, at the side
            ),
        )
        val track = buildNearPlayerTrack(raw, keypoints)
        assertEquals(1, track.samples.size)
        assertEquals(1, track.rejections[RejectionReason.WRONG_SIDE])
        assertEquals(1, track.rejections[RejectionReason.OFF_COURT])
    }

    @Test
    fun an_unusable_court_gives_no_track_rather_than_a_thin_one() {
        // A degenerate net line would otherwise classify the whole frame as one
        // side and quietly produce a track of whoever was most confident.
        val raw = RawInference(header(), (0 until 5).map { frame(it, listOf(person(966.0, 900.0))) })
        val track = buildNearPlayerTrack(
            raw,
            keypoints.copy(netLeft = Point(0.0, 0.0), netRight = Point(0.0, 0.0)),
        )
        assertTrue(track.samples.isEmpty())
        assertEquals(0, track.framesWithPose)
        assertEquals(0.0, track.coverage)
        assertEquals(5, track.rejections[RejectionReason.BAD_COURT])
    }

    @Test
    fun the_track_feeds_the_heatmap_in_court_metres() {
        val raw = RawInference(
            header(),
            (0 until 30).map { frame(it, listOf(person(if (it < 15) 700.0 else 1200.0, 900.0))) },
        )
        val track = buildNearPlayerTrack(raw, keypoints)
        val occupancy = CourtOccupancy()
        occupancy.addAll(track.samples, fps = 30.0)

        // Thirty frames at 30fps is one second, split between two places.
        assertEquals(1.0, occupancy.totalSeconds, 0.05)
        assertEquals(2, occupancy.grid().flatten().count { it > 0.0 })
    }
}
