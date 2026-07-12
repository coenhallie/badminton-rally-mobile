package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.AnnotationKind
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

/** On-phone annotations keyed by local video id, persisted as JSON in Settings. */
class LocalAnnotationsRepository(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer =
        MapSerializer(String.serializer(), ListSerializer(LocalAnnotation.serializer()))

    private val state = MutableStateFlow(load())
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>> = state.asStateFlow()

    // Serializes read-modify-write cycles; callers include app-scoped analyze
    // pipelines running concurrently on Dispatchers.Default.
    private val lock = SyncLock()

    fun annotationsFor(videoId: String): List<LocalAnnotation> =
        state.value[videoId].orEmpty()

    fun hasAnnotations(videoId: String): Boolean =
        state.value[videoId]?.isNotEmpty() == true

    fun add(videoId: String, timestampSeconds: Float, body: String, kind: AnnotationKind?): LocalAnnotation {
        val annotation = LocalAnnotation(
            id = randomUuid(),
            timestampSeconds = timestampSeconds,
            body = body,
            kind = kind,
            createdAtEpochMs = nowEpochMs(),
        )
        mutate(videoId) { it + annotation }
        return annotation
    }

    fun delete(videoId: String, annotationId: String) =
        mutate(videoId) { list -> list.filterNot { it.id == annotationId } }

    fun removeAllFor(videoId: String) = lock.withLock {
        val next = state.value - videoId
        persist(next)
    }

    private fun mutate(videoId: String, transform: (List<LocalAnnotation>) -> List<LocalAnnotation>) =
        lock.withLock {
            val current = state.value[videoId].orEmpty()
            val updated = transform(current).sortedBy { it.timestampSeconds }
            persist(state.value + (videoId to updated))
        }

    private fun persist(next: Map<String, List<LocalAnnotation>>) {
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    private fun load(): Map<String, List<LocalAnnotation>> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?.mapValues { (_, list) -> list.sortedBy { it.timestampSeconds } }
            ?: emptyMap()

    private companion object { const val KEY = "local_annotations" }
}
