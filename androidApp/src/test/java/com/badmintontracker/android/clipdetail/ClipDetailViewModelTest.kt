package com.badmintontracker.android.clipdetail

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeAnnotationsRepository.AddCall
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeMediaRepository
import com.badmintontracker.shared.model.AnnotationKind
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

    private data class Setup(
        val vm: ClipDetailViewModel,
        val media: FakeMediaRepository,
        val clips: FakeClipsRepository,
        val annotations: FakeAnnotationsRepository,
    )

    private fun setup(
        clipsList: List<RallyClip> = listOf(sampleClip),
        annotations: List<RallyAnnotation> = emptyList(),
        media: FakeMediaRepository = FakeMediaRepository(),
    ): Setup {
        val clips = FakeClipsRepository().apply { this.clips.value = clipsList }
        val ann = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to annotations)
        }
        val vm = ClipDetailViewModel("c1", clips, ann, media)
        return Setup(vm, media, clips, ann)
    }

    @Test
    fun init_loads_clip_annotations_and_signs_url() = runTest {
        val (vm, media, _, _) = setup(
            annotations = listOf(
                RallyAnnotation("a1", "c1", 1.5f, "great", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
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
        val (vm, _, _, _) = setup()
        advanceUntilIdle()

        vm.seekTo.test {
            vm.onAnnotationTap(RallyAnnotation("a", "c1", 4.2f, "x", createdAt = Instant.parse("2026-05-04T12:00:00Z")))
            awaitItem() shouldBe 4200L
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun first_player_error_resigns_url() = runTest {
        var i = 0
        val media = FakeMediaRepository().apply { nextClipUrl = { "url-${++i}" } }
        val (vm, _, _, _) = setup(media = media)
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
        val (vm, _, _, _) = setup()
        advanceUntilIdle()

        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()

        vm.state.value.error shouldBe "Couldn't load video"
    }

    @Test
    fun onManualRetry_resets_attempts_and_resigns() = runTest {
        val (vm, media, _, _) = setup()
        advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        val callsBefore = media.clipUrlCalls.size

        vm.onManualRetry(); advanceUntilIdle()

        vm.state.value.error shouldBe null
        media.clipUrlCalls.size shouldBe callsBefore + 1
    }

    @Test
    fun addAnnotation_appends_to_state_in_timestamp_order() = runTest {
        val existing = RallyAnnotation(
            "a1", "c1", 5.0f, "later",
            createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        val (vm, _, _, ann) = setup(annotations = listOf(existing))
        advanceUntilIdle()

        vm.addAnnotation(timestampSeconds = 2.0f, body = "earlier", kind = null)
        advanceUntilIdle()

        ann.addCalls shouldBe listOf(AddCall("c1", 2.0f, "earlier", null))
        vm.state.value.annotations.map { it.body } shouldBe listOf("earlier", "later")
        vm.state.value.actionError shouldBe null
    }

    @Test
    fun addAnnotation_blank_body_and_no_kind_is_ignored() = runTest {
        val (vm, _, _, ann) = setup()
        advanceUntilIdle()

        vm.addAnnotation(timestampSeconds = 1f, body = "   ", kind = null)
        advanceUntilIdle()

        ann.addCalls.size shouldBe 0
        vm.state.value.annotations.size shouldBe 0
    }

    @Test
    fun addAnnotation_blank_body_with_kind_is_persisted() = runTest {
        val (vm, _, _, ann) = setup()
        advanceUntilIdle()

        vm.addAnnotation(timestampSeconds = 3f, body = "", kind = AnnotationKind.GOOD_SHOT)
        advanceUntilIdle()

        ann.addCalls shouldBe listOf(AddCall("c1", 3f, "", AnnotationKind.GOOD_SHOT))
        vm.state.value.annotations.map { it.kind } shouldBe listOf(AnnotationKind.GOOD_SHOT)
    }

    @Test
    fun addAnnotation_failure_sets_actionError() = runTest {
        val (vm, _, _, ann) = setup()
        ann.addError = RuntimeException("boom")
        advanceUntilIdle()

        vm.addAnnotation(1f, "x", null)
        advanceUntilIdle()

        vm.state.value.annotations.size shouldBe 0
        vm.state.value.actionError shouldBe "boom"
    }

    @Test
    fun deleteAnnotation_removes_from_state() = runTest {
        val a1 = RallyAnnotation("a1", "c1", 1f, "one", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
        val a2 = RallyAnnotation("a2", "c1", 2f, "two", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
        val (vm, _, _, ann) = setup(annotations = listOf(a1, a2))
        advanceUntilIdle()

        vm.deleteAnnotation("a1")
        advanceUntilIdle()

        ann.deleteCalls shouldBe listOf("a1")
        vm.state.value.annotations.map { it.id } shouldBe listOf("a2")
    }

    @Test
    fun deleteAnnotation_failure_keeps_state_and_sets_actionError() = runTest {
        val a1 = RallyAnnotation("a1", "c1", 1f, "one", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
        val (vm, _, _, ann) = setup(annotations = listOf(a1))
        ann.deleteError = RuntimeException("nope")
        advanceUntilIdle()

        vm.deleteAnnotation("a1")
        advanceUntilIdle()

        vm.state.value.annotations.map { it.id } shouldBe listOf("a1")
        vm.state.value.actionError shouldBe "nope"
    }
}
