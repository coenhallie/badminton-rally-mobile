package com.badmintontracker.android.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.repo.AuthRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

sealed interface SignInEvent {
    data object SignedIn : SignInEvent
}

class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    val state  = MutableStateFlow(SignInState())
    val events = MutableSharedFlow<SignInEvent>(extraBufferCapacity = 1)

    fun onEmailChange(v: String)    { state.update { it.copy(email = v, error = null) } }
    fun onPasswordChange(v: String) { state.update { it.copy(password = v, error = null) } }

    fun submitEmail() {
        viewModelScope.launch {
            state.update { it.copy(isSubmitting = true, error = null) }
            auth.signInEmail(state.value.email.trim(), state.value.password)
                .onSuccess {
                    events.tryEmit(SignInEvent.SignedIn)
                    state.update { it.copy(isSubmitting = false) }
                }
                .onFailure { e ->
                    state.update { it.copy(isSubmitting = false, error = friendlyAuthError(e)) }
                }
        }
    }
}
