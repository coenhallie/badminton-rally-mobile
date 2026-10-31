package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class LocalVideoEntrySerializationTest {

    /**
     * A registry persisted before title and description existed must still decode.
     * LocalVideoRepository.load() is runCatching { ... }.getOrNull() ?: emptyList(),
     * so a field without a default does not surface as an error — it silently wipes
     * the user's entire local library on the first launch after the update.
     */
    @Test
    fun decodes_a_registry_persisted_before_title_and_description_existed() {
        val legacy = """
            [
              {
                "id":             "11111111-1111-1111-1111-111111111111",
                "uri":            "content://media/video/17",
                "displayName":    "match-2026-05-01.mp4",
                "durationMs":     60000,
                "sizeBytes":      1024,
                "addedAtEpochMs": 1746091200000,
                "stage":          "LOCAL"
              }
            ]
        """.trimIndent()

        val entries = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(LocalVideoEntry.serializer()), legacy)

        entries.size shouldBe 1
        entries[0].displayName shouldBe "match-2026-05-01.mp4"
        entries[0].title.shouldBeNull()
        entries[0].description.shouldBeNull()
    }

    /** The same payload, through the real load path that would swallow a failure. */
    @Test
    fun a_pre_metadata_registry_survives_the_repository_load_path() {
        val legacy = """
            [
              {
                "id":             "11111111-1111-1111-1111-111111111111",
                "uri":            "content://media/video/17",
                "displayName":    "match-2026-05-01.mp4",
                "durationMs":     60000,
                "sizeBytes":      1024,
                "addedAtEpochMs": 1746091200000,
                "stage":          "LOCAL"
              }
            ]
        """.trimIndent()
        val settings = MapSettings().apply { putString("local_videos", legacy) }

        val entries = LocalVideoRepository(settings).entries.value

        entries.map { it.id } shouldBe listOf("11111111-1111-1111-1111-111111111111")
        entries[0].title.shouldBeNull()
    }

    @Test
    fun a_registry_written_before_score_log_ids_existed_still_decodes() {
        // LocalVideoRepository.load() swallows a decode failure and returns an empty
        // library, so a non-defaulted field here would silently wipe every local
        // video on the phone the first time this build runs.
        val legacy = """
            [
              {
                "id":"e1",
                "uri":"content://x/e1",
                "displayName":"m.mp4",
                "durationMs":1000,
                "sizeBytes":10,
                "addedAtEpochMs":0
              }
            ]
        """.trimIndent()
        val settings = MapSettings().apply { putString("local_videos", legacy) }

        val entries = LocalVideoRepository(settings).entries.value

        entries.map { it.id } shouldBe listOf("e1")
        entries[0].scoreLogId.shouldBeNull()
    }

    @Test
    fun a_video_picked_for_a_match_remembers_which_match() {
        val entry = LocalVideoEntry(
            id = "e1", uri = "content://x/e1", displayName = "m.mp4",
            durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
            scoreLogId = "log-1",
        )
        val json = Json { ignoreUnknownKeys = true }
        val round = json.decodeFromString(
            LocalVideoEntry.serializer(),
            json.encodeToString(LocalVideoEntry.serializer(), entry),
        )
        round.scoreLogId shouldBe "log-1"
    }

    @Test
    fun an_entry_without_the_assignment_decodes_with_none() {
        // Same wipe-the-library hazard as above: a non-defaulted assignment field
        // would empty the registry on the first launch of this build.
        val legacy = """
            [{"id":"e1","uri":"content://x/e1","displayName":"m.mp4",
              "durationMs":1000,"sizeBytes":10,"addedAtEpochMs":0}]
        """.trimIndent()
        val settings = MapSettings().apply { putString("local_videos", legacy) }

        val entries = LocalVideoRepository(settings).entries.value

        entries.map { it.id } shouldBe listOf("e1")
        entries[0].matchPlayers shouldBe MatchPlayers.NONE
        entries[0].nearSide.shouldBeNull()
    }

    @Test
    fun an_assignment_survives_the_registry_round_trip() {
        val settings = MapSettings()
        LocalVideoRepository(settings).apply {
            add(LocalVideoEntry(id = "e1", uri = "u", displayName = "m.mp4", durationMs = 1, sizeBytes = 1, addedAtEpochMs = 0))
            setMatchPlayers("e1", MatchPlayers("player-marco", null, emptyList(), listOf("Jo")), Side.AWAY)
        }

        val entry = LocalVideoRepository(settings).get("e1")

        entry?.matchPlayers shouldBe MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        entry?.nearSide shouldBe Side.AWAY
    }

    @Test
    fun forgetting_a_player_rewrites_only_the_entries_that_name_them() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(LocalVideoEntry(id = "e1", uri = "u", displayName = "a.mp4", durationMs = 1, sizeBytes = 1, addedAtEpochMs = 0,
            homePlayerId = "player-marco", nearSide = Side.HOME))
        repo.add(LocalVideoEntry(id = "e2", uri = "u", displayName = "b.mp4", durationMs = 1, sizeBytes = 1, addedAtEpochMs = 1,
            awayPlayerId = "player-ana"))

        repo.forgetPlayer("player-marco", "Marco")

        repo.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), emptyList())
        repo.get("e1")?.nearSide shouldBe Side.HOME
        repo.get("e2")?.awayPlayerId shouldBe "player-ana"
    }
}
