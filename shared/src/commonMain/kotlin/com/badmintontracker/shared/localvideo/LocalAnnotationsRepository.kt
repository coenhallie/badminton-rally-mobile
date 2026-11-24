package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.nowEpochMs
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** On-phone annotations keyed by local video id, persisted in Settings and isolated per account. */
class LocalAnnotationsRepository(
    private val settings: Settings,
    private val ownerId: (() -> String?)? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer =
        MapSerializer(String.serializer(), ListSerializer(LocalAnnotation.serializer()))

    private var loadedScope: String? = currentScope()
    private val state = MutableStateFlow(loadForScope(loadedScope))
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>> = state.asStateFlow()

    // Serializes read-modify-write cycles; callers include app-scoped analyze
    // pipelines running concurrently on Dispatchers.Default.
    private val lock = SyncLock()

    fun annotationsFor(videoId: String): List<LocalAnnotation> =
        state.value[videoId].orEmpty()

    fun hasAnnotations(videoId: String): Boolean =
        state.value[videoId]?.isNotEmpty() == true

    fun add(
        videoId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): LocalAnnotation {
        val annotation = LocalAnnotation(
            id = randomUuid(),
            timestampSeconds = timestampSeconds,
            body = body,
            labelName = label?.name,
            labelColor = label?.colorKey,
            createdAtEpochMs = nowEpochMs(),
        )
        mutate(videoId) { it + annotation }
        return annotation
    }

    fun delete(videoId: String, annotationId: String) =
        mutate(videoId) { list -> list.filterNot { it.id == annotationId } }

    fun removeAllFor(videoId: String) = lock.withLock {
        ensureScopeCurrentLocked()
        val next = state.value - videoId
        persistLocked(next)
    }

    /** Reloads per-account local notes when the signed-in account changes. */
    fun reloadForCurrentOwner() = lock.withLock {
        val scope = currentScope()
        if (scope == loadedScope) return@withLock
        loadedScope = scope
        state.value = loadForScope(scope)
    }

    private fun mutate(videoId: String, transform: (List<LocalAnnotation>) -> List<LocalAnnotation>) =
        lock.withLock {
            ensureScopeCurrentLocked()
            val current = state.value[videoId].orEmpty()
            val updated = transform(current).sortedBy { it.timestampSeconds }
            persistLocked(state.value + (videoId to updated))
        }

    private fun currentScope(): String? = if (ownerId == null) LOCAL_SCOPE else ownerId.invoke()

    private fun ensureScopeCurrentLocked() {
        val scope = currentScope()
        if (scope != loadedScope) {
            loadedScope = scope
            state.value = loadForScope(scope)
        }
    }

    private fun persistLocked(next: Map<String, List<LocalAnnotation>>) {
        state.value = next
        val scope = loadedScope ?: return
        val key = if (scope == LOCAL_SCOPE) KEY else "$KEY_PREFIX$scope"
        settings.putString(key, json.encodeToString(serializer, next))
    }

    private fun loadForScope(scope: String?): Map<String, List<LocalAnnotation>> {
        if (scope == null) return emptyMap()
        if (scope == LOCAL_SCOPE) return loadRaw(KEY)
        val scopedKey = "$KEY_PREFIX$scope"
        val existing = settings.getStringOrNull(scopedKey)
        if (existing != null) return decodeNotes(existing)
        if (settings.getStringOrNull(KEY_LEGACY_MIGRATED) == null) {
            val legacy = loadRaw(KEY)
            settings.putString(KEY_LEGACY_MIGRATED, scope)
            if (legacy.isNotEmpty()) {
                settings.putString(scopedKey, json.encodeToString(serializer, legacy))
                settings.remove(KEY)
                return legacy
            }
        }
        return emptyMap()
    }

    private fun loadRaw(key: String): Map<String, List<LocalAnnotation>> =
        settings.getStringOrNull(key)?.let(::decodeNotes) ?: emptyMap()

    private fun decodeNotes(raw: String): Map<String, List<LocalAnnotation>> =
        runCatching { json.decodeFromString(serializer, raw) }
            .getOrNull()
            ?.mapValues { (_, list) -> list.sortedBy { it.timestampSeconds } }
            ?: emptyMap()

    private companion object {
        const val LOCAL_SCOPE = "__local__"
        const val KEY = "local_annotations"
        const val KEY_PREFIX = "local_annotations:"
        const val KEY_LEGACY_MIGRATED = "local_annotations_legacy_migrated_v1"
    }
}
