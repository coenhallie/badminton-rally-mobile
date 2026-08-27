package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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

    private fun currentOwnerId(): String? = ownerId()

    internal companion object {
        const val KEY_CACHE = "score_logs_cache"
        const val TABLE = "score_logs"
    }
}
