package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
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
private const val SEP_8 = "2026-09-08T10:00:00Z"

private fun entry(id: String, stage: AnalyzeStage = AnalyzeStage.LOCAL) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 1000, sizeBytes = 10, addedAtEpochMs = Instant.parse(SEP_8).toEpochMilliseconds(),
    stage = stage,
)

private fun clip(videoId: String, index: Int) = RallyClip(
    id = "$videoId-$index", videoId = videoId, ownerId = ME, rallyIndex = index,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = "thumbs/$videoId/$index.jpg",
    annotationCount = 0, createdAt = Instant.parse(SEP_8),
)

private fun log(id: String) = ScoreLog(
    id = id, videoId = null, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = emptyList(), status = ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(SEP_8), updatedAt = Instant.parse(SEP_8),
)

private fun input(
    clips: List<RallyClip> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
) = MatchListInput(
    clips = clips, currentUserId = ME, sharerByVideoId = emptyMap(), metadataByVideoId = emptyMap(),
    scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
    storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = emptyMap(),
)

private fun build(i: MatchListInput) = buildMatchList(i, TimeZone.UTC)

class MatchListFilterTest {

    @Test
    fun the_threshold_is_six() {
        MATCH_LIST_FILTER_THRESHOLD shouldBe 6
        showsFilters(mineCount = 5) shouldBe false
        showsFilters(mineCount = 6) shouldBe true
    }

    @Test
    fun a_chosen_filter_falls_back_to_all_once_the_chips_hide() {
        effectiveFilter(MatchListFilter.ON_THIS_PHONE, mineCount = 5) shouldBe MatchListFilter.ALL
        effectiveFilter(MatchListFilter.NOT_ANALYZED, mineCount = 5) shouldBe MatchListFilter.ALL
        effectiveFilter(MatchListFilter.ON_THIS_PHONE, mineCount = 6) shouldBe MatchListFilter.ON_THIS_PHONE
    }

    @Test
    fun all_always_passes() {
        val onPhone = build(input(entries = listOf(entry("v1")))).mine.single()
        val both = build(input(entries = listOf(entry("v2", AnalyzeStage.ANALYZED)), clips = listOf(clip("v2", 1)))).mine.single()
        val cloudOnly = build(input(clips = listOf(clip("v3", 1)))).mine.single()
        val noVideo = build(input(logs = listOf(log("l1")))).mine.single()
        listOf(onPhone, both, cloudOnly, noVideo).forEach { it.passes(MatchListFilter.ALL) shouldBe true }
    }

    @Test
    fun on_this_phone_passes_phone_and_both_only() {
        val onPhone = build(input(entries = listOf(entry("v1")))).mine.single()
        val both = build(input(entries = listOf(entry("v2", AnalyzeStage.ANALYZED)), clips = listOf(clip("v2", 1)))).mine.single()
        val cloudOnly = build(input(clips = listOf(clip("v3", 1)))).mine.single()
        val noVideo = build(input(logs = listOf(log("l1")))).mine.single()

        onPhone.passes(MatchListFilter.ON_THIS_PHONE) shouldBe true
        both.passes(MatchListFilter.ON_THIS_PHONE) shouldBe true
        cloudOnly.passes(MatchListFilter.ON_THIS_PHONE) shouldBe false
        noVideo.passes(MatchListFilter.ON_THIS_PHONE) shouldBe false
    }

    @Test
    fun not_analyzed_passes_only_an_unanalysed_video_on_this_phone() {
        val notAnalyzed = build(input(entries = listOf(entry("v1")))).mine.single()
        val analyzedOnPhone = build(input(entries = listOf(entry("v2", AnalyzeStage.ANALYZED)))).mine.single()
        val both = build(input(entries = listOf(entry("v3", AnalyzeStage.ANALYZED)), clips = listOf(clip("v3", 1)))).mine.single()
        val cloudOnly = build(input(clips = listOf(clip("v4", 1)))).mine.single()
        val noVideo = build(input(logs = listOf(log("l1")))).mine.single()

        notAnalyzed.passes(MatchListFilter.NOT_ANALYZED) shouldBe true
        // Review focus 3: an ANALYZED entry with no clips in sight says "Analyzed"
        // and is excluded, not left in limbo.
        analyzedOnPhone.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
        both.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
        cloudOnly.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
        noVideo.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
    }
}
