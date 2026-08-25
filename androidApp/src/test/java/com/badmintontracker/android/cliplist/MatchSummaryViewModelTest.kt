package com.badmintontracker.android.cliplist

import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.TopRally
import com.badmintontracker.shared.repo.AnnotationsRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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

class MatchSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private fun clip(id: String, rallyIndex: Int, videoId: String = "v1", title: String? = null) =
        RallyClip(
            id = id, videoId = videoId, ownerId = "u", rallyIndex = rallyIndex,
            startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
            clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
            title = title, annotationCount = 0,
            createdAt = Instant.parse("2026-08-26T12:00:00Z"),
        )

    private fun note(id: String, clipId: String, label: String, color: String = "green") =
        RallyAnnotation(
            id = id, clipId = clipId, timestampSeconds = 1f, body = "",
            labelName = label, labelColor = color,
            createdAt = Instant.parse("2026-08-26T12:00:00Z"),
        )

    @Test
    fun builds_a_summary_from_the_videos_own_clips() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply {
            clips.value = listOf(clip("c1", 1), clip("c2", 2), clip("other", 1, videoId = "v2"))
        }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf(
                "c1" to listOf(note("a1", "c1", "Good shot"), note("a2", "c1", "Good shot")),
                "c2" to listOf(note("a3", "c2", "Unforced error", color = "red")),
                "other" to listOf(note("a4", "other", "Good shot")),
            )
        }

        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        val summary = vm.summary.value!!
        summary.labelledNoteCount shouldBe 3
        summary.labels.map { it.name } shouldBe listOf("Good shot", "Unforced error")
        summary.topRally!!.clipId shouldBe "c1"
        annotations.listForClipsCalls.single() shouldBe listOf("c1", "c2")
    }

    @Test
    fun refetches_when_the_clip_set_changes() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()
        vm.summary.value!!.labelledNoteCount shouldBe 1

        annotations.byClipId = annotations.byClipId + ("c2" to listOf(note("a2", "c2", "Good shot")))
        clips.clips.value = listOf(clip("c1", 1), clip("c2", 2))
        advanceUntilIdle()

        vm.summary.value!!.labelledNoteCount shouldBe 2
        annotations.listForClipsCalls.size shouldBe 2
    }

    @Test
    fun a_failed_fetch_leaves_the_previous_summary_on_screen() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        annotations.listForClipsError = RuntimeException("offline")
        vm.refresh()
        advanceUntilIdle()

        vm.summary.value!!.labelledNoteCount shouldBe 1
    }

    @Test
    fun rapid_refreshes_do_not_stack_up_extra_fetches() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()
        annotations.listForClipsCalls.size shouldBe 1

        // Two refreshes with no dispatch in between. What suppresses the second
        // fetch here is load()'s unconditional cancel of a job that has not run
        // yet, not the in-flight guard: this test passes either way. The guard
        // itself is covered by refresh_does_not_restart_a_fetch_that_is_already_running,
        // which parks a fetch mid-flight and fails without it.
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()

        annotations.listForClipsCalls.size shouldBe 2
    }

    @Test
    fun refresh_does_not_restart_a_fetch_that_is_already_running() = runTest(dispatcher) {
        // GatedAnnotationsRepository suspends inside listForClips until the test
        // completes the gate, so the init collector's fetch can be parked
        // mid-flight (call recorded, coroutine genuinely suspended) before
        // refresh() is called. FakeAnnotationsRepository cannot do this: it runs
        // to completion in one dispatch with no suspension point, so a second
        // refresh() there always finds a job that either already finished or was
        // never dispatched, and load()'s own "cancel the previous job" step
        // produces the same call count whether or not the isActive guard exists.
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = GatedAnnotationsRepository(response = listOf(note("a1", "c1", "Good shot")))

        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()
        annotations.listForClipsCalls.size shouldBe 1

        vm.refresh()
        advanceUntilIdle()
        annotations.listForClipsCalls.size shouldBe 1

        annotations.gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun pruning_the_matchs_clips_drops_the_summary() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        clips.pruneVideo("v1")
        advanceUntilIdle()

        vm.summary.value shouldBe null
    }

    @Test
    fun top_rally_name_matches_the_row_the_rally_shows_in_the_list() {
        val clips = listOf(clip("c1", 1), clip("c2", 2, title = "The long rally"))

        topRallyName(TopRally("c2", 2, 4), clips, matchTitle = null) shouldBe "The long rally"
        topRallyName(TopRally("c1", 1, 4), clips, matchTitle = null) shouldBe "Rally #1"
    }

    @Test
    fun top_rally_name_falls_back_to_the_rally_number_when_the_clip_is_gone() {
        topRallyName(TopRally("pruned", 7, 4), clips = emptyList(), matchTitle = null) shouldBe "Rally #7"
    }
}

/**
 * An [AnnotationsRepository] whose [listForClips] records the call and then
 * suspends on [gate] until the test completes it. Used only to put a fetch
 * genuinely in flight (started, not finished) so a guard against a concurrent
 * refresh can actually be observed; [FakeAnnotationsRepository] has no
 * suspension point and cannot produce that state.
 */
private class GatedAnnotationsRepository(private val response: List<RallyAnnotation>) : AnnotationsRepository {
    val listForClipsCalls = mutableListOf<List<String>>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun list(clipId: String): List<RallyAnnotation> = emptyList()

    override suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>> {
        listForClipsCalls += clipIds
        gate.await()
        return Result.success(response)
    }

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation> = error("not used in this test")

    override suspend fun delete(id: String): Result<Unit> = error("not used in this test")
}
