package com.badmintontracker.android.signin

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    @Test
    fun email_change_updates_state_and_clears_error() = runTest {
        val auth = FakeAuthRepository()
        val vm = SignInViewModel(auth)

        vm.onEmailChange("a@b.co")

        vm.state.value.email shouldBe "a@b.co"
        vm.state.value.error shouldBe null
    }

    @Test
    fun submitEmail_success_emits_SignedIn_event() = runTest {
        val auth = FakeAuthRepository().apply { nextEmailResult = Result.success(Unit) }
        val vm = SignInViewModel(auth)
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("secret")

        vm.events.test {
            vm.submitEmail()
            awaitItem() shouldBe SignInEvent.SignedIn
            cancelAndIgnoreRemainingEvents()
        }
        auth.emailCalls shouldBe listOf("a@b.co" to "secret")
        vm.state.value.isSubmitting shouldBe false
    }

    @Test
    fun submitEmail_failure_surfaces_error_and_no_event() = runTest {
        val auth = FakeAuthRepository().apply {
            nextEmailResult = Result.failure(IllegalStateException("bad creds"))
        }
        val vm = SignInViewModel(auth)
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("secret")

        vm.submitEmail()
        dispatcher.scheduler.advanceUntilIdle()

        vm.state.value.error shouldBe "Something went wrong. Please check your connection and try again."
        vm.state.value.isSubmitting shouldBe false
    }
}
