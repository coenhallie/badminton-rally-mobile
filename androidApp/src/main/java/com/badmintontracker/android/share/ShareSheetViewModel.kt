package com.badmintontracker.android.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareSheetState(
    val email: String = "",
    val recipients: List<MatchShare> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
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
        _state.update { it.copy(email = value, error = null) }
    }

    fun onShareClicked() {
        if (_state.value.isBusy) return
        val email = _state.value.email.trim()
        if (email.isEmpty()) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            shares.share(videoId, email)
                .onSuccess {
                    _state.update { it.copy(email = "", isBusy = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = (e as? ShareError).userMessage(),
                        )
                    }
                }
        }
    }

    fun onUnshare(userId: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            shares.unshare(videoId, userId)
                .onSuccess {
                    _state.update { it.copy(isBusy = false) }
                    refresh()
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = "Couldn't remove access — please try again.",
                        )
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            shares.listShares(videoId)
                .onSuccess { list ->
                    _state.update { it.copy(recipients = list, isLoading = false, loadError = null) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            loadError = "Couldn't load people with access.",
                        )
                    }
                }
        }
    }
}
