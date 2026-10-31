package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PlayersRepository {
    /** Last known list, sorted by [sortedPlayers]. Survives a cold start offline. */
    val players: StateFlow<List<Player>>

    suspend fun refresh(): Result<Unit>
    /** [racketArm] null leaves the hand unsaid. */
    suspend fun create(name: String, racketArm: RacketArm?): Result<Player>
    suspend fun rename(id: String, name: String): Result<Unit>
    /** [racketArm] null clears the hand. */
    suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}

/**
 * The coach's players, read and written through Postgrest and cached in
 * [Settings] under the signed-in owner. Shaped like [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl],
 * which carries the long form of the reasoning behind the cache scoping and the
 * cold-start reconciliation.
 */
class PlayersRepositoryImpl internal constructor(
    private val client: SupabaseClient,
    private val settings: Settings,
    private val awaitAuthReady: suspend () -> Unit,
) : PlayersRepository {

    /**
     * The only constructor visible outside this module, and the one the exported
     * Swift/ObjC surface sees. It resolves [awaitAuthReady] to the real
     * client.auth.awaitInitialization(); the internal constructor is the seam a
     * test uses to control that wait.
     */
    constructor(client: SupabaseClient, settings: Settings) :
        this(client, settings, { client.auth.awaitInitialization() })

    private val json = Json { ignoreUnknownKeys = true }

    /** Runs [cacheReconciliation] once. This object lives as long as the process, so it is never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val state = MutableStateFlow(loadCache())
    override val players: StateFlow<List<Player>> = state.asStateFlow()

    /**
     * supabase-kt restores a persisted session asynchronously, so the synchronous
     * [loadCache] in the constructor sees no owner on a real cold start and
     * (fail-safe) publishes nothing. This waits for the owner to be known, then
     * re-reads the cache through the same owner check. compareAndSet only
     * overwrites a still-empty state, so a refresh() that already landed real data
     * is never clobbered by a stale cache read.
     */
    internal val cacheReconciliation: Job = scope.launch {
        runCatching {
            awaitAuthReady()
            val reconciled = loadCache()
            if (reconciled.isNotEmpty()) state.compareAndSet(emptyList(), reconciled)
        }
    }

    @Serializable private data class NewPlayerRow(
        val name: String,
        // No default on purpose: see HandPatch.
        @SerialName("racket_arm") val racketArm: RacketArm?,
    )
    @Serializable private data class NamePatch(val name: String)

    /**
     * supabase-kt's serializer omits a property equal to its default, so a
     * property defaulting to null would drop `"racket_arm":null` and a "clear the
     * hand" PATCH would send an empty body. No default, and the null is sent.
     */
    @Serializable private data class HandPatch(@SerialName("racket_arm") val racketArm: RacketArm?)

    /** The cache envelope, stamped with whoever was signed in when it was written. */
    @Serializable private data class CachedPlayers(
        @SerialName("owner_id") val ownerId: String?,
        val players: List<Player>,
    )

    override suspend fun refresh(): Result<Unit> = runCatching {
        val rows = client.postgrest.from(TABLE)
            .select { order("created_at", Order.ASCENDING) }
            .decodeList<Player>()
        publish(rows)
    }

    override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> {
        playerNameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        return runCatching {
            val row = client.postgrest.from(TABLE)
                .insert(NewPlayerRow(name.trim(), racketArm)) { select() }
                .decodeSingle<Player>()
            publish(state.value + row)
            row
        }
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        playerNameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        val trimmed = name.trim()
        return runCatching {
            client.postgrest.from(TABLE).update(NamePatch(trimmed)) { filter { eq("id", id) } }
            publish(state.value.map { if (it.id == id) it.copy(name = trimmed) else it })
        }
    }

    override suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(HandPatch(racketArm)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(racketArm = racketArm) else it })
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).delete { filter { eq("id", id) } }
        publish(state.value.filterNot { it.id == id })
    }

    private fun publish(next: List<Player>) {
        val sorted = sortedPlayers(next)
        state.value = sorted
        val envelope = CachedPlayers(currentOwnerId(), sorted)
        settings.putString(KEY_CACHE, json.encodeToString(CachedPlayers.serializer(), envelope))
    }

    /**
     * Hands back a cached list only when it was written by whoever is signed in
     * right now. A missing owner on either side is treated as a mismatch: better
     * an empty picker than one coach's players in another coach's app.
     */
    private fun loadCache(): List<Player> {
        val cached = settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedPlayers.serializer(), it) }.getOrNull() }
            ?: return emptyList()
        val owner = currentOwnerId()
        return if (owner != null && owner == cached.ownerId) sortedPlayers(cached.players) else emptyList()
    }

    private fun currentOwnerId(): String? = client.auth.currentUserOrNull()?.id

    private companion object {
        const val TABLE = "players"
        const val KEY_CACHE = "players_cache"
    }
}
