package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

private const val ME = "user-me"
private const val OTHER = "user-other"

/** Sep [day], 2026, 10:00 UTC: later days are newer matches. */
private fun sep(day: Int) = "2026-09-${day.toString().padStart(2, '0')}T10:00:00Z"

private fun entry(id: String, day: Int, stage: AnalyzeStage = AnalyzeStage.LOCAL) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(sep(day)).toEpochMilliseconds(),
    stage = stage,
)

/** [notes] per clip, one clip per element: `clips("v1", 5, 2, 0)` is three rallies carrying seven notes. */
private fun clips(videoId: String, day: Int, vararg notes: Int, owner: String = ME) = notes.mapIndexed { i, n ->
    RallyClip(
        id = "$videoId-${i + 1}", videoId = videoId, ownerId = owner, rallyIndex = i + 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/$videoId/${i + 1}.mp4", thumbnailStoragePath = null,
        annotationCount = n, createdAt = Instant.parse(sep(day)),
    )
}

private fun log(id: String, day: Int, videoId: String? = null) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = emptyList(), status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(sep(day)), updatedAt = Instant.parse(sep(day)),
)

private fun build(
    clips: List<RallyClip> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
    deviceClips: Map<String, Int> = emptyMap(),
    sharers: Map<String, String> = emptyMap(),
    localNotes: Map<String, Int> = emptyMap(),
) = buildMatchList(
    MatchListInput(
        clips = clips, currentUserId = ME, sharerByVideoId = sharers, metadataByVideoId = emptyMap(),
        scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
        storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = deviceClips,
        localNoteCounts = localNotes,
    ),
    TimeZone.UTC,
)

private fun List<MatchListItem>.keys() = map { it.key }

class MatchListSortTest {

    // --- what each row counts ---

    @Test
    fun a_cloud_match_counts_its_clips_and_every_note_on_them() {
        val item = build(clips = clips("v1", 8, 5, 2, 0)).mine.single()
        item.rallyCount shouldBe 3
        item.noteCount shouldBe 7
    }

    @Test
    fun a_match_cut_only_on_this_phone_counts_the_device_rallies_and_no_notes() {
        val item = build(entries = listOf(entry("e1", 8)), deviceClips = mapOf("e1" to 4)).mine.single()
        item.rallyCount shouldBe 4
        item.noteCount shouldBe 0
    }

    @Test
    fun cloud_clips_win_over_device_rallies_as_the_row_detail_does() {
        val item = build(
            entries = listOf(entry("v1", 8, AnalyzeStage.ANALYZED)),
            clips = clips("v1", 8, 1, 1),
            deviceClips = mapOf("v1" to 9),
        ).mine.single()
        item.detail shouldBe "2 rallies · Sep 8, 2026"
        item.rallyCount shouldBe 2
        item.noteCount shouldBe 2
    }

    @Test
    fun a_scored_match_counts_its_bound_video_or_else_its_device_run() {
        val list = build(
            logs = listOf(log("l1", 8, videoId = "v1"), log("l2", 9)),
            clips = clips("v1", 8, 3, 0, 1),
            entries = listOf(entry("e2", 9).copy(scoreLogId = "l2")),
            deviceClips = mapOf("e2" to 6),
        )
        val bound = list.mine.single { it.key == "score-l1" }
        bound.rallyCount shouldBe 3
        bound.noteCount shouldBe 4
        val onDevice = list.mine.single { it.key == "score-l2" }
        onDevice.rallyCount shouldBe 6
        onDevice.noteCount shouldBe 0
    }

    @Test
    fun a_match_only_on_this_phone_counts_the_notes_made_on_it() {
        val item = build(entries = listOf(entry("e1", 8)), localNotes = mapOf("e1" to 3)).mine.single()
        item.noteCount shouldBe 3
    }

    @Test
    fun a_match_on_the_phone_and_in_the_cloud_adds_both_kinds_of_note() {
        // Two stores that never meet: a note on the phone's copy is never
        // uploaded, and a clip's count only counts rally_annotations rows, so
        // adding them counts no note twice.
        val item = build(
            entries = listOf(entry("v1", 8, AnalyzeStage.ANALYZED)),
            clips = clips("v1", 8, 2, 1),
            localNotes = mapOf("v1" to 4),
        ).mine.single()
        item.noteCount shouldBe 7
    }

    @Test
    fun a_scored_match_counts_the_notes_on_its_own_entry() {
        val item = build(
            logs = listOf(log("l1", 8)),
            entries = listOf(entry("e1", 8).copy(scoreLogId = "l1")),
            localNotes = mapOf("e1" to 2),
        ).mine.single()
        item.key shouldBe "score-l1"
        item.noteCount shouldBe 2
    }

    @Test
    fun a_cloud_match_is_not_credited_with_notes_on_an_entry_it_does_not_have() {
        // Keyed by entry id: a count for a video this phone does not hold
        // (the entry was removed, its notes with it) has no row to land on.
        val item = build(clips = clips("v1", 8, 1), localNotes = mapOf("v1" to 5)).mine.single()
        item.noteCount shouldBe 1
    }

    @Test
    fun local_note_counts_count_each_videos_notes_and_leave_out_empty_lists() {
        fun note(id: String) = LocalAnnotation(id = id, timestampSeconds = 1f, body = "", createdAtEpochMs = 0)
        localNoteCounts(mapOf("a" to listOf(note("1"), note("2")), "b" to emptyList(), "c" to listOf(note("3")))) shouldBe
            mapOf("a" to 2, "c" to 1)
    }

    @Test
    fun a_match_with_nothing_cut_counts_nothing() {
        val list = build(logs = listOf(log("l1", 8)), entries = listOf(entry("e1", 9)))
        list.mine.forEach {
            it.rallyCount shouldBe 0
            it.noteCount shouldBe 0
        }
    }

    @Test
    fun a_shared_match_counts_the_owners_notes_it_shows() {
        // The recipient reads the owner's notes on every clip (the rallies
        // section prints each clip's count), so Most notes has something to
        // order the shared section by.
        val item = build(clips = clips("s1", 8, 2, 3, owner = OTHER), sharers = mapOf("s1" to "coach@club.es"))
            .shared.single()
        item.rallyCount shouldBe 2
        item.noteCount shouldBe 5
    }

    // --- the orders ---

    private val list = build(
        clips = clips("a", 3, 0, 0, 0, 0, 0) + // 5 rallies, 0 notes, Sep 3
            clips("b", 5, 4, 4) + // 2 rallies, 8 notes, Sep 5
            clips("c", 7, 1, 1, 0, 0, 0) + // 5 rallies, 2 notes, Sep 7
            clips("d", 7, 6, 2), // 2 rallies, 8 notes, Sep 7: ties c on date, b on notes
        entries = listOf(entry("e", 9)), // nothing cut, Sep 9
        logs = listOf(log("l", 1)), // nothing cut, Sep 1
    )

    @Test
    fun newest_is_the_order_the_builder_gives() {
        list.mine.keys() shouldBe listOf("video-e", "video-c", "video-d", "video-b", "video-a", "score-l")
        sortMatches(list.mine, MatchListSort.NEWEST) shouldBe list.mine
        sortMatches(list.mine.reversed(), MatchListSort.NEWEST) shouldBe list.mine
    }

    @Test
    fun oldest_reverses_the_dates_and_keeps_the_key_order_on_a_tie() {
        sortMatches(list.mine, MatchListSort.OLDEST).keys() shouldBe
            listOf("score-l", "video-a", "video-b", "video-c", "video-d", "video-e")
    }

    @Test
    fun most_rallies_breaks_a_tie_newest_first_and_puts_matches_with_none_last() {
        sortMatches(list.mine, MatchListSort.MOST_RALLIES).keys() shouldBe
            // c and a have 5 (c newer); d and b have 2 (d newer); e and l have none.
            listOf("video-c", "video-a", "video-d", "video-b", "video-e", "score-l")
    }

    @Test
    fun most_notes_breaks_a_tie_newest_first_and_puts_matches_with_none_last() {
        sortMatches(list.mine, MatchListSort.MOST_NOTES).keys() shouldBe
            // d and b have 8 (d newer); c has 2; a has clips but no notes, and
            // e and l no clips at all: all three count 0 and go newest first.
            listOf("video-d", "video-b", "video-c", "video-e", "video-a", "score-l")
    }

    @Test
    fun a_noted_match_only_on_this_phone_rises_under_most_notes() {
        val list = build(
            clips = clips("cloud", 9, 1, 1),
            entries = listOf(entry("phone", 3)),
            localNotes = mapOf("phone" to 5),
        ).mine
        list.keys() shouldBe listOf("video-cloud", "video-phone")
        sortMatches(list, MatchListSort.MOST_NOTES).keys() shouldBe listOf("video-phone", "video-cloud")
    }

    @Test
    fun equal_counts_on_the_same_day_fall_back_to_the_key() {
        val tied = build(clips = clips("y", 4, 1) + clips("x", 4, 1)).mine
        MatchListSort.entries.forEach { sort -> sortMatches(tied, sort).keys() shouldBe listOf("video-x", "video-y") }
    }

    @Test
    fun a_sort_orders_what_the_filter_left() {
        val filtered = build(
            entries = listOf(entry("p1", 3), entry("p2", 4), entry("p3", 5)),
            clips = clips("cloud", 6, 0, 0, 0, 0, 0, 0, 0),
            deviceClips = mapOf("p1" to 3, "p2" to 1, "p3" to 2),
        ).mine.filter { it.passes(MatchListFilter.ON_THIS_PHONE) }
        sortMatches(filtered, MatchListSort.MOST_RALLIES).keys() shouldBe listOf("video-p1", "video-p3", "video-p2")
    }

    @Test
    fun a_chosen_sort_falls_back_to_newest_once_the_control_hides() {
        MatchListSort.entries.forEach { effectiveSort(it, mineCount = 5) shouldBe MatchListSort.NEWEST }
        MatchListSort.entries.forEach { effectiveSort(it, mineCount = 6) shouldBe it }
    }

    // --- copy ---

    @Test
    fun the_button_names_the_current_order_and_the_menu_lists_all_four_in_order() {
        MatchListSort.entries.map { MatchListCopy.sortLabel(it) } shouldBe
            listOf("Newest", "Oldest", "Most rallies", "Most notes")
        MatchListCopy.sortDescription(MatchListSort.MOST_NOTES) shouldBe "Sort matches, Most notes"
    }
}
