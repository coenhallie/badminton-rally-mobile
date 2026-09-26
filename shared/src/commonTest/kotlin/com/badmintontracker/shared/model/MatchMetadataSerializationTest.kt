package com.badmintontracker.shared.model

import com.badmintontracker.shared.scoring.MatchFormat
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
        row.playersLine() shouldBe "Coen / Ana vs Marco / Li"
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
        row.playersLine().shouldBeNull()
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
}
