package com.badmintontracker.shared.testing

import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersRepository
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
private class InMemoryPlayersRepository(initial: List<Player>) : PlayersRepository {

    private val state = MutableStateFlow(sortedPlayers(initial))
    private var nextId = 0

    override val players: StateFlow<List<Player>> = state.asStateFlow()

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> = runCatching {
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
fun testPlayersRepository(players: List<Player>): PlayersRepository = InMemoryPlayersRepository(players)
