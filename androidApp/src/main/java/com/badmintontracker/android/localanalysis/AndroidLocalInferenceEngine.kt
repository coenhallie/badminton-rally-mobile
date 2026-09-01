package com.badmintontracker.android.localanalysis

import android.content.Context
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.analysis.raw.RawShuttle
import com.badmintontracker.shared.local.LocalInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android platform layer: decode, run models, emit raw output.
 *
 * Satisfies the whole of section 5.1's contract and nothing beyond it. It
 * computes no metric, assigns no player_id and decides no rally boundary -
 * those live in `:analysis`, which this device calls directly because
 * `androidApp` is Kotlin.
 *
 * **Detections are not populated yet.** Section 5.2 gives `RawInference` a
 * place for the badminton detector's boxes, and Phase 1 in the cloud fuses
 * them with TrackNet's output to form the track its shot-gap detector reads.
 * This engine emits TrackNet only, so downstream the fusion track and the
 * TrackNet track are the same thing. That is a real divergence from the cloud
 * and is why rally counts from this engine should not yet be read as a parity
 * result. Adding [DetectorRunner] is additive: one more model in the same
 * decode pass, filling `boxes`.
 */
class AndroidLocalInferenceEngine(
    private val context: Context,
    /**
     * Stop after this many frames. For bounded measurement on a device only:
     * a partial track silently produces rallies for part of a match, so
     * production must never set it.
     */
    private val maxFrames: Int = Int.MAX_VALUE,
) : LocalInferenceEngine {

    override suspend fun run(
        videoPath: String,
        onProgress: (Float) -> Unit,
    ): RawInference = withContext(Dispatchers.Default) {
        val file = File(videoPath)
        require(file.isFile) { "no such video: $videoPath" }

        val source = VideoFrameSource(file)
        val meta = source.metadata()

        val track = TrackNetRunner(context, source).track(
            sourceWidth = meta.width,
            sourceHeight = meta.height,
            maxFrames = maxFrames,
            onProgress = onProgress,
        )
        val frameCount = minOf(meta.frameCount, if (maxFrames == Int.MAX_VALUE) meta.frameCount else maxFrames)

        // Every decoded frame gets a record, present or absent. A sparse frame
        // list would make the rally detectors' frame arithmetic wrong, since
        // they index by position rather than by key.
        val frames = (0 until frameCount).map { i ->
            val s = track[i]
            RawFrame(
                frame = i,
                timestamp = if (meta.fps > 0) i / meta.fps else 0.0,
                shuttle = s?.let {
                    RawShuttle(it.x.toFloat(), it.y.toFloat(), if (it.visible) 1f else 0f, it.visible)
                },
                boxes = emptyList(),
                persons = emptyList(),
            )
        }

        RawInference(
            header = RawHeader(
                version = RawInferenceCodec.VERSION,
                fps = meta.fps,
                totalFrames = frameCount,
                videoWidth = meta.width,
                videoHeight = meta.height,
                modelVersion = ModelCatalog.VERSION,
            ),
            frames = frames,
        )
    }
}
