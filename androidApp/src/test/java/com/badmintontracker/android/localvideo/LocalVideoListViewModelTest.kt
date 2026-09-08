package com.badmintontracker.android.localvideo

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.localanalysis.LocalAnalysisState
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
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** No on-device run in flight, which is what every stage-driven case wants. */
    private fun device(vararg states: Pair<String, LocalAnalysisState>) =
        MutableStateFlow(states.toMap())

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
        val vm = LocalVideoListViewModel(
            localVideos, coordinator(localVideos, localAnnotations), localAnnotations, device(),
        )
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
        val vm = LocalVideoListViewModel(
            localVideos, coordinator(localVideos, localAnnotations), localAnnotations, device(),
        )
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
        val vm = LocalVideoListViewModel(localVideos, coordinator, localAnnotations, device())
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
        val vm = LocalVideoListViewModel(
            localVideos, coordinator(localVideos, localAnnotations), localAnnotations, device(),
        )

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
        val vm = LocalVideoListViewModel(
            localVideos, coordinator(localVideos, localAnnotations), localAnnotations, device(),
        )

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
        val vm = LocalVideoListViewModel(
            localVideos, coordinator(localVideos, localAnnotations), localAnnotations, device(),
        )

        vm.setDetails("a", "  Thu League vs Marco  ", "   ")

        localVideos.get("a")?.title shouldBe "Thu League vs Marco"
        // Blank stays null: "" would be rejected by videos_title_length_check on insert.
        localVideos.get("a")?.description shouldBe null
    }

    // --- the on-device pipeline, which never moves the entry's stage ---

    @Test
    fun a_device_run_in_flight_replaces_the_analyze_button_with_its_own_progress() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(
            localVideos,
            coordinator(localVideos, localAnnotations),
            localAnnotations,
            device("a" to LocalAnalysisState.Analysing(0.42f)),
        )

        val row = vm.rows.value.single()
        // The stage is still LOCAL - that is exactly the trap this closes.
        row.entry.stage shouldBe AnalyzeStage.LOCAL
        // No percentage: the drawer's column is too narrow to hold both, and
        // appending one truncated it straight back off.
        row.statusText shouldBe "Analyzing on device…"
        row.canAnalyze shouldBe false
        row.isBusy shouldBe true
        // The run is decoding the file; deleting it now would pull it out from
        // under the run, so the swipe and the menu item both go away.
        row.canRemove shouldBe false
    }

    @Test
    fun each_device_phase_says_what_it_is_doing() = runTest {
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        listOf(
            LocalAnalysisState.Preparing("Copying video") to "Preparing video…",
            LocalAnalysisState.Analysing(0f) to "Analyzing on device…",
            LocalAnalysisState.Cutting(done = 2, total = 9) to "Cutting clips…",
        ).forEach { (state, expected) ->
            val repo = LocalVideoRepository(MapSettings())
            repo.add(
                LocalVideoEntry(
                    id = "a", uri = "content://a", displayName = "m.mp4",
                    durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
                ),
            )
            val vm = LocalVideoListViewModel(
                repo, coordinator(repo, localAnnotations), localAnnotations, device("a" to state),
            )
            vm.rows.value.single().statusText shouldBe expected
        }
    }

    @Test
    fun cutting_clips_still_counts_as_busy() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(
            localVideos,
            coordinator(localVideos, localAnnotations),
            localAnnotations,
            device("a" to LocalAnalysisState.Cutting(done = 2, total = 9)),
        )

        // The last phase of a run is still a run: the button stays away and the
        // row keeps spinning until the clips are on disk.
        vm.rows.value.single().isBusy shouldBe true
        vm.rows.value.single().canAnalyze shouldBe false
    }

    @Test
    fun a_failed_device_run_says_so_on_the_row_and_offers_analyze_again() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(
            localVideos,
            coordinator(localVideos, localAnnotations),
            localAnnotations,
            device("a" to LocalAnalysisState.Failed("no court found")),
        )

        val row = vm.rows.value.single()
        // The result dialog is gated on stage == FAILED, which a device run
        // never reaches, so the row has to carry the failure itself.
        row.statusText shouldBe "Analysis failed: no court found"
        row.canAnalyze shouldBe true
        row.isBusy shouldBe false
    }

    @Test
    fun a_finished_device_run_leaves_the_row_alone() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0,
            ),
        )
        val vm = LocalVideoListViewModel(
            localVideos,
            coordinator(localVideos, localAnnotations),
            localAnnotations,
            device("a" to doneState()),
        )

        // Done stays in the runner's map for the rest of the process. The row
        // goes back to offering another run - what the last one produced is
        // reachable from the overflow menu (clips, heatmap), not from a status
        // line that would then never clear.
        val row = vm.rows.value.single()
        row.statusText shouldBe null
        row.canAnalyze shouldBe true
        row.isBusy shouldBe false
        row.canRemove shouldBe true
    }

    @Test
    fun a_device_run_speaks_over_a_cloud_upload_on_the_same_video() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.UPLOADING,
            ),
        )
        val vm = LocalVideoListViewModel(
            localVideos,
            coordinator(localVideos, localAnnotations),
            localAnnotations,
            device("a" to LocalAnalysisState.Analysing(0.1f)),
        )

        // Both pipelines are busy; the row's own button starts the device one,
        // so that is the run it accounts for. Same precedence as affordanceFor.
        vm.rows.value.single().statusText shouldBe "Analyzing on device…"
    }

    private fun doneState() = LocalAnalysisState.Done(
        rallies = 3,
        shuttleVisible = 100,
        totalFrames = 200,
        clips = emptyList(),
        elapsedSeconds = 12.0,
        playerTrack = PlayerTrack(samples = emptyList(), framesWithPose = 0, rejections = emptyMap()),
        fps = 30.0,
    )
}
