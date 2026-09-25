package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

/**
 * [clipRowTitle]'s own fallback: a clip keeps its own title when it has one,
 * and otherwise falls back to its rally number rather than repeating the match
 * name every clip already carries.
 *
 * Used to also pin `matchRowPrimary`/`matchRowSecondary` (ClipListScreen.kt's
 * own match-row headline/sub-line formatting). Task 4 moved the Analytics
 * picker onto `MatchListItem.title`/`.detail` directly, which was
 * `matchRowPrimary`/`matchRowSecondary`'s last caller outside their own tests,
 * so those two functions and the five tests that covered them were deleted
 * together rather than left pinning dead code.
 */
class MatchRowLabelsTest {
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
