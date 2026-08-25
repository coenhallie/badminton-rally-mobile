package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchRowLabelsTest {
    private fun match(title: String?, rallyCount: Int = 12) = MatchSummary(
        videoId = "v",
        rallyCount = rallyCount,
        latestCreatedAt = Instant.parse("2026-07-25T12:00:00Z"),
        coverClip = RallyClip(
            id = "c", videoId = "v", ownerId = "u", rallyIndex = 0,
            startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
            clipStoragePath = "p/c.mp4", thumbnailStoragePath = null,
            title = title, annotationCount = 0,
            createdAt = Instant.parse("2026-07-25T12:00:00Z"),
        ),
        isOwned = true,
        title = title,
    )

    @Test
    fun named_match_leads_with_the_match_name() {
        matchRowPrimary(match("Thu League vs Marco")) shouldBe "Thu League vs Marco"
    }

    @Test
    fun named_match_keeps_the_date_beside_the_rally_count() {
        matchRowSecondary(match("Thu League vs Marco")) shouldBe "12 RALLIES · JUL 25, 2026"
    }

    @Test
    fun unnamed_match_keeps_the_original_date_headline() {
        matchRowPrimary(match(null)) shouldBe "Match · Jul 25, 2026"
    }

    @Test
    fun unnamed_match_secondary_stays_rally_count_only() {
        matchRowSecondary(match(null)) shouldBe "12 RALLIES"
    }

    @Test
    fun single_rally_is_not_pluralised() {
        matchRowSecondary(match(null, rallyCount = 1)) shouldBe "1 RALLY"
    }

    private fun clip(rallyIndex: Int, title: String?) = RallyClip(
        id = "c$rallyIndex", videoId = "v", ownerId = "u", rallyIndex = rallyIndex,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c.mp4", thumbnailStoragePath = null,
        title = title, annotationCount = 0,
        createdAt = Instant.parse("2026-07-25T12:00:00Z"),
    )

    @Test
    fun clip_carrying_only_the_match_name_shows_its_rally_number() {
        // Every clip of a match is stamped with the match name; repeating it on
        // each rally row would make the rallies indistinguishable.
        clipRowTitle(clip(3, "Thu League vs Marco"), "Thu League vs Marco") shouldBe "Rally #3"
    }

    @Test
    fun clip_renamed_by_the_user_keeps_its_own_title() {
        clipRowTitle(clip(3, "Great smash"), "Thu League vs Marco") shouldBe "Great smash"
    }

    @Test
    fun untitled_clip_shows_its_rally_number() {
        clipRowTitle(clip(3, null), "Thu League vs Marco") shouldBe "Rally #3"
    }

    @Test
    fun untitled_clip_in_an_unnamed_match_shows_its_rally_number() {
        clipRowTitle(clip(3, null), null) shouldBe "Rally #3"
    }
}
