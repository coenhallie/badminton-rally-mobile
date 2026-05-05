package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.RallyAnnotation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface AnnotationsRepository {
    suspend fun list(clipId: String): List<RallyAnnotation>
    suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        kind: AnnotationKind?,
    ): Result<RallyAnnotation>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationsRepositoryImpl(private val client: SupabaseClient) : AnnotationsRepository {

    @Serializable
    private data class NewAnnotationRow(
        @SerialName("clip_id")           val clipId: String,
        @SerialName("timestamp_seconds") val timestampSeconds: Float,
        val body: String,
        val kind: AnnotationKind? = null,
    )

    override suspend fun list(clipId: String): List<RallyAnnotation> =
        client.postgrest.from("rally_annotations")
            .select {
                filter { eq("clip_id", clipId) }
                order("timestamp_seconds", Order.ASCENDING)
            }
            .decodeList()

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        kind: AnnotationKind?,
    ): Result<RallyAnnotation> = runCatching {
        // owner_id is filled server-side via the column's `default auth.uid()`.
        client.postgrest.from("rally_annotations")
            .insert(NewAnnotationRow(clipId, timestampSeconds, body, kind)) { select() }
            .decodeSingle<RallyAnnotation>()
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from("rally_annotations").delete { filter { eq("id", id) } }
        Unit
    }
}
