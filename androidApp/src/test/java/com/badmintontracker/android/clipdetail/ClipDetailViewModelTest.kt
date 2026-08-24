package com.badmintontracker.android.clipdetail

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeAnnotationsRepository.AddCall
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeMediaRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.collections.shouldBeEmpty
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
        id = "c1", videoId = "v", ownerId = "user-self", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c1.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 1,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    private val netKill = AnnotationLabel(
        id = "l4", name = "Net kill", colorKey = "teal",
        createdAt = Instant.parse("2026-08-24T12:00:00Z"),
    )

    private data class Setup(
        val vm: ClipDetailViewModel,
        val media: FakeMediaRepository,
        val clips: FakeClipsRepository,
        val annotations: FakeAnnotationsRepository,
        val auth: FakeAuthRepository,
    )

    private fun setup(
        clipsList: List<RallyClip> = listOf(sampleClip),
        annotations: List<RallyAnnotation> = emptyList(),
        media: FakeMediaRepository = FakeMediaRepository(),
        auth: FakeAuthRepository = FakeAuthRepository().apply { currentUserIdValue = "user-self" },
        labels: FakeAnnotationLabelsRepository = FakeAnnotationLabelsRepository(emptyList()),
    ): Setup {
        val clips = FakeClipsRepository().apply { this.clips.value = clipsList }
        val ann = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to annotations)
        }
        val vm = ClipDetailViewModel("c1", clips, ann, media, auth, labels)
        return Setup(vm, media, clips, ann, auth)
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
    fun annotation_load_failure_does_not_block_playback() = runTest {
        val (vm, _, _, ann) = setup(
            media = FakeMediaRepository().apply { nextClipUrl = { "https://signed/c1?token=1" } },
        )
        ann.listError = RuntimeException("annotations down")
        advanceUntilIdle()

        val s = vm.state.value
        s.signedClipUrl shouldContain "token=1"
        s.error shouldBe null                    // player overlay must not appear
        s.actionError shouldBe "annotations down" // surfaced as snackbar instead
    }

    @Test
    fun isLoading_true_until_load_completes() = runTest {
        val (vm, _, _, _) = setup()
        vm.state.value.isLoading shouldBe true

        advanceUntilIdle()

        vm.state.value.isLoading shouldBe false
    }

    @Test
    fun refresh_failure_surfaces_cause_instead_of_clip_not_found() = runTest {
        val (vm, _, _, _) = setup(clipsList = emptyList()).also {
            it.clips.refreshError = RuntimeException("network down")
        }
        advanceUntilIdle()

        vm.state.value.error shouldContain "network down"
        vm.state.value.isLoading shouldBe false
    }

    @Test
    fun manual_retry_reloads_when_clip_never_loaded() = runTest {
        val s = setup(clipsList = emptyList())
        advanceUntilIdle()
        s.vm.state.value.error shouldBe "Clip not found"

        s.clips.clips.value = listOf(sampleClip)   // clip appears (e.g. network back)
        s.vm.onManualRetry()
        advanceUntilIdle()

        s.vm.state.value.clip?.id shouldBe "c1"
        s.vm.state.value.error shouldBe null
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

        vm.addAnnotation(timestampSeconds = 2.0f, body = "earlier", label = null)
        advanceUntilIdle()

        ann.addCalls shouldBe listOf(AddCall("c1", 2.0f, "earlier", null))
        vm.state.value.annotations.map { it.body } shouldBe listOf("earlier", "later")
        vm.state.value.actionError shouldBe null
    }

    @Test
    fun addAnnotation_blank_body_and_no_label_is_ignored() = runTest {
        val (vm, _, _, ann) = setup()
        advanceUntilIdle()

        vm.addAnnotation(timestampSeconds = 1f, body = "   ", label = null)
        advanceUntilIdle()

        ann.addCalls.size shouldBe 0
        vm.state.value.annotations.size shouldBe 0
    }

    @Test
    fun addAnnotation_blank_body_with_label_is_persisted() = runTest {
        val (vm, _, _, ann) = setup()
        advanceUntilIdle()

        vm.addAnnotation(timestampSeconds = 3f, body = "", label = netKill)
        advanceUntilIdle()

        ann.addCalls shouldBe listOf(AddCall("c1", 3f, "", netKill))
        vm.state.value.annotations.map { it.labelName } shouldBe listOf(netKill.name)
    }

    // NOTE: the brief's own fixture for these two tests constructs
    // FakeClipsRepository() with no clips, so ClipDetailViewModel.load() hits
    // its "Clip not found" branch and state.isOwner never leaves its false
    // default. addAnnotation's pre-existing `if (!state.value.isOwner) return`
    // guard (unchanged by this task; addAnnotation_ignored_when_not_owner still
    // depends on it) then blocks the add before the guard under test is ever
    // reached, so repo.added.single() throws NoSuchElementException and the
    // "ignored when empty" test is vacuously green. Fixed here by seeding the
    // owned clip and letting load() settle before acting, while keeping the
    // brief's assertions character-for-character.
    @Test
    fun addAnnotation_passes_the_label_through_to_the_repository() = runTest {
        val repo = FakeAnnotationsRepository()
        val labels = FakeAnnotationLabelsRepository(listOf(netKill))
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository().apply { clips.value = listOf(sampleClip) },
            annotations = repo, media = FakeMediaRepository(), auth = FakeAuthRepository(), labels = labels,
        )
        advanceUntilIdle()

        vm.addAnnotation(1.5f, "", netKill)
        advanceUntilIdle()

        repo.added.single().label shouldBe netKill
    }

    @Test
    fun addAnnotation_is_ignored_when_both_body_and_label_are_empty() = runTest {
        val repo = FakeAnnotationsRepository()
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository().apply { clips.value = listOf(sampleClip) },
            annotations = repo, media = FakeMediaRepository(), auth = FakeAuthRepository(),
            labels = FakeAnnotationLabelsRepository(emptyList()),
        )
        advanceUntilIdle()

        vm.addAnnotation(1.5f, "   ", null)
        advanceUntilIdle()

        repo.added.shouldBeEmpty()
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

    @Test
    fun isOwner_true_when_current_user_matches_clip_owner() = runTest {
        val (vm) = setup(
            auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" },
        )
        advanceUntilIdle()
        vm.state.value.isOwner shouldBe true
    }

    @Test
    fun isOwner_false_when_current_user_differs_from_clip_owner() = runTest {
        val (vm) = setup(
            auth = FakeAuthRepository().apply { currentUserIdValue = "user-other" },
        )
        advanceUntilIdle()
        vm.state.value.isOwner shouldBe false
    }

    @Test
    fun addAnnotation_ignored_when_not_owner() = runTest {
        val (vm, _, _, ann) = setup(
            auth = FakeAuthRepository().apply { currentUserIdValue = "user-other" },
        )
        advanceUntilIdle()
        vm.addAnnotation(timestampSeconds = 1f, body = "x", label = null)
        advanceUntilIdle()
        ann.addCalls.size shouldBe 0
    }

    @Test
    fun deleteAnnotation_ignored_when_not_owner() = runTest {
        val a1 = RallyAnnotation("a1", "c1", 1f, "one", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
        val (vm, _, _, ann) = setup(
            annotations = listOf(a1),
            auth = FakeAuthRepository().apply { currentUserIdValue = "user-other" },
        )
        advanceUntilIdle()
        vm.deleteAnnotation("a1")
        advanceUntilIdle()
        ann.deleteCalls.size shouldBe 0
        vm.state.value.annotations.map { it.id } shouldBe listOf("a1")
    }

    @Test
    fun display_title_falls_back_to_rally_number_when_clip_only_carries_the_match_name() = runTest {
        // Every clip of a match is stamped with the match name at cut time.
        val siblings = listOf(
            sampleClip.copy(id = "c1", rallyIndex = 3, title = "Thu League vs Marco"),
            sampleClip.copy(id = "c2", rallyIndex = 4, title = "Thu League vs Marco"),
        )
        val s = setup(clipsList = siblings)
        s.vm.state.test {
            var st = awaitItem()
            while (st.clip == null) st = awaitItem()
            st.displayTitle shouldBe "Rally #3"
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun display_title_keeps_a_clip_the_user_renamed() = runTest {
        val siblings = listOf(
            sampleClip.copy(id = "c1", rallyIndex = 3, title = "Great smash"),
            sampleClip.copy(id = "c2", rallyIndex = 4, title = "Thu League vs Marco"),
            sampleClip.copy(id = "c3", rallyIndex = 5, title = "Thu League vs Marco"),
        )
        val s = setup(clipsList = siblings)
        s.vm.state.test {
            var st = awaitItem()
            while (st.clip == null) st = awaitItem()
            st.displayTitle shouldBe "Great smash"
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun state_labels_reflects_the_label_repository() = runTest {
        val (vm, _, _, _) = setup(labels = FakeAnnotationLabelsRepository(listOf(netKill)))
        advanceUntilIdle()

        vm.state.value.labels shouldBe listOf(netKill)
    }
}
