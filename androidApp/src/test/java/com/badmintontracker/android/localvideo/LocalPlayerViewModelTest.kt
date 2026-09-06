package com.badmintontracker.android.localvideo

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelUsage
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerViewModelTest {

    private val repo = LocalAnnotationsRepository(MapSettings())

    private val netKill = AnnotationLabel(
        id = "l4", name = "Net kill", colorKey = "teal",
        createdAt = Instant.parse("2026-08-24T12:00:00Z"),
    )

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun vm(labels: FakeAnnotationLabelsRepository = FakeAnnotationLabelsRepository(emptyList())) =
        LocalPlayerViewModel("v1", repo, labels)

    @Test
    fun add_label_only_and_note_only_and_both() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "", netKill)             // label only
        vm.addAnnotation(2f, "just a note", null)     // note only
        vm.addAnnotation(3f, "both", netKill)
        vm.state.value.map { it.timestampSeconds } shouldBe listOf(1f, 2f, 3f)
    }

    @Test
    fun add_ignored_when_blank_body_and_no_label() = runTest {
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
                LocalAnnotation(id = "x", timestampSeconds = 2.5f, body = "b", createdAtEpochMs = 0),
            )
            awaitItem() shouldBe 2500L
        }
    }

    @Test
    fun labelOptions_reflects_the_label_repository() = runTest {
        val vm = vm(FakeAnnotationLabelsRepository(listOf(netKill)))
        vm.labelOptions.value shouldBe listOf(netKill)
    }

    @Test
    fun labelOptions_excludes_a_label_scoped_only_to_the_scoreboard() = runTest {
        val boardOnly = AnnotationLabel(
            id = "l9", name = "Serve", colorKey = "blue",
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
            usage = LabelUsage.SCOREBOARD.key,
        )
        val vm = vm(FakeAnnotationLabelsRepository(listOf(netKill, boardOnly)))
        vm.labelOptions.value shouldBe listOf(netKill)
    }

    @Test
    fun errorMessage_is_set_when_label_refresh_fails() = runTest {
        val labels = FakeAnnotationLabelsRepository(emptyList()).apply {
            refreshError = RuntimeException("labels down")
        }
        val vm = vm(labels)
        vm.errorMessage.value shouldBe "labels down"

        vm.errorShown()
        vm.errorMessage.value shouldBe null
    }
}
