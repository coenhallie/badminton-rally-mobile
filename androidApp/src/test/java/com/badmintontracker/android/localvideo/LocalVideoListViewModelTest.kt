package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
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

    private fun coordinator(localVideos: LocalVideoRepository) = AnalyzeCoordinator(
        localVideos, FakeVideosRepository(), FakeClipsRepository(),
        CoroutineScope(UnconfinedTestDispatcher()),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
    )

    @Test
    fun maps_entries_to_rows_with_status_text() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
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
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos))
        val rows = vm.rows.value
        rows.map { it.entry.id } shouldBe listOf("b", "a")
        rows[0].statusText shouldBe "Failed: network — tap Analyze to retry"
        rows[1].statusText shouldBe null            // plain LOCAL entry
        rows[1].durationText shouldBe "1:05"
        rows[1].canAnalyze shouldBe true
        rows[0].canAnalyze shouldBe true
    }

    @Test
    fun uploading_rows_show_progress_and_disable_analyze() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 0, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.UPLOADING,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator(localVideos))
        vm.rows.value.single().statusText shouldBe "Uploading…"
        vm.rows.value.single().canAnalyze shouldBe false
    }
}
