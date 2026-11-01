package com.badmintontracker.shared.testing

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
 * A separate file from [IosTestDoubles] and the labels double, for the same
 * reason they are apart: a change on one surface never touches a file with
 * unrelated in-flight hunks on another. Swift cannot implement a Kotlin
 * interface that has `suspend fun` members, so the iOS test bundle builds this
 * through [testPlayersRepository] instead of faking it in Swift.
 */
private class InMemoryPlayersRepository(
    initial: List<Player>,
    /** What a refresh brings back from "the server"; null leaves the list as it is. */
    private val afterRefresh: List<Player>?,
) : PlayersRepository {

    private val state = MutableStateFlow(sortedPlayers(initial))
    private var nextId = 0

    override val players: StateFlow<List<Player>> = state.asStateFlow()

    override suspend fun refresh(): Result<Unit> {
        afterRefresh?.let { state.value = sortedPlayers(it) }
        return Result.success(Unit)
    }

    override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> = runCatching {
        playerNameProblem(name)?.let { throw IllegalArgumentException(it) }
        val row = Player(
            id = "player-${++nextId}",
            ownerId = "test-owner",
            name = name.trim(),
            racketArm = racketArm,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        )
        state.value = sortedPlayers(state.value + row)
        row
    }

    override suspend fun rename(id: String, name: String): Result<Unit> = runCatching {
        playerNameProblem(name)?.let { throw IllegalArgumentException(it) }
        state.value = sortedPlayers(state.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    override suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit> = runCatching {
        state.value = state.value.map { if (it.id == id) it.copy(racketArm = racketArm) else it }
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        state.value = state.value.filterNot { it.id == id }
    }

    private companion object {
        val EPOCH = Instant.parse("2026-10-31T12:00:00Z")
    }
}

/** A [PlayersRepository] the iOS test bundle can build, seeded with [players] up front. */
fun testPlayersRepository(players: List<Player>): PlayersRepository = InMemoryPlayersRepository(players, null)

/**
 * A [PlayersRepository] seeded with [players] whose every refresh succeeds and
 * publishes [afterRefresh], as a refresh does after a player was deleted on
 * another device.
 */
fun testPlayersRepository(players: List<Player>, afterRefresh: List<Player>): PlayersRepository =
    InMemoryPlayersRepository(players, afterRefresh)

/**
 * A [PlayersRepository] with no signal, seeded with [players]: the cached list
 * stays, and every call that would reach the server fails, as supabase-kt does
 * offline. For the failed-load and failed-save paths of the Players screen.
 */
fun testOfflinePlayersRepository(players: List<Player>): PlayersRepository =
    object : PlayersRepository by InMemoryPlayersRepository(players, null) {
        override suspend fun refresh(): Result<Unit> = Result.failure(IllegalStateException("offline"))
        override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> =
            Result.failure(IllegalStateException("offline"))
        override suspend fun rename(id: String, name: String): Result<Unit> =
            Result.failure(IllegalStateException("offline"))
        override suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit> =
            Result.failure(IllegalStateException("offline"))
        override suspend fun delete(id: String): Result<Unit> = Result.failure(IllegalStateException("offline"))
    }
