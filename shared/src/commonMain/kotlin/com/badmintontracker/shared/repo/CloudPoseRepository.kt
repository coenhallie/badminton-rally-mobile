package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.onDownload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where one video's cloud pose artifact lives, and how big it is.
 *
 * [frameCount] is read from results_meta so a caller can say what it is about
 * to fetch before fetching it. It is the worker's count of emitted frames, not
 * a byte size.
 *
 * [keypoints] rides along because the stream is meaningless without the court
 * it was projected against, and they come off the same row. A phone with no
 * local entry for this video - a match analyzed in the cloud from another
 * phone, whose Analysis works from the track alone - has no other copy of them.
 */
data class CloudPoseArtifact(
    val videoId: String,
    val storagePath: String,
    val frameCount: Int,
    val keypoints: CourtKeypoints,
)

/**
 * The cloud's per-frame pose output for one video.
 *
 * Two calls rather than one because they cost very different things: the
 * lookup is a single row read a list screen can afford, and the download is
 * tens of megabytes a list screen must never start.
 */
interface CloudPoseRepository {
    /** Null when Phase 2 has not run, or ran and could not upload its artifact. */
    suspend fun artifact(videoId: String): Result<CloudPoseArtifact?>

    /** @param onProgress fraction in [0, 1) of the transfer. Completion is the caller's to report. */
    suspend fun download(artifact: CloudPoseArtifact, onProgress: (Float) -> Unit): Result<ByteArray>
}

class CloudPoseRepositoryImpl(private val client: SupabaseClient) : CloudPoseRepository {

    @Serializable
    private data class ArtifactArgs(@SerialName("p_video_id") val videoId: String)

    @Serializable
    private data class ArtifactRow(
        @SerialName("poses_artifact_path") val storagePath: String? = null,
        @SerialName("poses_frame_count") val frameCount: Int = 0,
        @SerialName("manual_court_keypoints") val keypoints: CourtKeypoints? = null,
    )

    override suspend fun artifact(videoId: String): Result<CloudPoseArtifact?> = runCatching {
        // Narrow SECURITY DEFINER RPC keeps raw public.videos SELECT owner-only
        // while granting linked players pose metadata without exposing opponent IDs.
        val row = client.postgrest
            .rpc("get_cloud_pose_artifact", ArtifactArgs(videoId))
            .decodeList<ArtifactRow>()
            .firstOrNull()
            ?: return@runCatching null
        val path = row.storagePath?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val keypoints = row.keypoints ?: return@runCatching null
        CloudPoseArtifact(
            videoId = videoId,
            storagePath = path,
            frameCount = row.frameCount,
            keypoints = keypoints,
        )
    }.annotateHttpStatus()

    override suspend fun download(
        artifact: CloudPoseArtifact,
        onProgress: (Float) -> Unit,
    ): Result<ByteArray> = runCatching {
        client.storage.from("results")
            .downloadAuthenticated(artifact.storagePath) {
                // supabase-kt 3.5.0's DownloadOptionBuilder has no progress
                // hook of its own - only `transform` and `httpOverride`, which
                // is a ktor HttpRequestBuilder.() -> Unit. So the progress
                // comes off ktor directly.
                httpOverride {
                    onDownload { transferred, total ->
                        // Clamped below 1: completion belongs to the
                        // coordinator, after the decode and the selection that
                        // follow the transfer, not to the moment bytes land.
                        // A null total is a response with no Content-Length,
                        // where a fraction cannot be computed at all.
                        if (total != null && total > 0) {
                            onProgress((transferred.toFloat() / total).coerceIn(0f, 0.999f))
                        }
                    }
                }
            }
    }.annotateHttpStatus()
}
