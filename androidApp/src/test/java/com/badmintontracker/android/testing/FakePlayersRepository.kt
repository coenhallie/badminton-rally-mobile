package com.badmintontracker.android.testing

import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersRepository
import com.badmintontracker.shared.players.playerNameProblem
import com.badmintontracker.shared.players.sortedPlayers
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * In-memory double for [PlayersRepository]. Applies the same name rule as the
 * real one and records what was asked of it. Set [nextError] to make the next
 * call fail with it (it is cleared by that call), for the failure paths.
 */
class FakePlayersRepository(initial: List<Player> = emptyList()) : PlayersRepository {

    private val state = MutableStateFlow(sortedPlayers(initial))
    override val players: StateFlow<List<Player>> = state.asStateFlow()

    /** Each create, as the name saved (trimmed) and the hand. */
    val created = mutableListOf<Pair<String, RacketArm?>>()
    /** Each rename, as the id and the name saved (trimmed). */
    val renamed = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    var nextError: Throwable? = null

    private var nextId = 0

    private fun takeError(): Throwable? = nextError.also { nextError = null }

    override suspend fun refresh(): Result<Unit> =
        takeError()?.let { Result.failure(it) } ?: Result.success(Unit)

    override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> {
        playerNameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        takeError()?.let { return Result.failure(it) }
        val row = Player(
            id = "player-${++nextId}",
            ownerId = "owner",
            name = name.trim(),
            racketArm = racketArm,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        )
        created += row.name to racketArm
        state.value = sortedPlayers(state.value + row)
        return Result.success(row)
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        playerNameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        takeError()?.let { return Result.failure(it) }
        val trimmed = name.trim()
        renamed += id to trimmed
        state.value = sortedPlayers(state.value.map { if (it.id == id) it.copy(name = trimmed) else it })
        return Result.success(Unit)
    }

    override suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit> {
        takeError()?.let { return Result.failure(it) }
        state.value = state.value.map { if (it.id == id) it.copy(racketArm = racketArm) else it }
        return Result.success(Unit)
    }

    override suspend fun delete(id: String): Result<Unit> {
        takeError()?.let { return Result.failure(it) }
        deleted += id
        state.value = state.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    private companion object {
        val EPOCH = Instant.parse("2026-10-31T12:00:00Z")
    }
}
