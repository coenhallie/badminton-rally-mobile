package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes what [PoseRunner] sees, so it can be checked off-device.
 *
 * [PoseRunnerTest] proves the decode produces a plausible player; it cannot say
 * whether a keypoint is where the model put it or a few pixels off through a
 * letterbox or layout slip, because nothing on the device knows the answer.
 * This dumps every person in the first N frames, raw, in source-video pixels,
 * and `tools/models/pose_parity.py` runs the same ONNX graph over the same
 * frames on the host and diffs them. Agreement to about a pixel is the claim
 * the runner's KDoc makes; this is what keeps it a measurement.
 *
 *   adb push tools/models/onnx/posen.960.fp16.onnx /data/local/tmp/
 *   adb push <corpus>/743d7fb1-.../source.mp4 /data/local/tmp/corpus-743d7fb1.mp4
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.badmintontracker.android.localanalysis.PoseParityDumpTest
 *   adb pull /sdcard/Android/data/com.badmintontracker.android/files/pose-dump.csv
 */
@RunWith(AndroidJUnit4::class)
class PoseParityDumpTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dump_raw_pose_output_for_the_first_frames() {
        val frames = InstrumentationRegistry.getArguments()
            .getString("frames")?.toIntOrNull() ?: FRAMES
        val video = stagedCorpusVideo(context)
        val model = File("/data/local/tmp/posen.960.fp16.onnx").takeIf { it.isFile }
        assumeTrue("SKIPPED: corpus video absent", video != null)
        assumeTrue("SKIPPED: pose model absent from /data/local/tmp", model != null)

        val out = StringBuilder()
        out.append("frame,person,box_confidence")
        for (k in 0 until 17) out.append(",x$k,y$k,c$k")
        out.append('\n')
        var people = 0
        PoseRunner(model!!.path).use { runner ->
            VideoFrameSource(video!!).forEachFrame(frames) { index, _, image ->
                runner.detect(index, image).people.forEachIndexed { p, person ->
                    people++
                    out.append(index).append(',').append(p).append(',').append(person.boxConfidence)
                    for (k in 0 until 17) {
                        out.append(',').append(person.keypoints[k].x)
                            .append(',').append(person.keypoints[k].y)
                            .append(',').append(person.keypointConfidence[k])
                    }
                    out.append('\n')
                }
            }
        }
        File(context.getExternalFilesDir(null), "pose-dump.csv").writeText(out.toString())
        assertTrue("no people detected in $frames frames", people > 0)
    }

    private companion object {
        const val FRAMES = 40
    }
}
