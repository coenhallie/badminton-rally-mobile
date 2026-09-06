package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalVideoRulesTest {

    // Values are irrelevant here: these rules only ever ask whether the court was
    // marked, never where.
    private val CORNERS = CourtKeypoints(
        topLeft = listOf(0f, 0f), topRight = listOf(1f, 0f),
        bottomRight = listOf(1f, 1f), bottomLeft = listOf(0f, 1f),
        netLeft = listOf(0f, 0.5f), netRight = listOf(1f, 0.5f),
        serviceLineNearLeft = listOf(0f, 0.7f), serviceLineNearRight = listOf(1f, 0.7f),
        serviceLineFarLeft = listOf(0f, 0.3f), serviceLineFarRight = listOf(1f, 0.3f),
        centerNear = listOf(0.5f, 0.7f), centerFar = listOf(0.5f, 0.3f),
    )

    private fun entry(stage: AnalyzeStage, keypoints: CourtKeypoints?) = LocalVideoEntry(
        id = "e1",
        uri = "content://v",
        displayName = "v.mp4",
        durationMs = 1_000,
        sizeBytes = 1,
        addedAtEpochMs = 0,
        keypoints = keypoints,
        stage = stage,
    )

    @Test
    fun spinner_shows_only_while_the_pipeline_is_actively_running() {
        // ANALYZED is a settled stage: an entry kept for its annotations must
        // not show an eternal loading indicator.
        isAnalysisRunning(AnalyzeStage.UPLOADING) shouldBe true
        isAnalysisRunning(AnalyzeStage.PROCESSING) shouldBe true
        isAnalysisRunning(AnalyzeStage.LOCAL) shouldBe false
        isAnalysisRunning(AnalyzeStage.FAILED) shouldBe false
        isAnalysisRunning(AnalyzeStage.ANALYZED) shouldBe false
    }

    @Test
    fun remove_is_blocked_only_while_the_pipeline_is_actively_running() {
        canRemoveLocalVideo(AnalyzeStage.LOCAL) shouldBe true
        canRemoveLocalVideo(AnalyzeStage.FAILED) shouldBe true
        canRemoveLocalVideo(AnalyzeStage.ANALYZED) shouldBe true
        // Uploading reads the file; processing still owes the user a result.
        canRemoveLocalVideo(AnalyzeStage.UPLOADING) shouldBe false
        canRemoveLocalVideo(AnalyzeStage.PROCESSING) shouldBe false
    }

    @Test
    fun details_are_editable_only_before_the_pipeline_starts() {
        // Metadata rides on the videos INSERT and the DB grants no UPDATE on
        // either column, so any stage past LOCAL means the row is already
        // written (or about to be) with what the user last saw.
        canEditLocalVideoDetails(AnalyzeStage.LOCAL) shouldBe true
        canEditLocalVideoDetails(AnalyzeStage.UPLOADING) shouldBe false
        canEditLocalVideoDetails(AnalyzeStage.PROCESSING) shouldBe false
        canEditLocalVideoDetails(AnalyzeStage.ANALYZED) shouldBe false
        // FAILED included, deliberately: a run that failed at UPLOAD has no row
        // yet, but one that failed later does, and the rule cannot tell them
        // apart from the stage alone.
        canEditLocalVideoDetails(AnalyzeStage.FAILED) shouldBe false
    }

    @Test
    fun a_failed_run_that_still_has_its_court_resumes_instead_of_asking_again() {
        canResumeFailedAnalysis(entry(AnalyzeStage.FAILED, CORNERS)) shouldBe true
    }

    @Test
    fun a_failed_run_with_no_court_must_be_marked_again() {
        // The guard the three call sites exist for. Resuming here would run a
        // pipeline that does not know where the court is.
        canResumeFailedAnalysis(entry(AnalyzeStage.FAILED, null)) shouldBe false
    }

    @Test
    fun a_run_that_has_not_failed_is_never_resumed_however_complete_its_court_is() {
        // Notably LOCAL: a device analysis leaves the stage at LOCAL when it
        // fails, so its "Retry" must fall through to court marking, which is
        // where LocalAnalysisRunner takes its keypoints from.
        canResumeFailedAnalysis(entry(AnalyzeStage.LOCAL, CORNERS)) shouldBe false
        canResumeFailedAnalysis(entry(AnalyzeStage.UPLOADING, CORNERS)) shouldBe false
        canResumeFailedAnalysis(entry(AnalyzeStage.PROCESSING, CORNERS)) shouldBe false
        canResumeFailedAnalysis(entry(AnalyzeStage.ANALYZED, CORNERS)) shouldBe false
    }
    @Test
    fun a_video_whose_match_is_gone_is_handed_back_to_the_local_list() {
        // The whole defect in one case. Nothing lists this video: "on this phone"
        // filters to entries with no scoreLogId, and the match it names does not
        // exist to show it under. Ten of eleven videos on one real device.
        val orphan = entry(AnalyzeStage.LOCAL, null).copy(id = "v1", scoreLogId = "gone")
        orphanedLocalVideoIds(listOf(orphan), knownScoreLogIds = setOf("still-here")) shouldBe
            listOf("v1")
    }

    @Test
    fun a_video_whose_match_still_exists_is_left_alone() {
        val bound = entry(AnalyzeStage.LOCAL, null).copy(id = "v1", scoreLogId = "log1")
        orphanedLocalVideoIds(listOf(bound), knownScoreLogIds = setOf("log1")) shouldBe emptyList()
    }

    @Test
    fun a_video_that_never_belonged_to_a_match_is_not_an_orphan() {
        // Already standalone and already visible. Returning it would make the
        // reconciliation write on every sync forever.
        val free = entry(AnalyzeStage.LOCAL, null).copy(id = "v1", scoreLogId = null)
        orphanedLocalVideoIds(listOf(free), knownScoreLogIds = emptySet()) shouldBe emptyList()
    }

    @Test
    fun an_upload_in_flight_is_still_detached_from_a_match_that_is_gone() {
        // Deliberate, and the opposite of canRemoveLocalVideo's rule. The run is
        // keyed by entry id so it is unharmed, and clearing the binding is what
        // stops the finished upload attaching itself to a deleted match.
        val running = entry(AnalyzeStage.UPLOADING, CORNERS).copy(id = "v1", scoreLogId = "gone")
        orphanedLocalVideoIds(listOf(running), knownScoreLogIds = emptySet()) shouldBe listOf("v1")
    }

}
