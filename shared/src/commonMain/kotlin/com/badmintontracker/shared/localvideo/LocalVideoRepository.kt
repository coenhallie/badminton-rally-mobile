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
class LocalVideoRepository(private val settings: Settings) {

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

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun get(id: String): LocalVideoEntry? = state.value.firstOrNull { it.id == id }

    private fun mutate(transform: (List<LocalVideoEntry>) -> List<LocalVideoEntry>) = lock.withLock {
        val next = transform(state.value).sortedByDescending { it.addedAtEpochMs }
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
