package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.DeviceRun
import com.badmintontracker.shared.matchlist.DeviceRunKind
import com.badmintontracker.shared.matchlist.MatchDelete
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

/**
 * What a swipe on a row does ([swipeFor]) and what its delete everywhere calls
 * ([deleteEverywhereFor]). Built through [buildMatchList], so a case here
 * cannot assert a combination the real join could never produce. The swipe
 * cases mirror iOS's RowSwipeTests.
 */
class RowGesturesTest {
    private val t = Instant.parse("2026-09-08T10:00:00Z")

    private fun entry(id: String, stage: AnalyzeStage = AnalyzeStage.LOCAL, scoreLogId: String? = null) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "$id.mp4", durationMs = 48_000, sizeBytes = 10,
        addedAtEpochMs = t.toEpochMilliseconds(), stage = stage, scoreLogId = scoreLogId,
    )

    private fun clip(videoId: String, owner: String = "user-self") = RallyClip(
        id = "$videoId-1", videoId = videoId, ownerId = owner, rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/$videoId/1.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0, createdAt = t,
    )

    private fun log(id: String, videoId: String? = null) = ScoreLog(
        id = id, videoId = videoId, title = "Thu League",
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME), events = emptyList(),
        status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
        createdAt = t, updatedAt = t,
    )

    private fun item(
        clips: List<RallyClip> = emptyList(),
        entries: List<LocalVideoEntry> = emptyList(),
        logs: List<ScoreLog> = emptyList(),
        deviceRuns: Map<String, DeviceRun> = emptyMap(),
    ): MatchListItem {
        val list = buildMatchList(
            MatchListInput(
                clips = clips, currentUserId = "user-self", sharerByVideoId = emptyMap(),
                metadataByVideoId = emptyMap(), scoreLogs = logs, localEntries = entries,
                cloudProgress = emptyMap(), deviceRuns = deviceRuns, storedTrackIds = emptySet(),
                storedSkeletonIds = emptySet(), deviceClipCounts = emptyMap(),
                localNoteCounts = emptyMap(),
            ),
        )
        return (list.mine + list.shared).single()
    }

    // --- the swipe ---

    @Test
    fun a_match_on_this_phone_and_in_the_cloud_offers_the_two_way_choice() {
        val match = item(clips = listOf(clip("v1")), entries = listOf(entry("v1", AnalyzeStage.ANALYZED)))
        match.delete shouldBe MatchDelete.VIDEO_MATCH
        match.canRemoveFromPhone shouldBe true
        swipeFor(match) shouldBe Swipe.CHOOSE
        swipeFor(match)?.label shouldBe MatchListCopy.SWIPE_DELETE
    }

    @Test
    fun a_match_only_in_the_cloud_goes_straight_to_the_single_delete() {
        val match = item(clips = listOf(clip("v1")))
        match.canRemoveFromPhone shouldBe false
        swipeFor(match) shouldBe Swipe.DELETE
    }

    @Test
    fun a_shared_match_leaves_rather_than_deletes() {
        val match = item(clips = listOf(clip("v1", owner = "someone-else")))
        match.delete shouldBe MatchDelete.LEAVE_SHARE
        swipeFor(match) shouldBe Swipe.LEAVE
        swipeFor(match)?.label shouldBe MatchListCopy.SWIPE_REMOVE
    }

    @Test
    fun a_video_on_this_phone_with_nothing_in_the_cloud_offers_remove_only() {
        val match = item(entries = listOf(entry("e1")))
        match.delete shouldBe MatchDelete.NONE
        swipeFor(match) shouldBe Swipe.REMOVE
        swipeFor(match)?.label shouldBe MatchListCopy.SWIPE_REMOVE
    }

    @Test
    fun a_run_in_flight_offers_no_swipe_at_all() {
        val match = item(entries = listOf(entry("e1")), deviceRuns = mapOf("e1" to DeviceRun(DeviceRunKind.RUNNING)))
        match.delete shouldBe MatchDelete.NONE
        match.canRemoveFromPhone shouldBe false
        swipeFor(match) shouldBe null
    }

    // --- delete everywhere hands over the row's own entry ---

    @Test
    fun a_video_match_on_this_phone_deletes_with_its_entry() {
        deleteEverywhereFor(item(clips = listOf(clip("v1")), entries = listOf(entry("v1", AnalyzeStage.ANALYZED)))) shouldBe
            DeleteEverywhere.VideoMatch(videoId = "v1", entryId = "v1")
    }

    @Test
    fun a_scored_match_deletes_with_its_entry() {
        deleteEverywhereFor(item(logs = listOf(log("l1")), entries = listOf(entry("e1", scoreLogId = "l1")))) shouldBe
            DeleteEverywhere.ScoreMatch(scoreLogId = "l1", entryId = "e1")
    }

    /**
     * The row's entry joined by the log's video id, not by its own scoreLogId:
     * a lookup by scoreLogId would miss it and leave the file behind.
     */
    @Test
    fun a_bound_match_deletes_with_the_entry_the_list_joined_by_video_id() {
        deleteEverywhereFor(
            item(logs = listOf(log("l1", videoId = "v1")), clips = listOf(clip("v1")), entries = listOf(entry("v1", AnalyzeStage.ANALYZED))),
        ) shouldBe DeleteEverywhere.BoundMatch(videoId = "v1", scoreLogId = "l1", entryId = "v1")
    }

    @Test
    fun a_shared_match_is_left_and_a_match_with_nothing_to_delete_does_nothing() {
        deleteEverywhereFor(item(clips = listOf(clip("v1", owner = "someone-else")))) shouldBe DeleteEverywhere.LeaveShare("v1")
        deleteEverywhereFor(item(entries = listOf(entry("e1")))) shouldBe null
    }
}
