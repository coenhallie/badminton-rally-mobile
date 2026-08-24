package com.badmintontracker.android.labels

import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
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
        vm.state.value.expandedId shouldBe "l1"

        vm.expand("l2")
        vm.state.value.expandedId shouldBe "l2"

        vm.expand("l2")
        vm.state.value.expandedId.shouldBeNull()
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
}
