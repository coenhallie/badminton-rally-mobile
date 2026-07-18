package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.testing.FakeClipsRepository
import com.badmintontracker.shared.testing.FakeVideosRepository
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.russhwolf.settings.MapSettings
import kotlinx.datetime.Instant
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeCoordinatorTest {

    private val localVideos = LocalVideoRepository(MapSettings())
    private val videos = FakeVideosRepository()
    private val clips = FakeClipsRepository()
    private val localAnnotations = LocalAnnotationsRepository(MapSettings())

    private fun keypoints() = CourtKeypoints(
        topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
    )

    private fun entry(id: String = "e1") = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
    )

    private fun clipFor(videoId: String) = RallyClip(
        id = "clip-$videoId", videoId = videoId, ownerId = "user-self", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 5f, durationSeconds = 5f,
        clipStoragePath = "user-self/$videoId/rally_1.mp4", annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun TestScope.coordinator() = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        localAnnotations = localAnnotations,
    )

    @Test
    fun happy_path_uploads_creates_row_sets_keypoints_triggers_polls_and_removes_entry() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))   // pipeline produced rally clips
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        videos.uploadCalls shouldBe listOf("e1")
        videos.createCalls.single().first shouldBe "e1"
        videos.keypointsCalls.single().first shouldBe "e1"
        videos.startCalls shouldBe listOf("e1")
        clips.refreshCalls.size shouldBe 1
        localVideos.get("e1").shouldBeNull()   // auto-removed on success
    }

    @Test
    fun upload_failure_marks_failed_at_upload_and_retry_resumes_from_upload() = runTest {
        localVideos.add(entry())
        videos.uploadStates = listOf(UploadState.Failed("network"))
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        val failed = localVideos.get("e1").shouldNotBeNull()
        failed.stage shouldBe AnalyzeStage.FAILED
        failed.failedStep shouldBe AnalyzeStep.UPLOAD
        failed.keypoints.shouldNotBeNull()     // never re-tap

        videos.uploadStates = listOf(UploadState.Done)
        clips.clips.value = listOf(clipFor("e1"))   // retry now completes end-to-end
        c.retry("e1")
        runCurrent()
        videos.uploadCalls shouldBe listOf("e1", "e1")
        localVideos.get("e1").shouldBeNull()
    }

    @Test
    fun trigger_failure_retry_skips_upload_and_row_creation() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        videos.nextStartResult = Result.failure(IllegalStateException("409"))
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        localVideos.get("e1")?.failedStep shouldBe AnalyzeStep.TRIGGER

        videos.nextStartResult = Result.success(Unit)
        c.retry("e1")
        runCurrent()
        videos.uploadCalls.size shouldBe 1     // not re-uploaded
        videos.createCalls.size shouldBe 1     // not re-created
        videos.startCalls.size shouldBe 2
        localVideos.get("e1").shouldBeNull()
    }

    @Test
    fun duplicate_row_on_retry_is_treated_as_success() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        videos.nextCreateResult = Result.failure(
            IllegalStateException("""duplicate key value violates unique constraint "videos_pkey""""),
        )
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        // CREATE_ROW duplicate -> continue; pipeline completes.
        localVideos.get("e1").shouldBeNull()
    }

    @Test
    fun pipeline_failure_marks_processing_step_with_server_error() = runTest {
        localVideos.add(entry())
        videos.processingUpdates = listOf(ProcessingUpdate("failed_phase1", null, "no court detected"))
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        val failed = localVideos.get("e1").shouldNotBeNull()
        failed.failedStep shouldBe AnalyzeStep.PROCESSING
        failed.failureMessage shouldBe "no court detected"
    }

    @Test
    fun success_but_no_clips_marks_no_rallies_instead_of_vanishing() = runTest {
        localVideos.add(entry())
        // Pipeline completes but produced no rally clips for this video.
        clips.clips.value = emptyList()
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        advanceTimeBy(10_000)   // cover the clip-count retry delays
        runCurrent()
        val failed = localVideos.get("e1").shouldNotBeNull()
        failed.stage shouldBe AnalyzeStage.FAILED
        failed.failedStep shouldBe AnalyzeStep.PROCESSING
        failed.failureMessage shouldBe "Analysis finished but found no rallies in this video."
        clips.countCalls.size shouldBe 3       // zero is only trusted after retries
    }

    @Test
    fun transient_count_failure_retries_and_succeeds_instead_of_reporting_no_rallies() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))                        // clips exist on the server
        clips.countResults += Result.failure(IllegalStateException("HTTP 503"))  // first count query fails
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        advanceTimeBy(10_000)   // cover the clip-count retry delays
        runCurrent()
        clips.countCalls.size shouldBe 2       // retried after the failed query
        localVideos.get("e1").shouldBeNull()   // success, not a false "no rallies"
    }

    @Test
    fun clip_rows_lagging_behind_status_flip_are_retried_before_declaring_no_rallies() = runTest {
        localVideos.add(entry())
        // Server says done, but the rally_clips rows only become visible on the second query.
        clips.countResults += Result.success(0)
        clips.countResults += Result.success(74)
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        advanceTimeBy(10_000)   // cover the clip-count retry delays
        runCurrent()
        clips.countCalls.size shouldBe 2
        localVideos.get("e1").shouldBeNull()   // success, not a false "no rallies"
    }

    @Test
    fun count_query_never_succeeding_does_not_report_false_no_rallies() = runTest {
        localVideos.add(entry())
        clips.clips.value = emptyList()
        repeat(3) { clips.countResults += Result.failure(IllegalStateException("HTTP 503")) }
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        advanceTimeBy(10_000)   // cover the clip-count retry delays
        runCurrent()
        // The pipeline reported success and the count was never confirmed to be
        // zero — treat as success rather than contradicting the matches list.
        localVideos.get("e1").shouldBeNull()
    }

    @Test
    fun reattach_resumes_polling_for_persisted_processing_entries() = runTest {
        localVideos.add(entry().copy(stage = AnalyzeStage.PROCESSING, keypoints = keypoints()))
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator()
        c.reattachToProcessing()
        runCurrent()
        videos.uploadCalls shouldBe emptyList<String>()
        localVideos.get("e1").shouldBeNull()   // completed via observeProcessing
    }

    @Test
    fun reattach_restarts_pipeline_for_entries_stuck_in_uploading() = runTest {
        // Process died mid-upload: stage persisted as UPLOADING, no failedStep.
        localVideos.add(entry().copy(stage = AnalyzeStage.UPLOADING, keypoints = keypoints()))
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator()
        c.reattachToProcessing()
        runCurrent()
        videos.uploadCalls shouldBe listOf("e1")   // resumed from the UPLOAD step
        localVideos.get("e1").shouldBeNull()       // completed end-to-end
    }

    @Test
    fun success_with_annotations_keeps_entry_as_analyzed() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        localAnnotations.add("e1", 1f, "note", null)   // has an annotation
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        val kept = localVideos.get("e1").shouldNotBeNull()
        kept.stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun hasActiveUpload_true_only_while_uploading() = runTest {
        localVideos.add(entry())
        val c = coordinator()
        c.hasActiveUpload.value shouldBe false
        c.startAnalysis("e1", keypoints())
        runCurrent()
        c.hasActiveUpload.value shouldBe false // finished already
    }
}
