package com.badmintontracker.shared.localvideo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalVideoRulesTest {

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
