package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every match this account has scored, local first.
 *
 * Local first is not an optimisation here. A coach scores in a sports hall with no
 * signal, on a phone that goes in a bag between games, so every mutation lands in
 * memory and on disk synchronously and nothing between rallies awaits a network
 * call. [sync] is the only suspending write.
 *
 * Registry pattern from [com.badmintontracker.shared.localvideo.LocalVideoRepository];
 * owner-scoped cache envelope from
 * [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl].
 */
class ScoreLogsRepository internal constructor(
    /**
     * Null only in tests of the local half, which never reach the network. Every
     * suspending method on this class returns a failed Result rather than throwing
     * when it is null, so a mis-wired app graph surfaces as a sync error and not as
     * a crash between rallies.
     */
    private val client: SupabaseClient?,
    private val settings: Settings,
    private val now: () -> Instant,
    /**
     * Whose matches these are. A seam for the same reason
     * [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl] has one:
     * the real answer comes from supabase-kt's asynchronously restored session,
     * which a test cannot control, and the owner decides whether the cache is
     * readable at all.
     */
    private val ownerId: () -> String?,
) {

    /**
     * The only constructor visible outside this module, and the only one the
     * exported Swift surface sees.
     */
    constructor(client: SupabaseClient, settings: Settings, now: () -> Instant) :
        this(client, settings, now, { client.auth.currentUserOrNull()?.id })

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A stored match plus whether this device has changes the server has not seen.
     * [dirty] rather than a timestamp comparison: the server stamps updated_at from
     * its own clock, and a courtside phone's clock is not something to arbitrate on.
     */
    @Serializable
    private data class StoredLog(val log: ScoreLog, val dirty: Boolean)

    @Serializable
    private data class CachedLogs(
        @SerialName("owner_id") val ownerId: String?,
        val logs: List<StoredLog>,
    )

    private var stored: List<StoredLog> = loadStored()

    private val state = MutableStateFlow(stored.map { it.log })

    /** Newest first, which is the order the match list renders. */
    val logs: StateFlow<List<ScoreLog>> = state.asStateFlow()

    // Serializes read-modify-write cycles. The scoring surface writes from the main
    // thread and sync writes from a background dispatcher.
    private val lock = SyncLock()

    fun get(id: String): ScoreLog? = state.value.firstOrNull { it.id == id }

    fun create(
        title: String,
        homePlayers: List<String>,
        awayPlayers: List<String>,
        rules: ScoringRules,
        setup: MatchSetup,
    ): ScoreLog {
        val timestamp = now()
        val log = ScoreLog(
            id = randomUuid(),
            videoId = null,
            title = title.trim(),
            homePlayers = homePlayers.map { it.trim() }.filter { it.isNotEmpty() },
            awayPlayers = awayPlayers.map { it.trim() }.filter { it.isNotEmpty() },
            rules = rules,
            setup = setup,
            events = emptyList(),
            status = ScoreLogStatus.LIVE,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        mutate { it + StoredLog(log, dirty = true) }
        return log
    }

    fun replaceEvents(id: String, events: List<ScoreEvent>) =
        edit(id) { it.copy(events = events) }

    fun rename(id: String, title: String) =
        edit(id) { it.copy(title = title.trim()) }

    fun finish(id: String) =
        edit(id) { it.copy(status = ScoreLogStatus.UNBOUND) }

    /** Drops a match from this device without touching the server. Used by [delete] and by sign-out. */
    fun removeLocally(id: String) = mutate { list -> list.filterNot { it.log.id == id } }

    private fun edit(id: String, transform: (ScoreLog) -> ScoreLog) = mutate { list ->
        list.map {
            if (it.log.id != id) it
            else StoredLog(transform(it.log).copy(updatedAt = now()), dirty = true)
        }
    }

    private fun mutate(transform: (List<StoredLog>) -> List<StoredLog>) = lock.withLock {
        persist(transform(stored))
    }

    /** Caller must hold [lock]. */
    private fun persist(next: List<StoredLog>) {
        val sorted = next.sortedByDescending { it.log.createdAt }
        stored = sorted
        settings.putString(KEY_CACHE, json.encodeToString(
            CachedLogs.serializer(), CachedLogs(currentOwnerId(), sorted),
        ))
        state.value = sorted.map { it.log }
    }

    private fun loadStored(): List<StoredLog> {
        val cached = settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedLogs.serializer(), it) }.getOrNull() }
            ?: return emptyList()
        // A cache written by another account, or by a build before this scoping
        // existed, is discarded rather than shown. Better an empty list than one
        // coach's match names in another coach's app.
        val owner = currentOwnerId()
        val usable = owner != null && owner == cached.ownerId
        return if (usable) cached.logs.sortedByDescending { it.log.createdAt } else emptyList()
    }

    /**
     * Pushes what this device changed, then takes the server's list for everything
     * it did not.
     *
     * Single writer per account: one phone scores a match, and a second device
     * following along is L3, which is not proposed. So a locally dirty row always
     * wins over its server copy - the only way both could have changed is a second
     * writer this release does not have. L3 replaces this rule outright, and the
     * log being append-only is what will let it replay rather than reconcile.
     */
    suspend fun sync(): Result<Unit> {
        val postgrest = client?.postgrest ?: return Result.failure(
            IllegalStateException("Not signed in.")
        )
        return runCatching {
            val dirty = stored.filter { it.dirty }.map { it.log }
            if (dirty.isNotEmpty()) {
                postgrest.from(TABLE).upsert(dirty)
            }
            val remote = postgrest.from(TABLE)
                .select { order("created_at", Order.DESCENDING) }
                .decodeList<ScoreLog>()

            lock.withLock {
                // Compared by content, not by id. A row that is still byte for byte
                // what we pushed is settled and takes the server's copy; a row that
                // has changed since - the coach scored while the request was on the
                // wire - is still ahead of the server and keeps its local version.
                // Comparing ids instead would silently un-score that rally.
                val pushedById = dirty.associateBy { it.id }
                val localById = stored.associateBy { it.log.id }
                val merged = remote.map { row ->
                    val local = localById[row.id]
                    when {
                        local == null -> StoredLog(row, dirty = false)
                        local.dirty && local.log != pushedById[row.id] -> local
                        else -> StoredLog(row, dirty = false)
                    }
                }
                val remoteIds = remote.map { it.id }.toSet()
                // A dirty row the server has not acknowledged yet must not vanish
                // because the pull did not contain it.
                val unacknowledged = stored.filter { it.dirty && it.log.id !in remoteIds }
                persist(merged + unacknowledged)
            }
        }
    }

    /**
     * Removes a match everywhere. The local removal happens whether or not the
     * server call succeeds: the row is the user's own, the gesture is explicit, and
     * a match that reappears after a failed delete is a worse outcome than a server
     * row the next sync will not resurrect, since [sync] only adds rows the server
     * still returns.
     */
    suspend fun delete(id: String): Result<Unit> {
        removeLocally(id)
        val postgrest = client?.postgrest ?: return Result.failure(
            IllegalStateException("Not signed in.")
        )
        return runCatching {
            postgrest.from(TABLE).delete { filter { eq("id", id) } }
            Unit
        }
    }

    private fun currentOwnerId(): String? = ownerId()

    internal companion object {
        const val KEY_CACHE = "score_logs_cache"
        const val TABLE = "score_logs"
    }
}
