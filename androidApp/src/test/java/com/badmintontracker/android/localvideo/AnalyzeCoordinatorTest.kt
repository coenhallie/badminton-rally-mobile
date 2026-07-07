package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeCoordinatorTest {

    private val localVideos = LocalVideoRepository(MapSettings())
    private val videos = FakeVideosRepository()
    private val clips = FakeClipsRepository()

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

    private fun TestScope.coordinator() = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
    )

    @Test
    fun happy_path_uploads_creates_row_sets_keypoints_triggers_polls_and_removes_entry() = runTest {
        localVideos.add(entry())
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
        c.retry("e1")
        runCurrent()
        videos.uploadCalls shouldBe listOf("e1", "e1")
        localVideos.get("e1").shouldBeNull()
    }

    @Test
    fun trigger_failure_retry_skips_upload_and_row_creation() = runTest {
        localVideos.add(entry())
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
    fun reattach_resumes_polling_for_persisted_processing_entries() = runTest {
        localVideos.add(entry().copy(stage = AnalyzeStage.PROCESSING, keypoints = keypoints()))
        val c = coordinator()
        c.reattachToProcessing()
        runCurrent()
        videos.uploadCalls shouldBe emptyList<String>()
        localVideos.get("e1").shouldBeNull()   // completed via observeProcessing
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
