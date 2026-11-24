package com.badmintontracker.android.invites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.invites.CreatedInvite
import com.badmintontracker.shared.invites.InviteErrorKind
import com.badmintontracker.shared.invites.InviteException
import com.badmintontracker.shared.invites.LinkedPlayerProfile
import com.badmintontracker.shared.invites.PlayerAccessRepository
import com.badmintontracker.shared.invites.PlayerAccessState
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.RedeemedInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerAccessUiState(
    val accessByPlayerId: Map<String, PlayerAccessState> = emptyMap(),
    val linkedProfiles: List<LinkedPlayerProfile> = emptyList(),
    val linkedProfilesLoaded: Boolean = false,
    val busyPlayerId: String? = null,
    val errorMessage: String? = null,
)

class PlayerAccessViewModel(
    private val repo: PlayerAccessRepository,
) : ViewModel() {

    private val busyPlayerId = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<PlayerAccessUiState> = combine(
        repo.accessByPlayerId,
        repo.linkedProfiles,
        repo.linkedProfilesLoaded,
        busyPlayerId,
        errorMessage,
        ::PlayerAccessUiState,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PlayerAccessUiState(
            accessByPlayerId = repo.accessByPlayerId.value,
            linkedProfiles = repo.linkedProfiles.value,
            linkedProfilesLoaded = repo.linkedProfilesLoaded.value,
        ),
    )

    fun refreshAccessStates() {
        viewModelScope.launch { repo.refreshAccessStates() }
    }

    fun refreshLinkedProfiles() {
        viewModelScope.launch { repo.refreshLinkedProfiles() }
    }

    suspend fun createInvite(playerId: String): Result<CreatedInvite> {
        busyPlayerId.value = playerId
        return try {
            repo.createInvite(playerId).onFailure { err ->
                errorMessage.value = err.toInviteDisplayMessage()
            }
        } finally {
            if (busyPlayerId.value == playerId) busyPlayerId.value = null
        }
    }

    suspend fun revokeInvite(playerId: String): Result<Unit> {
        busyPlayerId.value = playerId
        return try {
            repo.revokeInvite(playerId).onFailure { err ->
                errorMessage.value = err.toInviteDisplayMessage()
            }
        } finally {
            if (busyPlayerId.value == playerId) busyPlayerId.value = null
        }
    }

    suspend fun unlinkPlayer(playerId: String): Result<Unit> {
        busyPlayerId.value = playerId
        return try {
            repo.unlinkPlayer(playerId).onFailure { err ->
                errorMessage.value = err.toInviteDisplayMessage()
            }
        } finally {
            if (busyPlayerId.value == playerId) busyPlayerId.value = null
        }
    }

    suspend fun redeemExistingCode(code: String): Result<RedeemedInvite> =
        repo.redeemInviteExistingAccount(code)

    fun dismissError() {
        errorMessage.value = null
    }
}

internal fun Throwable.toInviteDisplayMessage(): String =
    (this as? InviteException)?.message ?: PlayerInviteCopy.errorMessage(InviteErrorKind.UNKNOWN)
