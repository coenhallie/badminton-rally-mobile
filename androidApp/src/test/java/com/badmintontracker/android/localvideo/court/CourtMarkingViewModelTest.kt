package com.badmintontracker.android.localvideo.court

import com.badmintontracker.analysis.court.CourtFinding
import com.badmintontracker.shared.localvideo.court.CourtFinderStatus
import com.badmintontracker.shared.localvideo.court.CourtPoint
import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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
class CourtMarkingViewModelTest {

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    /** What the view model wrote to the log: android.util.Log is a stub that throws on this JVM. */
    private class RecordingLog : CourtFinderLog {
        val answers = mutableListOf<Pair<String, CourtFinding>>()
        val failures = mutableListOf<Pair<String, Throwable>>()
        override fun answered(call: String, finding: CourtFinding) { answers += call to finding }
        override fun failed(call: String, error: Throwable) { failures += call to error }
    }

    private fun vm(
        loadOk: Boolean = true,
        savedKeypoints: CourtKeypoints? = null,
        finder: CourtFinderPort = DefaultCourtFinderPort,
        log: CourtFinderLog = RecordingLog(),
    ) = CourtMarkingViewModel(
        entryId = "e1",
        savedKeypoints = savedKeypoints,
        loadFrame = {
            if (loadOk) CourtFrame(frame = null, width = 1920, height = 1080)
            else error("no frame")
        },
        finder = finder,
        log = log,
    )

    @Test
    fun loads_frame_dimensions_and_starts_empty() = runTest {
        val vm = vm()
        val s = vm.state.value
        s.marking.shouldNotBeNull().videoWidth shouldBe 1920
        s.marking!!.points shouldBe emptyList()
        s.error shouldBe null
    }

    @Test
    fun tap_undo_complete_flow() = runTest {
        val vm = vm()
        repeat(12) { vm.onTap(displayX = 10f, displayY = 10f, displayWidth = 192f, displayHeight = 108f) }
        vm.state.value.marking!!.isComplete.shouldBeTrue()
        vm.onUndo()
        vm.state.value.marking!!.points.size shouldBe 11
    }

    private fun savedKeypoints() = CourtKeypoints(
        topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
    )

    @Test
    fun opens_on_the_marks_the_entry_was_last_analysed_with() = runTest {
        val saved = savedKeypoints()

        val marking = vm(savedKeypoints = saved).state.value.marking.shouldNotBeNull()

        marking.isComplete.shouldBeTrue()
        marking.toCourtKeypoints() shouldBe saved
        // Still editable from there: the marks are a starting point, not a
        // read-only record.
        marking.undo().points.size shouldBe 11
    }

    @Test
    fun says_where_preloaded_marks_came_from_until_they_are_edited() = runTest {
        val vm = vm(savedKeypoints = savedKeypoints())
        vm.state.value.showsSavedMarks.shouldBeTrue()

        // A tap on a complete marking that lands on no point does nothing, so
        // it does not count as an edit; Undo and Clear do (and moving a point,
        // below).
        vm.onTap(displayX = 10f, displayY = 10f, displayWidth = 192f, displayHeight = 108f)
        vm.state.value.showsSavedMarks.shouldBeTrue()

        vm.onUndo()
        vm.state.value.showsSavedMarks shouldBe false

        vm(savedKeypoints = savedKeypoints()).let {
            it.onClear()
            it.state.value.showsSavedMarks shouldBe false
        }

        // Nothing to say when the coach placed the points himself.
        vm().state.value.showsSavedMarks shouldBe false
    }

    @Test
    fun moving_a_saved_point_is_an_edit() = runTest {
        val vm = vm(savedKeypoints = savedKeypoints())

        // Picking the top-left mark up is not an edit yet ...
        vm.onTap(1f, 2f, 1920f, 1080f, 22f)
        vm.state.value.marking!!.selected shouldBe 0
        vm.state.value.showsSavedMarks.shouldBeTrue()

        // ... putting it down somewhere else is.
        vm.onTap(500f, 500f, 1920f, 1080f, 22f)
        vm.state.value.marking!!.points[0] shouldBe CourtPoint(500f, 500f)
        vm.state.value.showsSavedMarks shouldBe false
    }

    @Test
    fun marks_placed_here_carry_the_time_of_the_frame_they_were_drawn_on() = runTest {
        val vm = vm()
        repeat(12) { vm.onTap(displayX = 10f, displayY = 10f, displayWidth = 192f, displayHeight = 108f) }

        vm.state.value.marking!!.toCourtKeypoints().markedAtSeconds shouldBe 0.1
    }

    @Test
    fun frame_load_failure_sets_error() = runTest {
        vm(loadOk = false).state.value.error shouldBe "no frame"
    }

    // --- The court finder -------------------------------------------------------

    private fun p(i: Int) = com.badmintontracker.analysis.geometry.Point(100.0 + i * 50, 200.0 + i * 20)
    private fun found12() = CourtFinding(
        com.badmintontracker.analysis.geometry.CourtKeypoints(
            p(0), p(1), p(2), p(3), p(4), p(5), p(6), p(7), p(8), p(9), p(10), p(11),
        ),
        null, "ok", 0.3, 12,
    )
    private fun notFound() = CourtFinding(null, null, "no hypothesis", null, 5)

    private val fourCorners = listOf(10f to 10f, 1900f to 10f, 1900f to 1000f, 10f to 1000f)

    private fun CourtMarkingViewModel.tapCorners() =
        fourCorners.forEach { (x, y) -> onTap(x, y, 1920f, 1080f, 22f) }

    @Test
    fun detection_fills_an_empty_marking() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { found12() }, fromCorners = { _, _ -> notFound() }))
        vm.state.value.marking!!.isComplete shouldBe true
        vm.state.value.status shouldBe CourtFinderStatus.FOUND
    }

    @Test
    fun detection_after_a_tap_is_discarded() = runTest {
        val gate = CompletableDeferred<CourtFinding>()
        val vm = vm(finder = CourtFinderPort(find = { gate.await() }, fromCorners = { _, _ -> notFound() }))
        vm.state.value.status shouldBe CourtFinderStatus.FINDING
        vm.onTap(10f, 10f, 1920f, 1080f, 22f)
        gate.complete(found12())
        vm.state.value.marking!!.points.size shouldBe 1
        // The coach is marking by hand now: nothing about the finder to say.
        vm.state.value.status shouldBe CourtFinderStatus.IDLE
    }

    @Test
    fun a_failing_finder_falls_back_to_manual() = runTest {
        val log = RecordingLog()
        val vm = vm(
            finder = CourtFinderPort(find = { error("boom") }, fromCorners = { _, _ -> notFound() }),
            log = log,
        )
        vm.state.value.status shouldBe CourtFinderStatus.NOT_FOUND
        log.failures.single().second.message shouldBe "boom"
        vm.onTap(10f, 10f, 1920f, 1080f, 22f)
        vm.state.value.marking!!.points.size shouldBe 1
    }

    @Test
    fun an_out_of_memory_finder_falls_back_to_manual() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { throw OutOfMemoryError("4K") }, fromCorners = { _, _ -> notFound() }))
        vm.state.value.status shouldBe CourtFinderStatus.NOT_FOUND
    }

    @Test
    fun every_answer_is_logged() = runTest {
        val log = RecordingLog()
        vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> notFound() }), log = log)
        log.answers.single().second.reason shouldBe "no hypothesis"
    }

    @Test
    fun the_fourth_corner_asks_for_completion() = runTest {
        var asked: List<CourtPoint>? = null
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, c -> asked = c; found12() }))
        vm.tapCorners()
        asked!!.size shouldBe 4
        vm.state.value.marking!!.isComplete shouldBe true
        vm.state.value.status shouldBe CourtFinderStatus.ALIGNED
    }

    @Test
    fun undo_after_a_completion_returns_the_four_taps_and_does_not_ask_again() = runTest {
        var asks = 0
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> asks++; found12() }))
        vm.tapCorners()
        val taps = fourCorners.map { (x, y) -> CourtPoint(x, y) }

        vm.onUndo()
        vm.state.value.marking!!.points shouldBe taps
        vm.state.value.status shouldBe CourtFinderStatus.IDLE

        vm.onTap(960f, 540f, 1920f, 1080f, 22f)
        vm.state.value.marking!!.points.size shouldBe 5
        asks shouldBe 1
    }

    @Test
    fun a_failed_completion_keeps_the_four_taps() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> notFound() }))
        vm.tapCorners()
        vm.state.value.marking!!.points.size shouldBe 4
        vm.state.value.status shouldBe CourtFinderStatus.ALIGN_FAILED
    }

    @Test
    fun a_tap_before_the_completion_answers_wins() = runTest {
        val gate = CompletableDeferred<CourtFinding>()
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> gate.await() }))
        vm.tapCorners()
        vm.state.value.status shouldBe CourtFinderStatus.ALIGNING

        vm.onTap(960f, 540f, 1920f, 1080f, 22f)
        gate.complete(found12())

        vm.state.value.marking!!.points.size shouldBe 5
        vm.state.value.status shouldBe CourtFinderStatus.IDLE
    }

    @Test
    fun saved_marks_do_not_run_the_finder() = runTest {
        var ran = false
        vm(savedKeypoints = savedKeypoints(), finder = CourtFinderPort(find = { ran = true; found12() }, fromCorners = { _, _ -> notFound() }))
        ran shouldBe false
    }

    @Test
    fun find_court_replaces_saved_marks_as_one_undo_step() = runTest {
        val saved = savedKeypoints().copy(markedAtSeconds = 3.5)
        val vm = vm(savedKeypoints = saved, finder = CourtFinderPort(find = { found12() }, fromCorners = { _, _ -> notFound() }))

        vm.onFindCourt()
        vm.state.value.status shouldBe CourtFinderStatus.FOUND
        vm.state.value.showsSavedMarks shouldBe false
        val found = vm.state.value.marking!!
        found.points[0] shouldBe CourtPoint(100f, 200f)
        // Found on the frame on screen, so stamped with its time.
        found.markedAtSeconds shouldBe 0.1

        vm.onUndo()
        vm.state.value.marking!!.toCourtKeypoints() shouldBe saved
        vm.state.value.status shouldBe CourtFinderStatus.IDLE
    }

    @Test
    fun find_court_that_finds_nothing_leaves_the_marks() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> notFound() }))
        vm.onTap(10f, 10f, 1920f, 1080f, 22f)
        vm.onFindCourt()
        vm.state.value.marking!!.points.size shouldBe 1
        // Not NOT_FOUND: "tap the four corners" is wrong once a point is down.
        vm.state.value.status shouldBe CourtFinderStatus.FIND_FAILED
    }

    @Test
    fun find_court_that_throws_over_marks_leaves_them() = runTest {
        var calls = 0
        val vm = vm(finder = CourtFinderPort(find = { if (calls++ == 0) notFound() else error("boom") }, fromCorners = { _, _ -> notFound() }))
        vm.tapCorners()
        vm.onFindCourt()
        vm.state.value.marking!!.points.size shouldBe 4
        vm.state.value.status shouldBe CourtFinderStatus.FIND_FAILED
    }

    @Test
    fun find_court_that_finds_nothing_on_an_empty_marking_says_tap_the_corners() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, _ -> notFound() }))
        vm.onClear()
        vm.onFindCourt()
        vm.state.value.marking!!.points shouldBe emptyList()
        vm.state.value.status shouldBe CourtFinderStatus.NOT_FOUND
    }

    @Test
    fun find_court_is_ignored_while_the_finder_is_busy() = runTest {
        val gate = CompletableDeferred<CourtFinding>()
        var finds = 0
        val vm = vm(finder = CourtFinderPort(find = { finds++; gate.await() }, fromCorners = { _, _ -> notFound() }))
        vm.onFindCourt()
        finds shouldBe 1
        gate.complete(found12())
        vm.state.value.status shouldBe CourtFinderStatus.FOUND
    }

    @Test
    fun clear_resets_the_finder_status() = runTest {
        val vm = vm(finder = CourtFinderPort(find = { found12() }, fromCorners = { _, _ -> notFound() }))
        vm.onClear()
        vm.state.value.marking!!.points shouldBe emptyList()
        vm.state.value.status shouldBe CourtFinderStatus.IDLE
    }
}
