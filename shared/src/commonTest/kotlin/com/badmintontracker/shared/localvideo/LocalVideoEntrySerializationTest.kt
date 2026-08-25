package com.badmintontracker.shared.localvideo

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
}
