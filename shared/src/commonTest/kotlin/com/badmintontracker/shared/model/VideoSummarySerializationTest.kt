package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class VideoSummarySerializationTest {
    @Test
    fun decodes_payload() {
        val payload = """
            {
              "id":         "22222222-2222-2222-2222-222222222222",
              "filename":   "match-2026-05-01.mp4",
              "created_at": "2026-05-01T10:00:00Z"
            }
        """.trimIndent()
        val v = Json.decodeFromString(VideoSummary.serializer(), payload)
        v.filename shouldBe "match-2026-05-01.mp4"
        v.createdAt shouldBe Instant.parse("2026-05-01T10:00:00Z")
    }
}
