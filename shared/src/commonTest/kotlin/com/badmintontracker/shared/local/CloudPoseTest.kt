package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the phone does with a pose stream the cloud sent it.
 *
 * The whole of the cloud path's computation, and deliberately almost nothing:
 * it runs the same player selection a device run runs and stops. It does NOT
 * detect rallies. rally_clips is the rally truth for a cloud video (design 3),
 * because three rally lists already exist for one video and a fourth, computed
 * on the phone from a different track, would disagree with the clips the coach
 * has already annotated.
 *
 * That invariant is structural: [CloudPoseOutcome] has no rally field, so
 * there is nothing for a caller to read and nothing for a later edit to start
 * filling in. These tests pin the parts a type cannot.
 */
class CloudPoseTest {

    private fun raw(fps: Double = 30.0, frames: List<RawFrame> = emptyList()) = RawInference(
        header = RawHeader(
            version = 1, fps = fps, totalFrames = frames.size,
            videoWidth = 1920, videoHeight = 1080, modelVersion = "cloud:test",
        ),
        frames = frames,
    )

    @Test
    fun both_sides_come_back_even_from_a_stream_with_no_people_in_it() {
        // Empty rather than absent, matching LocalAnalysisOutcome's reasoning:
        // "nobody was found" and "this build does not know about the far
        // player" must not look the same to a store or a panel.
        val outcome = poseOnlyAnalysis(raw(frames = listOf(RawFrame(0, 0.0, null, emptyList(), emptyList()))), marks)

        outcome.selections.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        outcome.selections.forEach { it.track.samples shouldBe emptyList() }
    }

    @Test
    fun the_video_dimensions_travel_with_the_poses() {
        // A renderer fits joints to a display box using these. Taking them
        // from anywhere else pairs them with the poses by coincidence, which
        // is the mistake SkeletonStore's KDoc exists to prevent.
        val outcome = poseOnlyAnalysis(raw(), marks)

        outcome.videoWidth shouldBe 1920
        outcome.videoHeight shouldBe 1080
    }

    @Test
    fun an_impossible_frame_rate_is_normalised_the_way_every_other_path_normalises_it() {
        // A zero fps reaches here from a container the prober could not read,
        // and it divides into every occupancy figure the heatmap draws.
        // normalizeFps is the one place that decision lives.
        poseOnlyAnalysis(raw(fps = 0.0), marks).fps shouldBe 30.0
    }

    @Test
    fun a_sane_frame_rate_is_passed_through_unrounded() {
        // 59.94 is not 60, and rounding it would drift a 30-minute skeleton
        // by seconds against its own video.
        poseOnlyAnalysis(raw(fps = 59.94), marks).fps shouldBe 59.94
    }

    @Test
    fun a_stream_that_happens_to_carry_a_shuttle_still_produces_only_poses() {
        // The cloud writes no shuttle into this artifact, and the phone does
        // not depend on that: even handed one, this path has nowhere to put
        // it. A regression that started emitting shuttle data server-side
        // must not quietly start producing rallies here.
        val withShuttle = raw(
            frames = listOf(
                RawFrame(
                    frame = 0, timestamp = 0.0,
                    shuttle = com.badmintontracker.analysis.raw.RawShuttle(1f, 2f, 0.9f, true),
                    boxes = emptyList(), persons = emptyList(),
                ),
            ),
        )

        val outcome = poseOnlyAnalysis(withShuttle, marks)

        outcome.selections.size shouldBe 2
        outcome.selections.forEach { it.track.samples shouldBe emptyList() }
    }

    /**
     * The wire keypoints, as the court-marking screen produces them and as
     * videos.manual_court_keypoints stores them.
     *
     * LocalAnalysisCoordinatorTest's fixture, copied: a square-on camera with
     * the net across the middle. Marks that do NOT fit a court would make
     * every test in this file assert BAD_COURT, which passes for the wrong
     * reason.
     */
    private val marks = CourtKeypoints(
        topLeft = listOf(200f, 200f), topRight = listOf(1700f, 200f),
        bottomRight = listOf(1700f, 900f), bottomLeft = listOf(200f, 900f),
        netLeft = listOf(200f, 550f), netRight = listOf(1700f, 550f),
        serviceLineNearLeft = listOf(200f, 430f), serviceLineNearRight = listOf(1700f, 430f),
        serviceLineFarLeft = listOf(200f, 670f), serviceLineFarRight = listOf(1700f, 670f),
        centerNear = listOf(950f, 430f), centerFar = listOf(950f, 670f),
    )
}
