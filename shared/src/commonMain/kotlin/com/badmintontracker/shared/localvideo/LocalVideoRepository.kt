package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.forgetting
import com.badmintontracker.shared.players.holds
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.scoring.Side
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
     * entirely from them and work with no video at all: a track kept after its
     * file is gone still opens the match page's Analysis, for a match that
     * also has a cloud copy, whose row lists the kept track. A match only on
     * this phone leaves the match list with its entry, so nothing lists its
     * track again (`MatchListCopy.removeFromPhoneDetail` tells the coach so).
     */
    VIDEO_ONLY,
}

/** Registry of on-device recordings, persisted as JSON in Settings, isolated per signed-in account. */
class LocalVideoRepository(
    private val settings: Settings,
    private val onRemoved: (LocalVideoEntry, LocalVideoRemoval) -> Unit = { _, _ -> },
    private val ownerId: (() -> String?)? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocalVideoEntry.serializer())

    private var loadedScope: String? = currentScope()
    private val state = MutableStateFlow(loadForScope(loadedScope))
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
            ensureScopeCurrentLocked()
            val entry = state.value.firstOrNull { it.id == id } ?: return@withLock null
            persistLocked(state.value.filterNot { it.id == id })
            entry
        }
        // Outside the lock, and only once the registry no longer lists the entry:
        // the callback reaches into platform file IO, and a crash between the two
        // should leave an orphaned file (reclaimable) rather than an entry pointing
        // at nothing (a dead row in the user's library).
        removed?.let { onRemoved(it, reason) }
    }

    fun get(id: String): LocalVideoEntry? = state.value.firstOrNull { it.id == id }

    /** Reloads per-account local entries when the signed-in account changes. */
    fun reloadForCurrentOwner() = lock.withLock {
        val scope = currentScope()
        if (scope == loadedScope) return@withLock
        loadedScope = scope
        state.value = loadForScope(scope)
    }

    private fun mutate(transform: (List<LocalVideoEntry>) -> List<LocalVideoEntry>) = lock.withLock {
        ensureScopeCurrentLocked()
        persistLocked(transform(state.value))
    }

    private fun currentScope(): String? = if (ownerId == null) LOCAL_SCOPE else ownerId.invoke()

    private fun ensureScopeCurrentLocked() {
        val scope = currentScope()
        if (scope != loadedScope) {
            loadedScope = scope
            state.value = loadForScope(scope)
        }
    }

    /** Caller must hold [lock]. */
    private fun persistLocked(entries: List<LocalVideoEntry>) {
        val next = entries.sortedByDescending { it.addedAtEpochMs }
        state.value = next
        val scope = loadedScope ?: return
        val key = if (scope == LOCAL_SCOPE) KEY else "$KEY_PREFIX$scope"
        settings.putString(key, json.encodeToString(serializer, next))
    }

    private fun loadForScope(scope: String?): List<LocalVideoEntry> {
        if (scope == null) return emptyList()
        if (scope == LOCAL_SCOPE) return loadRaw(KEY)
        val scopedKey = "$KEY_PREFIX$scope"
        val existing = settings.getStringOrNull(scopedKey)
        if (existing != null) return decodeEntries(existing)
        // One-time migration of pre-upgrade un-scoped entries to the first signed-in user.
        if (settings.getStringOrNull(KEY_LEGACY_MIGRATED) == null) {
            val legacy = loadRaw(KEY)
            settings.putString(KEY_LEGACY_MIGRATED, scope)
            if (legacy.isNotEmpty()) {
                settings.putString(scopedKey, json.encodeToString(serializer, legacy))
                settings.remove(KEY)
                return legacy
            }
        }
        return emptyList()
    }

    private fun loadRaw(key: String): List<LocalVideoEntry> =
        settings.getStringOrNull(key)?.let(::decodeEntries) ?: emptyList()

    private fun decodeEntries(raw: String): List<LocalVideoEntry> =
        runCatching { json.decodeFromString(serializer, raw) }
            .getOrNull()
            ?.sortedByDescending { it.addedAtEpochMs }
            ?: emptyList()

    private companion object {
        const val LOCAL_SCOPE = "__local__"
        const val KEY = "local_videos"
        const val KEY_PREFIX = "local_videos:"
        const val KEY_LEGACY_MIGRATED = "local_videos_legacy_migrated_v1"
    }
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

/**
 * Stores who is on each side and which end the home side was at, on the entry that
 * will upload them. No stage check, unlike [setDetails]: the caller routes, writing
 * the videos row first once the entry has left LOCAL and calling this only to follow
 * it (or, while LOCAL, instead of it), so the row on the server stays the record.
 */
fun LocalVideoRepository.setMatchPlayers(id: String, players: MatchPlayers, nearSide: Side?) =
    update(id) {
        it.copy(
            homePlayerId = players.homePlayerId,
            awayPlayerId = players.awayPlayerId,
            homePlayers = players.homePlayers,
            awayPlayers = players.awayPlayers,
            nearSide = nearSide,
        )
    }

/**
 * A player was deleted: every entry that named them keeps [name] as a typed name
 * and loses the id (see [com.badmintontracker.shared.players.forgetting] for a null
 * [name]). Entries that do not hold the id are not written.
 */
fun LocalVideoRepository.forgetPlayer(playerId: String, name: String?) {
    for (id in entries.value.filter { it.matchPlayers.holds(playerId) }.map { it.id }) {
        update(id) { entry ->
            val after = entry.matchPlayers.forgetting(playerId, name)
            entry.copy(
                homePlayerId = after.homePlayerId,
                awayPlayerId = after.awayPlayerId,
                homePlayers = after.homePlayers,
                awayPlayers = after.awayPlayers,
            )
        }
    }
}
