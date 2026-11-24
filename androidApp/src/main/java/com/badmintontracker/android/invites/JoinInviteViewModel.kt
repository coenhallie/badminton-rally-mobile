package com.badmintontracker.android.invites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.invites.InviteErrorKind
import com.badmintontracker.shared.invites.InviteException
import com.badmintontracker.shared.invites.InvitePreview
import com.badmintontracker.shared.invites.PlayerAccessRepository
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.formatInviteCode
import com.badmintontracker.shared.invites.formatInviteInput
import com.badmintontracker.shared.invites.normalizeInviteCode
import com.badmintontracker.shared.invites.parseQrInvitePayload
import com.badmintontracker.shared.repo.AuthRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class JoinAuthMode { CREATE_ACCOUNT, SIGN_IN }

data class JoinInviteUiState(
    val codeInput: String = "",
    val preview: InvitePreview? = null,
    val verifiedCode: String? = null,
    val authMode: JoinAuthMode = JoinAuthMode.CREATE_ACCOUNT,
    val email: String = "",
    val password: String = "",
    val isCheckingCode: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val offerSignInSwitch: Boolean = false,
)

sealed interface JoinInviteEvent {
    data object Completed : JoinInviteEvent
}

class JoinInviteViewModel(
    private val playerAccess: PlayerAccessRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(JoinInviteUiState())
    val state: StateFlow<JoinInviteUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<JoinInviteEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<JoinInviteEvent> = _events.asSharedFlow()

    fun onCodeChange(raw: String) {
        val formatted = formatInviteInput(raw)
        val normalized = normalizeInviteCode(formatted)
        _state.update { current ->
            val keepPreview = current.verifiedCode != null && normalized == current.verifiedCode
            current.copy(
                codeInput = formatted,
                preview = if (keepPreview) current.preview else null,
                verifiedCode = if (keepPreview) current.verifiedCode else null,
                error = null,
                offerSignInSwitch = false,
            )
        }
    }

    fun onQrScanned(raw: String) {
        val parsed = parseQrInvitePayload(raw)
        if (parsed == null) {
            _state.update { it.copy(error = PlayerInviteCopy.JOIN_QR_INVALID, offerSignInSwitch = false) }
            return
        }
        _state.update {
            it.copy(
                codeInput = formatInviteCode(parsed),
                preview = null,
                verifiedCode = null,
                error = null,
                offerSignInSwitch = false,
            )
        }
        verifyCode()
    }

    fun onQrUnavailable() {
        _state.update { it.copy(error = PlayerInviteCopy.JOIN_QR_UNAVAILABLE, offerSignInSwitch = false) }
    }

    fun verifyCode() {
        val current = _state.value
        if (current.isCheckingCode || current.isSubmitting) return
        val normalized = normalizeInviteCode(current.codeInput)
        if (normalized == null) {
            _state.update {
                it.copy(
                    error = PlayerInviteCopy.errorMessage(InviteErrorKind.INVALID_OR_EXPIRED),
                    offerSignInSwitch = false,
                )
            }
            return
        }
        _state.update { it.copy(isCheckingCode = true, error = null, offerSignInSwitch = false) }
        viewModelScope.launch {
            playerAccess.previewInvite(normalized)
                .onSuccess { preview ->
                    _state.update {
                        it.copy(
                            codeInput = formatInviteCode(normalized),
                            preview = preview,
                            verifiedCode = normalized,
                            isCheckingCode = false,
                            error = null,
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isCheckingCode = false,
                            preview = null,
                            verifiedCode = null,
                            error = err.toInviteDisplayMessage(),
                        )
                    }
                }
        }
    }

    fun onEmailChange(value: String) {
        _state.update { it.copy(email = value, error = null, offerSignInSwitch = false) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null, offerSignInSwitch = false) }
    }

    fun switchAuthMode(mode: JoinAuthMode) {
        _state.update {
            it.copy(
                authMode = mode,
                error = null,
                offerSignInSwitch = false,
            )
        }
    }

    fun submit() {
        val current = _state.value
        val code = current.verifiedCode ?: return
        if (current.isSubmitting || current.isCheckingCode) return

        val email = current.email.trim()
        val password = current.password
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(error = "Enter your email and password.", offerSignInSwitch = false) }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null, offerSignInSwitch = false) }
        viewModelScope.launch {
            when (current.authMode) {
                JoinAuthMode.CREATE_ACCOUNT -> {
                    playerAccess.redeemInviteNewAccount(code, email, password)
                        .onSuccess {
                            _state.update { it.copy(isSubmitting = false) }
                            _events.tryEmit(JoinInviteEvent.Completed)
                        }
                        .onFailure { err ->
                            val kind = (err as? InviteException)?.kind
                            _state.update {
                                it.copy(
                                    isSubmitting = false,
                                    error = err.toInviteDisplayMessage(),
                                    offerSignInSwitch = kind == InviteErrorKind.EMAIL_EXISTS,
                                )
                            }
                        }
                }
                JoinAuthMode.SIGN_IN -> {
                    val needsSignIn = auth.currentUserId() == null ||
                        !auth.currentUserEmail().equals(email, ignoreCase = true)
                    if (needsSignIn) {
                        val signInResult = auth.signInEmail(email, password)
                        if (signInResult.isFailure) {
                            val err = signInResult.exceptionOrNull()
                            _state.update {
                                it.copy(
                                    isSubmitting = false,
                                    error = err?.let(::friendlyAuthError)
                                        ?: PlayerInviteCopy.errorMessage(InviteErrorKind.UNKNOWN),
                                )
                            }
                            return@launch
                        }
                    }
                    playerAccess.redeemInviteExistingAccount(code)
                        .onSuccess {
                            _state.update { it.copy(isSubmitting = false) }
                            _events.tryEmit(JoinInviteEvent.Completed)
                        }
                        .onFailure { err ->
                            _state.update {
                                it.copy(
                                    isSubmitting = false,
                                    error = err.toInviteDisplayMessage(),
                                )
                            }
                        }
                }
            }
        }
    }
}
