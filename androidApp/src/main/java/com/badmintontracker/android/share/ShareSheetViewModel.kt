package com.badmintontracker.android.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShareSheetState(
    val email: String = "",
    val recipients: List<MatchShare> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
)

class ShareSheetViewModel(
    private val videoId: String,
    private val shares: SharesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareSheetState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, error = null)
    }

    fun onShareClicked() {
        val email = _state.value.email.trim()
        if (email.isEmpty()) return
        _state.value = _state.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            shares.share(videoId, email)
                .onSuccess {
                    _state.value = _state.value.copy(email = "", isBusy = false)
                    refresh()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        error = (e as? ShareError).toMessage(),
                    )
                }
        }
    }

    fun onUnshare(userId: String) {
        viewModelScope.launch {
            shares.unshare(videoId, userId).onSuccess { refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            shares.listShares(videoId).onSuccess {
                _state.value = _state.value.copy(recipients = it)
            }
        }
    }
}

private fun ShareError?.toMessage(): String = when (this) {
    ShareError.NotOwner        -> "You can only share matches you uploaded."
    ShareError.NoSuchUser      -> "No Shuttl user found with that email."
    ShareError.CannotShareSelf -> "You can't share a match with yourself."
    is ShareError.Unknown      -> "Could not share — please try again."
    null                       -> "Unknown error."
}
