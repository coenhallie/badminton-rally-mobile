package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalVideoListViewModelTest {

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun coordinator(localVideos: LocalVideoRepository, localAnnotations: LocalAnnotationsRepository) =
        AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher()),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
            localAnnotations = localAnnotations,
        )

    @Test
    fun maps_entries_to_rows_with_status_text() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 65_000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        localVideos.add(
            LocalVideoEntry(
                id = "b", uri = "content://b", displayName = "n.mp4",
                durationMs = 1_000, sizeBytes = 1, addedAtEpochMs = 1,
                stage = AnalyzeStage.FAILED, failedStep = AnalyzeStep.UPLOAD, failureMessage = "network",
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos, localAnnotations), localAnnotations)
        val rows = vm.rows.value
        rows.map { it.entry.id } shouldBe listOf("b", "a")
        rows[0].statusText shouldBe null            // FAILED surfaces via dialog, not card text
        rows[1].statusText shouldBe null            // plain LOCAL entry
        rows[1].durationText shouldBe "1:05"
        rows[1].canAnalyze shouldBe true
        rows[0].canAnalyze shouldBe true
        rows[1].analyzeLabel shouldBe "Analyze"     // fresh LOCAL entry
        rows[0].analyzeLabel shouldBe "Re-analyze"  // FAILED after an attempt
    }

    @Test
    fun uploading_rows_show_progress_and_disable_analyze() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 0, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.UPLOADING,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos, localAnnotations), localAnnotations)
        vm.rows.value.single().statusText shouldBe "Uploading…"
        vm.rows.value.single().canAnalyze shouldBe false
    }

    @Test
    fun analyzed_row_shows_label_and_hides_analyze() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher()),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
            localAnnotations = localAnnotations,
        )
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.ANALYZED,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator, localAnnotations)
        val row = vm.rows.value.single()
        row.statusText shouldBe "Analyzed"
        row.canAnalyze shouldBe false
    }

    @Test
    fun details_are_editable_only_while_the_entry_is_still_local() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        AnalyzeStage.entries.forEachIndexed { i, stage ->
            localVideos.add(
                LocalVideoEntry(
                    id = stage.name, uri = "content://$stage", displayName = "m.mp4",
                    durationMs = 1000, sizeBytes = 1, addedAtEpochMs = i.toLong(), stage = stage,
                ),
            )
        }
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos, localAnnotations), localAnnotations)

        val editableStages = vm.rows.value.filter { it.canEditDetails }.map { it.entry.stage }

        editableStages shouldBe listOf(AnalyzeStage.LOCAL)
    }

    @Test
    fun a_named_entry_leads_with_its_name_and_an_unnamed_one_with_its_file_name() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "named", uri = "content://a", displayName = "VID_0042.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 1,
                title = "Thu League vs Marco",
            ),
        )
        localVideos.add(
            LocalVideoEntry(
                id = "unnamed", uri = "content://b", displayName = "VID_0043.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos, localAnnotations), localAnnotations)

        val byId = vm.rows.value.associateBy { it.entry.id }
        byId.getValue("named").primaryText shouldBe "Thu League vs Marco"
        byId.getValue("unnamed").primaryText shouldBe "VID_0043.mp4"
    }

    @Test
    fun setDetails_normalizes_before_storing() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos, localAnnotations), localAnnotations)

        vm.setDetails("a", "  Thu League vs Marco  ", "   ")

        localVideos.get("a")?.title shouldBe "Thu League vs Marco"
        // Blank stays null: "" would be rejected by videos_title_length_check on insert.
        localVideos.get("a")?.description shouldBe null
    }
}
