package com.badmintontracker.android.share

import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ShareError
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

class ShareSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    @Test
    fun init_loads_existing_recipients() = runTest {
        val shares = FakeSharesRepository().apply {
            sharesByVideo = mapOf("v1" to listOf(
                MatchShare("u1", "a@x", Instant.parse("2026-05-06T12:00:00Z")),
            ))
        }
        val vm = ShareSheetViewModel(videoId = "v1", shares = shares)
        advanceUntilIdle()
        vm.state.value.recipients.map { it.email } shouldBe listOf("a@x")
    }

    @Test
    fun share_success_clears_email_and_refreshes_list() = runTest {
        val shares = FakeSharesRepository()
        val vm = ShareSheetViewModel("v1", shares)
        vm.onEmailChange("coach@example.com")
        shares.sharesByVideo = mapOf("v1" to listOf(
            MatchShare("u1", "coach@example.com", Instant.parse("2026-05-06T12:00:00Z")),
        ))
        vm.onShareClicked()
        advanceUntilIdle()
        vm.state.value.email shouldBe ""
        vm.state.value.error shouldBe null
        vm.state.value.recipients.map { it.email } shouldBe listOf("coach@example.com")
        shares.shareCalls shouldBe listOf("v1" to "coach@example.com")
    }

    @Test
    fun share_no_such_user_sets_user_facing_error() = runTest {
        val shares = FakeSharesRepository().apply {
            nextShareResult = Result.failure(ShareError.NoSuchUser)
        }
        val vm = ShareSheetViewModel("v1", shares)
        vm.onEmailChange("ghost@example.com")
        vm.onShareClicked()
        advanceUntilIdle()
        vm.state.value.error shouldBe "No Shuttl user found with that email."
    }

    @Test
    fun unshare_calls_repo_and_refreshes() = runTest {
        val shares = FakeSharesRepository().apply {
            sharesByVideo = mapOf("v1" to listOf(
                MatchShare("u1", "a@x", Instant.parse("2026-05-06T12:00:00Z")),
            ))
        }
        val vm = ShareSheetViewModel("v1", shares)
        advanceUntilIdle()
        shares.sharesByVideo = mapOf("v1" to emptyList())
        vm.onUnshare("u1")
        advanceUntilIdle()
        shares.unshareCalls shouldBe listOf("v1" to "u1")
        vm.state.value.recipients shouldBe emptyList()
    }
}
