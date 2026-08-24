package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface AnnotationLabelsRepository {
    /** Last known list, creation order. Survives a cold start offline. */
    val labels: StateFlow<List<AnnotationLabel>>
    suspend fun refresh(): Result<Unit>
    /** [color] null picks a swatch automatically; the inline picker path passes null. */
    suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel>
    suspend fun rename(id: String, name: String): Result<Unit>
    suspend fun recolor(id: String, color: LabelColor): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationLabelsRepositoryImpl(
    private val client: SupabaseClient,
    private val settings: Settings,
) : AnnotationLabelsRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AnnotationLabel.serializer())

    private val state = MutableStateFlow(loadCache())
    override val labels: StateFlow<List<AnnotationLabel>> = state.asStateFlow()

    @Serializable private data class NewLabelRow(
        val name: String,
        @SerialName("color_key") val colorKey: String,
    )
    @Serializable private data class NamePatch(val name: String)
    @Serializable private data class ColorPatch(@SerialName("color_key") val colorKey: String)

    override suspend fun refresh(): Result<Unit> = runCatching {
        val rows = client.postgrest.from(TABLE)
            .select { order("created_at", Order.ASCENDING) }
            .decodeList<AnnotationLabel>()
        publish(rows)
    }

    override suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: nextUnusedColor(state.value.map { it.colorKey })
        return runCatching {
            val row = client.postgrest.from(TABLE)
                .insert(NewLabelRow(trimmed, swatch.key)) { select() }
                .decodeSingle<AnnotationLabel>()
            publish(state.value + row)
            row
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        val trimmed = name.trim()
        validate(trimmed, ignoringId = id)?.let { return Result.failure(it) }
        return runCatching {
            client.postgrest.from(TABLE).update(NamePatch(trimmed)) { filter { eq("id", id) } }
            publish(state.value.map { if (it.id == id) it.copy(name = trimmed) else it })
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun recolor(id: String, color: LabelColor): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(ColorPatch(color.key)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(colorKey = color.key) else it })
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).delete { filter { eq("id", id) } }
        publish(state.value.filterNot { it.id == id })
    }

    /**
     * The unique index is the real authority, and [validate] can only see the
     * in-memory list: on a cold start offline, or before the first refresh lands,
     * that list is empty and the duplicate check passes. So the server's rejection
     * is a normal path, not an edge case, and it gets the same sentence rather
     * than surfacing as "HTTP 409".
     */
    private fun Throwable.asDuplicateName(name: String): Throwable {
        val text = message.orEmpty()
        val duplicate = "23505" in text ||
            "duplicate key" in text ||
            "annotation_labels_owner_name_key" in text
        return if (duplicate) IllegalArgumentException("You already have a label called \"$name\".") else this
    }

    /**
     * Mirrors the DB's own rules so the user sees the problem before a round trip,
     * and so the message is ours rather than a Postgres constraint name.
     */
    private fun validate(trimmed: String, ignoringId: String? = null): Throwable? = when {
        trimmed.isEmpty() -> IllegalArgumentException("Give the label a name.")
        trimmed.length > MAX_NAME -> IllegalArgumentException("Keep the name under $MAX_NAME characters.")
        state.value.any { it.id != ignoringId && it.name.equals(trimmed, ignoreCase = true) } ->
            IllegalArgumentException("You already have a label called \"$trimmed\".")
        else -> null
    }

    private fun publish(next: List<AnnotationLabel>) {
        state.value = next
        settings.putString(KEY_CACHE, json.encodeToString(serializer, next))
    }

    private fun loadCache(): List<AnnotationLabel> =
        settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    companion object {
        private const val TABLE = "annotation_labels"
        private const val KEY_CACHE = "annotation_labels_cache"
        private const val MAX_NAME = 24

        /**
         * First swatch not already in use. Past ten labels every swatch is taken,
         * so it wraps by count rather than returning null: the inline picker path
         * always passes a null colour and has no way to handle running out.
         */
        fun nextUnusedColor(takenKeys: List<String>): LabelColor =
            LabelColor.PALETTE.firstOrNull { it.key !in takenKeys }
                ?: LabelColor.PALETTE[takenKeys.size % LabelColor.PALETTE.size]
    }
}
