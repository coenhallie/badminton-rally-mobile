package com.badmintontracker.shared.localvideo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalVideoRulesTest {

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
}
