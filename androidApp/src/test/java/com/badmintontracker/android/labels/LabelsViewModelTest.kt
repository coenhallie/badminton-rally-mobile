package com.badmintontracker.android.labels

import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

class LabelsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private fun label(id: String, name: String, key: String) = AnnotationLabel(
        id = id, name = name, colorKey = key,
        createdAt = Instant.parse("2026-08-24T12:00:00Z"),
    )

    @Test
    fun publishes_the_repository_list() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.state.value.labels shouldHaveSize 1
    }

    @Test
    fun expanding_a_row_collapses_the_previous_one() {
        val vm = LabelsViewModel(FakeAnnotationLabelsRepository(emptyList()))

        vm.expand("l1")
        vm.state.value.expanded shouldBe LabelEditTarget.Existing("l1")

        vm.expand("l2")
        vm.state.value.expanded shouldBe LabelEditTarget.Existing("l2")

        vm.expand("l2")
        vm.state.value.expanded.shouldBeNull()
    }

    @Test
    fun starting_creation_closes_an_open_row_and_is_its_own_toggle() {
        val vm = LabelsViewModel(FakeAnnotationLabelsRepository(emptyList()))

        vm.expand("l1")
        vm.state.value.expanded shouldBe LabelEditTarget.Existing("l1")

        vm.startCreating()
        vm.state.value.expanded shouldBe LabelEditTarget.New

        vm.startCreating()
        vm.state.value.expanded.shouldBeNull()
    }

    @Test
    fun expanding_a_row_closes_an_open_draft() {
        val vm = LabelsViewModel(FakeAnnotationLabelsRepository(emptyList()))

        vm.startCreating()
        vm.state.value.expanded shouldBe LabelEditTarget.New

        vm.expand("l1")
        vm.state.value.expanded shouldBe LabelEditTarget.Existing("l1")
    }

    @Test
    fun a_successful_create_opens_the_new_labels_own_editor_and_reports_success() = runTest {
        val vm = LabelsViewModel(FakeAnnotationLabelsRepository(emptyList()))
        advanceUntilIdle()

        vm.startCreating()
        val succeeded = vm.create("Smash winner", LabelColor.PURPLE, LabelUsage.BOTH)
        advanceUntilIdle()

        succeeded shouldBe true
        val created = vm.state.value.labels.single()
        vm.state.value.expanded shouldBe LabelEditTarget.Existing(created.id)
    }

    @Test
    fun a_rejected_create_leaves_the_draft_open_and_reports_failure() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.startCreating()
        val succeeded = vm.create("good shot", LabelColor.PURPLE, LabelUsage.BOTH)
        advanceUntilIdle()

        // This return value is what lets DraftLabelRow's CommitGuard roll
        // itself back on failure - without it, a corrected retry of the same
        // name would be indistinguishable from the commit that just failed
        // and would be silently dropped.
        succeeded shouldBe false
        vm.state.value.expanded shouldBe LabelEditTarget.New
        vm.state.value.errorMessage shouldBe "You already have a label called \"good shot\"."
    }

    @Test
    fun a_rejected_rename_surfaces_a_short_message_and_leaves_the_list_alone() = runTest {
        val repo = FakeAnnotationLabelsRepository(
            listOf(label("l1", "Good shot", "green"), label("l2", "Forced error", "amber"))
        )
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.rename("l2", "good shot")
        advanceUntilIdle()

        vm.state.value.errorMessage shouldBe "You already have a label called \"good shot\"."
        vm.state.value.labels.first { it.id == "l2" }.name shouldBe "Forced error"
    }

    @Test
    fun delete_removes_the_label() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.delete("l1")
        advanceUntilIdle()

        vm.state.value.labels.shouldHaveSize(0)
    }

    @Test
    fun create_adds_a_label_with_the_chosen_color() = runTest {
        val repo = FakeAnnotationLabelsRepository(emptyList())
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.create("Smash winner", LabelColor.PURPLE, LabelUsage.BOTH)
        advanceUntilIdle()

        val created = vm.state.value.labels.single()
        created.name shouldBe "Smash winner"
        created.colorKey shouldBe LabelColor.PURPLE.key
    }

    @Test
    fun a_failed_refresh_surfaces_the_fallback_message_and_marks_the_load_failed() = runTest {
        val repo = FakeAnnotationLabelsRepository(emptyList())
        repo.refreshError = RuntimeException()
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.state.value.errorMessage shouldBe "Couldn't load your labels"
        vm.state.value.loadFailed shouldBe true
    }

    @Test
    fun recolor_updates_the_labels_color() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.recolor("l1", LabelColor.TEAL)
        advanceUntilIdle()

        vm.state.value.labels.first { it.id == "l1" }.colorKey shouldBe LabelColor.TEAL.key
    }

    @Test
    fun setting_a_scope_writes_it_through() = runTest {
        val goodShot = label("l1", "Good shot", "green")
        val repo = FakeAnnotationLabelsRepository(listOf(goodShot))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.setUsage(goodShot.id, LabelUsage.SCOREBOARD)
        advanceUntilIdle()

        vm.state.value.labels.single().scope shouldBe LabelUsage.SCOREBOARD
        repo.scoreboardLabels.value.map { it.id } shouldBe listOf(goodShot.id)
        repo.clipLabels.value.shouldBeEmpty()
    }

    @Test
    fun creating_a_label_carries_its_scope() = runTest {
        val repo = FakeAnnotationLabelsRepository()
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.create("Serve", LabelColor.BLUE, LabelUsage.SCOREBOARD) shouldBe true
        advanceUntilIdle()

        repo.labels.value.single().scope shouldBe LabelUsage.SCOREBOARD
    }

    @Test
    fun errorShown_clears_the_error_message() = runTest {
        val repo = FakeAnnotationLabelsRepository(
            listOf(label("l1", "Good shot", "green"), label("l2", "Forced error", "amber"))
        )
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.rename("l2", "good shot")
        advanceUntilIdle()
        vm.state.value.errorMessage shouldBe "You already have a label called \"good shot\"."

        vm.errorShown()

        vm.state.value.errorMessage.shouldBeNull()
    }
}
