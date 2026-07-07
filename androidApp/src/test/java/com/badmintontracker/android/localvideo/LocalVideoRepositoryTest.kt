package com.badmintontracker.android.localvideo

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

private fun entry(id: String, addedAt: Long = 0L) = LocalVideoEntry(
    id = id,
    uri = "content://media/video/$id",
    displayName = "match-$id.mp4",
    durationMs = 60_000L,
    sizeBytes = 1_000L,
    addedAtEpochMs = addedAt,
)

class LocalVideoRepositoryTest {

    @Test
    fun add_get_remove_roundtrip() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.get("a")?.displayName shouldBe "match-a.mp4"
        repo.remove("a")
        repo.get("a").shouldBeNull()
        repo.entries.value shouldBe emptyList()
    }

    @Test
    fun entries_sorted_newest_first() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("old", addedAt = 1))
        repo.add(entry("new", addedAt = 2))
        repo.entries.value.map { it.id } shouldBe listOf("new", "old")
    }

    @Test
    fun update_transforms_entry() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.update("a") { it.copy(stage = AnalyzeStage.UPLOADING) }
        repo.get("a")?.stage shouldBe AnalyzeStage.UPLOADING
    }

    @Test
    fun persists_across_instances_via_settings() {
        val settings = MapSettings()
        LocalVideoRepository(settings).add(entry("a"))
        LocalVideoRepository(settings).get("a")?.id shouldBe "a"
    }

    @Test
    fun corrupt_stored_json_yields_empty_list() {
        val settings = MapSettings().apply { putString("local_videos", "not-json") }
        LocalVideoRepository(settings).entries.value shouldBe emptyList()
    }
}
