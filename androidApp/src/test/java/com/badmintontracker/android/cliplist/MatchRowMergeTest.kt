package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.AttachStatus
import com.badmintontracker.shared.scoring.ScoreMatchCard
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class MatchRowMergeTest {

    private fun coverClip(videoId: String) = RallyClip(
        id = "c-$videoId", videoId = videoId, ownerId = "user-self", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c-$videoId.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun videoMatch(videoId: String, atMillis: Long) = MatchSummary(
        videoId = videoId,
        rallyCount = 3,
        latestCreatedAt = Instant.fromEpochMilliseconds(atMillis),
        coverClip = coverClip(videoId),
        isOwned = true,
    )

    private fun scoreMatch(id: String, atMillis: Long, videoId: String? = null) = ScoreMatchCard(
        scoreLogId = id,
        videoId = videoId,
        title = "Thu League",
        createdAtEpochMs = atMillis,
        playersLine = "Coen vs Marco",
        scoreLine = "11-9",
        statusLine = "Scoring",
        isLive = true,
        hasVideo = videoId != null,
    )

    @Test
    fun the_two_kinds_interleave_by_date_rather_than_stacking() {
        // The whole point of the decision this implements: a match scored last night
        // sits above a video analysed last week, not in a section beneath it.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 300), videoMatch("v2", 100)),
            scoreMatches = listOf(scoreMatch("s1", 200)),
        )
        rows.map { it.key } shouldBe listOf("video-v1", "score-s1", "video-v2")
    }

    @Test
    fun rows_carry_their_own_kind() {
        val rows = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 200)))
        (rows[0] as MatchRow.Score).card.scoreLogId shouldBe "s1"
        (rows[1] as MatchRow.Video).match.videoId shouldBe "v1"
    }

    @Test
    fun two_matches_at_the_same_instant_keep_a_fixed_order() {
        // A list that reshuffles two same-second rows between refreshes reads as a
        // glitch, so the tie-break is on the key and is fully determined.
        val a = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 100)))
        val b = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 100)))
        a.map { it.key } shouldBe b.map { it.key }
        a.map { it.key } shouldBe listOf("score-s1", "video-v1")
    }

    @Test
    fun keys_from_the_two_kinds_cannot_collide() {
        // Both ids are UUIDs from the same generator, so an unprefixed key would let
        // a video match and a score match claim the same LazyColumn slot.
        val rows = mergeMatchRows(listOf(videoMatch("same", 100)), listOf(scoreMatch("same", 200)))
        rows.map { it.key } shouldBe listOf("score-same", "video-same")
    }

    @Test
    fun an_account_with_only_scored_matches_still_gets_a_list() {
        mergeMatchRows(emptyList(), listOf(scoreMatch("s1", 100))).map { it.key } shouldBe listOf("score-s1")
    }

    @Test
    fun an_account_with_neither_gets_nothing() {
        mergeMatchRows(emptyList(), emptyList()).shouldBeEmpty()
    }

    @Test
    fun a_bound_match_is_one_row_and_not_two() {
        // Once the pipeline produces clips, toMatches builds a video match for the
        // same video the score log points at. Unmerged that is the same match twice,
        // in the same section, which is the defect this fold exists to prevent.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 300)),
            scoreMatches = listOf(scoreMatch("s1", 200, videoId = "v1")),
        )
        rows.map { it.key } shouldBe listOf("score-s1")
        (rows[0] as MatchRow.Score).video?.videoId shouldBe "v1"
    }

    @Test
    fun a_bound_match_keeps_the_place_its_score_log_earned() {
        // The coach created that match on Tuesday. It must not jump to the top of
        // the list on Friday just because its clips arrived.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 900), videoMatch("v2", 500)),
            scoreMatches = listOf(scoreMatch("s1", 100, videoId = "v1")),
        )
        rows.map { it.key } shouldBe listOf("video-v2", "score-s1")
    }

    @Test
    fun a_video_imported_on_its_own_is_still_its_own_row() {
        val rows = mergeMatchRows(listOf(videoMatch("v1", 300)), listOf(scoreMatch("s1", 200)))
        rows.map { it.key } shouldBe listOf("video-v1", "score-s1")
    }

    @Test
    fun a_match_whose_video_is_still_clipping_carries_the_status_but_no_video() {
        val rows = mergeMatchRows(
            videoMatches = emptyList(),
            scoreMatches = listOf(scoreMatch("s1", 200, videoId = "v1")),
            attachByScoreLogId = mapOf("s1" to AttachStatus("Clipping…", AttachKind.CLIPPING)),
        )
        val row = rows.single() as MatchRow.Score
        row.video.shouldBeNull()
        row.attach?.text shouldBe "Clipping…"
    }
}
