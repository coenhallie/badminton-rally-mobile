package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
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
 * The video and the cut clips go either way. What differs is the ANALYSIS, and
 * the difference is the whole point: only the skeleton needs the footage, so a
 * coach freeing space keeps the heatmap and the base position while a coach
 * deleting the match keeps nothing. Collapsing the two turns "get my storage
 * back" into "lose my analysis", silently.
 */
enum class LocalVideoRemoval {
    /**
     * The coach threw this video away. Everything derived from it goes too -
     * cut clips, tracks, skeletons, both sources.
     */
    DISCARDED,

    /**
     * The coach is freeing the footage, not throwing the match away.
     *
     * The video, the cut clips and the SKELETON go - the skeleton is drawn over
     * playback, so without the file it is an artifact nothing can display. The
     * tracks stay, because the heatmap and the base position are computed
     * entirely from them and work with no video at all. That is what
     * `AnalyticsRowState.READY_NO_VIDEO` is for - for a match that also has a
     * cloud copy, whose row lists the kept track. A match only on this phone
     * leaves the match list with its entry, so nothing lists its track again
     * (`MatchListCopy.removeFromPhoneDetail` tells the coach so).
     */
    VIDEO_ONLY,
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
     * The coach wants the space back. See [LocalVideoRemoval.VIDEO_ONLY].
     *
     * A separate entry point rather than a flag on [remove] because the two are
     * different promises to the coach, and a boolean at the call site reads as a
     * tuning knob rather than as which of the two happened.
     */
    fun freeVideo(id: String) = drop(id, LocalVideoRemoval.VIDEO_ONLY)

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
 * Saves the court points marked for this video (Swift-friendly single-purpose
 * mutation). Every run saves them before it starts, cloud and on-device alike:
 * they are how the match list tells a marked court from an unmarked one (an
 * on-device run never moves the stage off LOCAL), they let a failed cloud run
 * resume without re-marking, and they prefill the marking screen next time.
 */
fun LocalVideoRepository.setKeypoints(id: String, keypoints: CourtKeypoints) =
    update(id) { it.copy(keypoints = keypoints) }

/**
 * Stores the match name and description (Swift-friendly single-purpose mutation).
 *
 * A no-op once the entry has left LOCAL. Both platforms hide the edit affordance
 * by then, but the window is a data integrity rule rather than a UI convention:
 * the values ride on the videos INSERT, so a write here after CREATE_ROW would
 * leave the app showing a name the database does not have. Once the row exists
 * the videos row is the record, and it is edited from the match page, which
 * writes the server first and then [mirrorVideoDetails] here. See
 * [canEditLocalVideoDetails].
 */
fun LocalVideoRepository.setDetails(id: String, title: String?, description: String?) =
    update(id) {
        if (canEditLocalVideoDetails(it.stage)) it.copy(title = title, description = description) else it
    }

/**
 * Follows a rename that has already landed on the videos row, so the entry that
 * uploaded that video (its id is the videos row's id) and the match built from
 * the row cannot show two names. No stage check, on purpose: the server has
 * already accepted the value, which is the one thing [setDetails]'s window is
 * there to guarantee. Only ever called after `updateVideoMatchDetailsOrMessage`
 * succeeds.
 */
fun LocalVideoRepository.mirrorVideoDetails(videoId: String, title: String?, description: String?) =
    update(videoId) { it.copy(title = title, description = description) }
