package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.PlayerTrack
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DeviceRunInFlightTest {

    @Test
    fun the_three_working_states_are_in_flight() {
        isDeviceRunInFlight(LocalAnalysisState.Preparing("Preparing video")) shouldBe true
        isDeviceRunInFlight(LocalAnalysisState.Analysing(0.4f)) shouldBe true
        isDeviceRunInFlight(LocalAnalysisState.Cutting(done = 2, total = 9)) shouldBe true
    }

    @Test
    fun an_outcome_is_not_work() {
        // The point of the rule: after either outcome the Analyze button has to
        // come back, because a failed run is worth retrying and a forgotten one
        // is worth running again.
        isDeviceRunInFlight(LocalAnalysisState.Failed("out of memory")) shouldBe false
        isDeviceRunInFlight(done()) shouldBe false
    }

    @Test
    fun a_video_nobody_has_analysed_is_not_in_flight() {
        isDeviceRunInFlight(LocalAnalysisState.Idle) shouldBe false
    }

    private fun done() = LocalAnalysisState.Done(
        rallies = 3,
        shuttleVisible = 100,
        totalFrames = 200,
        clips = emptyList(),
        elapsedSeconds = 12.0,
        playerTrack = PlayerTrack(samples = emptyList(), framesWithPose = 0, rejections = emptyMap()),
        fps = 30.0,
    )
}
