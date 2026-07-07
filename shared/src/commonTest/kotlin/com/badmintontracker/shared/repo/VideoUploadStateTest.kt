package com.badmintontracker.shared.repo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class VideoUploadStateTest {
    @Test
    fun maps_progress_and_done() {
        toUploadState(progress = 0.25f, isDone = false) shouldBe UploadState.InProgress(0.25f)
        toUploadState(progress = 1f, isDone = true) shouldBe UploadState.Done
    }

    @Test
    fun awaitUploadStates_emits_all_states_and_completes_after_done() = runTest {
        val out = awaitUploadStates(flowOf(0.3f to false, 0.7f to false, 1f to true)).toList()
        out shouldBe listOf(
            UploadState.InProgress(0.3f),
            UploadState.InProgress(0.7f),
            UploadState.Done,
        )
    }

    @Test
    fun awaitUploadStates_does_not_complete_without_a_done_state() = runTest {
        // Regression guard: reporting Done before the transfer finishes triggered
        // processing on a not-yet-uploaded file ("Could not sign video URL").
        shouldThrow<NoSuchElementException> {
            awaitUploadStates(flowOf(0.5f to false, 0.9f to false)).toList()
        }
    }
}
