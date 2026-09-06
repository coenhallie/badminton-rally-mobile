package com.badmintontracker.analysis.result

import com.badmintontracker.analysis.Phase1Output
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `results.json` payload, mirroring what the cloud worker writes.
 *
 * Field for field the same shape as `phase1_results` at
 * modal_supabase_processor.py:4189-4200, because this is what the comparator
 * diffs and what the sync layer uploads. The web app and the corpus tooling
 * both read the cloud's schema, so a divergence here is not a local detail.
 *
 * `producer` and the model-version stamp deliberately do NOT live here. Section
 * 5.5 puts them in `results_meta`, a column on the videos row, and adding a key
 * the cloud never writes would make this stop being a mirror.
 */
@Serializable
data class AnalysisResult(
    @SerialName("rallies") val rallies: List<SerializedRally>,
    @SerialName("shuttle_positions") val shuttlePositions: Map<String, SerializedShuttle>,
    @SerialName("fps") val fps: Double,
    @SerialName("total_frames") val totalFrames: Int,
    @SerialName("video_metadata") val videoMetadata: VideoMetadata,
    /**
     * Omitted entirely unless A/B mode asked for it. Null rather than empty:
     * the web app branches on the key's presence, so an explicit null or an
     * empty array would both read as "there is per-frame data here".
     */
    @SerialName("skeleton_data") val skeletonData: List<SkeletonFrame>? = null,
) {
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("phase")
    val phase: String = "phase1"

    /** legacy, not gb_fusion: section 8 puts the fusion variant out of scope. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("pipeline_variant")
    val pipelineVariant: String = "legacy"

    fun toJson(): String = FORMAT.encodeToString(serializer(), this)

    companion object {
        // ignoreUnknownKeys so a field the cloud adds later does not break
        // reading its output. That tolerance is exactly why
        // AnalysisResultCorpusTest diffs key sets against a real capture:
        // silently dropping a field is the failure this reader cannot raise.
        private val FORMAT = Json {
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun fromJson(text: String): AnalysisResult = FORMAT.decodeFromString(serializer(), text)
    }
}

@Serializable
data class VideoMetadata(
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("filename") val filename: String,
)

@Serializable
data class SerializedRally(
    @SerialName("id") val id: Int,
    @SerialName("start_frame") val startFrame: Int,
    @SerialName("end_frame") val endFrame: Int,
    @SerialName("start_timestamp") val startTimestamp: Double,
    @SerialName("end_timestamp") val endTimestamp: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
)

@Serializable
data class SerializedShuttle(
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
    @SerialName("visible") val visible: Boolean,
)

/**
 * Present only in A/B mode. Phase 1 never populates it.
 *
 * These two fields are PROVISIONAL, not the cloud's schema. Nothing in Stage 1
 * writes a skeleton frame, so the shape has never been checked against a real
 * capture; Stage 3 must take it from the worker's own payload before anything
 * relies on it. Read the current pair as a placeholder that keeps the A/B key
 * present, not as the contract.
 */
@Serializable
data class SkeletonFrame(
    @SerialName("frame") val frame: Int,
    @SerialName("timestamp") val timestamp: Double,
)

fun Rally.serialized(): SerializedRally =
    SerializedRally(id, startFrame, endFrame, startTimestamp, endTimestamp, durationSeconds)

fun ShuttleSample.serialized(): SerializedShuttle = SerializedShuttle(x, y, visible)

/**
 * Build the payload from a Phase 1 run.
 *
 * The stored rally set, not the clip set: the cloud puts the gradient/shot-gap
 * union in results.json so both timelines stay available to readers, and the
 * refined clip separation drives rally_clips instead.
 */
fun AnalysisResult.Companion.fromPhase1(
    output: Phase1Output,
    fps: Double,
    totalFrames: Int,
    durationSeconds: Double,
    filename: String,
    skeletonData: List<SkeletonFrame>? = null,
): AnalysisResult = AnalysisResult(
    rallies = output.storedRallies.map { it.serialized() },
    shuttlePositions = output.filteredTrack.entries
        .sortedBy { it.key }
        .associate { (frame, sample) -> frame.toString() to sample.serialized() },
    fps = fps,
    totalFrames = totalFrames,
    videoMetadata = VideoMetadata(durationSeconds, filename),
    skeletonData = skeletonData,
)
