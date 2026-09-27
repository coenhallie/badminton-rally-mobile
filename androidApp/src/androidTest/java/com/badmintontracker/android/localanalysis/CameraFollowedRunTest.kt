package com.badmintontracker.android.localanalysis

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.camera.CameraMotionTracker
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.local.LocalAnalysisCoordinator
import com.badmintontracker.shared.local.LocalAnalysisOutcome
import com.badmintontracker.shared.local.LocalInferenceEngine
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * A handheld recording analysed on this device, the near player held against
 * the reference (camera-motion design `docs/plans/2026-09-27-camera-motion-design.md`
 * §8.5).
 *
 * `IMG_5892` is 72.7 s of 1080p HEVC shot on a hand-held phone, so the court
 * drifts across the frame and the marks drawn at 0.1 s stop fitting it within
 * seconds (§1). This runs what `LocalAnalysisRunner` runs for a pose metric: the
 * real [AndroidLocalInferenceEngine] with the pose model staged by
 * [ModelCatalog], under [LocalAnalysisCoordinator], on the corpus's ideal
 * marks as the wire [CourtKeypoints] with their marking time. The near track it
 * returns is joined by frame with `reference_tracks.json`'s `ref_track`: the
 * same selection rules and the same nano pose with the camera followed by
 * every-frame ORB. Both number frames from 0 in decode order, so no timestamp
 * is involved. The bounds are §8.5's, and all three are checked together so a
 * coverage miss does not hide an accuracy one.
 *
 * Beyond the verdict it logs (tag `CameraFollowedRun`) the camera's
 * followed and dropped samples as the engine reports them, the pass time, and
 * the tracker's own share of it. The share comes from a second, decode-only
 * pass that offers the same frames to a tracker of its own and times only the
 * downsample and the offer, since the engine does not time its parts. On a
 * debuggable build that share is an upper bound (the tracker costs several
 * times more there than in a release build). The pose file and the near track,
 * with the reference beside each position, are written to the app's external
 * files dir, so a miss can be looked into on a computer without another run
 * on the device.
 *
 * Takes about twenty minutes on the emulator, which needs two things a phone
 * does not. Its hardware HEVC decoder (`c2.goldfish.hevc.decoder`) accepts
 * this recording's samples and never returns a frame, so the pass waits
 * forever: start it with `-feature -HardwareDecoder` for the software
 * decoder. And the three models at work need more than a 2 GB AVD has, so
 * give it `-memory 6144`. Run it with
 * `am instrument` on an installed build, not `connectedAndroidTest`, which
 * uninstalls the app afterwards and with it the signed-in session:
 *
 *   adb push ~/shuttl-corpus/IMG_5892/source.mov /data/local/tmp/IMG_5892.mov
 *   adb push ~/shuttl-corpus/IMG_5892/marks_ideal.json /data/local/tmp/IMG_5892.marks_ideal.json
 *   adb push ~/shuttl-corpus/IMG_5892/reference_tracks.json /data/local/tmp/IMG_5892.reference_tracks.json
 *   adb shell am instrument -w -e class com.badmintontracker.android.localanalysis.CameraFollowedRunTest \
 *     com.badmintontracker.android.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Arguments (`-e`): `frames` caps the pass for a quick check of the plumbing
 * (the bounds still apply); `entryId` also stores the run's track, skeleton,
 * clips and video under that local entry, the files `LocalAnalysisRunner`
 * leaves, so the match page can draw this run.
 *
 * Skips, and says why, when any of the three files is not on the device. A
 * skip also prints `OK` from `am instrument`: read the `CameraFollowedRun` log
 * for the numbers.
 */
@RunWith(AndroidJUnit4::class)
class CameraFollowedRunTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun a_handheld_recording_puts_the_near_player_where_the_reference_has_her() {
        val marksFile = pushed(MARKS)
        val referenceFile = pushed(REFERENCE)
        val video = stagedCorpusVideo(context, VIDEO)
        assumeTrue(
            "SKIPPED: push IMG_5892's source.mov, marks_ideal.json and reference_tracks.json " +
                "to /data/local/tmp as $VIDEO, $MARKS and $REFERENCE (see the KDoc)",
            video != null && marksFile != null && referenceFile != null,
        )
        val frames = arguments.getString("frames")?.toIntOrNull() ?: Int.MAX_VALUE
        val keypoints = parseMarks(marksFile!!)
        val reference = parseReference(referenceFile!!)

        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val log: (String) -> Unit = { line ->
            Log.i(TAG, line)
            lines.add(line)
        }
        val engine = AndroidLocalInferenceEngine(
            context = context,
            maxFrames = frames,
            poseModelPath = ModelCatalog.path(context, Model.POSE),
            log = log,
        )
        // Hands the engine's output on untouched and keeps a reference to it:
        // the coordinator's outcome carries the track, not the camera.
        var raw: RawInference? = null
        val recording = object : LocalInferenceEngine {
            override suspend fun run(videoPath: String, markingTimeS: Double?, onProgress: (Float) -> Unit) =
                engine.run(videoPath, markingTimeS, onProgress).also { raw = it }
        }

        var reported = -1
        val started = SystemClock.elapsedRealtime()
        val outcome = runBlocking {
            LocalAnalysisCoordinator(recording, log).analyze(video!!.path, keypoints) { fraction ->
                // A line every tenth, so a run can be watched from logcat.
                val tenth = (fraction * 10).toInt()
                if (tenth > reported) {
                    reported = tenth
                    Log.i(TAG, "progress ${tenth * 10}% after ${(SystemClock.elapsedRealtime() - started) / 1000}s")
                }
            }
        }.getOrThrow()
        val passMs = SystemClock.elapsedRealtime() - started
        val inference = raw!!
        val track = outcome.playerTrack

        val trackerMs = timeTheTracker(video!!, frames)

        val errors = ArrayList<Double>()
        var withoutReference = 0
        for (sample in track.samples) {
            val expected = reference[sample.frame]
            if (expected == null) {
                withoutReference++
            } else {
                errors.add(hypot(sample.courtPosition.x - expected.x, sample.courtPosition.y - expected.y))
            }
        }
        // Were the join off by a frame, the error would be smallest at a shift.
        val shifted = listOf(-1, 1).associateWith { shift ->
            track.samples.mapNotNull { s ->
                reference[s.frame + shift]?.let { hypot(s.courtPosition.x - it.x, s.courtPosition.y - it.y) }
            }.let { if (it.isEmpty()) Double.NaN else percentile(it, 50.0) }
        }

        writeDiagnostics(inference, outcome, reference)
        entryIdToStore()?.let { store(it, video, outcome, keypoints) }

        val median = if (errors.isEmpty()) Double.NaN else percentile(errors, 50.0)
        val p95 = if (errors.isEmpty()) Double.NaN else percentile(errors, 95.0)
        log(
            "pose file: %d frames of %d x %d at %.3f fps, camera %s; reference covers %d frames"
                .format(
                    inference.frames.size, inference.header.videoWidth, inference.header.videoHeight,
                    inference.header.fps,
                    inference.camera?.let { "${it.samples.size} samples" } ?: "absent",
                    reference.size,
                ),
        )
        log(
            "NEAR: %d positions over %d pose frames (coverage %.4f), %.4f of every frame; rejections %s"
                .format(
                    track.samples.size, track.framesWithPose, track.coverage,
                    track.samples.size.toDouble() / inference.frames.size, track.rejections,
                ),
        )
        log(
            "against ref_track: median %.1f cm, p95 %.1f cm, worst %.1f cm over %d frames (%d without a reference); join shifted -1: median %.1f cm, +1: %.1f cm"
                .format(
                    median * 100, p95 * 100, (errors.maxOrNull() ?: Double.NaN) * 100, errors.size,
                    withoutReference, shifted.getValue(-1) * 100, shifted.getValue(1) * 100,
                ),
        )
        log(
            "pass %.1f s (%.1f ms per frame); tracker %.1f s of it (%.2f%%), measured on a separate decode; clip windows %d"
                .format(
                    passMs / 1000.0, passMs.toDouble() / inference.frames.size, trackerMs / 1000.0,
                    100.0 * trackerMs / passMs, outcome.clipWindows.size,
                ),
        )
        val engineCamera = lines.filter { it.startsWith("camera:") }
        log("engine said: ${engineCamera.joinToString(" | ")}")

        assertNotNull("the camera was not followed: ${engineCamera.joinToString()}", inference.camera)
        assertTrue("no position had a reference to be held against", errors.isNotEmpty())
        val misses = listOfNotNull(
            "coverage %.4f < 0.95".format(track.coverage).takeIf { track.coverage < 0.95 },
            "median %.1f cm > 5 cm".format(median * 100).takeIf { median > 0.05 },
            "p95 %.1f cm > 15 cm".format(p95 * 100).takeIf { p95 > 0.15 },
        )
        assertTrue("outside §8.5's bounds: ${misses.joinToString("; ")}", misses.isEmpty())
    }

    /**
     * Milliseconds the tracker spends on [video]'s sampled frames: the Y plane
     * averaged down plus the offer, exactly the work the engine does per sample,
     * on the same decoder, timed without the models around it. Logs the decoded
     * picture's size and crop once, since a 1088-row buffer is read through its
     * crop and that is the first thing to rule out when positions are off.
     */
    private fun timeTheTracker(video: File, frames: Int): Double {
        val source = VideoFrameSource(video)
        val meta = source.metadata()
        val tracker = CameraMotionTracker(MARKED_AT_S, meta.width, meta.height)
        val luma = LumaPlane()
        var samples = 0
        var ns = 0L
        var described = false
        source.forEachFrame(frames) { _, seconds, image ->
            if (!described) {
                described = true
                Log.i(TAG, "decoded ${image.width}x${image.height}, crop ${image.cropRect}, container ${meta.width}x${meta.height}")
            }
            if (!tracker.wants(seconds)) return@forEachFrame
            val t0 = System.nanoTime()
            tracker.offer(
                seconds,
                luma.downsample(image, meta.width, meta.height, tracker.trackerWidth, tracker.trackerHeight),
            )
            ns += System.nanoTime() - t0
            samples++
        }
        tracker.finish()
        Log.i(
            TAG,
            "tracker alone: %d samples (%d followed, %d dropped), %.2f ms per sample"
                .format(samples, tracker.acceptedSamples, tracker.droppedSamples, ns / 1e6 / maxOf(samples, 1)),
        )
        return ns / 1e6
    }

    /** The pose file and the near track beside the reference, for a look off the device. */
    private fun writeDiagnostics(raw: RawInference, outcome: LocalAnalysisOutcome, reference: Map<Int, Point>) {
        val dir = context.getExternalFilesDir(null) ?: return
        File(dir, "IMG_5892.poses.raw").writeBytes(RawInferenceCodec.encode(raw))
        File(dir, "IMG_5892.near-track.csv").writeText(
            buildString {
                append("frame,timestamp,x,y,ref_x,ref_y\n")
                outcome.playerTrack.samples.forEach { s ->
                    val ref = reference[s.frame]
                    append(s.frame).append(',').append(raw.frames[s.frame].timestamp).append(',')
                        .append(s.courtPosition.x).append(',').append(s.courtPosition.y).append(',')
                        .append(ref?.x ?: "").append(',').append(ref?.y ?: "").append('\n')
                }
            },
        )
        Log.i(TAG, "wrote the pose file and the near track to $dir")
    }

    private fun entryIdToStore(): String? = arguments.getString("entryId")?.takeIf { it.isNotBlank() }

    /**
     * Leaves what `LocalAnalysisRunner.start` leaves for [entryId] after a pose
     * run: the video copied where `materialise` puts a gallery pick, the track,
     * the skeleton and the cut clips. Nothing here is asserted; it is so the
     * match page can be looked at.
     */
    private fun store(entryId: String, video: File, outcome: LocalAnalysisOutcome, keypoints: CourtKeypoints) {
        val files = context.filesDir
        val copy = File(files, "local-sources/$entryId.mp4")
        if (!(copy.isFile && copy.length() == video.length())) {
            copy.parentFile?.mkdirs()
            video.copyTo(copy, overwrite = true)
        }
        val fps = outcome.result.fps
        val tracks = PlayerTrackStore(files)
        if (outcome.playerTrack.samples.isNotEmpty()) tracks.save(entryId, outcome.playerTrack, fps)
        SkeletonStore(files).save(
            entryId, outcome.poses, fps, outcome.videoWidth, outcome.videoHeight, keypoints.toAnalysis(),
        )
        val clips = if (outcome.clipWindows.isEmpty()) {
            emptyList()
        } else {
            ClipCutter().cut(copy, outcome.clipWindows, File(files, "local-clips/$entryId"))
        }
        tracks.saveClips(entryId, clips)
        Log.i(TAG, "stored the run for entry $entryId: ${clips.size} clips")
    }

    private fun pushed(name: String): File? = File("/data/local/tmp/$name").takeIf { it.isFile && it.canRead() }

    /** `manual_court_keypoints` as the marking screen hands them on, drawn at [MARKED_AT_S]. */
    private fun parseMarks(file: File): CourtKeypoints {
        val m = JSONObject(file.readText()).getJSONObject("manual_court_keypoints")
        fun p(key: String): List<Float> = m.getJSONArray(key).let { listOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
        return CourtKeypoints(
            topLeft = p("top_left"), topRight = p("top_right"),
            bottomRight = p("bottom_right"), bottomLeft = p("bottom_left"),
            netLeft = p("net_left"), netRight = p("net_right"),
            serviceLineNearLeft = p("service_line_near_left"), serviceLineNearRight = p("service_line_near_right"),
            serviceLineFarLeft = p("service_line_far_left"), serviceLineFarRight = p("service_line_far_right"),
            centerNear = p("center_near"), centerFar = p("center_far"),
            markedAtSeconds = MARKED_AT_S,
        )
    }

    /** `ref_track`: 0-based frame to the near player's court position in metres. */
    private fun parseReference(file: File): Map<Int, Point> {
        val track = JSONObject(file.readText()).getJSONObject("ref_track")
        return track.keys().asSequence().associate { key ->
            val xy = track.getJSONArray(key)
            key.toInt() to Point(xy.getDouble(0), xy.getDouble(1))
        }
    }

    /** Linear-interpolated percentile, numpy's default, as the JVM corpus tests take it. */
    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
    }

    private companion object {
        const val TAG = "CameraFollowedRun"
        const val VIDEO = "IMG_5892.mov"
        const val MARKS = "IMG_5892.marks_ideal.json"
        const val REFERENCE = "IMG_5892.reference_tracks.json"

        /** When the ideal marks were taken: the frame the corpus probe marked. */
        const val MARKED_AT_S = 0.1
    }
}
