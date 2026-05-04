package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyClipSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_postgrest_payload() {
        val payload = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "video_id": "22222222-2222-2222-2222-222222222222",
              "rally_index": 7,
              "start_timestamp": 12.5,
              "end_timestamp": 18.25,
              "duration_seconds": 5.75,
              "clip_storage_path": "uid/video/clip-7.mp4",
              "thumbnail_storage_path": "uid/video/clip-7.jpg",
              "title": "good smash",
              "annotation_count": 2,
              "created_at": "2026-05-04T12:00:00Z"
            }
        """.trimIndent()

        val clip = json.decodeFromString(RallyClip.serializer(), payload)

        clip.id shouldBe "11111111-1111-1111-1111-111111111111"
        clip.rallyIndex shouldBe 7
        clip.startTimestamp shouldBe 12.5f
        clip.thumbnailStoragePath shouldBe "uid/video/clip-7.jpg"
        clip.title shouldBe "good smash"
        clip.annotationCount shouldBe 2
        clip.createdAt shouldBe Instant.parse("2026-05-04T12:00:00Z")
    }

    @Test
    fun handles_null_title_and_thumbnail() {
        val payload = """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "video_id": "22222222-2222-2222-2222-222222222222",
              "rally_index": 0,
              "start_timestamp": 0.0,
              "end_timestamp": 1.0,
              "duration_seconds": 1.0,
              "clip_storage_path": "uid/video/clip-0.mp4",
              "thumbnail_storage_path": null,
              "title": null,
              "annotation_count": 0,
              "created_at": "2026-05-04T12:00:00Z"
            }
        """.trimIndent()

        val clip = json.decodeFromString(RallyClip.serializer(), payload)

        clip.title shouldBe null
        clip.thumbnailStoragePath shouldBe null
    }
}
