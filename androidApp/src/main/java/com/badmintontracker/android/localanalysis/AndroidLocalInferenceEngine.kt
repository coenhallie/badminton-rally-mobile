package com.badmintontracker.android.localanalysis

import android.content.Context
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
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
 * What it emits is section 5.2's contract exactly: the **unfiltered** TrackNet
 * peak per frame, plus the detector's boxes. Not a filtered or fused track -
 * the cloud derives two different tracks from these, its filtered track from
 * raw TrackNet and its fusion track from TrackNet with YOLO as fallback, and
 * collapsing them here would make one of the two rally detectors read the
 * wrong input.
 *
 * All the models share one decode pass. Decode and colour conversion are about
 * a third of the per-frame cost, so running any of them separately would pay
 * that again.
 */
class AndroidLocalInferenceEngine(
    private val context: Context,
    /**
     * Stop after this many frames. For bounded measurement on a device only:
     * a partial track silently produces rallies for part of a match, so
     * production must never set it.
     */
    private val maxFrames: Int = Int.MAX_VALUE,
    /**
     * Pose model path, or null for Phase 1 only.
     *
     * Opt-in rather than always-on because pose is not bundled yet and because
     * it roughly triples the per-frame cost: 235ms for Phase 1 against about
     * 230ms more for `yolo26n-pose` at 960. A caller that only wants rallies
     * must not pay for a player track it will not read.
     */
    private val poseModelPath: String? = null,
) : LocalInferenceEngine {

    override suspend fun run(
        videoPath: String,
        onProgress: (Float) -> Unit,
    ): RawInference = withContext(Dispatchers.Default) {
        val file = File(videoPath)
        require(file.isFile) { "no such video: $videoPath" }

        val source = VideoFrameSource(file)
        val meta = source.metadata()

        val detections = HashMap<Int, List<com.badmintontracker.analysis.shuttle.ShuttleDetection>>()
        val people = HashMap<Int, List<RawPerson>>()
        val pose = poseModelPath?.let { PoseRunner(it) }
        val track = try {
            DetectorRunner(context).use { detector ->
                TrackNetRunner(context, source).track(
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    maxFrames = maxFrames,
                    onProgress = onProgress,
                    onFrame = { index, image ->
                        val found = detector.detect(image)
                        if (found.isNotEmpty()) detections[index] = found
                        // Same frame, same decode. Emitted raw: which person is
                        // the near player, and where they stand on the court,
                        // are :analysis decisions.
                        pose?.detect(index, image)?.people?.let { found ->
                            if (found.isNotEmpty()) {
                                people[index] = found.map { person ->
                                    RawPerson(
                                        box = RawBox(
                                            classId = 0,
                                            confidence = person.boxConfidence,
                                            x1 = 0f, y1 = 0f, x2 = 0f, y2 = 0f,
                                        ),
                                        keypoints = person.keypoints.mapIndexed { k, point ->
                                            RawKeypoint(
                                                point.x.toFloat(),
                                                point.y.toFloat(),
                                                person.keypointConfidence[k],
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        } finally {
            pose?.close()
        }
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
                // class 2 is the shuttle, the only class the fusion reads.
                boxes = detections[i].orEmpty().map {
                    RawBox(
                        classId = 2,
                        confidence = it.confidence.toFloat(),
                        x1 = it.x.toFloat(), y1 = it.y.toFloat(),
                        x2 = it.x.toFloat(), y2 = it.y.toFloat(),
                    )
                },
                persons = people[i].orEmpty(),
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
