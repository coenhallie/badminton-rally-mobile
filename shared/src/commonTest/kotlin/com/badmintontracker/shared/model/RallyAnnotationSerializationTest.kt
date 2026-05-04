package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyAnnotationSerializationTest {

    @Test
    fun decodes_postgrest_payload() {
        val payload = """
            {
              "id":               "33333333-3333-3333-3333-333333333333",
              "clip_id":          "11111111-1111-1111-1111-111111111111",
              "timestamp_seconds": 4.2,
              "body":             "great footwork",
              "created_at":       "2026-05-04T12:00:00Z"
            }
        """.trimIndent()

        val a = Json.decodeFromString(RallyAnnotation.serializer(), payload)

        a.clipId shouldBe "11111111-1111-1111-1111-111111111111"
        a.timestampSeconds shouldBe 4.2f
        a.body shouldBe "great footwork"
        a.createdAt shouldBe Instant.parse("2026-05-04T12:00:00Z")
    }
}
