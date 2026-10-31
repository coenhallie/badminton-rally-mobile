package com.badmintontracker.shared.model

import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MatchMetadataSerializationTest {

    /** The RPC returns snake_case video_id; a casing slip here silently loses every title. */
    @Test
    fun decodes_the_rpc_payload() {
        val payload = """
            [
              {
                "video_id":    "22222222-2222-2222-2222-222222222222",
                "title":       "Thu League vs Marco",
                "description": "Best of three, indoor court 2."
              }
            ]
        """.trimIndent()

        val rows = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload)

        rows.single().videoId shouldBe "22222222-2222-2222-2222-222222222222"
        rows.single().title shouldBe "Thu League vs Marco"
        rows.single().description shouldBe "Best of three, indoor court 2."
    }

    @Test
    fun decodes_the_players_and_the_format() {
        // text[] columns arrive as JSON arrays and the format as the database spells it.
        val payload = """
            [{"video_id":"v1","title":"Club final","description":null,
              "home_players":["Coen","Ana"],"away_players":["Marco","Li"],"format":"mixed_doubles"}]
        """.trimIndent()

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.homePlayers shouldBe listOf("Coen", "Ana")
        row.awayPlayers shouldBe listOf("Marco", "Li")
        row.format shouldBe MatchFormat.MIXED_DOUBLES
    }

    @Test
    fun a_row_from_before_the_details_columns_still_decodes() {
        // The RPC of a project the migration has not reached yet, and a cache
        // written by the build before this one.
        val payload = """[{"video_id":"v1","title":"Thu League","description":null}]"""

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.homePlayers shouldBe emptyList()
        row.awayPlayers shouldBe emptyList()
        row.format.shouldBeNull()
    }

    @Test
    fun decodes_a_match_with_neither_field_set() {
        val payload = """
            [{"video_id":"22222222-2222-2222-2222-222222222222","title":null,"description":null}]
        """.trimIndent()

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.title.shouldBeNull()
        row.description.shouldBeNull()
    }

    @Test
    fun decodes_the_assignment_and_the_resolved_names() {
        val payload = """
            [{"video_id":"v1","title":"Club final","description":null,
              "home_players":[],"away_players":["Jo"],"format":"singles",
              "home_player_id":"player-marco","away_player_id":null,"near_side":"away",
              "home_names":["Marco"],"away_names":["Jo"]}]
        """.trimIndent()

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.homePlayerId shouldBe "player-marco"
        row.awayPlayerId.shouldBeNull()
        row.nearSide shouldBe Side.AWAY
        row.homeNames shouldBe listOf("Marco")
        row.awayNames shouldBe listOf("Jo")
        // The record is the typed names and the ids; the names are for display.
        row.matchPlayers shouldBe MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
    }

    @Test
    fun a_row_without_the_assignment_decodes_with_none() {
        val payload = """[{"video_id":"v1","title":"Thu League","description":null}]"""

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.matchPlayers shouldBe MatchPlayers.NONE
        row.nearSide.shouldBeNull()
        row.homeNames shouldBe emptyList()
        row.awayNames shouldBe emptyList()
    }
}
