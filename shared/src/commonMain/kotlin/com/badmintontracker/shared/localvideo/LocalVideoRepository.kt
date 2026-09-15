package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Why an entry left the registry, which decides how much of it goes with it.
 *
 * The footage is released either way - that part never differed. What differs
 * is everything DERIVED from the video, and the difference is not a detail: a
 * cloud analysis is written against the entry id and the entry is then retired
 * by the very pipeline that produced it, moments later. Treating that as a
 * discard deletes the result the coach waited on a GPU for, seconds after it
 * landed.
 */
enum class LocalVideoRemoval {
    /**
     * The coach threw this video away. Everything derived from it goes too -
     * cut clips, tracks, skeletons, both sources.
     */
    DISCARDED,

    /**
     * The pipeline finished and the footage now lives in the cloud.
     *
     * The entry and the file go; the analysis stays. A cloud track and skeleton
     * ARE the result of this run, so the run that succeeded must not delete
     * them on its way out.
     */
    RETIRED,
}

/** Registry of on-device recordings, persisted as JSON in Settings. */
class LocalVideoRepository(
    private val settings: Settings,
    /**
     * Releases whatever backs a departing entry. iOS keeps its own copy of the
     * video inside the app container and this entry is the only reference to it,
     * so a removal that skips the file strands it with no way for the user to
     * reclaim the space. Android stores a content:// reference into the user's
     * gallery and leaves it alone, which is why the default does nothing.
     *
     * Belongs here rather than at each call site so "the entry owns the file" is a
     * property of the store, not something three unrelated callers must remember.
     *
     * [LocalVideoRemoval] rides along because the hook also drives the derived
     * stores, and those two cases genuinely differ. The reason is a parameter
     * rather than a second hook so a platform cannot wire one and forget the
     * other - there is one cleanup path, told what kind of cleanup it is.
     */
    private val onRemoved: (LocalVideoEntry, LocalVideoRemoval) -> Unit = { _, _ -> },
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

    /** The coach threw this video away. See [LocalVideoRemoval.DISCARDED]. */
    fun remove(id: String) = drop(id, LocalVideoRemoval.DISCARDED)

    /**
     * The pipeline finished with this video. See [LocalVideoRemoval.RETIRED].
     *
     * A separate entry point rather than a flag on [remove] because the two are
     * different promises to the coach, and a boolean at the call site reads as a
     * tuning knob rather than as which of the two happened.
     */
    fun retire(id: String) = drop(id, LocalVideoRemoval.RETIRED)

    private fun drop(id: String, reason: LocalVideoRemoval) {
        val removed = lock.withLock {
            val entry = state.value.firstOrNull { it.id == id } ?: return@withLock null
            persist(state.value.filterNot { it.id == id })
            entry
        }
        // Outside the lock, and only once the registry no longer lists the entry:
        // the callback reaches into platform file IO, and a crash between the two
        // should leave an orphaned file (reclaimable) rather than an entry pointing
        // at nothing (a dead row in the user's library).
        removed?.let { onRemoved(it, reason) }
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

/**
 * Stores the match name and description (Swift-friendly single-purpose mutation).
 *
 * A no-op once the entry has left LOCAL. Both platforms hide the edit affordance
 * by then, but the window is a data integrity rule rather than a UI convention:
 * the values ride on the videos INSERT and no UPDATE grant exists on either
 * column, so a late write would leave the app showing a name the database does
 * not have, with no way to reconcile it. See [canEditLocalVideoDetails].
 */
fun LocalVideoRepository.setDetails(id: String, title: String?, description: String?) =
    update(id) {
        if (canEditLocalVideoDetails(it.stage)) it.copy(title = title, description = description) else it
    }
