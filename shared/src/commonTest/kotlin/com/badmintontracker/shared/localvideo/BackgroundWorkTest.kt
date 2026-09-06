package com.badmintontracker.shared.localvideo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundWorkTest {

    private fun entry(id: String, stage: AnalyzeStage) = LocalVideoEntry(
        id = id,
        uri = "file://$id",
        displayName = id,
        durationMs = 1_000,
        sizeBytes = 1_000,
        addedAtEpochMs = 0,
        stage = stage,
    )

    private fun work(
        entries: List<LocalVideoEntry> = emptyList(),
        progress: Map<String, AnalyzeProgress> = emptyMap(),
        device: List<DeviceWork> = emptyList(),
        sessionFailures: Set<String> = emptySet(),
    ) = backgroundWork(entries, progress, device, sessionFailures)

    @Test
    fun nothing_running_shows_nothing() {
        assertNull(work())
    }

    @Test
    fun settled_stages_are_not_running() {
        // Delegates to isAnalysisRunning rather than restating the rule, so a
        // change to which stages count cannot leave the indicator behind.
        listOf(AnalyzeStage.LOCAL, AnalyzeStage.ANALYZED, AnalyzeStage.FAILED).forEach { stage ->
            assertNull(work(entries = listOf(entry("a", stage))), "$stage must not count as running")
        }
    }

    @Test
    fun an_upload_reports_its_own_progress() {
        val w = work(
            entries = listOf(entry("a", AnalyzeStage.UPLOADING)),
            progress = mapOf("a" to AnalyzeProgress("a", uploadProgress = 0.4f)),
        )
        assertEquals("Uploading 40%", w?.label)
        assertEquals(0.4f, w?.fraction)
        assertEquals(1, w?.activeCount)
    }

    @Test
    fun pipeline_progress_wins_once_it_appears() {
        val w = work(
            entries = listOf(entry("a", AnalyzeStage.PROCESSING)),
            progress = mapOf("a" to AnalyzeProgress("a", uploadProgress = 1.0f, pipelineProgress = 0.25f)),
        )
        assertEquals("Processing in the cloud 25%", w?.label)
        assertEquals(0.25f, w?.fraction)
    }

    @Test
    fun an_unknown_fraction_leaves_the_label_bare_and_the_ring_indeterminate() {
        val w = work(entries = listOf(entry("a", AnalyzeStage.PROCESSING)))
        assertEquals("Processing in the cloud", w?.label)
        assertNull(w?.fraction)
    }

    @Test
    fun a_device_run_is_named_apart_from_a_cloud_one() {
        val w = work(device = listOf(DeviceWork("a", DevicePhase.ANALYSING, fraction = 0.12f, failed = false)))
        assertEquals("Analysing on device 12%", w?.label)
        assertEquals(0.12f, w?.fraction)
    }

    @Test
    fun each_device_phase_says_what_it_is_doing() {
        // Copying a multi-gigabyte file and running inference over it are
        // minutes apart in what the user should expect next, so "Analysing"
        // during the copy is a wrong answer rather than a vague one.
        fun label(phase: DevicePhase) =
            work(device = listOf(DeviceWork("a", phase, fraction = null, failed = false)))?.label

        assertEquals("Preparing video", label(DevicePhase.PREPARING))
        assertEquals("Analysing on device", label(DevicePhase.ANALYSING))
        assertEquals("Cutting clips", label(DevicePhase.CUTTING))
    }

    @Test
    fun a_failed_device_run_is_not_active() {
        assertNull(work(device = listOf(DeviceWork("a", DevicePhase.ANALYSING, fraction = null, failed = true))))
    }

    @Test
    fun several_runs_are_counted_rather_than_averaged() {
        val w = work(
            entries = listOf(entry("a", AnalyzeStage.UPLOADING)),
            progress = mapOf("a" to AnalyzeProgress("a", uploadProgress = 0.9f)),
            device = listOf(DeviceWork("b", DevicePhase.ANALYSING, fraction = 0.1f, failed = false)),
        )
        assertEquals(2, w?.activeCount)
        assertEquals("2 analyses in progress", w?.label)
        // Averaging 90% and 10% would read as a confident 50% while neither run
        // is anywhere near half done.
        assertNull(w?.fraction)
    }

    @Test
    fun both_pipelines_on_one_video_count_as_two_analyses() {
        // The comparison the app exists to make: one video, both pipelines.
        val w = work(
            entries = listOf(entry("a", AnalyzeStage.UPLOADING)),
            device = listOf(DeviceWork("a", DevicePhase.ANALYSING, fraction = 0.5f, failed = false)),
        )
        assertEquals(2, w?.activeCount)
        assertEquals("2 analyses in progress", w?.label)
    }

    @Test
    fun a_persisted_failure_does_not_badge_the_indicator() {
        // The regression this rule exists for: stage FAILED is durable and is
        // cleared only by a retry, so badging it would light the indicator
        // permanently for a video that failed last week.
        assertNull(work(entries = listOf(entry("a", AnalyzeStage.FAILED))))

        val alongside = work(
            entries = listOf(entry("a", AnalyzeStage.FAILED), entry("b", AnalyzeStage.UPLOADING)),
        )
        assertFalse(alongside!!.hasFailure, "a stale FAILED entry must not badge a healthy run")
    }

    @Test
    fun a_failure_seen_this_session_badges_and_outlives_the_run() {
        val duringRun = work(
            entries = listOf(entry("b", AnalyzeStage.UPLOADING)),
            sessionFailures = setOf("a"),
        )
        assertTrue(duringRun!!.hasFailure)

        // Nothing left running: the badge is the only trace the user gets if
        // they were on another screen when it broke.
        val afterRun = work(sessionFailures = setOf("a"))
        assertEquals("Analysis failed", afterRun?.label)
        assertTrue(afterRun!!.hasFailure)
        assertEquals(0, afterRun.activeCount)
    }
}
