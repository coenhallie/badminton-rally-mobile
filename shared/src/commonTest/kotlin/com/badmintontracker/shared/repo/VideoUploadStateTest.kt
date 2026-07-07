package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoUploadStateTest {
    @Test
    fun maps_progress_and_done() {
        toUploadState(progress = 0.25f, isDone = false) shouldBe UploadState.InProgress(0.25f)
        toUploadState(progress = 1f, isDone = true) shouldBe UploadState.Done
    }
}
