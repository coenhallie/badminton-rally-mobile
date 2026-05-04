package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.RallyClip
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ClipsRepository {
    suspend fun listClips(): List<RallyClip>
    fun observeClips(): Flow<List<RallyClip>>
    suspend fun refresh()
    suspend fun updateTitle(clipId: String, title: String?): Result<Unit>
}

class ClipsRepositoryImpl(private val client: SupabaseClient) : ClipsRepository {

    private val _clips = MutableStateFlow<List<RallyClip>>(emptyList())

    override suspend fun listClips(): List<RallyClip> =
        client.postgrest.from("rally_clips")
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<RallyClip>()

    override fun observeClips(): Flow<List<RallyClip>> = _clips.asStateFlow()

    override suspend fun refresh() {
        _clips.value = listClips()
    }

    override suspend fun updateTitle(clipId: String, title: String?): Result<Unit> = runCatching {
        client.postgrest.from("rally_clips")
            .update(mapOf("title" to title)) {
                filter { eq("id", clipId) }
            }
        Unit
    }
}
