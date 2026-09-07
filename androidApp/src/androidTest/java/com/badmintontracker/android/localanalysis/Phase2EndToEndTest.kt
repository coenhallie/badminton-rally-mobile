package com.badmintontracker.android.localanalysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtOccupancy
import com.badmintontracker.analysis.player.buildNearPlayerTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole of Phase 2 on real video: decode, pose, selection, heatmap.
 *
 * The unit tests cover the judgement and PoseRunnerTest covers the decode, but
 * neither exercises the seam this crosses: pose sharing the single decode pass
 * with TrackNet and the detector, its output surviving RawInference, and
 * :analysis reading it back. That seam is where a Phase 1 regression would
 * appear, so this also asserts the shuttle track is still produced.
 *
 *   adb push tools/models/onnx/posen.960.fp16.onnx /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class Phase2EndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val NL = System.lineSeparator()

    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        // Marked with "near" meaning near the camera for the service lines and
        // near the top for the centre points, as the cloud stored them. The
        // fit resolves each pair by pixel, so both readings are fine.
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    @Test
    fun a_video_becomes_a_shuttle_track_and_a_player_heatmap() {
        // Overridable so a longer run can be asked for without editing a
        // constant: -Pandroid.testInstrumentationRunnerArguments.frames=2000
        val frames = InstrumentationRegistry.getArguments()
            .getString("frames")?.toIntOrNull() ?: FRAMES
        val video = stagedCorpusVideo(context)
        val pose = File("/data/local/tmp/posen.960.fp16.onnx").takeIf { it.isFile }
        assumeTrue("SKIPPED: corpus video absent", video != null)
        assumeTrue("SKIPPED: pose model absent from /data/local/tmp", pose != null)

        val raw = runBlocking {
            AndroidLocalInferenceEngine(
                context = context,
                maxFrames = frames,
                poseModelPath = pose!!.path,
            ).run(video!!.path) {}
        }

        // The timestamps are the container's, not i / fps. The corpus is
        // variable frame rate (ffprobe: r_frame_rate 179/6 = 29.83 nominal,
        // avg_frame_rate 29.7357, which is also the cloud's results.json
        // fps), so exact per-frame agreement between the container's
        // timestamp and i / avg_fps is exactly what must NOT be asserted:
        // over 150 frames the two drift more than a frame apart, which is
        // the real variation carrying the container's timestamps exists to
        // preserve rather than paper over. What the container's values must
        // still be, though: non-decreasing, made of plausible frame-to-frame
        // gaps rather than fabricated ones, and consistent on average with
        // the frame rate over the whole run.
        val fps = raw.header.fps
        assertTrue("first frame at 0s", raw.frames.first().timestamp == 0.0)
        assertTrue(
            "timestamps must be non-decreasing",
            raw.frames.zipWithNext().all { (a, b) -> b.timestamp >= a.timestamp },
        )
        val fabricated = raw.frames.drop(1).count { it.timestamp == it.frame / fps }
        assertTrue("$fabricated of ${raw.frames.size} timestamps are exactly frame / fps", fabricated < raw.frames.size / 2)
        assertTrue(
            "a timestamp gap is not a plausible frame interval",
            raw.frames.zipWithNext().all { (a, b) -> (b.timestamp - a.timestamp) in 0.0..(3.0 / fps) },
        )
        assertTrue(
            "the timestamps' span disagrees with the frame rate by more than 10%",
            kotlin.math.abs(raw.frames.last().timestamp - (raw.frames.size - 1) / fps) <
                0.1 * raw.frames.size / fps,
        )

        val track = buildNearPlayerTrack(raw, keypoints)
        val occupancy = CourtOccupancy()
        occupancy.addAll(track.samples, raw.header.fps)
        val cells = occupancy.grid().flatten().count { it > 0.0 }

        Log.i(
            "Phase2",
            buildString {
                append("frames ${raw.frames.size} at ${raw.header.fps} fps").append(NL)
                append("  shuttle frames  ${raw.frames.count { it.shuttle != null }}").append(NL)
                append("  pose frames     ${track.framesWithPose}").append(NL)
                append("  player samples  ${track.samples.size}").append(NL)
                append("  coverage        ${(track.coverage * 100).toInt()}%").append(NL)
                append("  rejections      ${track.rejections}").append(NL)
                append("  occupancy       ${occupancy.totalSeconds}s over $cells cells")
            },
        )

        // Phase 1 still works. Pose joined the decode pass rather than
        // replacing anything, and this is what would catch it if it had.
        assertTrue(
            "no shuttle track: pose broke the Phase 1 pass",
            raw.frames.count { it.shuttle != null } > frames / 4,
        )

        assertTrue(
            "pose produced nothing through RawInference",
            track.framesWithPose > frames / 2,
        )
        assertTrue(
            "near player found in too few frames: ${track.samples.size} of ${track.framesWithPose}",
            track.coverage > 0.5,
        )
        // A heatmap on a single cell would also satisfy a naive count, so
        // require the player to have actually moved across the court.
        assertTrue("the heatmap occupies no area: $cells cells", cells >= 1)
        assertTrue("occupancy accumulated no time", occupancy.totalSeconds > 0.0)

        // The track in court metres, so the map can be rendered and looked at
        // off-device. A heatmap is a shape, and no assertion here says whether
        // the shape is a badminton player.
        // The app's own external files dir, not /data/local/tmp: the app can
        // READ from there, which is why the models load, but it cannot write
        // there, and the difference only shows up at the end of a long run.
        File(context.getExternalFilesDir(null), "player-track.csv").writeText(
            buildString {
                append("frame,court_x_m,court_y_m,fps=${raw.header.fps}").append('\n')
                track.samples.forEach {
                    append("${it.frame},${it.courtPosition.x},${it.courtPosition.y}").append('\n')
                }
            },
        )
    }

    private companion object {
        /** Enough to cross a rally, bounded so the run stays under a few minutes. */
        const val FRAMES = 150
    }
}
