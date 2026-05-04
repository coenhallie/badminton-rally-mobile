package com.badmintontracker.android.clipdetail

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeMediaRepository
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ClipDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val sampleClip = RallyClip(
        id = "c1", videoId = "v", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c1.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 1,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    private fun setup(
        clipsList: List<RallyClip> = listOf(sampleClip),
        annotations: List<RallyAnnotation> = emptyList(),
        media: FakeMediaRepository = FakeMediaRepository(),
    ): Triple<ClipDetailViewModel, FakeMediaRepository, FakeClipsRepository> {
        val clips = FakeClipsRepository().apply { this.clips.value = clipsList }
        val ann = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to annotations)
        }
        val vm = ClipDetailViewModel("c1", clips, ann, media)
        return Triple(vm, media, clips)
    }

    @Test
    fun init_loads_clip_annotations_and_signs_url() = runTest {
        val (vm, media, _) = setup(
            annotations = listOf(
                RallyAnnotation("a1", "c1", 1.5f, "great", Instant.parse("2026-05-04T12:00:00Z"))
            ),
            media = FakeMediaRepository().apply { nextClipUrl = { "https://signed/c1?token=1" } },
        )
        advanceUntilIdle()

        val s = vm.state.value
        s.clip?.id shouldBe "c1"
        s.annotations.map { it.id } shouldBe listOf("a1")
        s.signedClipUrl shouldContain "token=1"
        media.clipUrlCalls.size shouldBe 1
    }

    @Test
    fun onAnnotationTap_emits_seek_in_ms() = runTest {
        val (vm, _, _) = setup()
        advanceUntilIdle()

        vm.seekTo.test {
            vm.onAnnotationTap(RallyAnnotation("a", "c1", 4.2f, "x", Instant.parse("2026-05-04T12:00:00Z")))
            awaitItem() shouldBe 4200L
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun first_player_error_resigns_url() = runTest {
        var i = 0
        val media = FakeMediaRepository().apply { nextClipUrl = { "url-${++i}" } }
        val (vm, _, _) = setup(media = media)
        advanceUntilIdle()
        val firstUrl = vm.state.value.signedClipUrl

        vm.onPlayerError()
        advanceUntilIdle()

        vm.state.value.signedClipUrl shouldBe "url-2"
        firstUrl shouldBe "url-1"
        vm.state.value.error shouldBe null
    }

    @Test
    fun second_player_error_surfaces_user_facing_error() = runTest {
        val (vm, _, _) = setup()
        advanceUntilIdle()

        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()

        vm.state.value.error shouldBe "Couldn't load video"
    }

    @Test
    fun onManualRetry_resets_attempts_and_resigns() = runTest {
        val (vm, media, _) = setup()
        advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        val callsBefore = media.clipUrlCalls.size

        vm.onManualRetry(); advanceUntilIdle()

        vm.state.value.error shouldBe null
        media.clipUrlCalls.size shouldBe callsBefore + 1
    }
}
