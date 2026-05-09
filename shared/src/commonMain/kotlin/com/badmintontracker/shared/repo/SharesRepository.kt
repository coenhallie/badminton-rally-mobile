package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.MatchShare
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceivedShare(
    @SerialName("video_id")     val videoId: String,
    @SerialName("sharer_email") val sharerEmail: String?,
    @SerialName("shared_at")    val sharedAt: Instant,
)

interface SharesRepository {
    suspend fun share(videoId: String, email: String): Result<Unit>
    suspend fun unshare(videoId: String, userId: String): Result<Unit>
    suspend fun listShares(videoId: String): Result<List<MatchShare>>
    suspend fun listReceived(): List<ReceivedShare>
}

class SharesRepositoryImpl(private val client: SupabaseClient) : SharesRepository {

    @Serializable private data class ShareArgs(
        @SerialName("p_video_id") val videoId: String,
        @SerialName("p_email")    val email: String,
    )
    @Serializable private data class UnshareArgs(
        @SerialName("p_video_id") val videoId: String,
        @SerialName("p_user_id")  val userId: String,
    )
    @Serializable private data class ListArgs(
        @SerialName("p_video_id") val videoId: String,
    )

    override suspend fun share(videoId: String, email: String): Result<Unit> = runCatching {
        client.postgrest.rpc("share_match", ShareArgs(videoId, email))
        Unit
    }.recoverCatching { throw it.toShareError() }

    override suspend fun unshare(videoId: String, userId: String): Result<Unit> = runCatching {
        client.postgrest.rpc("unshare_match", UnshareArgs(videoId, userId))
        Unit
    }

    override suspend fun listShares(videoId: String): Result<List<MatchShare>> = runCatching {
        client.postgrest.rpc("list_match_shares", ListArgs(videoId))
            .decodeList<MatchShare>()
    }

    override suspend fun listReceived(): List<ReceivedShare> =
        client.postgrest.rpc("list_received_match_shares")
            .decodeList<ReceivedShare>()
}
