package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchFormat
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
    fun a_match_takes_its_players_and_format_from_the_metadata_map() {
        // A match that was only ever filmed keeps these on its videos row, and
        // its list row must read them the way a scored match's does.
        val matches = listOf(clip("c1", "v1")).toMatches(
            currentUserId = "user-self",
            sharerByVideoId = emptyMap(),
            metadataByVideoId = mapOf(
                "v1" to MatchMetadata(
                    "v1", "Club final", null,
                    homePlayers = listOf("Coen", "Ana"), awayPlayers = listOf("Marco", "Li"),
                    format = MatchFormat.MIXED_DOUBLES,
                ),
            ),
        )

        matches.single().playersLine shouldBe "Coen / Ana vs Marco / Li"
        matches.single().format shouldBe MatchFormat.MIXED_DOUBLES
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
        matches.single().playersLine.shouldBeNull()
    }

    @Test
    fun a_match_with_neither_source_has_no_title() {
        // The date-headline fallback itself ("Match · <date>") is now each
        // caller's own concern (MatchScreen.kt, and MatchListItem.title in
        // the shared builder) rather than a shared matchRowPrimary this test
        // could call directly - deleted alongside matchRowSecondary once
        // neither had a caller left outside its own tests. What toMatches() itself owes downstream is a null
        // title to fall back from, which this still pins.
        val matches = listOf(clip("c1", "v1")).toMatches(
            currentUserId = "user-self",
            sharerByVideoId = emptyMap(),
            metadataByVideoId = emptyMap(),
        )

        matches.single().title.shouldBeNull()
    }
}
