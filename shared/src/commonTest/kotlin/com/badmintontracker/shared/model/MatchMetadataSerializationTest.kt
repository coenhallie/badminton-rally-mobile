package com.badmintontracker.shared.model

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
    fun decodes_a_match_with_neither_field_set() {
        val payload = """
            [{"video_id":"22222222-2222-2222-2222-222222222222","title":null,"description":null}]
        """.trimIndent()

        val row = Json.decodeFromString(ListSerializer(MatchMetadata.serializer()), payload).single()

        row.title.shouldBeNull()
        row.description.shouldBeNull()
    }
}
