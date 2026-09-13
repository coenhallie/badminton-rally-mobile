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
        s?.tracks?.map { it.track } shouldBe listOf(walked)
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
        s?.tracks?.map { it.track } shouldBe listOf(sat)
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
        heatmapSource(done(walked), null)?.tracks?.map { it.track } shouldBe listOf(walked)
        heatmapSource(null, stored(sat, fps = 60.0))?.tracks?.map { it.track } shouldBe listOf(sat)
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

    @Test
    fun a_stored_pair_of_tracks_is_offered_as_a_pair() {
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 2)),
            ),
            fps = 30.0,
        )

        val source = heatmapSource(done = null, stored = stored)!!

        source.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        source.fps shouldBe 30.0
    }

    @Test
    fun an_in_memory_run_is_still_one_near_track() {
        // A device run produces the near player and nothing else, so the
        // in-memory branch cannot grow a second track and the toggle will not
        // appear for it. Pinned because a reader of the pair type above would
        // reasonably assume otherwise.
        val done = done(trackWith(samples = 4))

        val source = heatmapSource(done = done, stored = null)!!

        source.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR)
    }

    @Test
    fun an_empty_in_memory_run_still_falls_through_to_a_stored_pair() {
        // Unchanged behaviour, restated against the new shape. A run that
        // asked for no pose metric completes with an EMPTY PlayerTrack, and
        // preferring it blindly replaced a perfectly good stored heatmap with
        // "No pose data for this video". Court marking seeds its metrics to
        // RALLY_CLIPS alone, so a pose-less run is the DEFAULT.
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 2)),
            ),
            fps = 30.0,
        )
        val done = done(trackWith(samples = 0))

        heatmapSource(done, stored)!!.tracks.size shouldBe 2
    }

    @Test
    fun a_side_with_no_samples_is_not_offered_as_a_choice() {
        // A toggle whose second option draws an empty court is a control that
        // cannot usefully be actuated, which is the same objection the tab row
        // was gated on. One usable track means no toggle.
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 0)),
            ),
            fps = 30.0,
        )

        heatmapSource(done = null, stored = stored)!!.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR)
    }

    @Test
    fun a_stored_pair_with_nobody_on_either_side_draws_no_court() {
        // The filter can empty the list, and an empty HeatmapSource would be a
        // panel drawing a court with no heat on it rather than saying so.
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 0)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 0)),
            ),
            fps = 30.0,
        )

        heatmapSource(done = null, stored = stored) shouldBe null
    }

    private fun trackWith(samples: Int) = PlayerTrack(
        samples = (0 until samples).map { PlayerSample(it, Point(it * 0.1, 3.05)) },
        framesWithPose = samples,
        rejections = emptyMap(),
    )

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
