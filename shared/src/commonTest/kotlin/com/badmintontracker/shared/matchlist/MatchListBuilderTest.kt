package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SEP_8 = "2026-09-08T10:00:00Z"

private fun entry(
    id: String,
    stage: AnalyzeStage = AnalyzeStage.LOCAL,
    scoreLogId: String? = null,
    title: String? = null,
    addedAt: String = SEP_8,
    failureMessage: String? = null,
) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(addedAt).toEpochMilliseconds(),
    title = title, stage = stage,
    failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
    failureMessage = failureMessage, scoreLogId = scoreLogId,
)

private fun clip(videoId: String, index: Int, owner: String = ME, at: String = SEP_8, title: String? = null) = RallyClip(
    id = "$videoId-$index", videoId = videoId, ownerId = owner, rallyIndex = index,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = "thumbs/$videoId/$index.jpg",
    title = title, annotationCount = 0, createdAt = Instant.parse(at),
)

private fun log(id: String, videoId: String? = null, points: Int = 0, at: String = SEP_8) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = List(points) { ScoreEvent.PointTo(Side.HOME) },
    status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(at), updatedAt = Instant.parse(at),
)

private fun input(
    clips: List<RallyClip> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    deviceRuns: Map<String, DeviceRun> = emptyMap(),
    tracks: Set<String> = emptySet(),
    skeletons: Set<String> = emptySet(),
    deviceClips: Map<String, Int> = emptyMap(),
    metadata: Map<String, MatchMetadata> = emptyMap(),
    sharers: Map<String, String> = emptyMap(),
) = MatchListInput(
    clips = clips, currentUserId = ME, sharerByVideoId = sharers, metadataByVideoId = metadata,
    scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = deviceRuns,
    storedTrackIds = tracks, storedSkeletonIds = skeletons, deviceClipCounts = deviceClips,
)

private fun build(i: MatchListInput) = buildMatchList(i, TimeZone.UTC)

class MatchListBuilderTest {

    // --- the join: one row per match ---

    @Test
    fun a_video_analysed_in_the_cloud_is_one_row_on_the_phone_and_in_the_cloud() {
        // The duplicate this design exists to remove: the entry keeps its id as
        // the cloud video's id and stays ANALYZED after the run.
        val list = build(input(
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED)),
            clips = listOf(clip("v1", 1), clip("v1", 2)),
        ))
        list.mine.size shouldBe 1
        val item = list.mine.single()
        item.key shouldBe "video-v1"
        item.where shouldBe MatchWhere.BOTH
        item.entryId shouldBe "v1"
        item.videoId shouldBe "v1"
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES)
        item.opensLocalPlayer shouldBe false
    }

    @Test
    fun a_local_video_nobody_has_analysed_asks_to_be_analysed() {
        val item = build(input(entries = listOf(entry("v1")))).mine.single()
        item.where shouldBe MatchWhere.PHONE
        item.analyses.shouldBeEmpty()
        item.action shouldBe MatchAction.ANALYZE
        item.statusText shouldBe MatchListCopy.NOT_ANALYZED
        item.opensLocalPlayer shouldBe true
        item.detail shouldBe "0:48 · Sep 8, 2026"
    }

    @Test
    fun a_cloud_match_from_another_phone_is_in_the_cloud_only() {
        val item = build(input(clips = listOf(clip("v9", 1), clip("v9", 2), clip("v9", 3)))).mine.single()
        item.where shouldBe MatchWhere.CLOUD
        item.entryId.shouldBeNull()
        item.detail shouldBe "3 rallies · Sep 8, 2026"
        item.canRemoveFromPhone shouldBe false
        item.delete shouldBe MatchDelete.VIDEO_MATCH
    }

    @Test
    fun a_scored_match_with_no_video_has_no_where_and_shows_its_score() {
        val item = build(input(logs = listOf(log("l1", points = 3)))).mine.single()
        item.key shouldBe "score-l1"
        item.where.shouldBeNull()
        item.analyses shouldContainExactly listOf(MatchAnalysis.SCORE)
        item.scoreText shouldBe "3-0"
        item.delete shouldBe MatchDelete.SCORE_MATCH
        item.canShare shouldBe false
    }

    @Test
    fun a_scored_match_nobody_has_started_draws_no_score_glyph() {
        val item = build(input(logs = listOf(log("l1")))).mine.single()
        item.analyses.shouldBeEmpty()
        item.scoreText.shouldBeNull()
    }

    @Test
    fun a_scored_match_with_its_video_clipped_folds_the_video_in() {
        val item = build(input(
            logs = listOf(log("l1", videoId = "v1", points = 2)),
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED, scoreLogId = "l1")),
            clips = listOf(clip("v1", 1)),
        )).mine.single()
        item.key shouldBe "score-l1"
        item.where shouldBe MatchWhere.BOTH
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.SCORE)
        item.delete shouldBe MatchDelete.BOUND_MATCH
    }

    @Test
    fun an_attached_video_whose_match_is_not_loaded_yet_is_not_a_row_of_its_own() {
        // Review focus 1. The score-log cache starts empty on a cold start; an
        // entry pointing at a log not in the list is that window, not an orphan.
        build(input(entries = listOf(entry("v1", scoreLogId = "l-missing")))).mine.shouldBeEmpty()
    }

    @Test
    fun an_entry_naming_a_missing_log_also_claims_its_own_clips() {
        // The same cold-start window as the test above, but the video already has
        // clips: without claiming entry.id too, those clips would surface as an
        // orphaned CLOUD row that knows nothing about the entry sitting on the
        // phone, then jump to a different key entirely once the log loads.
        val list = build(input(
            entries = listOf(entry("v1", scoreLogId = "l-missing")),
            clips = listOf(clip("v1", 1)),
        ))
        list.mine.shouldBeEmpty()
        list.shared.shouldBeEmpty()
    }

    @Test
    fun a_score_logs_entry_is_found_by_shared_id_when_its_own_scoreLogId_is_unset() {
        // A video-first import later bound to a log by video_id, without the
        // entry itself ever being told scoreLogId. Falling back to the shared id
        // is what keeps this from being the "appears nowhere" class
        // orphanedLocalVideoIds documents: the log claims v1 by videoId, and the
        // entry must be found by the same id rather than left stranded.
        val list = build(input(
            logs = listOf(log("l1", videoId = "v1")),
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED, scoreLogId = null)),
            clips = listOf(clip("v1", 1)),
        ))
        val item = list.mine.single()
        item.key shouldBe "score-l1"
        item.entryId shouldBe "v1"
        item.where shouldBe MatchWhere.BOTH
    }

    @Test
    fun the_shared_id_fallback_does_not_borrow_an_entry_another_log_already_owns() {
        // Refinement of the shared-id fallback above: the entry belongs to l2 by
        // scoreLogId, so l1 (which merely happens to share its video id) must not
        // also pick it up - or entryId "v1" would sit on two rows at once, which
        // is exactly what review focus 2's invariant exists to rule out, across
        // every log in the input rather than just within one.
        val list = build(input(
            logs = listOf(log("l1", videoId = "v1"), log("l2")),
            entries = listOf(entry("v1", scoreLogId = "l2")),
        ))
        val ids = list.mine.mapNotNull { it.entryId }
        ids.size shouldBe ids.toSet().size
        list.mine.first { it.key == "score-l2" }.entryId shouldBe "v1"
        list.mine.first { it.key == "score-l1" }.entryId.shouldBeNull()
    }

    @Test
    fun no_id_is_ever_listed_twice() {
        // Review focus 2, as an invariant over every kind of row at once.
        val list = build(input(
            entries = listOf(
                entry("a", AnalyzeStage.ANALYZED), entry("b"), entry("c", scoreLogId = "l1"),
                entry("d", AnalyzeStage.ANALYZED, scoreLogId = "l2"),
            ),
            logs = listOf(log("l1"), log("l2", videoId = "d"), log("l3")),
            clips = listOf(clip("a", 1), clip("d", 1), clip("e", 1), clip("s", 1, owner = OTHER)),
            sharers = mapOf("s" to "coach@club.es"),
        ))
        val all = list.mine + list.shared
        val ids = all.flatMap { listOfNotNull(it.entryId, it.videoId, it.scoreLogId).distinct() }
        ids.size shouldBe ids.toSet().size
        all.map { it.key }.toSet().size shouldBe all.size
        list.shared.map { it.videoId } shouldContainExactly listOf("s")
    }

    // --- what it has ---

    @Test
    fun a_device_run_shows_rallies_heatmap_and_skeleton_and_offers_to_analyze_again() {
        val item = build(input(
            entries = listOf(entry("v1")),
            tracks = setOf("v1"), skeletons = setOf("v1"), deviceClips = mapOf("v1" to 7),
        )).mine.single()
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP, MatchAnalysis.SKELETON)
        item.detail shouldBe "7 rallies · Sep 8, 2026"
        item.action shouldBe MatchAction.NONE
        item.statusText.shouldBeNull()
        item.canAnalyzeAgain shouldBe true
        item.deviceClipCount shouldBe 7
        item.heatmapId shouldBe "v1"
    }

    @Test
    fun the_skeleton_is_not_offered_once_the_video_has_left_the_phone() {
        // Same rule as AnalyticsRowState.READY_NO_VIDEO: it is drawn over playback.
        val item = build(input(
            clips = listOf(clip("v1", 1)), tracks = setOf("v1"), skeletons = setOf("v1"),
        )).mine.single()
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP)
    }

    @Test
    fun an_analyzed_video_with_no_clips_in_sight_says_analyzed_and_asks_nothing() {
        // Review focus 3: offline, or the cloud match was deleted elsewhere.
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)))).mine.single()
        item.statusText shouldBe MatchListCopy.ANALYZED
        item.action shouldBe MatchAction.NONE
        item.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
    }

    // --- what it asks ---

    @Test
    fun a_device_run_in_flight_speaks_over_the_cloud_stage() {
        val item = build(input(
            entries = listOf(entry("v1")),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.RUNNING, DevicePhase.ANALYSING)),
        )).mine.single()
        item.action shouldBe MatchAction.RUNNING
        item.statusText shouldBe "Analyzing on device…"
        item.canRemoveFromPhone shouldBe false
    }

    @Test
    fun a_failed_device_run_asks_for_a_retry_in_red() {
        val item = build(input(
            entries = listOf(entry("v1")),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.FAILED, message = "Out of memory")),
        )).mine.single()
        item.action shouldBe MatchAction.RETRY
        item.statusText shouldBe "Analysis failed: Out of memory"
        item.statusIsError shouldBe true
    }

    @Test
    fun a_failed_cloud_run_shows_its_message_and_retries() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.FAILED, failureMessage = "Upload failed")))).mine.single()
        item.action shouldBe MatchAction.RETRY
        item.statusText shouldBe "Upload failed"
        item.statusIsError shouldBe true
    }

    @Test
    fun a_scored_match_with_an_unmarked_court_asks_for_the_court() {
        val item = build(input(
            logs = listOf(log("l1")), entries = listOf(entry("v1", scoreLogId = "l1")),
        )).mine.single()
        item.action shouldBe MatchAction.MARK_COURT
        item.where shouldBe MatchWhere.PHONE
    }

    // --- removal and delete (review focus 4) ---

    @Test
    fun a_merged_row_offers_both_remove_and_delete_when_nothing_is_running() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)))).mine.single()
        item.canRemoveFromPhone shouldBe true
        item.delete shouldBe MatchDelete.VIDEO_MATCH
    }

    @Test
    fun a_merged_row_offers_no_delete_while_a_device_run_holds_the_file() {
        val item = build(input(
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.RUNNING)),
        )).mine.single()
        item.canRemoveFromPhone shouldBe false
        item.delete shouldBe MatchDelete.NONE
    }

    @Test
    fun a_video_only_on_this_phone_is_removed_not_deleted() {
        val item = build(input(entries = listOf(entry("v1")))).mine.single()
        item.canRemoveFromPhone shouldBe true
        item.delete shouldBe MatchDelete.NONE
    }

    // --- shared, titles, order ---

    @Test
    fun a_shared_match_is_listed_apart_with_who_shared_it() {
        val list = build(input(clips = listOf(clip("s", 1, owner = OTHER)), sharers = mapOf("s" to "coach@club.es")))
        list.mine.shouldBeEmpty()
        val item = list.shared.single()
        item.sharedBy shouldBe "Shared by coach@club.es"
        item.delete shouldBe MatchDelete.LEAVE_SHARE
        item.canShare shouldBe false
        item.isOwned shouldBe false
    }

    @Test
    fun the_name_comes_from_the_videos_row_before_the_clips_and_the_file() {
        val meta = MatchMetadata(videoId = "v1", title = "Club final")
        build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED, title = "old")), clips = listOf(clip("v1", 1, title = "clip name")), metadata = mapOf("v1" to meta)))
            .mine.single().title shouldBe "Club final"
        build(input(entries = listOf(entry("v2")))).mine.single().title shouldBe "v2.mp4"
    }

    @Test
    fun rows_sort_on_when_the_match_began_and_a_cloud_match_does_not_jump_when_it_is_recut() {
        val list = build(input(
            entries = listOf(entry("new", addedAt = "2026-09-20T10:00:00Z")),
            clips = listOf(clip("old", 1, at = "2026-09-01T10:00:00Z"), clip("old", 2, at = "2026-09-24T10:00:00Z")),
            logs = listOf(log("mid", at = "2026-09-10T10:00:00Z")),
        ))
        list.mine.map { it.key } shouldContainExactly listOf("video-new", "score-mid", "video-old")
    }

    @Test
    fun the_status_line_reads_as_one_sentence() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)), tracks = setOf("v1"))).mine.single()
        item.accessibilityStatus shouldBe "On this phone and in the cloud. Rallies, heatmap."
    }
}
