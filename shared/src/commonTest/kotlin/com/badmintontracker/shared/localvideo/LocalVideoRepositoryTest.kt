package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
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
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> removed += e })
        repo.add(entry("a"))
        repo.add(entry("b"))

        repo.remove("a")

        removed.map { it.id } shouldBe listOf("a")
    }

    @Test
    fun remove_reports_the_entry_as_discarded() {
        // A coach deleting a video wants everything derived from it gone too.
        val reasons = mutableListOf<LocalVideoRemoval>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { _, r -> reasons += r })
        repo.add(entry("a"))

        repo.remove("a")

        reasons shouldBe listOf(LocalVideoRemoval.DISCARDED)
    }

    @Test
    fun freeVideo_reports_the_entry_as_video_only_and_still_releases_the_file() {
        // The coach wants the space back. The footage goes, but the tracks must
        // survive so the heatmap and base position keep working, which is what
        // the reason lets the cleanup hook tell apart.
        val released = mutableListOf<String>()
        val reasons = mutableListOf<LocalVideoRemoval>()
        val repo = LocalVideoRepository(
            MapSettings(),
            onRemoved = { e, r -> released += e.id; reasons += r },
        )
        repo.add(entry("a"))

        repo.freeVideo("a")

        repo.get("a").shouldBeNull()
        released shouldBe listOf("a")
        reasons shouldBe listOf(LocalVideoRemoval.VIDEO_ONLY)
    }

    @Test
    fun freeing_an_unknown_id_does_not_fire_the_cleanup_hook() {
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> removed += e })
        repo.add(entry("a"))

        repo.freeVideo("nope")

        removed shouldBe emptyList()
    }

    @Test
    fun removing_an_unknown_id_does_not_fire_the_cleanup_hook() {
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> removed += e })
        repo.add(entry("a"))

        repo.remove("nope")

        removed shouldBe emptyList()
    }

    @Test
    fun add_and_update_never_fire_the_cleanup_hook() {
        val removed = mutableListOf<LocalVideoEntry>()
        val repo = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> removed += e })
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
        repo = LocalVideoRepository(MapSettings(), onRemoved = { e, _ -> stillPresent = repo.get(e.id) })
        repo.add(entry("a"))

        repo.remove("a")

        stillPresent.shouldBeNull()
    }

    @Test
    fun setKeypoints_saves_the_court_on_a_local_entry_and_leaves_its_stage() {
        // An on-device run saves the court this way and never moves the stage.
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        val court = List(12) { listOf(it.toFloat(), 0f) }.let {
            CourtKeypoints(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8], it[9], it[10], it[11])
        }

        repo.setKeypoints("a", court)

        repo.get("a")?.keypoints shouldBe court
        repo.get("a")?.stage shouldBe AnalyzeStage.LOCAL
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

    @Test
    fun setDetails_is_a_no_op_once_the_entry_has_left_local() {
        // The insert is the only write path for these columns, so a write after
        // CREATE_ROW would show a name the database does not have.
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.setDetails("a", "Thu League", "notes")
        repo.update("a") { it.copy(stage = AnalyzeStage.UPLOADING) }

        repo.setDetails("a", "Renamed after upload", "new notes")

        repo.get("a")?.title shouldBe "Thu League"
        repo.get("a")?.description shouldBe "notes"
    }

    @Test
    fun mirrorVideoDetails_follows_a_rename_that_landed_on_the_server_at_any_stage() {
        // The match page renames the videos row; the entry that uploaded it must
        // not go on showing the old name in "On this phone".
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.setDetails("a", "Thu League", "notes")
        repo.update("a") { it.copy(stage = AnalyzeStage.ANALYZED) }

        repo.mirrorVideoDetails("a", "Club final", null)

        repo.get("a")?.title shouldBe "Club final"
        repo.get("a")?.description.shouldBeNull()
    }

    @Test
    fun mirrorVideoDetails_ignores_a_video_this_phone_never_held() {
        // A match filmed on the web app or another phone has no entry here.
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))

        repo.mirrorVideoDetails("elsewhere", "Club final", null)

        repo.entries.value.map { it.id } shouldBe listOf("a")
        repo.get("a")?.title.shouldBeNull()
    }
}
