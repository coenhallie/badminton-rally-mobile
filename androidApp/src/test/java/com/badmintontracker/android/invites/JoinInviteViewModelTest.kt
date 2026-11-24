package com.badmintontracker.android.invites

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakePlayerAccessRepository
import com.badmintontracker.shared.invites.InviteErrorKind
import com.badmintontracker.shared.invites.InviteException
import com.badmintontracker.shared.invites.InvitePreview
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinInviteViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val preview = InvitePreview(
        playerName = "Marco",
        racketArm = RacketArm.RIGHT,
        coachEmail = "coach@example.com",
    )

    @Test
    fun qr_scan_populates_and_verifies_valid_payload() = runTest {
        val repo = FakePlayerAccessRepository().apply {
            nextPreviewResult = Result.success(preview)
        }
        val vm = JoinInviteViewModel(repo, FakeAuthRepository())

        vm.onQrScanned("shuttl-invite:k7m4qp9r")
        advanceUntilIdle()

        vm.state.value.codeInput shouldBe "K7M4-QP9R"
        vm.state.value.verifiedCode shouldBe "K7M4QP9R"
        vm.state.value.preview shouldBe preview
        vm.state.value.error shouldBe null
        repo.previewCalls shouldBe listOf("K7M4QP9R")
    }

    @Test
    fun qr_scan_rejects_unrelated_qr_without_network_call() = runTest {
        val repo = FakePlayerAccessRepository()
        val vm = JoinInviteViewModel(repo, FakeAuthRepository())

        vm.onQrScanned("https://example.com/menu")
        advanceUntilIdle()

        vm.state.value.error shouldBe PlayerInviteCopy.JOIN_QR_INVALID
        vm.state.value.preview shouldBe null
        repo.previewCalls shouldBe emptyList()
    }

    @Test
    fun create_account_happy_path_emits_completed() = runTest {
        val repo = FakePlayerAccessRepository().apply {
            nextPreviewResult = Result.success(preview)
        }
        val vm = JoinInviteViewModel(repo, FakeAuthRepository())
        vm.onCodeChange("k7m4-qp9r")
        vm.verifyCode()
        advanceUntilIdle()

        vm.onEmailChange("marco@example.com")
        vm.onPasswordChange("strongpass123")

        vm.events.test {
            vm.submit()
            awaitItem() shouldBe JoinInviteEvent.Completed
            cancelAndIgnoreRemainingEvents()
        }
        repo.redeemNewCalls shouldBe listOf(Triple("K7M4QP9R", "marco@example.com", "strongpass123"))
    }

    @Test
    fun email_exists_offers_switch_to_sign_in_keeping_email_prefilled() = runTest {
        val repo = FakePlayerAccessRepository().apply {
            nextPreviewResult = Result.success(preview)
            nextRedeemNewResult = Result.failure(InviteException(InviteErrorKind.EMAIL_EXISTS))
        }
        val vm = JoinInviteViewModel(repo, FakeAuthRepository())
        vm.onCodeChange("K7M4QP9R")
        vm.verifyCode()
        advanceUntilIdle()

        vm.onEmailChange("marco@example.com")
        vm.onPasswordChange("strongpass123")
        vm.submit()
        advanceUntilIdle()

        vm.state.value.offerSignInSwitch shouldBe true
        vm.state.value.error shouldBe PlayerInviteCopy.errorMessage(InviteErrorKind.EMAIL_EXISTS)

        vm.switchAuthMode(JoinAuthMode.SIGN_IN)
        vm.state.value.authMode shouldBe JoinAuthMode.SIGN_IN
        vm.state.value.email shouldBe "marco@example.com"
        vm.state.value.offerSignInSwitch shouldBe false
        vm.state.value.error shouldBe null
    }

    @Test
    fun sign_in_and_link_signs_in_then_redeems_authenticated() = runTest {
        val repo = FakePlayerAccessRepository().apply {
            nextPreviewResult = Result.success(preview)
        }
        val auth = FakeAuthRepository().apply {
            currentUserIdValue = null
            nextEmailResult = Result.success(Unit)
        }
        val vm = JoinInviteViewModel(repo, auth)
        vm.onCodeChange("K7M4QP9R")
        vm.verifyCode()
        advanceUntilIdle()

        vm.switchAuthMode(JoinAuthMode.SIGN_IN)
        vm.onEmailChange("marco@example.com")
        vm.onPasswordChange("secret123")

        vm.events.test {
            vm.submit()
            awaitItem() shouldBe JoinInviteEvent.Completed
            cancelAndIgnoreRemainingEvents()
        }
        auth.emailCalls shouldBe listOf("marco@example.com" to "secret123")
        repo.redeemExistingCalls shouldBe listOf("K7M4QP9R")
    }

    @Test
    fun editing_code_after_verification_clears_preview() = runTest {
        val repo = FakePlayerAccessRepository().apply {
            nextPreviewResult = Result.success(preview)
        }
        val vm = JoinInviteViewModel(repo, FakeAuthRepository())
        vm.onCodeChange("K7M4QP9R")
        vm.verifyCode()
        advanceUntilIdle()
        vm.state.value.preview shouldBe preview

        vm.onCodeChange("K7M4QP9")
        vm.state.value.preview shouldBe null
        vm.state.value.verifiedCode shouldBe null
    }
}
