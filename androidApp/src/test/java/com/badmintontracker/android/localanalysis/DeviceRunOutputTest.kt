package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerTrack
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * Whether a finished device run is announced as analyzed: AuthGate's watcher
 * drops the ones that found nothing, from the run's own outcome.
 */
class DeviceRunOutputTest {

    @Test
    fun a_run_that_found_nothing_has_nothing_to_announce() {
        done().foundSomething shouldBe false
    }

    @Test
    fun a_cut_rally_is_output() {
        done(clips = listOf(ClipCutter.Clip(index = 1, startSeconds = 1.0, endSeconds = 8.0, file = File("/tmp/1.mp4"))))
            .foundSomething shouldBe true
    }

    @Test
    fun a_track_is_output() {
        val track = PlayerTrack(listOf(PlayerSample(frame = 0, courtPosition = Point(1.0, 2.0))), 1, emptyMap())
        done(track = track).foundSomething shouldBe true
    }

    // The poses can be found while the near/far gate keeps no player, which
    // leaves the track empty and the skeleton stored.
    @Test
    fun a_skeleton_alone_is_output() {
        done(skeletonSaved = true).foundSomething shouldBe true
    }

    private fun done(
        clips: List<ClipCutter.Clip> = emptyList(),
        track: PlayerTrack = PlayerTrack(emptyList(), 0, emptyMap()),
        skeletonSaved: Boolean = false,
    ) = LocalAnalysisState.Done(
        rallies = 0,
        shuttleVisible = 0,
        totalFrames = 163,
        clips = clips,
        elapsedSeconds = 43.0,
        playerTrack = track,
        fps = 30.0,
        skeletonSaved = skeletonSaved,
    )
}
