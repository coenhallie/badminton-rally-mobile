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
import kotlinx.coroutines.CompletableDeferred
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

    private fun TestScope.coordinator(
        registry: LocalVideoRepository = localVideos,
        installCloudPoses: suspend (String, (Float) -> Unit) -> Unit = { _, _ -> },
    ) = AnalyzeCoordinator(
        localVideos = registry,
        videos = videos,
        clips = clips,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        localAnnotations = localAnnotations,
        installCloudPoses = installCloudPoses,
    )

    @Test
    fun happy_path_uploads_creates_row_sets_keypoints_triggers_polls_and_removes_entry() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))   // pipeline produced rally clips
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        videos.uploadCalls shouldBe listOf("e1")
        videos.createCalls.single().videoId shouldBe "e1"
        videos.keypointsCalls.single().first shouldBe "e1"
        videos.startCalls shouldBe listOf("e1")
        clips.refreshCalls.size shouldBe 1
        // Kept, not removed. The cloud computes a skeleton for every run and the
        // skeleton is an overlay on playback, so releasing the footage left an
        // 11MB artifact on the phone that nothing could ever draw.
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // success, not a false "no rallies"
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // success, not a false "no rallies"
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun reattach_resumes_polling_for_persisted_processing_entries() = runTest {
        localVideos.add(entry().copy(stage = AnalyzeStage.PROCESSING, keypoints = keypoints()))
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator()
        c.reattachToProcessing()
        runCurrent()
        videos.uploadCalls shouldBe emptyList<String>()
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // completed via observeProcessing
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
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // completed end-to-end
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
    fun a_successful_analysis_keeps_the_local_file() = runTest {
        // It used to release it: the clips live in the cloud, so the on-device
        // copy looked like pure dead weight. It is not. The cloud computes a
        // skeleton for every run, the skeleton is drawn OVER playback, and
        // releasing the video left that artifact unviewable forever - the coach
        // paid GPU time and storage for a panel the app could never offer.
        val released = mutableListOf<String>()
        val registry = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> released += e.uri })
        registry.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator(registry)
        c.startAnalysis("e1", keypoints())
        runCurrent()
        released shouldBe emptyList()
    }

    @Test
    fun a_successful_analysis_keeps_the_cloud_analysis_it_just_installed() = runTest {
        // The run downloads a pose artifact, selects both players from it and
        // writes two stores - and then retires the entry. Retiring releases the
        // footage (the clips live in the cloud now) but must NOT take the
        // analysis with it: that is the whole result the coach waited for.
        val cloudArtifacts = mutableSetOf<String>()
        val registry = LocalVideoRepository(
            MapSettings(),
            // What RallyAndroidApp wires: AnalysisFiles.deleteAll, whose store
            // list includes cloud-tracks and cloud-skeletons.
            onRemoved = { entry, reason ->
                if (reason == LocalVideoRemoval.DISCARDED) cloudArtifacts.remove(entry.id)
            },
        )
        registry.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator(registry, installCloudPoses = { id, _ -> cloudArtifacts.add(id) })
        c.startAnalysis("e1", keypoints())
        runCurrent()

        registry.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // kept, with its analysis
        cloudArtifacts shouldBe setOf("e1")        // the heatmap is not
    }

    @Test
    fun a_finished_run_announces_itself_so_the_coach_can_be_taken_to_it() = runTest {
        // A device run that lands pulls the coach to Analytics; a cloud run
        // landed in silence, and the entry it had been watching simply vanished
        // from the list. Same rule, same screen - announceFinishedRuns decides
        // whether to navigate, this only says a run finished.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator()
        c.finishedRuns.value shouldBe emptySet()

        c.startAnalysis("e1", keypoints())
        runCurrent()

        c.finishedRuns.value shouldBe setOf("e1")
    }

    @Test
    fun a_failed_run_announces_nothing() = runTest {
        // There is nothing to take the coach to. The row's own failure message
        // is where a failure is reported.
        localVideos.add(entry())
        videos.processingUpdates = listOf(ProcessingUpdate("failed_phase1", null, "no court detected"))
        val c = coordinator()

        c.startAnalysis("e1", keypoints())
        runCurrent()

        c.finishedRuns.value shouldBe emptySet()
    }

    @Test
    fun a_successful_analysis_removes_nothing_at_all() = runTest {
        // The guard on the defect this whole area exists for. The success path
        // must not reach the removal hook, because that hook deletes what an
        // analysis produced - and the analysis it would delete is the one that
        // just finished.
        val removals = mutableListOf<String>()
        val registry = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> removals += e.id })
        registry.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        coordinator(registry).startAnalysis("e1", keypoints())
        runCurrent()
        removals shouldBe emptyList()
    }

    @Test
    fun an_analysis_that_keeps_an_annotated_entry_keeps_its_file() = runTest {
        // The entry survives as ANALYZED so the notes stay playable; deleting the
        // file underneath it would leave the user tapping a dead video.
        val released = mutableListOf<String>()
        val registry = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> released += e.uri })
        registry.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        localAnnotations.add("e1", 1f, "note", null)
        val c = coordinator(registry)
        c.startAnalysis("e1", keypoints())
        runCurrent()
        released shouldBe emptyList()
    }

    @Test
    fun a_failed_analysis_keeps_the_local_file_for_the_retry() = runTest {
        val released = mutableListOf<String>()
        val registry = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> released += e.uri })
        registry.add(entry())
        videos.processingUpdates = listOf(ProcessingUpdate("failed_phase1", null, "no court detected"))
        val c = coordinator(registry)
        c.startAnalysis("e1", keypoints())
        runCurrent()
        released shouldBe emptyList()
    }

    @Test
    fun retry_after_processing_failure_retriggers_the_edge_function() = runTest {
        localVideos.add(entry())
        videos.processingUpdates = listOf(ProcessingUpdate("failed_phase1", null, "no court detected"))
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        localVideos.get("e1")?.failedStep shouldBe AnalyzeStep.PROCESSING

        // The videos row still holds the terminal failed_* status; polling it
        // again without a new trigger would just re-read the old failure.
        videos.processingUpdates = listOf(ProcessingUpdate("phase1_complete", 1f, null))
        clips.clips.value = listOf(clipFor("e1"))
        c.retry("e1")
        runCurrent()
        videos.startCalls.size shouldBe 2      // edge function re-invoked
        videos.uploadCalls.size shouldBe 1     // upload not repeated
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // retry completed end-to-end
    }

    @Test
    fun hasActiveUpload_stays_true_until_the_last_concurrent_upload_finishes() = runTest {
        localVideos.add(entry("e1"))
        localVideos.add(entry("e2"))
        clips.clips.value = listOf(clipFor("e1"), clipFor("e2"))
        // Gate both uploads so they genuinely overlap, then finish e1 first.
        videos.uploadGates["e1"] = CompletableDeferred()
        videos.uploadGates["e2"] = CompletableDeferred()
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        c.startAnalysis("e2", keypoints())
        runCurrent()
        c.hasActiveUpload.value shouldBe true
        videos.uploadGates.getValue("e1").complete(Unit)
        runCurrent()
        // e1 finished, e2 is still mid-upload: the keep-awake flag must hold.
        c.hasActiveUpload.value shouldBe true
        videos.uploadGates.getValue("e2").complete(Unit)
        runCurrent()
        c.hasActiveUpload.value shouldBe false
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

    @Test
    fun create_row_carries_the_entry_s_title_and_description() = runTest {
        // The only write path for match metadata: the DB grants no UPDATE on
        // either column, so a drop here cannot be repaired from the app.
        localVideos.add(
            entry().copy(title = "Thu League vs Marco", description = "Indoor court 2."),
        )
        clips.clips.value = listOf(clipFor("e1"))
        val c = coordinator()

        c.startAnalysis("e1", keypoints())
        runCurrent()

        val call = videos.createCalls.single()
        call.title shouldBe "Thu League vs Marco"
        call.description shouldBe "Indoor court 2."
    }

    @Test
    fun the_video_row_hook_fires_once_the_row_exists() {
        // Nothing may be told about the videos row before it exists: score_logs
        // has a foreign key to it.
        runTest {
            val ready = mutableListOf<String>()
            localVideos.add(entry())
            clips.clips.value = listOf(clipFor("e1"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { ready += it },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            ready shouldBe listOf("e1")
        }
    }

    @Test
    fun a_reattached_run_waits_for_the_session_before_it_touches_anything() {
        // reattachToProcessing runs at launch, and supabase-kt restores the session
        // asynchronously. A pipeline that ran ahead of it told the scoring store
        // about a video while that store still held the empty list of a cold
        // start, and asked the backend for an upload as nobody.
        runTest {
            val sessionReady = CompletableDeferred<Unit>()
            val ready = mutableListOf<String>()
            localVideos.add(entry().copy(stage = AnalyzeStage.PROCESSING, keypoints = keypoints()))
            clips.clips.value = listOf(clipFor("e1"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { ready += it },
                awaitSession = { sessionReady.await() },
            )
            c.reattachToProcessing()
            runCurrent()
            ready shouldBe emptyList()
            videos.awaitAnalyticsCalls shouldBe emptyList()

            sessionReady.complete(Unit)
            runCurrent()
            ready shouldBe listOf("e1")
        }
    }

    @Test
    fun the_video_row_hook_fires_on_a_retry_that_resumes_after_create_row() {
        // retry() resumes from the failed step, so a run that failed at TRIGGER
        // never re-enters the CREATE_ROW branch. Hanging the hook off that branch
        // body would silently skip it here, and the match would stay unbound.
        runTest {
            val ready = mutableListOf<String>()
            localVideos.add(entry())
            videos.nextStartResult = Result.failure(IllegalStateException("boom"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { ready += it },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            localVideos.get("e1")?.failedStep shouldBe AnalyzeStep.TRIGGER
            ready.clear()

            videos.nextStartResult = Result.success(Unit)
            clips.clips.value = listOf(clipFor("e1"))
            c.retry("e1")
            runCurrent()
            ready shouldBe listOf("e1")
        }
    }

    @Test
    fun the_video_row_hook_fires_before_the_entry_is_removed() {
        // runPipeline ends by deleting the entry. Anything the client needs to
        // remember about the attachment has to be durable before that line, or a
        // crash in between orphans the link with no entry left to recover it from.
        runTest {
            var entryAtHook: LocalVideoEntry? = null
            localVideos.add(entry())
            clips.clips.value = listOf(clipFor("e1"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { entryAtHook = localVideos.get(it) },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            entryAtHook.shouldNotBeNull()
            localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
        }
    }

    // MARK: - Phase 2, which must never turn a good Phase 1 red (Task 11)

    @Test
    fun the_pose_pass_runs_after_the_clips_and_before_the_entry_is_removed() = runTest {
        // Both stores are keyed by entry id, so an install after the removal
        // below would write a track against an id the registry no longer
        // lists. The install is handed the id and must find the entry there.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        val stagesSeen = mutableListOf<AnalyzeStage?>()

        val c = coordinator(installCloudPoses = { id, _ ->
            stagesSeen += localVideos.get(id)?.stage
        })
        c.startAnalysis("e1", keypoints())
        runCurrent()

        videos.startAnalyticsCalls shouldBe listOf("e1")
        // MEASURING while it runs, which is what makes the row unremovable.
        stagesSeen shouldBe listOf(AnalyzeStage.MEASURING)
        // And the ordinary end state is unchanged.
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun the_clip_wait_and_the_analytics_wait_are_different_waits() = runTest {
        // The bug ProcessingUpdateTest describes but cannot catch at this
        // level: a Phase 2 wait that stopped at phase1_complete would fetch a
        // poses.raw Modal has not written yet.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))

        coordinator().startAnalysis("e1", keypoints())
        runCurrent()

        videos.awaitAnalyticsCalls shouldBe listOf(false, true)
    }

    @Test
    fun a_phase_two_that_never_starts_leaves_a_good_phase_one_alone() = runTest {
        // start-analytics answers 409 to a row it will not accept. The clips
        // exist and are watchable; painting the row red and asking the coach
        // to analyse the whole video again would be a lie about what failed.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        videos.nextStartAnalyticsResult = Result.failure(IllegalStateException("HTTP 409"))

        coordinator().startAnalysis("e1", keypoints())
        runCurrent()

        clips.refreshCalls.size shouldBe 1
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED  // kept on success, as ever now
    }

    @Test
    fun an_install_that_throws_leaves_a_good_phase_one_alone() = runTest {
        // The download broke, or the stream was corrupt. Same reasoning: the
        // heatmap is missing, the analysis is not.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))

        coordinator(installCloudPoses = { _, _ -> throw IllegalStateException("connection lost") })
            .startAnalysis("e1", keypoints())
        runCurrent()

        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun an_annotated_video_still_ends_analyzed_after_the_pose_pass() = runTest {
        // The other arm of the branch the install has to precede.
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        localAnnotations.add("e1", 1f, "note", null)

        coordinator().startAnalysis("e1", keypoints())
        runCurrent()

        localVideos.get("e1")?.stage shouldBe AnalyzeStage.ANALYZED
    }

    // MARK: - A run that died while measuring movement

    private fun measuringEntry() = entry().copy(stage = AnalyzeStage.MEASURING, keypoints = keypoints())

    @Test
    fun a_run_killed_before_phase_two_started_starts_it_on_relaunch() = runTest {
        // MEASURING counts as running, so the row can be neither removed nor
        // retried. Reattach ignored the stage, and Phase 2 takes minutes on a
        // phone that kills backgrounded apps: the row span "Measuring movement"
        // for ever.
        localVideos.add(measuringEntry())
        videos.processingUpdates = listOf(ProcessingUpdate("phase1_complete", 1f, null))
        val installed = mutableListOf<String>()

        coordinator(installCloudPoses = { id, _ -> installed += id }).reattachToProcessing()
        runCurrent()

        videos.startAnalyticsCalls shouldBe listOf("e1")
        installed shouldBe listOf("e1")
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun a_run_killed_while_phase_two_was_going_waits_for_it_rather_than_starting_another() = runTest {
        // start-analytics answers 409 to a row already in processing_phase2, and
        // measureMovement treats a refused start as "no heatmap this time". Asking
        // again would have thrown away a Phase 2 that was about to finish.
        localVideos.add(measuringEntry())
        videos.processingUpdates = listOf(
            ProcessingUpdate("processing_phase2", 0.4f, null),
            ProcessingUpdate("completed", 1f, null),
        )
        videos.nextStartAnalyticsResult = Result.failure(IllegalStateException("HTTP 409"))
        val installed = mutableListOf<String>()

        coordinator(installCloudPoses = { id, _ -> installed += id }).reattachToProcessing()
        runCurrent()

        videos.startAnalyticsCalls shouldBe emptyList()
        installed shouldBe listOf("e1")
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun a_run_killed_after_phase_two_finished_only_has_the_artifact_left_to_fetch() = runTest {
        localVideos.add(measuringEntry())
        videos.processingUpdates = listOf(ProcessingUpdate("completed", 1f, null))
        videos.nextStartAnalyticsResult = Result.failure(IllegalStateException("HTTP 409"))
        val installed = mutableListOf<String>()

        coordinator(installCloudPoses = { id, _ -> installed += id }).reattachToProcessing()
        runCurrent()

        videos.startAnalyticsCalls shouldBe emptyList()
        installed shouldBe listOf("e1")
        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }

    @Test
    fun a_relaunch_that_cannot_reach_the_server_still_frees_the_row() = runTest {
        // Phase 1 succeeded long ago. Whatever happens to the heatmap, the entry
        // must come out of MEASURING, or the coach can never remove it.
        localVideos.add(measuringEntry())
        videos.processingUpdates = listOf(ProcessingUpdate("failed_connection", null, "offline"))

        coordinator().reattachToProcessing()
        runCurrent()

        localVideos.get("e1").shouldNotBeNull().stage shouldBe AnalyzeStage.ANALYZED
    }
}
