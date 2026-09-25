package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
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
    ) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
        stage = stage,
        failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
        failureMessage = failureMessage,
        scoreLogId = scoreLogId,
    )

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
    fun the_per_log_derivation_finds_its_own_entry_among_others() {
        // Both the match row and the match page call this with the whole entry
        // list and the whole progress map - it has to pick out the one entry
        // that actually belongs to this log, not just the first one around.
        val status = scoreLogAttachStatus(
            log = log(),
            entries = listOf(entry(id = "other", scoreLogId = "log-other"), entry(id = "vid-1")),
            progress = emptyMap(),
            clipCount = 0,
        )
        status?.kind shouldBe AttachKind.COURT_NOT_MARKED
    }

    @Test
    fun the_per_log_derivation_converts_upload_progress_the_same_way_attachStatus_does() {
        val status = scoreLogAttachStatus(
            log = log(),
            entries = listOf(entry(AnalyzeStage.UPLOADING)),
            progress = mapOf("vid-1" to AnalyzeProgress(entryId = "vid-1", uploadProgress = 0.42f)),
            clipCount = 0,
        )
        status?.text shouldBe "Uploading 42%"
    }

    @Test
    fun the_per_log_derivation_reads_hasVideo_off_the_log_itself() {
        scoreLogAttachStatus(log = log(videoId = "v1"), entries = emptyList(), progress = emptyMap(), clipCount = 3)
            .shouldBeNull()
        scoreLogAttachStatus(log = log(videoId = "v1"), entries = emptyList(), progress = emptyMap(), clipCount = 0)
            ?.kind shouldBe AttachKind.FINISHING_UP
    }

    @Test
    fun entryForLog_prefers_scoreLogId_but_falls_back_to_a_shared_id() {
        // A video-first import later bound to a log by video_id alone, without
        // the entry itself ever being told scoreLogId - the same gap
        // orphanedLocalVideoIds documents as "appears nowhere".
        val bySharedId = entry(id = "vid-1", scoreLogId = null)
        entryForLog(log(videoId = "vid-1"), listOf(bySharedId)) shouldBe bySharedId

        // scoreLogId still wins when both could match, so an entry that does
        // carry the right scoreLogId is never shadowed by a coincidental id.
        val byScoreLogId = entry(id = "other", scoreLogId = "log-1")
        entryForLog(log(videoId = "vid-1"), listOf(bySharedId, byScoreLogId)) shouldBe byScoreLogId

        // No video on the log at all: nothing to fall back to.
        entryForLog(log(videoId = null), listOf(bySharedId)).shouldBeNull()
    }

    @Test
    fun the_per_log_derivation_uses_the_same_shared_id_fallback_as_entryForLog() {
        // scoreLogAttachStatus must not have its own, narrower notion of which
        // entry belongs to a log: the row and the match page read this off the
        // same function, so they can never disagree about which entry it is.
        val status = scoreLogAttachStatus(
            log = log(videoId = "vid-1"),
            entries = listOf(entry(AnalyzeStage.FAILED, "Out of memory", scoreLogId = null)),
            progress = emptyMap(),
            clipCount = 0,
        )
        status?.kind shouldBe AttachKind.FAILED
        status?.text shouldBe "Out of memory"
    }
}
