package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Registry of on-device recordings, persisted as JSON in Settings. */
class LocalVideoRepository(
    private val settings: Settings,
    /**
     * Releases whatever backs a removed entry. iOS keeps its own copy of the video
     * inside the app container and this entry is the only reference to it, so a
     * removal that skips the file strands it with no way for the user to reclaim
     * the space. Android stores a content:// reference into the user's gallery and
     * leaves it alone, which is why the default does nothing.
     *
     * Belongs here rather than at each call site so "the entry owns the file" is a
     * property of the store, not something three unrelated callers must remember.
     */
    private val onRemoved: (LocalVideoEntry) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocalVideoEntry.serializer())

    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<LocalVideoEntry>> = state.asStateFlow()

    // Serializes read-modify-write cycles: concurrent analyze pipelines mutate
    // this repo from Dispatchers.Default and would otherwise clobber each other.
    private val lock = SyncLock()

    fun add(entry: LocalVideoEntry) = mutate { it + entry }

    fun update(id: String, transform: (LocalVideoEntry) -> LocalVideoEntry) =
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    fun remove(id: String) {
        val removed = lock.withLock {
            val entry = state.value.firstOrNull { it.id == id } ?: return@withLock null
            persist(state.value.filterNot { it.id == id })
            entry
        }
        // Outside the lock, and only once the registry no longer lists the entry:
        // the callback reaches into platform file IO, and a crash between the two
        // should leave an orphaned file (reclaimable) rather than an entry pointing
        // at nothing (a dead row in the user's library).
        removed?.let(onRemoved)
    }

    fun get(id: String): LocalVideoEntry? = state.value.firstOrNull { it.id == id }

    private fun mutate(transform: (List<LocalVideoEntry>) -> List<LocalVideoEntry>) = lock.withLock {
        persist(transform(state.value))
    }

    /** Caller must hold [lock]. */
    private fun persist(entries: List<LocalVideoEntry>) {
        val next = entries.sortedByDescending { it.addedAtEpochMs }
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    private fun load(): List<LocalVideoEntry> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?.sortedByDescending { it.addedAtEpochMs }
            ?: emptyList()

    private companion object { const val KEY = "local_videos" }
}

/** Marks a FAILED entry's result dialog as seen (Swift-friendly single-purpose mutation). */
fun LocalVideoRepository.acknowledgeResult(id: String) =
    update(id) { it.copy(resultSeen = true) }
