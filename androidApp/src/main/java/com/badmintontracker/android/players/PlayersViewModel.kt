package com.badmintontracker.android.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.PlayersRepository
import com.badmintontracker.shared.players.deletePlayerOrMessage
import com.badmintontracker.shared.players.refreshPlayersAndForgetVanishedOrMessage
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayersUiState(
    /** Sorted by name: the repository publishes them through sortedPlayers. */
    val players: List<Player> = emptyList(),
    /**
     * True when the most recent [PlayersViewModel.refresh] failed, so an empty
     * list can say the load failed rather than "add your first player" once the
     * snackbar that reported it has gone.
     */
    val loadFailed: Boolean = false,
    /** A sentence for the snackbar, from PlayersCopy; cleared by errorShown. */
    val errorMessage: String? = null,
)

/**
 * The coach's players for the Players screen and a player's page. Modelled on
 * LabelsViewModel; every sentence it sets comes from PlayersCopy, by way of the
 * shared functions it calls.
 *
 * [currentOwnerId] is whoever is signed in, for the refresh's forget guard: see
 * refreshPlayersAndForgetVanishedOrMessage.
 */
class PlayersViewModel(
    private val players: PlayersRepository,
    private val scoreLogs: ScoreLogsRepository,
    private val localVideos: LocalVideoRepository,
    private val currentOwnerId: () -> String?,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayersUiState())
    val state: StateFlow<PlayersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            players.players.collect { rows -> _state.update { it.copy(players = rows) } }
        }
        refresh()
    }

    /**
     * Through the shared refresh-and-forget rather than the repository's own
     * refresh. A bare refresh would drop a player deleted on another device from
     * the repository's list, and the match list's next refresh, which reads its
     * "before" from that same list, would then never see the player go: the
     * cached logs would keep the dead id and the next sync would be refused.
     */
    fun refresh() {
        viewModelScope.launch {
            val message = refreshPlayersAndForgetVanishedOrMessage(players, scoreLogs, localVideos, currentOwnerId)
            _state.update {
                if (message == null) it.copy(loadFailed = false)
                else it.copy(loadFailed = true, errorMessage = message)
            }
        }
    }

    /**
     * The new player, or null when the save failed (the snackbar says why).
     * Runs on [viewModelScope] and is only awaited here, as LabelsViewModel.create
     * does: a caller whose composition goes away mid-request abandons the wait,
     * not an insert that may already have reached the server.
     */
    suspend fun create(name: String, racketArm: RacketArm?): Player? =
        viewModelScope.async {
            players.create(name, racketArm)
                .onFailure { e -> fail(PlayersCopy.failureMessage(e, PlayersCopy.SAVE_FAILED)) }
                .getOrNull()
        }.await()

    fun rename(id: String, name: String) = save { players.rename(id, name) }

    /** [racketArm] null clears the hand. */
    fun setRacketArm(id: String, racketArm: RacketArm?) = save { players.setRacketArm(id, racketArm) }

    /** The one delete path: the matches keep the name and lose the id, here and on the server. */
    fun delete(player: Player) {
        viewModelScope.launch {
            deletePlayerOrMessage(players, scoreLogs, localVideos, player)?.let(::fail)
        }
    }

    fun errorShown() = _state.update { it.copy(errorMessage = null) }

    private fun save(op: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            op().onFailure { e -> fail(PlayersCopy.failureMessage(e, PlayersCopy.SAVE_FAILED)) }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(errorMessage = message) }
}
