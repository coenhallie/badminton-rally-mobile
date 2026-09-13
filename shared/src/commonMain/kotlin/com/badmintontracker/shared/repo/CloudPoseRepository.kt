package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.onDownload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where one video's cloud pose artifact lives, and how big it is.
 *
 * [frameCount] is read from results_meta so a caller can say what it is about
 * to fetch before fetching it. It is the worker's count of emitted frames, not
 * a byte size.
 *
 * [keypoints] rides along because the stream is meaningless without the court
 * it was projected against, and they come off the same row. A phone with no
 * local entry for this video - which is exactly what READY_NO_VIDEO exists for
 * - has no other copy of them.
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
    private data class ArtifactRow(
        @SerialName("results_meta") val resultsMeta: JsonObject? = null,
        // The marks Phase 2 itself was handed. Read on the SAME query as the
        // artifact path, so the stream and the court it is interpreted
        // against come from one row rather than from two sources that could
        // disagree - and so a phone with no local entry for this video, which
        // is the whole point of READY_NO_VIDEO, has marks at all.
        @SerialName("manual_court_keypoints") val keypoints: CourtKeypoints? = null,
    )

    override suspend fun artifact(videoId: String): Result<CloudPoseArtifact?> = runCatching {
        val row = client.postgrest.from("videos")
            .select(Columns.list("results_meta", "manual_court_keypoints")) {
                filter { eq("id", videoId) }
            }
            .decodeSingleOrNull<ArtifactRow>()
            ?: return@runCatching null
        val meta = row.resultsMeta ?: return@runCatching null
        // A null path is the honest value the worker writes when the artifact
        // upload failed, which is non-fatal there. Treated the same as "Phase
        // 2 never ran": there is nothing to fetch either way.
        val path = meta["poses_artifact_path"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        // Phase 1 refuses to run without marks, so a row that reached Phase 2
        // always has them. Null here means a state nobody has seen, and an
        // artifact with no court to project onto is not worth attempting.
        val keypoints = row.keypoints ?: return@runCatching null
        CloudPoseArtifact(
            videoId = videoId,
            storagePath = path,
            frameCount = meta["poses_frame_count"]?.jsonPrimitive?.intOrNull ?: 0,
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
