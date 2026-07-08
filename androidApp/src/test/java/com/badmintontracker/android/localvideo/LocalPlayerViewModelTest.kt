package com.badmintontracker.android.localvideo

import app.cash.turbine.test
import com.badmintontracker.shared.model.AnnotationKind
import com.russhwolf.settings.MapSettings
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
class LocalPlayerViewModelTest {

    private val repo = LocalAnnotationsRepository(MapSettings())

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun vm() = LocalPlayerViewModel("v1", repo)

    @Test
    fun add_kind_only_and_note_only_and_both() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "", AnnotationKind.GOOD_SHOT)       // kind only
        vm.addAnnotation(2f, "just a note", null)               // note only
        vm.addAnnotation(3f, "both", AnnotationKind.FORCED_ERROR)
        vm.state.value.map { it.timestampSeconds } shouldBe listOf(1f, 2f, 3f)
    }

    @Test
    fun add_ignored_when_blank_body_and_no_kind() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "   ", null)
        vm.state.value shouldBe emptyList()
    }

    @Test
    fun add_trims_body_and_coerces_negative_timestamp() = runTest {
        val vm = vm()
        vm.addAnnotation(-5f, "  hi  ", null)
        val a = vm.state.value.single()
        a.body shouldBe "hi"
        a.timestampSeconds shouldBe 0f
    }

    @Test
    fun delete_removes_annotation() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "a", null)
        val id = vm.state.value.single().id
        vm.deleteAnnotation(id)
        vm.state.value shouldBe emptyList()
    }

    @Test
    fun state_is_sorted_by_timestamp() = runTest {
        val vm = vm()
        vm.addAnnotation(30f, "late", null)
        vm.addAnnotation(5f, "early", null)
        vm.state.value.map { it.body } shouldBe listOf("early", "late")
    }

    @Test
    fun onAnnotationTap_emits_milliseconds() = runTest {
        val vm = vm()
        vm.seekTo.test {
            vm.onAnnotationTap(
                LocalAnnotation(id = "x", timestampSeconds = 2.5f, body = "b", kind = null, createdAtEpochMs = 0),
            )
            awaitItem() shouldBe 2500L
        }
    }
}
