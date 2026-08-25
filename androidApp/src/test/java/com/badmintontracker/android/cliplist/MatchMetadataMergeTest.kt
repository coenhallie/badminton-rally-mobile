package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchMetadataMergeTest {

    private fun clip(id: String, videoId: String, title: String? = null) = RallyClip(
        id = id, videoId = videoId, ownerId = "user-self", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = title, annotationCount = 0,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    @Test
    fun a_match_takes_its_title_and_description_from_the_metadata_map() {
        val matches = listOf(clip("c1", "v1")).toMatches(
            currentUserId = "user-self",
            sharerByVideoId = emptyMap(),
            metadataByVideoId = mapOf(
                "v1" to MatchMetadata("v1", "Thu League vs Marco", "Indoor court 2."),
            ),
        )

        matches.single().title shouldBe "Thu League vs Marco"
        matches.single().description shouldBe "Indoor court 2."
    }

    @Test
    fun a_video_absent_from_the_map_falls_back_to_the_clip_stamped_name() {
        // The RPC is soft-failing, so an empty map is the transient-error case as
        // well as the never-named case; neither may blank out a visible name.
        val matches = listOf(clip("c1", "v1", title = "Thu League vs Marco")).toMatches(
            currentUserId = "user-self",
            sharerByVideoId = emptyMap(),
            metadataByVideoId = emptyMap(),
        )

        matches.single().title shouldBe "Thu League vs Marco"
        matches.single().description.shouldBeNull()
    }

    @Test
    fun a_match_with_neither_source_keeps_its_date_headline() {
        val matches = listOf(clip("c1", "v1")).toMatches(
            currentUserId = "user-self",
            sharerByVideoId = emptyMap(),
            metadataByVideoId = emptyMap(),
        )

        matches.single().title.shouldBeNull()
        matchRowPrimary(matches.single()) shouldBe "Match · May 4, 2026"
    }
}
