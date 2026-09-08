package com.badmintontracker.android.localanalysis

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundWorkMonitorTest {

    private fun entry(id: String, stage: AnalyzeStage) = LocalVideoEntry(
        id = id,
        uri = "file://$id",
        displayName = id,
        durationMs = 1_000,
        sizeBytes = 1_000,
        addedAtEpochMs = 0,
        stage = stage,
    )

    private class Fixture(scope: CoroutineScope) {
        val entries = MutableStateFlow<List<LocalVideoEntry>>(emptyList())
        val progress = MutableStateFlow<Map<String, AnalyzeProgress>>(emptyMap())
        val device = MutableStateFlow<Map<String, LocalAnalysisState>>(emptyMap())
        val monitor = BackgroundWorkMonitor(entries, progress, device, scope)
    }

    // backgroundScope, not the test scope: the monitor's collectors never
    // complete, so as children of the test they would hang runTest to its
    // timeout rather than failing.
    private fun fixture(scope: CoroutineScope) = Fixture(scope)

    @Test
    fun a_failure_that_happens_while_watching_badges_the_indicator() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.entries.value = listOf(entry("a", AnalyzeStage.UPLOADING))
        assertFalse(f.monitor.work.value!!.hasFailure)

        f.entries.value = listOf(entry("a", AnalyzeStage.FAILED))
        assertTrue("a failure seen live must badge", f.monitor.work.value!!.hasFailure)
    }

    @Test
    fun a_failure_from_a_previous_run_of_the_app_does_not() {
        runTest(UnconfinedTestDispatcher()) {
            val f = fixture(backgroundScope)
            // The registry is restored from storage already FAILED. This is the
            // first thing the monitor ever sees, so there is no transition, and
            // badging it would mark the chrome until the user retried a video
            // they may have given up on weeks ago.
            f.entries.value = listOf(entry("a", AnalyzeStage.FAILED))
            assertNull("a restored failure is not background work", f.monitor.work.value)

            f.entries.value = listOf(entry("a", AnalyzeStage.FAILED), entry("b", AnalyzeStage.UPLOADING))
            assertFalse(
                "the stale failure must not badge a healthy run",
                f.monitor.work.value!!.hasFailure,
            )
        }
    }

    @Test
    fun a_device_failure_badges_too() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.device.value = mapOf("a" to LocalAnalysisState.Failed("decoder gave up"))
        assertTrue(f.monitor.work.value!!.hasFailure)
    }

    @Test
    fun a_finished_device_run_stops_showing() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.device.value = mapOf("a" to LocalAnalysisState.Analysing(0.5f))
        assertEquals("Analyzing on device 50%", f.monitor.work.value!!.label)

        // Done and Idle stay in the runner's map after a run; neither may keep
        // the indicator spinning.
        f.device.value = mapOf(
            "a" to LocalAnalysisState.Done(
                rallies = 3, shuttleVisible = 10, totalFrames = 100,
                clips = emptyList(), elapsedSeconds = 1.0,
                playerTrack = com.badmintontracker.analysis.player.PlayerTrack(
                    samples = emptyList(), framesWithPose = 0, rejections = emptyMap(),
                ),
                fps = 30.0,
            ),
        )
        assertNull(f.monitor.work.value)
    }

    @Test
    fun a_successful_retry_clears_the_badge() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.entries.value = listOf(entry("a", AnalyzeStage.UPLOADING))
        f.entries.value = listOf(entry("a", AnalyzeStage.FAILED))
        assertTrue(f.monitor.work.value!!.hasFailure)

        // Retry: the row's own Retry puts the entry back in flight.
        f.entries.value = listOf(entry("a", AnalyzeStage.UPLOADING))
        assertFalse("retrying must take the dot down", f.monitor.work.value!!.hasFailure)

        f.entries.value = listOf(entry("a", AnalyzeStage.ANALYZED))
        // Nothing failed and nothing running: the indicator goes away entirely.
        // A badge that only ever accumulates would leave a red dot on five
        // screens with nothing left to click.
        assertNull(f.monitor.work.value)
    }

    @Test
    fun removing_a_failed_video_clears_its_badge() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.entries.value = listOf(entry("a", AnalyzeStage.UPLOADING))
        f.entries.value = listOf(entry("a", AnalyzeStage.FAILED))
        assertTrue(f.monitor.work.value!!.hasFailure)

        f.entries.value = emptyList()
        assertNull("a deleted video cannot be retried, so its badge must go", f.monitor.work.value)
    }

    @Test
    fun restarting_a_failed_device_run_clears_its_badge() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.device.value = mapOf("a" to LocalAnalysisState.Failed("decoder gave up"))
        assertTrue(f.monitor.work.value!!.hasFailure)

        f.device.value = mapOf("a" to LocalAnalysisState.Preparing("Copying video"))
        val work = f.monitor.work.value!!
        assertFalse(work.hasFailure)
        assertEquals("Preparing video", work.label)
    }

    @Test
    fun cloud_and_device_runs_are_counted_together() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.entries.value = listOf(entry("a", AnalyzeStage.PROCESSING))
        f.device.value = mapOf("b" to LocalAnalysisState.Analysing(0.5f))
        assertEquals(2, f.monitor.work.value!!.activeCount)
    }
}
