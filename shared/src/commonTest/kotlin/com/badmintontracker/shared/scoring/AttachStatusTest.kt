package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AttachStatusTest {

    private fun entry(
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failureMessage: String? = null,
    ) = LocalVideoEntry(
        id = "vid-1", uri = "content://x/vid-1", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
        stage = stage,
        failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
        failureMessage = failureMessage,
        scoreLogId = "log-1",
    )

    @Test
    fun a_match_with_no_video_at_all_has_nothing_to_say() {
        attachStatus(hasVideo = false, entry = null, uploadPercent = null, clipCount = 0).shouldBeNull()
    }

    @Test
    fun a_picked_video_whose_court_is_unmarked_asks_for_the_court() {
        val status = attachStatus(hasVideo = false, entry = entry(), uploadPercent = null, clipCount = 0)
        status?.kind shouldBe AttachKind.COURT_NOT_MARKED
        status?.text shouldBe "Video added, court not marked"
    }

    @Test
    fun an_upload_reports_its_percentage_when_it_has_one() {
        attachStatus(false, entry(AnalyzeStage.UPLOADING), uploadPercent = 42, clipCount = 0)
            ?.text shouldBe "Uploading 42%"
    }

    @Test
    fun an_upload_with_no_percentage_yet_still_says_it_is_uploading() {
        attachStatus(false, entry(AnalyzeStage.UPLOADING), uploadPercent = null, clipCount = 0)
            ?.text shouldBe "Uploading…"
    }

    @Test
    fun the_pipeline_running_is_called_clipping_rather_than_analyzing() {
        // The coach asked for the clips, not for an analysis. The word on the match
        // row is the word he used.
        val status = attachStatus(true, entry(AnalyzeStage.PROCESSING), null, clipCount = 0)
        status?.kind shouldBe AttachKind.CLIPPING
        status?.text shouldBe "Clipping…"
    }

    @Test
    fun a_failure_shows_the_pipelines_own_message() {
        val status = attachStatus(
            true, entry(AnalyzeStage.FAILED, "Analysis finished but found no rallies in this video."),
            null, clipCount = 0,
        )
        status?.kind shouldBe AttachKind.FAILED
        status?.text shouldBe "Analysis finished but found no rallies in this video."
    }

    @Test
    fun a_failure_with_no_message_still_says_something() {
        attachStatus(true, entry(AnalyzeStage.FAILED), null, 0)?.text shouldBe "Analysis failed"
    }

    @Test
    fun a_bound_match_whose_clips_have_landed_says_nothing_at_all() {
        // The rally facet is the answer at that point; a status line as well would
        // be noise on every finished match forever.
        attachStatus(hasVideo = true, entry = null, uploadPercent = null, clipCount = 12).shouldBeNull()
    }

    @Test
    fun a_bound_match_with_no_entry_and_no_clips_yet_is_finishing_up() {
        // The real gap between the pipeline succeeding, which removes the entry and
        // drops its progress, and clips.refresh() bringing the rows back. Two absent
        // signals, two different meanings, and this is the one that needs saying.
        val status = attachStatus(hasVideo = true, entry = null, uploadPercent = null, clipCount = 0)
        status?.kind shouldBe AttachKind.FINISHING_UP
        status?.text shouldBe "Finishing up…"
    }

    @Test
    fun an_analyzed_entry_kept_for_its_notes_is_not_a_pipeline_state() {
        // ANALYZED entries are the ones the pipeline kept because they carry local
        // annotations. Nothing is in flight.
        attachStatus(true, entry(AnalyzeStage.ANALYZED), null, clipCount = 3).shouldBeNull()
    }

    @Test
    fun an_analyzed_entry_whose_clips_have_not_synced_yet_is_finishing_up() {
        // The pipeline succeeding and clips.refresh() bringing the rows back are
        // two different moments; ANALYZED with no clips on screen yet is the same
        // gap the no-entry FINISHING_UP case exists for, not a silent match.
        val status = attachStatus(true, entry(AnalyzeStage.ANALYZED), null, clipCount = 0)
        status?.kind shouldBe AttachKind.FINISHING_UP
        status?.text shouldBe "Finishing up…"
    }
}
