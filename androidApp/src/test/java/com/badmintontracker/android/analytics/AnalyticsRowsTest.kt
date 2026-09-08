package com.badmintontracker.android.analytics

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.cliplist.MatchRow
import com.badmintontracker.android.cliplist.MatchSummary
import com.badmintontracker.android.cliplist.matchRowSecondary
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.localvideo.AnalyzeProgress
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
        isBusy = false,
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
        progress: Map<String, AnalyzeProgress> = emptyMap(),
        tracks: Set<String> = emptySet(),
    ) = buildAnalyticsRows(
        standaloneLocalRows = standalone,
        ownedRows = owned,
        sharedMatches = shared,
        localEntries = entries,
        liveAnalysisStates = live,
        progressByEntryId = progress,
        storedTrackIds = tracks,
    )

    // --- affordanceFor: the on-device pipeline's four non-idle states ---

    @Test
    fun a_device_run_preparing_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Preparing("Copying video"), null) shouldBe
            AnalyseAffordance.InProgress("Preparing video")
    }

    @Test
    fun a_device_run_analysing_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Analysing(0.4f), null) shouldBe
            AnalyseAffordance.InProgress("Analyzing on device")
    }

    @Test
    fun a_device_run_cutting_says_so() {
        affordanceFor(entry(), LocalAnalysisState.Cutting(done = 2, total = 5), null) shouldBe
            AnalyseAffordance.InProgress("Cutting clips")
    }

    @Test
    fun a_failed_device_run_carries_its_own_message() {
        affordanceFor(entry(), LocalAnalysisState.Failed("no court found"), null) shouldBe
            AnalyseAffordance.Failed("no court found")
    }

    // --- affordanceFor: the device run wins over the cloud one ---

    @Test
    fun a_live_device_run_speaks_over_a_live_cloud_upload() {
        // Both pipelines busy at once. The row's own button starts the device
        // run, so that is the run its control has to speak for.
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Analysing(0.1f), null) shouldBe
            AnalyseAffordance.InProgress("Analyzing on device")
    }

    @Test
    fun a_failed_device_run_speaks_over_a_live_cloud_upload() {
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Failed("out of memory"), null) shouldBe
            AnalyseAffordance.Failed("out of memory")
    }

    // --- affordanceFor: Idle and Done both fall through to the cloud ---

    @Test
    fun an_idle_device_shows_a_cloud_upload() {
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), LocalAnalysisState.Idle, null) shouldBe
            AnalyseAffordance.InProgress("Uploading…")
    }

    @Test
    fun an_idle_device_shows_cloud_processing() {
        affordanceFor(entry(stage = AnalyzeStage.PROCESSING), LocalAnalysisState.Idle, null) shouldBe
            AnalyseAffordance.InProgress("Analyzing…")
    }

    @Test
    fun a_cloud_run_reports_its_percentage_the_way_the_drawer_does() {
        // The divergence this closes: this screen used to say "Uploading" and
        // "Processing in the cloud" - the chrome indicator's vocabulary, with no
        // number - while the drawer's row for the same video, one tap away, said
        // "Uploading 42%…". iOS's port of this screen had already gone the other
        // way, so the two platforms disagreed as well.
        affordanceFor(
            entry(stage = AnalyzeStage.UPLOADING),
            LocalAnalysisState.Idle,
            AnalyzeProgress(entryId = "e1", uploadProgress = 0.42f),
        ) shouldBe AnalyseAffordance.InProgress("Uploading 42%…")
        affordanceFor(
            entry(stage = AnalyzeStage.PROCESSING),
            LocalAnalysisState.Idle,
            AnalyzeProgress(entryId = "e1", pipelineProgress = 0.8f),
        ) shouldBe AnalyseAffordance.InProgress("Analyzing 80%…")
    }

    @Test
    fun a_cloud_runs_progress_reaches_the_row_it_belongs_to() {
        val e = entry(id = "e1", stage = AnalyzeStage.UPLOADING)
        val row = rows(
            standalone = listOf(localRow(e)),
            entries = listOf(e),
            progress = mapOf("e1" to AnalyzeProgress(entryId = "e1", uploadProgress = 0.25f)),
        ).single()
        row.affordance shouldBe AnalyseAffordance.InProgress("Uploading 25%…")
    }

    @Test
    fun an_idle_device_shows_a_cloud_failure_with_its_reason() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED, failureMessage = "upload rejected"), LocalAnalysisState.Idle, null) shouldBe
            AnalyseAffordance.Failed("upload rejected")
    }

    @Test
    fun a_cloud_failure_with_no_message_still_says_something() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED), LocalAnalysisState.Idle, null) shouldBe
            AnalyseAffordance.Failed("Unknown error")
    }

    @Test
    fun a_settled_cloud_stage_with_an_idle_device_offers_analyse() {
        affordanceFor(entry(stage = AnalyzeStage.LOCAL), LocalAnalysisState.Idle, null) shouldBe
            AnalyseAffordance.Ready
    }

    @Test
    fun a_done_device_run_still_reports_a_live_cloud_upload() {
        // Done is not "nothing to say": a clips-only device run leaves no track,
        // so the row stays ANALYSABLE while the cloud is still working on it.
        affordanceFor(entry(stage = AnalyzeStage.UPLOADING), done, null) shouldBe
            AnalyseAffordance.InProgress("Uploading…")
    }

    @Test
    fun a_done_device_run_still_reports_a_cloud_failure() {
        affordanceFor(entry(stage = AnalyzeStage.FAILED, failureMessage = "quota"), done, null) shouldBe
            AnalyseAffordance.Failed("quota")
    }

    @Test
    fun a_done_device_run_that_saved_no_track_offers_analyse_again() {
        affordanceFor(entry(stage = AnalyzeStage.LOCAL), done, null) shouldBe AnalyseAffordance.Ready
    }

    // --- buildAnalyticsRows ---

    @Test
    fun a_scored_match_finds_its_video_through_the_entry_that_claims_the_log() {
        // The defect this guards: `card.videoId` is written only when the CLOUD
        // pipeline creates the videos row, so a match scored courtside and
        // filmed on this phone has none. Reading it would call a video sitting
        // on the phone "Not on this phone", forever, unless the coach uploads.
        val e = entry(id = "e1", scoreLogId = "log1")
        val row = rows(
            owned = listOf(MatchRow.Score(card = scoreCard(scoreLogId = "log1", videoId = null))),
            entries = listOf(e),
        ).single()
        row.state shouldBe AnalyticsRowState.ANALYSABLE
        row.entryId shouldBe "e1"
    }

    @Test
    fun a_scored_match_with_a_stored_track_is_ready() {
        val e = entry(id = "e1", scoreLogId = "log1")
        val row = rows(
            owned = listOf(MatchRow.Score(card = scoreCard(scoreLogId = "log1"))),
            entries = listOf(e),
            tracks = setOf("e1"),
        ).single()
        row.state shouldBe AnalyticsRowState.READY
    }

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
    fun a_scored_match_row_reports_its_own_entrys_liveness() {
        val e = entry(id = "e1", scoreLogId = "log1")
        val row = rows(
            owned = listOf(MatchRow.Score(card = scoreCard(scoreLogId = "log1"))),
            entries = listOf(e),
            live = mapOf("e1" to LocalAnalysisState.Analysing(0.2f)),
        ).single()
        row.affordance shouldBe AnalyseAffordance.InProgress("Analyzing on device")
    }

    @Test
    fun every_row_describes_itself_in_sentence_case() {
        // This has flipped twice. The screen once uppercased its match rows and
        // not its local video rows, so one list drew "1:00 · Jan 1, 1970" over
        // "3 RALLIES · JAN 1, 1970"; the fix at the time uppercased both. The
        // mock settles it the other way - "On this phone", "1:05 · Sep 2" - and
        // the redesign carries no uppercase anywhere, so a transform reappearing
        // at any of these three call sites is a regression rather than a choice.
        val e = entry(id = "e1")
        val out = rows(
            standalone = listOf(localRow(e)),
            entries = listOf(e),
            owned = listOf(MatchRow.Video(match = videoMatch("v1"))),
            shared = listOf(videoMatch("v2")),
        )
        out.map { it.group } shouldBe listOf(
            AnalyticsGroup.LOCAL_VIDEOS, AnalyticsGroup.OWNED_MATCHES, AnalyticsGroup.SHARED,
        )
        // Each subtitle still holds lower case somewhere, which an uppercasing
        // call site would strip. "3 rallies · Jan 1, 1970" passes; the same
        // string uppercased does not.
        out.forEach { row -> row.subtitle.any { it.isLowerCase() } shouldBe true }
        // Literals, deliberately, and NOT a comparison against the formatters
        // themselves: `subtitle shouldBe matchRowSecondary(match)` re-derives the
        // expectation from the code under test, so re-adding `.uppercase()` at
        // the call site would change both sides and the test would still pass.
        out.map { it.subtitle } shouldBe listOf(
            "1:00 · Jan 1, 1970",
            "3 rallies",
            "3 rallies",
        )
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
    fun one_video_produces_one_row_even_once_the_cloud_has_clipped_it() {
        // A video-first import that finished uploading is both a local video and
        // an owned match, and its two rows carry the same entry id, the same
        // state and the same "Analyze" button. Two identical buttons on one
        // video is a list bug, not a second thing the coach can do.
        val e = entry(id = "v1")
        val out = rows(
            standalone = listOf(localRow(e)),
            owned = listOf(MatchRow.Video(videoMatch("v1"))),
            entries = listOf(e),
        )
        out.map { it.key } shouldBe listOf("local-v1")
        out.single().group shouldBe AnalyticsGroup.LOCAL_VIDEOS
    }

    @Test
    fun two_matches_that_are_both_off_this_phone_keep_their_own_rows() {
        // The dedupe collapses on entry id, and every NOT_ON_DEVICE row has a
        // null one. Collapsing those together would hide every cloud match but
        // the first.
        val out = rows(shared = listOf(videoMatch("v1"), videoMatch("v2")))
        out.map { it.key } shouldBe listOf("shared-v1", "shared-v2")
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
    @Test
    fun a_list_with_a_live_button_and_no_dot_explains_the_button() {
        // The defect this decision exists for. ANALYSABLE fails the all-inert
        // test without contributing a dot, so the old two-way switch put
        // "Analyzed on this phone" and a green dot over a list whose only row
        // said "Analyze" and drew no dot at all.
        analyticsLegend(listOf(row(AnalyticsRowState.ANALYSABLE))) shouldBe
            AnalyticsLegend.ANALYSE_BUTTON
    }

    @Test
    fun a_dot_on_screen_outranks_a_button_on_screen() {
        // A dot is a symbol and needs explaining; a button labelled "Analyze"
        // already says what it does.
        analyticsLegend(
            listOf(row(AnalyticsRowState.ANALYSABLE), row(AnalyticsRowState.READY)),
        ) shouldBe AnalyticsLegend.DOT
    }

    @Test
    fun every_row_inert_gets_the_one_line_that_covers_them_all() {
        analyticsLegend(
            listOf(row(AnalyticsRowState.NOT_ON_DEVICE), row(AnalyticsRowState.NOT_ON_DEVICE)),
        ) shouldBe AnalyticsLegend.NOTHING_ON_THIS_PHONE
    }

    @Test
    fun an_empty_list_explains_nothing() {
        // Not NOTHING_ON_THIS_PHONE: "none of these matches" needs some matches.
        analyticsLegend(emptyList()) shouldBe AnalyticsLegend.SILENT
    }

    @Test
    fun the_legend_and_the_rows_never_both_explain_the_same_thing() {
        // The one line above the list says "none of these are on this phone",
        // so repeating it on every row would say it as many times as there are
        // rows. Every other legend leaves the rows to speak for themselves.
        AnalyticsLegend.NOTHING_ON_THIS_PHONE.showsNotOnDeviceSubtitle shouldBe false
        AnalyticsLegend.DOT.showsNotOnDeviceSubtitle shouldBe true
        AnalyticsLegend.ANALYSE_BUTTON.showsNotOnDeviceSubtitle shouldBe true
        AnalyticsLegend.SILENT.showsNotOnDeviceSubtitle shouldBe true
    }

    @Test
    fun only_the_dot_legend_claims_a_dot_is_on_screen() {
        AnalyticsLegend.DOT.showsDot shouldBe true
        AnalyticsLegend.ANALYSE_BUTTON.showsDot shouldBe false
        AnalyticsLegend.NOTHING_ON_THIS_PHONE.showsDot shouldBe false
        AnalyticsLegend.SILENT.showsDot shouldBe false
    }

    private fun row(state: AnalyticsRowState) = AnalyticsRow(
        key = "k-${state.name}",
        entryId = if (state == AnalyticsRowState.NOT_ON_DEVICE) null else "e-${state.name}",
        group = AnalyticsGroup.LOCAL_VIDEOS,
        title = "t",
        subtitle = "s",
        state = state,
    )

}
