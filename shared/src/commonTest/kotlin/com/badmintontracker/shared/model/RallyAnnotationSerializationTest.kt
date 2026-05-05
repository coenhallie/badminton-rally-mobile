package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyAnnotationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_postgrest_payload_without_kind() {
        val payload = """
            {
              "id":               "33333333-3333-3333-3333-333333333333",
              "clip_id":          "11111111-1111-1111-1111-111111111111",
              "timestamp_seconds": 4.2,
              "body":             "great footwork",
              "created_at":       "2026-05-04T12:00:00Z"
            }
        """.trimIndent()

        val a = json.decodeFromString(RallyAnnotation.serializer(), payload)

        a.clipId shouldBe "11111111-1111-1111-1111-111111111111"
        a.timestampSeconds shouldBe 4.2f
        a.body shouldBe "great footwork"
        a.kind shouldBe null
        a.createdAt shouldBe Instant.parse("2026-05-04T12:00:00Z")
    }

    @Test
    fun decodes_each_kind_value() {
        val pairs = listOf(
            "good_shot"      to AnnotationKind.GOOD_SHOT,
            "forced_error"   to AnnotationKind.FORCED_ERROR,
            "unforced_error" to AnnotationKind.UNFORCED_ERROR,
        )
        for ((wire, enum) in pairs) {
            val payload = """
                {
                  "id":"x","clip_id":"c","timestamp_seconds":1.0,"body":"",
                  "kind":"$wire","created_at":"2026-05-04T12:00:00Z"
                }
            """.trimIndent()
            val a = json.decodeFromString(RallyAnnotation.serializer(), payload)
            a.kind shouldBe enum
        }
    }

    @Test
    fun encodes_null_kind_as_omitted_or_null() {
        val a = RallyAnnotation(
            id = "x", clipId = "c", timestampSeconds = 1f, body = "hi",
            kind = null, createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        val out = Json.encodeToString(RallyAnnotation.serializer(), a)
        // Either field is absent, or explicitly null. Either is fine for postgrest.
        out.shouldNotContain("\"kind\":\"")
    }

    @Test
    fun encodes_non_null_kind_with_snake_case_value() {
        val a = RallyAnnotation(
            id = "x", clipId = "c", timestampSeconds = 1f, body = "",
            kind = AnnotationKind.UNFORCED_ERROR,
            createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        val out = Json.encodeToString(RallyAnnotation.serializer(), a)
        out.shouldContain("\"kind\":\"unforced_error\"")
    }
}
