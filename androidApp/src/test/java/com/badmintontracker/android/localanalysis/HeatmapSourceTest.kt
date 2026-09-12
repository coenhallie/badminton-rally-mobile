package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerTrack
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HeatmapSourceTest {

    @Test
    fun a_finished_pose_run_is_drawn_from_memory() {
        val s = heatmapSource(done(walked), stored(sat, fps = 60.0))
        s?.track shouldBe walked
        s?.fps shouldBe 30.0
    }

    @Test
    fun a_run_that_asked_for_no_pose_falls_through_to_the_stored_track() {
        // The defect this function exists for, and the test that pins the fps
        // pairing, since it is the only one where both sources exist and their
        // frame rates disagree. A pose-less run completes with an empty
        // PlayerTrack, so preferring it replaced a stored heatmap with "No pose
        // data for this video" - and court marking defaults to rallies only, so
        // a pose-less run is the ordinary case, not a corner.
        val s = heatmapSource(done(PlayerTrack(emptyList(), 0, emptyMap())), stored(sat, fps = 60.0))
        s?.track shouldBe sat
        s?.fps shouldBe 60.0
    }

    @Test
    fun each_source_can_be_drawn_on_its_own() {
        // Renamed after a review proved the old name was a lie: this was called
        // "the fps always belongs to the track that was chosen" and could not
        // fail for that, because each case here has exactly one non-null source,
        // so every independent-pairing implementation passes it. The fps pairing
        // is pinned by the test above, where both sources exist and disagree.
        // What this does uniquely cover is the in-memory-only path.
        heatmapSource(done(walked), null)?.track shouldBe walked
        heatmapSource(null, stored(sat, fps = 60.0))?.track shouldBe sat
    }

    @Test
    fun nothing_in_memory_and_nothing_on_disk_draws_no_court() {
        heatmapSource(null, null) shouldBe null
    }

    @Test
    fun a_pose_less_run_with_nothing_on_disk_draws_no_court() {
        // Not an empty court: the panel says so in words instead.
        heatmapSource(done(PlayerTrack(emptyList(), 0, emptyMap())), null) shouldBe null
    }

    private val walked = PlayerTrack(
        samples = listOf(PlayerSample(frame = 0, courtPosition = Point(1.0, 2.0))),
        framesWithPose = 1,
        rejections = emptyMap(),
    )
    private val sat = PlayerTrack(
        samples = listOf(PlayerSample(frame = 5, courtPosition = Point(3.0, 4.0))),
        framesWithPose = 1,
        rejections = emptyMap(),
    )

    private fun done(track: PlayerTrack) = LocalAnalysisState.Done(
        rallies = 2,
        shuttleVisible = 50,
        totalFrames = 100,
        clips = emptyList(),
        elapsedSeconds = 9.0,
        playerTrack = track,
        fps = 30.0,
    )

    private fun stored(track: PlayerTrack, fps: Double) = PlayerTrackStore.Stored(
        listOf(PlayerTrackStore.SideTrack(CourtSide.NEAR, track)),
        fps,
    )
}
