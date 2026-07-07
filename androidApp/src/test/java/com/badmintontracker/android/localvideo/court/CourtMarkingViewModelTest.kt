package com.badmintontracker.android.localvideo.court

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

    private fun vm(loadOk: Boolean = true) = CourtMarkingViewModel(
        entryId = "e1",
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

    @Test
    fun frame_load_failure_sets_error() = runTest {
        vm(loadOk = false).state.value.error shouldBe "no frame"
    }
}
