package com.badmintontracker.android.analytics

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.cliplist.MatchRow
import com.badmintontracker.android.cliplist.MatchSummary
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.ScoreMatchCard
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * The Analytics list's own decisions, which neither [analyticsRowState] nor the
 * screen's layout makes: which local video belongs to which match, and what a
 * row's control says while one of the two pipelines is busy with it.
 */
class AnalyticsRowsTest {

    private fun entry(
        id: String = "e1",
        scoreLogId: String? = null,
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failureMessage: String? = null,
    ) = LocalVideoEntry(
        id = id,
        uri = "content://$id",
        displayName = "$id.mp4",
        durationMs = 60_000,
        sizeBytes = 1_000_000,
        addedAtEpochMs = 1_000,
        scoreLogId = scoreLogId,
        stage = stage,
        failureMessage = failureMessage,
    )

    private fun localRow(e: LocalVideoEntry) = LocalVideoRow(
        entry = e,
        primaryText = "Court 3",
        statusText = null,
        durationText = "1:00",
        canAnalyze = true,
        analyzeLabel = "Analyze",
        canRemove = true,
        canEditDetails = true,
    )

    private fun scoreCard(scoreLogId: String = "log1", videoId: String? = null) = ScoreMatchCard(
        scoreLogId = scoreLogId,
        videoId = videoId,
        title = "Thu League",
        createdAtEpochMs = 2_000,
        playersLine = "Coen vs Marco",
        scoreLine = "11-9",
        statusLine = "Finished",
        isLive = false,
        hasVideo = videoId != null,
    )

    private fun videoMatch(videoId: String) = MatchSummary(
        videoId = videoId,
        rallyCount = 3,
        latestCreatedAt = Instant.fromEpochMilliseconds(3_000),
        coverClip = RallyClip(
            id = "c-$videoId", videoId = videoId, ownerId = "user-self", rallyIndex = 1,
            startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
            clipStoragePath = "p/c-$videoId.mp4", thumbnailStoragePath = null,
            title = null, annotationCount = 0,
            createdAt = Instant.fromEpochMilliseconds(3_000),
        ),
        isOwned = true,
    )

    private val done = LocalAnalysisState.Done(
        rallies = 1, shuttleVisible = 1, totalFrames = 1, clips = emptyList(),
        elapsedSeconds = 1.0, playerTrack = PlayerTrack(emptyList(), 0, emptyMap()), fps = 30.0,
    )

    private fun rows(
        standalone: List<LocalVideoRow> = emptyList(),
        owned: List<MatchRow> = emptyList(),
        shared: List<MatchSummary> = emptyList(),
        entries: List<LocalVideoEntry> = emptyList(),
        live: Map<String, LocalAnalysisState> = emptyMap(),
        tracks: Set<String> = emptySet(),
    ) = buildAnalyticsRows(
        standaloneLocalRows = standalone,
        ownedRows = owned,
        sharedMatches = shared,
        localEntries = entries,
        liveAnalysisStates = live,
        storedTrackIds = tracks,
    )

    // --- affordanceFor: the on-device pipeline's four non-idle states ---

    @Test
    fun a_device_run_preparing_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Preparing("Copying video")) shouldBe
            AnalyseAffordance.InProgress("Preparing video")
    }

    @Test
    fun a_device_run_analysing_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Analysing(0.4f)) shouldBe
            AnalyseAffordance.InProgress("Analysing on device")
    }

    @Test
    fun a_device_run_cutting_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Cutting(done = 2, total = 5)) shouldBe
            AnalyseAffordance.InProgress("Cutting clips")
    }

    @Test
    fun a_failed_device_run_carries_its_own_message() {
        affordanceFor(entry(), LocalAnalysisState.Failed("no court found")) shouldBe
            AnalyseAffordance.Failed("no court found")
    }

    // --- affordanceFor: the device run wins over the cloud one ---

    @Test
    fun a_live_device_run_speaks_over_a_live_cloud_upload() {
        // Both pipelines busy at once. The row's own button starts the device
        // run, so that is the run its control has to speak for.
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Analysing(0.1f)) shouldBe
            AnalyseAffordance.InProgress("Analysing on device")
    }

    @Test
    fun a_failed_device_run_speaks_over_a_live_cloud_upload() {
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Failed("out of memory")) shouldBe
            AnalyseAffordance.Failed("out of memory")
    }

    // --- affordanceFor: Idle and Done both fall through to the cloud ---

    @Test
    fun an_idle_device_shows_a_cloud_upload() {
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Idle) shouldBe
            AnalyseAffordance.InProgress("Uploading")
    }

    @Test
    fun an_idle_device_shows_cloud_processing() {
        affordanceFor(entry(stage = AnalyzeStage.PROCESSING), LocalAnalysisState.Idle) shouldBe
            AnalyseAffordance.InProgress("Processing in the cloud")
    }

    @Test
    fun an_idle_device_shows_a_cloud_failure_with_its_reason() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED, failureMessage = "upload rejected"), LocalAnalysisState.Idle) shouldBe
            AnalyseAffordance.Failed("upload rejected")
    }

    @Test
    fun a_cloud_failure_with_no_message_still_says_something() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED), LocalAnalysisState.Idle) shouldBe
            AnalyseAffordance.Failed("Unknown error")
    }

    @Test
    fun a_settled_cloud_stage_with_an_idle_device_offers_analyse() {
        affordanceFor(entry(stage = AnalyzeStage.LOCAL), LocalAnalysisState.Idle) shouldBe
            AnalyseAffordance.Ready
    }

    @Test
    fun a_done_device_run_still_reports_a_live_cloud_upload() {
        // Done is not "nothing to say": a clips-only device run leaves no track,
        // so the row stays ANALYSABLE while the cloud is still working on it.
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), done) shouldBe
            AnalyseAffordance.InProgress("Uploading")
    }

    @Test
    fun a_done_device_run_still_reports_a_cloud_failure() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED, failureMessage = "quota"), done) shouldBe
            AnalyseAffordance.Failed("quota")
    }

    @Test
    fun a_done_device_run_that_saved_no_track_offers_analyse_again() {
        affordanceFor(entry(stage = AnalyzeStage.LOCAL), done) shouldBe AnalyseAffordance.Ready
    }

    // --- buildAnalyticsRows ---

    @Test
    fun a_scored_match_whose_video_never_touched_this_phone_stays_inert() {
        val row = rows(
            owned = listOf(MatchRow.Score(card = scoreCard(scoreLogId = "log1"))),
            entries = listOf(entry(id = "e1", scoreLogId = "other-log")),
        ).single()
        row.state shouldBe AnalyticsRowState.NOT_ON_DEVICE
        row.entryId shouldBe null
    }

    @Test
    fun a_shared_match_is_never_on_this_phone() {
        val row = rows(shared = listOf(videoMatch("v1"))).single()
        row.group shouldBe AnalyticsGroup.SHARED
        row.state shouldBe AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun the_three_groups_come_out_in_display_order() {
        val e = entry(id = "e1")
        val out = rows(
            standalone = listOf(localRow(e)),
            owned = listOf(MatchRow.Video(videoMatch("v1"))),
            shared = listOf(videoMatch("v2")),
            entries = listOf(e),
        )
        out.map { it.group } shouldBe listOf(
            AnalyticsGroup.LOCAL_VIDEOS, AnalyticsGroup.OWNED_MATCHES, AnalyticsGroup.SHARED,
        )
    }

    @Test
    fun a_ready_row_keeps_its_dot_while_a_second_run_is_in_flight() {
        // Deliberate: a READY row's first track still opens, so the row keeps
        // the affordance that works rather than swapping it for a spinner.
        val e = entry(id = "e1")
        val row = rows(
            standalone = listOf(localRow(e)),
            entries = listOf(e),
            live = mapOf("e1" to LocalAnalysisState.Analysing(0.5f)),
            tracks = setOf("e1"),
        ).single()
        row.state shouldBe AnalyticsRowState.READY
        row.affordance shouldBe AnalyseAffordance.Ready
    }
}
