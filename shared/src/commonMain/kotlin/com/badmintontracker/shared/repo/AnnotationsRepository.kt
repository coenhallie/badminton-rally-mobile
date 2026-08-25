package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.RallyAnnotation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface AnnotationsRepository {
    suspend fun list(clipId: String): List<RallyAnnotation>

    /**
     * Every annotation on a set of clips, for match-level rollups. Filters on
     * clip ids rather than a video id because rally_annotations has no video
     * column, and every caller already holds the ids.
     */
    suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>>

    suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationsRepositoryImpl(private val client: SupabaseClient) : AnnotationsRepository {

    @Serializable
    private data class NewAnnotationRow(
        @SerialName("clip_id")           val clipId: String,
        @SerialName("timestamp_seconds") val timestampSeconds: Float,
        val body: String,
        @SerialName("label_name")  val labelName: String? = null,
        @SerialName("label_color") val labelColor: String? = null,
    )

    override suspend fun list(clipId: String): List<RallyAnnotation> =
        client.postgrest.from("rally_annotations")
            .select {
                filter { eq("clip_id", clipId) }
                order("timestamp_seconds", Order.ASCENDING)
            }
            .decodeList()

    override suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>> =
        runCatching {
            // An empty in.() is both pointless and malformed, so short-circuit
            // rather than issue it.
            if (clipIds.isEmpty()) return@runCatching emptyList()
            clipIds.distinct().chunked(CLIP_ID_CHUNK).flatMap { chunk ->
                client.postgrest.from("rally_annotations")
                    .select { filter { isIn("clip_id", chunk) } }
                    .decodeList<RallyAnnotation>()
            }
        }

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation> = runCatching {
        // owner_id is filled server-side via the column's `default auth.uid()`.
        client.postgrest.from("rally_annotations")
            .insert(
                NewAnnotationRow(clipId, timestampSeconds, body, label?.name, label?.colorKey)
            ) { select() }
            .decodeSingle<RallyAnnotation>()
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from("rally_annotations").delete { filter { eq("id", id) } }
        Unit
    }
}

/** Guard against URL length on a long match, not a normal path. */
private const val CLIP_ID_CHUNK = 100
