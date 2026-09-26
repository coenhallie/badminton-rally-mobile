package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.matchlist.DeviceRun
import com.badmintontracker.shared.matchlist.DeviceRunKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DeviceRunMappingTest {

    @Test
    fun each_working_state_is_a_running_run_in_its_own_phase() {
        toDeviceRun(LocalAnalysisState.Preparing("Preparing video")) shouldBe
            DeviceRun(DeviceRunKind.RUNNING, DevicePhase.PREPARING)
        toDeviceRun(LocalAnalysisState.Analysing(0.4f)) shouldBe
            DeviceRun(DeviceRunKind.RUNNING, DevicePhase.ANALYSING)
        toDeviceRun(LocalAnalysisState.Cutting(done = 2, total = 9)) shouldBe
            DeviceRun(DeviceRunKind.RUNNING, DevicePhase.CUTTING)
    }

    @Test
    fun a_failure_carries_its_reason() {
        toDeviceRun(LocalAnalysisState.Failed("out of memory")) shouldBe
            DeviceRun(DeviceRunKind.FAILED, message = "out of memory")
    }

    @Test
    fun idle_and_done_are_absent_because_their_files_speak_for_them() {
        toDeviceRun(LocalAnalysisState.Idle).shouldBeNull()
        toDeviceRun(
            LocalAnalysisState.Done(
                rallies = 3,
                shuttleVisible = 100,
                totalFrames = 200,
                clips = emptyList(),
                elapsedSeconds = 12.0,
                playerTrack = PlayerTrack(samples = emptyList(), framesWithPose = 0, rejections = emptyMap()),
                fps = 30.0,
            ),
        ).shouldBeNull()
    }
}
