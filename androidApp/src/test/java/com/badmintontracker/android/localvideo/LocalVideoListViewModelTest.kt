package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
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

/**
 * The actions left on this view model, and the two labels that live beside it.
 *
 * The row model it used to build (LocalVideoRow, and the tests that pinned
 * its status text, busy state and remove gate) went when the drawer moved onto
 * the shared match list; those rules are MatchListBuilderTest's now.
 */
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

    @Test
    fun a_failed_attempt_is_analyzed_again_and_a_fresh_video_analyzed() {
        analyzeButtonLabel(AnalyzeStage.LOCAL) shouldBe "Analyze"
        analyzeButtonLabel(AnalyzeStage.FAILED) shouldBe "Re-analyze"
    }

    @Test
    fun a_duration_reads_as_minutes_and_padded_seconds() {
        formatDuration(65_000) shouldBe "1:05"
        formatDuration(999) shouldBe "0:00"
        formatDuration(600_000) shouldBe "10:00"
    }
}
