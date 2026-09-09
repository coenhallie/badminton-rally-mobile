package com.badmintontracker.android.localvideo.court

import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

    private fun vm(
        loadOk: Boolean = true,
        savedKeypoints: CourtKeypoints? = null,
    ) = CourtMarkingViewModel(
        entryId = "e1",
        savedKeypoints = savedKeypoints,
        loadFrame = {
            if (loadOk) CourtFrame(frame = null, width = 1920, height = 1080)
            else error("no frame")
        },
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

    private fun saved() = CourtKeypoints(
        topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
    )

    @Test
    fun opens_on_the_marks_the_entry_was_last_analysed_with() = runTest {
        val saved = saved()

        val marking = vm(savedKeypoints = saved).state.value.marking.shouldNotBeNull()

        marking.isComplete.shouldBeTrue()
        marking.toCourtKeypoints() shouldBe saved
        // Still editable from there: the marks are a starting point, not a
        // read-only record.
        marking.undo().points.size shouldBe 11
    }

    @Test
    fun says_where_preloaded_marks_came_from_until_they_are_edited() = runTest {
        val vm = vm(savedKeypoints = saved())
        vm.state.value.showsSavedMarks.shouldBeTrue()

        // A tap on a complete marking is ignored, so it does not count as an
        // edit; Undo and Clear are the two that do.
        vm.onTap(displayX = 10f, displayY = 10f, displayWidth = 192f, displayHeight = 108f)
        vm.state.value.showsSavedMarks.shouldBeTrue()

        vm.onUndo()
        vm.state.value.showsSavedMarks shouldBe false

        vm(savedKeypoints = saved()).let {
            it.onClear()
            it.state.value.showsSavedMarks shouldBe false
        }

        // Nothing to say when the coach placed the points himself.
        vm().state.value.showsSavedMarks shouldBe false
    }

    @Test
    fun frame_load_failure_sets_error() = runTest {
        vm(loadOk = false).state.value.error shouldBe "no frame"
    }
}
