package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class AttachStatusTest {

    private fun entry(
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failureMessage: String? = null,
        id: String = "vid-1",
        scoreLogId: String? = "log-1",
        keypoints: CourtKeypoints? = null,
    ) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
        stage = stage,
        failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
        failureMessage = failureMessage,
        scoreLogId = scoreLogId,
        keypoints = keypoints,
    )

    private val court = List(12) { listOf(0f, 0f) }.let {
        CourtKeypoints(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], it[10], it[11])
    }

    private fun log(videoId: String? = null) = ScoreLog(
        id = "log-1",
        videoId = videoId,
        title = "Thu League",
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(firstServer = Side.HOME),
        events = emptyList(),
        status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
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
    fun a_picked_video_whose_court_is_marked_is_not_asked_for_the_court() {
        // A device run never moves the stage off LOCAL, so LOCAL alone does not
        // mean unmarked: the saved court points do. What the marked video is
        // doing next is the device run's to say, not the attach status's.
        attachStatus(hasVideo = false, entry = entry(keypoints = court), uploadPercent = null, clipCount = 0).shouldBeNull()
    }

    @Test
    fun a_marked_local_entry_on_a_bound_match_is_not_finishing_up() {
        // Nothing is uploading for a LOCAL entry, so the no-clips-yet gap the
        // FINISHING_UP spinner stands for is not this one.
        attachStatus(hasVideo = true, entry = entry(keypoints = court), uploadPercent = null, clipCount = 0).shouldBeNull()
    }

    @Test
    fun saved_court_points_do_not_change_what_a_cloud_stage_says() {
        attachStatus(false, entry(AnalyzeStage.UPLOADING, keypoints = court), uploadPercent = 42, clipCount = 0)
            ?.kind shouldBe AttachKind.UPLOADING
        attachStatus(true, entry(AnalyzeStage.PROCESSING, keypoints = court), null, clipCount = 0)
            ?.kind shouldBe AttachKind.CLIPPING
        attachStatus(true, entry(AnalyzeStage.FAILED, "Upload failed", keypoints = court), null, clipCount = 0)
            ?.kind shouldBe AttachKind.FAILED
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

    @Test
    fun attachStatusFor_converts_upload_progress_to_a_percentage() {
        val status = attachStatusFor(
            log = log(),
            entry = entry(AnalyzeStage.UPLOADING),
            progress = mapOf("vid-1" to AnalyzeProgress(entryId = "vid-1", uploadProgress = 0.42f)),
            clipCount = 0,
        )
        status?.text shouldBe "Uploading 42%"
    }

    @Test
    fun attachStatusFor_reads_hasVideo_off_the_log_itself() {
        attachStatusFor(log = log(videoId = "v1"), entry = null, progress = emptyMap(), clipCount = 3)
            .shouldBeNull()
        attachStatusFor(log = log(videoId = "v1"), entry = null, progress = emptyMap(), clipCount = 0)
            ?.kind shouldBe AttachKind.FINISHING_UP
    }
}
