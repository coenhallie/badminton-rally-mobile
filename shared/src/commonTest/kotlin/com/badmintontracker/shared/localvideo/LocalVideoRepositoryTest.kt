package com.badmintontracker.shared.localvideo

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun concurrent_updates_do_not_lose_writes() {
        // Multiple analyze pipelines mutate the repo from Dispatchers.Default at
        // once (e.g. two videos processing at relaunch); no write may be lost.
        val repo = LocalVideoRepository(MapSettings())
        val n = 100
        repeat(n) { i -> repo.add(entry("e$i")) }

        runTest {
            withContext(Dispatchers.Default) {
                (0 until n).map { i ->
                    launch { repo.update("e$i") { it.copy(stage = AnalyzeStage.PROCESSING) } }
                }.joinAll()
            }
        }

        repo.entries.value.count { it.stage == AnalyzeStage.PROCESSING } shouldBe n
    }

    @Test
    fun corrupt_stored_json_yields_empty_list() {
        val settings = MapSettings().apply { putString("local_videos", "not-json") }
        LocalVideoRepository(settings).entries.value shouldBe emptyList()
    }

    @Test
    fun acknowledgeResult_sets_resultSeen() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.acknowledgeResult("a")
        repo.get("a")?.resultSeen shouldBe true
    }

    @Test
    fun remove_hands_the_dropped_entry_to_the_file_cleanup_hook() {
        // iOS copies the video into its app container, so the registry entry is the
        // only reference to that file. A removal that skips this strands it forever.
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { removed += it })
        repo.add(entry("a"))
        repo.add(entry("b"))

        repo.remove("a")

        removed.map { it.id } shouldBe listOf("a")
    }

    @Test
    fun removing_an_unknown_id_does_not_fire_the_cleanup_hook() {
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { removed += it })
        repo.add(entry("a"))

        repo.remove("nope")

        removed shouldBe emptyList()
    }

    @Test
    fun add_and_update_never_fire_the_cleanup_hook() {
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { removed += it })
        repo.add(entry("a"))
        repo.update("a") { it.copy(stage = AnalyzeStage.UPLOADING) }

        removed shouldBe emptyList()
    }

    @Test
    fun the_cleanup_hook_runs_after_the_entry_is_gone_from_the_registry() {
        // Deleting the file first would leave a registry entry pointing at nothing
        // if the process died in between; that reads as a corrupt library.
        var stillPresent: LocalVideoEntry? = null
        lateinit var repo: LocalVideoRepository
        repo = LocalVideoRepository(MapSettings(), onRemoved = { stillPresent = repo.get(it.id) })
        repo.add(entry("a"))

        repo.remove("a")

        stillPresent.shouldBeNull()
    }

    @Test
    fun setDetails_round_trips_title_and_description() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))

        repo.setDetails("a", "Thu League vs Marco", "Best of three, indoor court 2.")

        repo.get("a")?.title shouldBe "Thu League vs Marco"
        repo.get("a")?.description shouldBe "Best of three, indoor court 2."
    }

    @Test
    fun setDetails_clears_both_when_given_nulls() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.setDetails("a", "Thu League", "notes")

        repo.setDetails("a", null, null)

        repo.get("a")?.title.shouldBeNull()
        repo.get("a")?.description.shouldBeNull()
    }
}
