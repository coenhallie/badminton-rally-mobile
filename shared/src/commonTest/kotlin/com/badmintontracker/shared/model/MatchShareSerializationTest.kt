package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MatchShareSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_postgrest_payload() {
        val payload = """
            {
              "shared_with_user_id": "33333333-3333-3333-3333-333333333333",
              "email": "coach@example.com",
              "created_at": "2026-05-06T12:00:00Z"
            }
        """.trimIndent()

        val share = json.decodeFromString(MatchShare.serializer(), payload)

        share.sharedWithUserId shouldBe "33333333-3333-3333-3333-333333333333"
        share.email shouldBe "coach@example.com"
        share.createdAt shouldBe Instant.parse("2026-05-06T12:00:00Z")
    }
}
