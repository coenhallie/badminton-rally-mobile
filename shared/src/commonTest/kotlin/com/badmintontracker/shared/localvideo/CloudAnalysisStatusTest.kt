package com.badmintontracker.shared.localvideo

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * The one line a row shows while the cloud is working on its video.
 *
 * Worth its own tests because four screens render it - the drawer's local video
 * row and the Analytics row, on each platform - and it used to be written out at
 * three of them. Android's Analytics said "Uploading" with no number while the
 * drawer beside it said "Uploading 42%…" for the same run, and iOS's port of
 * that screen had gone the other way, so the platforms disagreed too.
 */
class CloudAnalysisStatusTest {

    private fun progress(upload: Float? = null, pipeline: Float? = null) =
        AnalyzeProgress(entryId = "e1", uploadProgress = upload, pipelineProgress = pipeline)

    @Test
    fun a_video_that_has_not_been_sent_anywhere_says_nothing() {
        cloudAnalysisStatus(AnalyzeStage.LOCAL, null) shouldBe null
    }

    @Test
    fun a_failure_says_nothing_here_because_the_row_shows_its_message() {
        // The reason is on the entry, and a row that showed both would say
        // "Analyzing…" above the sentence explaining why it stopped.
        cloudAnalysisStatus(AnalyzeStage.FAILED, null) shouldBe null
    }

    @Test
    fun an_upload_and_the_cloud_run_are_named_apart() {
        // Not one "Analyzing" for both: an upload stops when the app leaves the
        // foreground and the cloud run does not, so a user waiting on one is
        // owed which of the two they are waiting on.
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, progress(upload = 0.42f)) shouldBe "Uploading 42%…"
        cloudAnalysisStatus(AnalyzeStage.PROCESSING, progress(pipeline = 0.8f)) shouldBe "Analyzing 80%…"
    }

    @Test
    fun a_stage_with_no_number_yet_still_says_it_is_working() {
        // The ellipsis carries it. A bare "Uploading" reads as a settled state.
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, null) shouldBe "Uploading…"
        cloudAnalysisStatus(AnalyzeStage.PROCESSING, null) shouldBe "Analyzing…"
        // A progress object for the OTHER half is the same as none: an upload
        // reports no pipeline fraction and vice versa.
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, progress(pipeline = 0.5f)) shouldBe "Uploading…"
    }

    @Test
    fun a_finished_run_says_so() {
        // Only a row ever shows this. The chrome indicator drops a run the
        // moment it stops running, so it has no settled state to name.
        cloudAnalysisStatus(AnalyzeStage.ANALYZED, null) shouldBe "Analyzed"
    }

    @Test
    fun the_percentage_is_truncated_rather_than_rounded() {
        // Deliberate, and the reason this does not share BackgroundWork.kt's
        // `withPercent`, which rounds. Both platforms have shipped truncation on
        // this line; rounding would move a ticking number by one at every step.
        cloudAnalysisStatus(AnalyzeStage.PROCESSING, progress(pipeline = 0.419f)) shouldBe "Analyzing 41%…"
    }

    @Test
    fun a_fraction_outside_the_range_is_clamped_rather_than_shown() {
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, progress(upload = 1.4f)) shouldBe "Uploading 100%…"
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, progress(upload = -0.2f)) shouldBe "Uploading 0%…"
        // NaN reaches here from a divide by a zero-length file, and "NaN%" on a
        // row is worse than no number at all.
        cloudAnalysisStatus(AnalyzeStage.UPLOADING, progress(upload = Float.NaN)) shouldBe "Uploading…"
    }

    @Test
    fun the_pose_pass_is_named_apart_from_the_clip_pass() {
        // Two waits, minutes apart, and the second is the longer one. A coach
        // who saw "Analyzing" twice would reasonably think the app had
        // restarted itself. What the second pass produces is the heatmap, so
        // that is what it is named after.
        cloudAnalysisStatus(AnalyzeStage.MEASURING, progress(pipeline = 0.4f)) shouldBe "Measuring movement 40%…"
        cloudAnalysisStatus(AnalyzeStage.MEASURING, null) shouldBe "Measuring movement…"
    }

    @Test
    fun the_pose_pass_counts_as_running() {
        // Drives the row spinner and, more importantly, the removal guard:
        // deleting the entry mid-run would leave the finished artifact with
        // no entry to land against.
        isAnalysisRunning(AnalyzeStage.MEASURING) shouldBe true
        canRemoveLocalVideo(AnalyzeStage.MEASURING) shouldBe false
    }

    @Test
    fun a_registry_written_before_this_stage_existed_still_decodes() {
        // AnalyzeStage gained a case in the MIDDLE of the enum. kotlinx
        // serializes enums by name, so that is safe - but LocalVideoEntry's
        // own comment warns that a registry which fails to decode empties the
        // library, so "safe" is checked here rather than assumed.
        fun entry(stage: String) = """
            {"id":"e1","uri":"a.mp4","displayName":"a","durationMs":1,
             "sizeBytes":2,"addedAtEpochMs":3,"stage":"$stage"}
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }

        json.decodeFromString<LocalVideoEntry>(entry("PROCESSING")).stage shouldBe AnalyzeStage.PROCESSING
        json.decodeFromString<LocalVideoEntry>(entry("ANALYZED")).stage shouldBe AnalyzeStage.ANALYZED
        // The case after the insertion point: if anything ever moved to
        // ordinals, this is the one that would come back as MEASURING.
        json.decodeFromString<LocalVideoEntry>(entry("FAILED")).stage shouldBe AnalyzeStage.FAILED
    }
}
