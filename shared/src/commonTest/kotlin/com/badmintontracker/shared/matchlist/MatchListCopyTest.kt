package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

class MatchListCopyTest {

    @Test
    fun a_match_with_no_video_and_a_score_reads_as_one_sentence() {
        statusDescription(null, listOf(MatchAnalysis.SCORE), null) shouldBe "No video. Score."
    }

    @Test
    fun a_running_status_keeps_its_ellipsis_rather_than_gaining_a_full_stop() {
        statusDescription(MatchWhere.PHONE, emptyList(), "Analyzing on device…") shouldBe
            "On this phone. Analyzing on device…"
    }

    @Test
    fun every_string_the_copy_object_can_produce_avoids_the_em_dash() {
        val fixed = listOf(
            MatchListCopy.NOT_ANALYZED, MatchListCopy.ANALYZED, MatchListCopy.FILTER_ALL,
            MatchListCopy.FILTER_ON_THIS_PHONE, MatchListCopy.FILTER_NOT_ANALYZED,
            MatchListCopy.EMPTY_ON_THIS_PHONE, MatchListCopy.EMPTY_NOT_ANALYZED,
            MatchListCopy.SHARED_SECTION, MatchListCopy.LEGEND_BUTTON, MatchListCopy.LEGEND_TITLE,
            MatchListCopy.WHERE_PHONE, MatchListCopy.WHERE_CLOUD, MatchListCopy.WHERE_BOTH, MatchListCopy.WHERE_NONE,
            MatchListCopy.ANALYSIS_RALLIES, MatchListCopy.ANALYSIS_HEATMAP,
            MatchListCopy.ANALYSIS_SKELETON, MatchListCopy.ANALYSIS_SCORE,
            MatchListCopy.MENU_SHARE, MatchListCopy.MENU_EDIT_DETAILS, MatchListCopy.MENU_ANALYZE_AGAIN,
            MatchListCopy.MENU_REMOVE_FROM_PHONE, MatchListCopy.MENU_DELETE,
            MatchListCopy.MENU_LEAVE_SHARE, MatchListCopy.SWIPE_DELETE, MatchListCopy.SWIPE_REMOVE,
            MatchListCopy.CHOICE_REMOVE, MatchListCopy.CHOICE_DELETE, MatchListCopy.CHOICE_CANCEL,
            MatchListCopy.REMOVE_VIDEO_TITLE, MatchListCopy.CONFIRM_REMOVE, MatchListCopy.CONFIRM_DELETE,
            MatchListCopy.MATCH_MENU, MatchListCopy.FILTER_GROUP, MatchListCopy.PAUSED,
            MatchListCopy.ANALYSIS_FAILED, MatchListCopy.analysisFailed(null), MatchListCopy.analysisFailed("x"),
            MatchListCopy.sharedBy("coach@club.es"), MatchListCopy.SORT,
        ) + MatchListSort.entries.flatMap { listOf(MatchListCopy.sortLabel(it), MatchListCopy.sortDescription(it)) }
        val produced = removableRows().flatMap { item ->
            listOf(MatchListCopy.removeFromPhoneDetail(item), MatchListCopy.choiceMessage(item))
        } + MatchAction.entries.mapNotNull { MatchListCopy.actionLabel(it) } +
            MatchDelete.entries.flatMap { listOfNotNull(MatchListCopy.deleteTitle(it), MatchListCopy.deleteConfirmLabel(it)) } +
            listOf(true, false).flatMap { onThisPhone ->
            MatchDelete.entries.flatMap { kind ->
                listOfNotNull(
                    MatchListCopy.choiceDeleteDetail(kind, onThisPhone),
                    MatchListCopy.deleteMessage(kind, onThisPhone),
                )
            }
        }
        val legend = MatchListCopy.LEGEND.flatMap { (label, line) -> listOf(label, line) }
        (fixed + produced + legend).forEach { it.containsEmDash() shouldBe false }
    }

    @Test
    fun without_a_file_on_this_phone_each_delete_says_what_it_said_before() {
        MatchListCopy.deleteMessage(MatchDelete.VIDEO_MATCH, onThisPhone = false) shouldBe
            "Delete this match and all its rally clips? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.SCORE_MATCH, onThisPhone = false) shouldBe
            "Delete this match and every point you scored? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.BOUND_MATCH, onThisPhone = false) shouldBe
            "Delete this match, every point you scored and all its rally clips? This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.LEAVE_SHARE, onThisPhone = false) shouldBe
            "Remove this shared match from your list? You'll need the owner to share it again."
        MatchListCopy.deleteMessage(MatchDelete.NONE, onThisPhone = false) shouldBe null
        MatchListCopy.choiceDeleteDetail(MatchDelete.VIDEO_MATCH, onThisPhone = false) shouldBe
            "Deletes this match and all its rally clips. This can't be undone."
        MatchListCopy.choiceDeleteDetail(MatchDelete.BOUND_MATCH, onThisPhone = false) shouldBe
            "Deletes this match, every point you scored and all its rally clips. This can't be undone."
    }

    /**
     * Deleting a match that is also on this phone discards the video and the
     * phone's analysis with it, so the confirmation has to say so. "This app",
     * because on Android the original stays in the gallery.
     */
    @Test
    fun with_a_file_on_this_phone_the_delete_says_the_video_goes_too() {
        MatchListCopy.deleteMessage(MatchDelete.VIDEO_MATCH, onThisPhone = true) shouldBe
            "Delete this match and all its rally clips, and remove its video and analysis from this app? " +
            "This can't be undone."
        MatchListCopy.deleteMessage(MatchDelete.BOUND_MATCH, onThisPhone = true) shouldBe
            "Delete this match, every point you scored and all its rally clips, and remove its video and " +
            "analysis from this app? This can't be undone."
        MatchListCopy.choiceDeleteDetail(MatchDelete.SCORE_MATCH, onThisPhone = true) shouldBe
            "Deletes this match and every point you scored, and removes its video and analysis from this app. " +
            "This can't be undone."
    }

    @Test
    fun a_row_asks_by_its_action_and_only_actions_with_something_to_press_have_a_label() {
        MatchListCopy.actionLabel(MatchAction.ANALYZE) shouldBe "Analyze"
        MatchListCopy.actionLabel(MatchAction.RETRY) shouldBe "Retry"
        MatchListCopy.actionLabel(MatchAction.MARK_COURT) shouldBe "Mark court"
        listOf(MatchAction.NONE, MatchAction.RUNNING, MatchAction.PAUSED).forEach {
            MatchListCopy.actionLabel(it) shouldBe null
        }
    }

    @Test
    fun the_delete_confirmation_is_titled_and_confirmed_per_kind() {
        listOf(MatchDelete.VIDEO_MATCH, MatchDelete.SCORE_MATCH, MatchDelete.BOUND_MATCH).forEach {
            MatchListCopy.deleteTitle(it) shouldBe "Delete match?"
            MatchListCopy.deleteConfirmLabel(it) shouldBe "Delete"
        }
        MatchListCopy.deleteTitle(MatchDelete.LEAVE_SHARE) shouldBe "Remove shared match?"
        MatchListCopy.deleteConfirmLabel(MatchDelete.LEAVE_SHARE) shouldBe "Remove"
        MatchListCopy.deleteTitle(MatchDelete.NONE) shouldBe null
        MatchListCopy.deleteConfirmLabel(MatchDelete.NONE) shouldBe null
    }

    // --- removing the video: what goes and what stays, per row ---

    private val t = Instant.parse("2026-09-08T10:00:00Z")

    private fun entry(id: String, stage: AnalyzeStage = AnalyzeStage.LOCAL, scoreLogId: String? = null) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "$id.mp4", durationMs = 48_000, sizeBytes = 10,
        addedAtEpochMs = t.toEpochMilliseconds(), stage = stage, scoreLogId = scoreLogId,
    )

    private fun clip(videoId: String) = RallyClip(
        id = "$videoId-1", videoId = videoId, ownerId = "me", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/$videoId/1.mp4", annotationCount = 0, createdAt = t,
    )

    private fun log(id: String, videoId: String? = null, points: Int = 0) = ScoreLog(
        id = id, videoId = videoId, title = "Thu League",
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
        events = List(points) { ScoreEvent.PointTo(Side.HOME) },
        status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
        createdAt = t, updatedAt = t,
    )

    /** The one row the builder makes of these, as the match list would get it. */
    private fun row(
        entries: List<LocalVideoEntry>,
        clips: List<RallyClip> = emptyList(),
        logs: List<ScoreLog> = emptyList(),
        tracks: Set<String> = emptySet(),
        skeletons: Set<String> = emptySet(),
        deviceClips: Map<String, Int> = emptyMap(),
    ): MatchListItem = buildMatchList(
        MatchListInput(
            clips = clips, currentUserId = "me", sharerByVideoId = emptyMap(), metadataByVideoId = emptyMap(),
            scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
            storedTrackIds = tracks, storedSkeletonIds = skeletons, deviceClipCounts = deviceClips,
            localNoteCounts = emptyMap(),
        ),
        TimeZone.UTC,
    ).mine.single().also { it.canRemoveFromPhone shouldBe true }

    private fun removableRows() = listOf(
        row(listOf(entry("e1"))),
        row(listOf(entry("e1")), tracks = setOf("e1"), skeletons = setOf("e1"), deviceClips = mapOf("e1" to 7)),
        row(listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")), tracks = setOf("v1"), skeletons = setOf("v1")),
        row(listOf(entry("e1", scoreLogId = "l1")), logs = listOf(log("l1", points = 3))),
        row(listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")), logs = listOf(log("l1", videoId = "v1", points = 3))),
    )

    @Test
    fun a_video_only_on_this_phone_says_it_leaves_the_list() {
        MatchListCopy.removeFromPhoneDetail(row(listOf(entry("e1")))) shouldBe
            "Removes the video and its notes from this app. The match is only on this phone, so it leaves your list."
    }

    /**
     * The track stays on disk, but with no cloud copy nothing lists it again:
     * as far as the coach can tell the heatmap is gone, and the old promise
     * that it stays was not true for this row.
     */
    @Test
    fun a_device_run_with_no_cloud_copy_names_everything_it_had_as_going() {
        val item = row(listOf(entry("e1")), tracks = setOf("e1"), skeletons = setOf("e1"), deviceClips = mapOf("e1" to 7))
        MatchListCopy.removeFromPhoneDetail(item) shouldBe
            "Removes the video, its notes, the rallies cut on this phone, the skeleton and the heatmap from this app. " +
            "The match is only on this phone, so it leaves your list."
    }

    /** The cloud row lists the heatmap once the file is gone; it stays, though not "in the cloud". */
    @Test
    fun a_match_also_in_the_cloud_keeps_its_rallies_there_and_its_heatmap() {
        val item = row(
            listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")),
            tracks = setOf("v1"), skeletons = setOf("v1"),
        )
        MatchListCopy.removeFromPhoneDetail(item) shouldBe
            "Removes the video, its notes and the skeleton from this app. " +
            "The match and its rallies stay in the cloud. The heatmap stays too."
    }

    @Test
    fun a_scored_match_keeps_its_points_whether_or_not_it_is_in_the_cloud() {
        MatchListCopy.removeFromPhoneDetail(row(listOf(entry("e1", scoreLogId = "l1")), logs = listOf(log("l1", points = 3)))) shouldBe
            "Removes the video and its notes from this app. The match and every point you scored stay."
        MatchListCopy.removeFromPhoneDetail(
            row(listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")), logs = listOf(log("l1", videoId = "v1", points = 3))),
        ) shouldBe
            "Removes the video and its notes from this app. The match, every point you scored and its rallies stay in the cloud."
        MatchListCopy.removeFromPhoneDetail(row(listOf(entry("e1", scoreLogId = "l1")), logs = listOf(log("l1")))) shouldBe
            "Removes the video and its notes from this app. The match stays."
    }

    @Test
    fun only_a_row_with_a_cloud_copy_mentions_the_cloud() {
        removableRows().forEach { item ->
            val detail = MatchListCopy.removeFromPhoneDetail(item)
            if (item.where == MatchWhere.BOTH) detail shouldContain "in the cloud" else detail shouldNotContain "cloud"
        }
    }

    /**
     * The shape the emulator check found: a video only on this phone, cut into
     * 3 rallies by a device run, with its track under the entry id. The list
     * is first built before the stored results are read (they arrive off the
     * main thread), then again with them. A dialog opened from the row must
     * read the row as it is now, found by its key, and say that the rallies
     * and the heatmap go too; the first build's item says neither.
     */
    @Test
    fun a_row_found_by_its_key_is_the_one_drawn_now_not_the_one_first_built() {
        fun build(withStored: Boolean) = buildMatchList(
            MatchListInput(
                clips = emptyList(), currentUserId = "me", sharerByVideoId = emptyMap(), metadataByVideoId = emptyMap(),
                scoreLogs = emptyList(), localEntries = listOf(entry("club")), cloudProgress = emptyMap(),
                deviceRuns = emptyMap(),
                storedTrackIds = if (withStored) setOf("club") else emptySet(),
                storedSkeletonIds = emptySet(),
                deviceClipCounts = if (withStored) mapOf("club" to 3) else emptyMap(),
                localNoteCounts = emptyMap(),
            ),
            TimeZone.UTC,
        )
        val first = build(withStored = false).mine.single()
        MatchListCopy.removeFromPhoneDetail(first) shouldBe
            "Removes the video and its notes from this app. The match is only on this phone, so it leaves your list."

        val now = build(withStored = true).itemWithKey(first.key)
        now?.deviceClipCount shouldBe 3
        now?.heatmapId shouldBe "club"
        MatchListCopy.removeFromPhoneDetail(now!!) shouldBe
            "Removes the video, its notes, the rallies cut on this phone and the heatmap from this app. " +
            "The match is only on this phone, so it leaves your list."
        build(withStored = true).itemWithKey("video-gone") shouldBe null
    }

    /** iOS's choice dialog has one message, so it carries both options' details. */
    @Test
    fun the_choice_message_explains_both_options() {
        val item = row(listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")))
        MatchListCopy.choiceMessage(item) shouldBe
            "${MatchListCopy.CHOICE_REMOVE}\n${MatchListCopy.removeFromPhoneDetail(item)}\n\n" +
            "${MatchListCopy.CHOICE_DELETE}\n${MatchListCopy.choiceDeleteDetail(item.delete, onThisPhone = true)}"
    }

    // Built from a code point rather than a literal, so this file itself never
    // carries an em dash to trip up the same check run over the repo's own source.
    private fun String.containsEmDash(): Boolean = contains(Char(0x2014))
}
