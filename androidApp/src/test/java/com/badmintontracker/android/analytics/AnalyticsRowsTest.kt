package com.badmintontracker.android.analytics

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.DeviceRun
import com.badmintontracker.shared.matchlist.DeviceRunKind
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SEP_8 = "2026-09-08T10:00:00Z"

/**
 * The Analytics list's own decisions, which neither [analyticsRowState] nor the
 * screen's layout makes: which of the shared [MatchListItem]s the screen shows,
 * and what a row's control says while one of the two pipelines is busy with it.
 *
 * Rows come from [buildMatchList] here the same way they do in the app
 * ([com.badmintontracker.android.cliplist.ClipListViewModel]), rather than
 * hand-built [MatchListItem]s, so a test cannot assert something the real join
 * could never produce.
 */
class AnalyticsRowsTest {

    private fun entry(
        id: String = "e1",
        scoreLogId: String? = null,
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failureMessage: String? = null,
        addedAt: String = SEP_8,
    ) = LocalVideoEntry(
        id = id,
        uri = "content://$id",
        displayName = "$id.mp4",
        durationMs = 60_000,
        sizeBytes = 1_000_000,
        addedAtEpochMs = Instant.parse(addedAt).toEpochMilliseconds(),
        scoreLogId = scoreLogId,
        stage = stage,
        failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
        failureMessage = failureMessage,
    )

    private fun clip(videoId: String, index: Int = 1, owner: String = ME, at: String = SEP_8) = RallyClip(
        id = "$videoId-$index", videoId = videoId, ownerId = owner, rallyIndex = index,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0, createdAt = Instant.parse(at),
    )

    private fun log(
        id: String,
        videoId: String? = null,
        points: Int = 0,
        at: String = SEP_8,
        homePlayers: List<String> = emptyList(),
        awayPlayers: List<String> = emptyList(),
    ) = ScoreLog(
        id = id, videoId = videoId, title = "Thu League",
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
        events = List(points) { ScoreEvent.PointTo(Side.HOME) },
        status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
        homePlayers = homePlayers, awayPlayers = awayPlayers,
        createdAt = Instant.parse(at), updatedAt = Instant.parse(at),
    )

    /**
     * Mirrors [com.badmintontracker.android.localanalysis.toDeviceRun] by hand,
     * so a test's two device-run inputs - the [MatchList] this builds and the
     * [LocalAnalysisState] map [buildAnalyticsRows] itself reads - describe the
     * same run rather than two that happen to agree by coincidence.
     */
    private fun deviceRunsFrom(live: Map<String, LocalAnalysisState>): Map<String, DeviceRun> =
        live.mapNotNull { (id, state) ->
            val run = when (state) {
                is LocalAnalysisState.Preparing -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.PREPARING)
                is LocalAnalysisState.Analysing -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.ANALYSING)
                is LocalAnalysisState.Cutting -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.CUTTING)
                is LocalAnalysisState.Failed -> DeviceRun(DeviceRunKind.FAILED, message = state.message)
                is LocalAnalysisState.Idle, is LocalAnalysisState.Done -> null
            }
            run?.let { id to it }
        }.toMap()

    private fun matchList(
        clips: List<RallyClip> = emptyList(),
        logs: List<ScoreLog> = emptyList(),
        entries: List<LocalVideoEntry> = emptyList(),
        live: Map<String, LocalAnalysisState> = emptyMap(),
        tracks: Set<String> = emptySet(),
        progress: Map<String, AnalyzeProgress> = emptyMap(),
    ): MatchList = buildMatchList(
        MatchListInput(
            clips = clips,
            currentUserId = ME,
            sharerByVideoId = emptyMap(),
            metadataByVideoId = emptyMap(),
            scoreLogs = logs,
            localEntries = entries,
            cloudProgress = progress,
            deviceRuns = deviceRunsFrom(live),
            storedTrackIds = tracks,
            storedSkeletonIds = emptySet(),
            deviceClipCounts = emptyMap(),
        ),
    )

    private fun rows(
        clips: List<RallyClip> = emptyList(),
        logs: List<ScoreLog> = emptyList(),
        entries: List<LocalVideoEntry> = emptyList(),
        live: Map<String, LocalAnalysisState> = emptyMap(),
        tracks: Set<String> = emptySet(),
        progress: Map<String, AnalyzeProgress> = emptyMap(),
    ) = buildAnalyticsRows(
        matchList = matchList(clips, logs, entries, live, tracks, progress),
        localEntries = entries,
        liveAnalysisStates = live,
        progressByEntryId = progress,
    )

    private val done = LocalAnalysisState.Done(
        rallies = 1, shuttleVisible = 1, totalFrames = 1, clips = emptyList(),
        elapsedSeconds = 1.0, playerTrack = PlayerTrack(emptyList(), 0, emptyMap()), fps = 30.0,
    )

    // --- affordanceFor: the on-device pipeline's four non-idle states ---
    // Unchanged by this move: affordanceFor still takes the raw entry, live
    // state and progress it always did, independent of MatchListItem.

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
    fun a_cloud_run_reports_its_percentage_the_way_the_match_list_does() {
        // The divergence this closes: this screen used to say "Uploading" and
        // "Processing in the cloud" - the chrome indicator's vocabulary, with no
        // number - while the match list's row for the same video, one tap
        // away, said "Uploading 42%…". iOS's port of this screen had already
        // gone the other way, so the two platforms disagreed as well.
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
        val row = rows(logs = listOf(log("log1")), entries = listOf(e)).single()
        row.state shouldBe AnalyticsRowState.ANALYSABLE
        row.entryId shouldBe "e1"
    }

    @Test
    fun a_scored_match_with_a_stored_track_is_ready() {
        val e = entry(id = "e1", scoreLogId = "log1")
        val row = rows(logs = listOf(log("log1")), entries = listOf(e), tracks = setOf("e1")).single()
        row.state shouldBe AnalyticsRowState.READY
    }

    @Test
    fun a_scored_match_whose_video_never_touched_this_phone_stays_inert() {
        // The entry's scoreLogId names a log this input never loaded ("other-log"
        // is not "log1"), so the builder hides the entry (review focus 1) rather
        // than showing it as a standalone row; the score row itself comes out
        // with nothing to point at.
        val row = rows(
            logs = listOf(log("log1")),
            entries = listOf(entry(id = "e1", scoreLogId = "other-log")),
        ).single()
        row.state shouldBe AnalyticsRowState.NOT_ON_DEVICE
        row.entryId shouldBe null
    }

    @Test
    fun a_scored_match_row_reports_its_own_entrys_liveness() {
        val e = entry(id = "e1", scoreLogId = "log1")
        val row = rows(
            logs = listOf(log("log1")), entries = listOf(e),
            live = mapOf("e1" to LocalAnalysisState.Analysing(0.2f)),
        ).single()
        row.affordance shouldBe AnalyseAffordance.InProgress("Analyzing on device")
    }

    @Test
    fun a_scored_matchs_players_line_is_carried_on_its_item_once_someone_is_named() {
        // Subtitle is now item.detail for every row - score, rallies, date - the
        // same text for a named and an unnamed match alike. The old version of
        // this test asserted the subtitle ITSELF switched between the players
        // and the score; that distinction moved to a line of its own
        // (row.item.playersLine, drawn under the subtitle by AnalyticsScreen the
        // same way MatchListRow.kt draws it on the Matches tab) rather than
        // replacing the score/date line, so this now pins the new split
        // instead.
        val unnamed = rows(logs = listOf(log("log1", points = 3))).single()
        unnamed.item.playersLine shouldBe null
        unnamed.subtitle shouldBe unnamed.item.detail

        val named = rows(
            logs = listOf(log("log1", points = 3, homePlayers = listOf("Coen"), awayPlayers = listOf("Marco"))),
        ).single()
        named.item.playersLine shouldBe "Coen vs Marco"
        named.subtitle shouldBe named.item.detail
    }

    @Test
    fun title_and_subtitle_are_the_items_own_text_untouched() {
        // The three-call-site uppercase regression this file used to guard
        // against (matchRowPrimary/matchRowSecondary and this screen's own
        // LOCAL_VIDEOS formatting, all since deleted) cannot recur here any
        // more: this screen no longer formats a row's text itself, title and
        // subtitle are a straight read of MatchListItem.title/.detail as the
        // shared builder wrote them, so there is no per-screen call site left
        // for a transform to hide in.
        val e = entry(id = "e1")
        val out = rows(entries = listOf(e), clips = listOf(clip("v9", owner = OTHER)))
        out.forEach { row ->
            row.title shouldBe row.item.title
            row.subtitle shouldBe row.item.detail
        }
    }

    @Test
    fun a_video_removed_from_this_phone_after_analysis_still_opens() {
        // A track outlives the entry it came from - a device run's own track
        // stays on disk after the file (and its entry) are gone, and the same
        // is true of a cloud run's track once the video has left the phone by
        // some other path. Either way the row must open the heatmap the coach
        // already has - it used to come out inert, with "Not on this phone"
        // under a match that had just been analysed.
        val row = rows(clips = listOf(clip("v1")), tracks = setOf("v1")).single()

        row.state shouldBe AnalyticsRowState.READY_NO_VIDEO
        // Null here is what made the row unopenable even once the state was
        // right: AnalyticsScreen navigates on the id.
        row.entryId shouldBe "v1"
    }

    @Test
    fun a_shared_match_with_a_track_opens_too() {
        // Same story for a match another coach shared: no local entry here
        // either, and the panels read only the stored track.
        // NOTE: synthetic. Nothing fetches a pose artifact except the phone
        // that uploaded, so a shared match cannot actually have a track here
        // yet. This pins the row logic for when one can - it is not evidence
        // the cross-phone case works, and cloudNotes deliberately does not
        // claim it does.
        val row = rows(clips = listOf(clip("v1", owner = OTHER)), tracks = setOf("v1")).single()

        row.state shouldBe AnalyticsRowState.READY_NO_VIDEO
        row.entryId shouldBe "v1"
        row.group shouldBe AnalyticsGroup.SHARED
    }

    @Test
    fun a_match_with_neither_a_video_nor_a_track_stays_inert_and_idless() {
        // The guard on the line above: only a row with something to show earns
        // an id. Two matches that are merely both absent must not collapse into
        // each other in the shared builder's own join, which is what a shared
        // non-null id would do if it were ever the same for two rows.
        val row = rows(clips = listOf(clip("v1"))).single()

        row.state shouldBe AnalyticsRowState.NOT_ON_DEVICE
        row.entryId shouldBe null
    }

    @Test
    fun a_shared_match_is_never_on_this_phone() {
        val row = rows(clips = listOf(clip("v1", owner = OTHER))).single()
        row.group shouldBe AnalyticsGroup.SHARED
        row.state shouldBe AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun mine_rows_come_before_shared_rows() {
        val e = entry(id = "e1")
        val out = rows(entries = listOf(e), clips = listOf(clip("v9", owner = OTHER)))
        out.map { it.group } shouldBe listOf(AnalyticsGroup.MINE, AnalyticsGroup.SHARED)
    }

    @Test
    fun one_video_produces_one_row_even_once_the_cloud_has_clipped_it() {
        // A video-first import that finished uploading is both on this phone
        // and in the cloud. It used to reach this screen as two candidate rows
        // - a standalone local video and an owned match - deduplicated here by
        // a `distinctBy` on entry id; the shared builder now folds the two into
        // a single MatchListItem before buildAnalyticsRows ever runs
        // (MatchListBuilder.videoItem), so there is nothing left here to
        // deduplicate.
        val e = entry(id = "v1", stage = AnalyzeStage.ANALYZED)
        val out = rows(entries = listOf(e), clips = listOf(clip("v1")))
        out.map { it.key } shouldBe listOf("video-v1")
        out.single().group shouldBe AnalyticsGroup.MINE
    }

    @Test
    fun two_matches_that_are_both_off_this_phone_keep_their_own_rows() {
        val out = rows(
            clips = listOf(
                clip("v1", owner = OTHER, at = "2026-09-01T10:00:00Z"),
                clip("v2", owner = OTHER, at = "2026-09-02T10:00:00Z"),
            ),
        )
        // Newest first, same as every other MatchList ordering.
        out.map { it.key } shouldBe listOf("video-v2", "video-v1")
        out.map { it.group } shouldBe listOf(AnalyticsGroup.SHARED, AnalyticsGroup.SHARED)
    }

    @Test
    fun a_ready_row_keeps_its_dot_while_a_second_run_is_in_flight() {
        // Deliberate: a READY row's first track still opens, so the row keeps
        // the affordance that works rather than swapping it for a spinner. The
        // run itself is not lost from the row altogether - it reaches the
        // screen through MatchStatusLine instead, which AnalyticsRowsTest does
        // not exercise (it is a Compose concern, not this function's).
        val e = entry(id = "e1")
        val row = rows(
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
    fun only_the_dot_legend_claims_a_dot_is_on_screen() {
        AnalyticsLegend.DOT.showsDot shouldBe true
        AnalyticsLegend.ANALYSE_BUTTON.showsDot shouldBe false
        AnalyticsLegend.NOTHING_ON_THIS_PHONE.showsDot shouldBe false
        AnalyticsLegend.SILENT.showsDot shouldBe false
    }

    // A trivial, valid MatchListItem for the legend tests below, which only
    // ever read `.state` off a row: hand-writing a 20-odd field data class for
    // each one would pin nothing the shared builder's own tests do not already.
    private val dummyItem: MatchListItem = matchList(logs = listOf(log("dummy"))).mine.single()

    private fun row(state: AnalyticsRowState) = AnalyticsRow(
        key = "k-${state.name}",
        entryId = if (state == AnalyticsRowState.NOT_ON_DEVICE) null else "e-${state.name}",
        group = AnalyticsGroup.MINE,
        title = "t",
        subtitle = "s",
        state = state,
        item = dummyItem,
    )
}
