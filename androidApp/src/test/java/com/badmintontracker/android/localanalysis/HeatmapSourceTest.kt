package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
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
        // The defect this function exists for. A pose-less run completes with an
        // empty PlayerTrack, so preferring it would draw an empty court over a
        // track that is sitting on disk - a coach who runs pose once and a
        // cheaper metric afterwards would lose the heatmap they already paid for.
        val s = heatmapSource(done(PlayerTrack(emptyList(), 0, emptyMap())), stored(sat, fps = 60.0))
        s?.track shouldBe sat
        s?.fps shouldBe 60.0
    }

    @Test
    fun the_fps_always_belongs_to_the_track_that_was_chosen() {
        // Picking track and fps independently paired them by coincidence. A
        // 60fps stored track drawn at the in-memory run's 30fps would halve
        // every dwell time on the court.
        heatmapSource(done(walked), null)?.fps shouldBe 30.0
        heatmapSource(null, stored(sat, fps = 60.0))?.fps shouldBe 60.0
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
        samples = listOf(PlayerSample(frame = 0, courtPosition = Point(1.0, 2.0), onAnkles = true)),
        framesWithPose = 1,
        rejections = emptyMap(),
    )
    private val sat = PlayerTrack(
        samples = listOf(PlayerSample(frame = 5, courtPosition = Point(3.0, 4.0), onAnkles = false)),
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

    private fun stored(track: PlayerTrack, fps: Double) = PlayerTrackStore.Stored(track, fps)
}
