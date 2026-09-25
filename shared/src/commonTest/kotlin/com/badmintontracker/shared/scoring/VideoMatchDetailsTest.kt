package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.testing.FakeVideosRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The save behind "Edit details" on a match that was only ever filmed. Both match
 * pages call this and nothing else, so its ordering is the ordering.
 */
class VideoMatchDetailsTest {

    private fun uploadedEntry(id: String) = LocalVideoEntry(
        id = id, uri = "content://media/video/$id", displayName = "match.mp4",
        durationMs = 60_000L, sizeBytes = 1_000L, addedAtEpochMs = 0L,
        title = "cloudia", description = "cloudbaH", stage = AnalyzeStage.ANALYZED,
    )

    @Test
    fun a_saved_edit_is_written_normalised_and_mirrored_onto_the_local_entry() = runTest {
        val videos = FakeVideosRepository()
        val local = LocalVideoRepository(MapSettings()).apply { add(uploadedEntry("v1")) }

        val message = updateVideoMatchDetailsOrMessage(
            videos, local, videoId = "v1",
            title = "  Club final ", description = "   ", format = MatchFormat.DOUBLES,
            homePlayers = listOf(" Coen ", "Ana"), awayPlayers = listOf("Marco", ""),
        )

        message.shouldBeNull()
        val written = videos.updateDetailsCalls.single()
        written.title shouldBe "Club final"
        written.description.shouldBeNull()
        written.format shouldBe MatchFormat.DOUBLES
        written.homePlayers shouldBe listOf("Coen", "Ana")
        written.awayPlayers shouldBe listOf("Marco")
        // The entry that uploaded this video now says the same as the match.
        local.get("v1")?.title shouldBe "Club final"
        local.get("v1")?.description.shouldBeNull()
    }

    @Test
    fun a_partner_typed_into_a_singles_match_is_not_stored() = runTest {
        // Same rule as the score log's editor: the fields off screen are not sent.
        val videos = FakeVideosRepository()
        val local = LocalVideoRepository(MapSettings())

        updateVideoMatchDetailsOrMessage(
            videos, local, "v1", "Club final", "", MatchFormat.SINGLES,
            homePlayers = listOf("Coen", "Ana"), awayPlayers = listOf("Marco", "Li"),
        )

        videos.updateDetailsCalls.single().homePlayers shouldBe listOf("Coen")
        videos.updateDetailsCalls.single().awayPlayers shouldBe listOf("Marco")
    }

    @Test
    fun a_refused_save_reports_and_leaves_the_local_entry_alone() = runTest {
        // The server is the record. A local rename ahead of a refused save would
        // be exactly the divergence the LOCAL-only edit window exists to prevent.
        val videos = FakeVideosRepository().apply {
            nextUpdateDetailsResult = Result.failure(IllegalStateException("HTTP 403"))
        }
        val local = LocalVideoRepository(MapSettings()).apply { add(uploadedEntry("v1")) }

        val message = updateVideoMatchDetailsOrMessage(
            videos, local, "v1", "Club final", "", null, emptyList(), emptyList(),
        )

        message shouldBe VIDEO_DETAILS_SAVE_FAILED_MESSAGE
        local.get("v1")?.title shouldBe "cloudia"
        local.get("v1")?.description shouldBe "cloudbaH"
    }

    @Test
    fun a_video_this_phone_never_held_saves_without_a_local_entry() = runTest {
        // Filmed on the web app or on another phone: nothing to mirror, nothing to fail.
        val videos = FakeVideosRepository()
        val local = LocalVideoRepository(MapSettings())

        val message = updateVideoMatchDetailsOrMessage(
            videos, local, "web-1", "Club final", "", null, emptyList(), emptyList(),
        )

        message.shouldBeNull()
        local.entries.value.shouldBeEmpty()
        videos.metadata.value.getValue("web-1").title shouldBe "Club final"
    }
}
