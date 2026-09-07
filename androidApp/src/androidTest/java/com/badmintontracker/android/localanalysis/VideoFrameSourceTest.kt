package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Decoding on the device, held to numbers a desktop run already pinned.
 *
 * The corpus video decodes to exactly 5972 frames under OpenCV, matching both
 * its container metadata and `results.json`'s `total_frames`, at fps
 * 29.73572449542545. A different count here is not a rounding difference: it
 * shifts every frame index and therefore every rally boundary.
 *
 * Needs the video staged on the device:
 *   adb push source.mp4 /data/local/tmp/corpus-743d7fb1.mp4
 *   adb shell chmod 644 /data/local/tmp/corpus-743d7fb1.mp4
 *
 * NOT the app's own storage, which is where this started. Gradle uninstalls
 * both APKs after an instrumented run, and Android wipes app-private storage
 * on uninstall, so a fixture staged only there deleted itself between runs.
 * /data/local/tmp survives, and its 0771 shell:shell mode lets an app
 * traverse to a world-readable file inside it.
 *
 * Read through [stagedCorpusVideo] rather than `/data/local/tmp` directly,
 * though: it copies the file from there into app storage, the same as
 * production does before decoding a real video, so a retriever call made
 * against it behaves the way it would in the app rather than the way it
 * happens to behave against a shell-owned file.
 *
 * Skips loudly rather than passing when the video is absent.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireVideo(): File {
        val f = stagedCorpusVideo(context)
        assumeTrue(
            "SKIPPED: corpus video absent or unreadable; run " +
                "adb push source.mp4 /data/local/tmp/corpus-743d7fb1.mp4 && " +
                "adb shell chmod 644 /data/local/tmp/corpus-743d7fb1.mp4",
            f != null,
        )
        return f!!
    }

    @Test
    fun the_container_reports_the_frame_count_the_desktop_run_measured() {
        assertEquals(5972, VideoFrameSource(requireVideo()).frameCount())
    }

    @Test
    fun the_sample_scan_counts_what_the_container_reports() {
        // The fallback for a retriever that has no frame count (the emulator's
        // does not; the S23's does). Both must agree on a file where both work,
        // and metadata() must succeed on the emulator through the fallback.
        val src = VideoFrameSource(requireVideo())
        assertEquals(5972, src.countSamples())
        assertEquals(5972, src.metadata().frameCount)
        // Nothing else here checks fps, and metadata() computes it from a
        // track duration read a different way than before - a unit slip
        // there (say, forgetting the microseconds-to-milliseconds divide)
        // would pass every other assertion in this file. The cloud's
        // results.json fps for this corpus is 29.7357.
        assertEquals(29.7357, src.metadata().fps, 0.001)
    }

    @Test
    fun background_sample_indices_truncate_the_way_numpy_linspace_does() {
        // np.linspace(0, 5971, 300, dtype=int) truncates rather than rounds.
        // Checked against the same arithmetic production performs, not against
        // this implementation: step is 5971/299, so index 1 is 19.97 -> 19,
        // and rounding would make it 20 and sample a different frame.
        val src = VideoFrameSource(File("unused"))
        val idx = src.backgroundSampleIndices(totalFrames = 5972, maxSamples = 300)
        assertEquals(300, idx.size)
        assertEquals(0, idx.first())
        assertEquals(19, idx[1])
        assertEquals(5971, idx.last())
        assertTrue("indices must be strictly increasing", idx.zipWithNext().all { it.first < it.second })
    }

    @Test
    fun a_short_video_samples_every_frame_without_duplicates() {
        val src = VideoFrameSource(File("unused"))
        assertEquals((0 until 10).toList(), src.backgroundSampleIndices(10, 300))
    }

    @Test
    fun the_sequential_pass_decodes_every_frame_in_order() {
        val src = VideoFrameSource(requireVideo())
        var count = 0
        var lastTs = -1.0
        var monotonic = true
        var width = 0
        var height = 0
        src.forEachFrame { index, ts, image ->
            if (index != count) monotonic = false
            if (ts < lastTs) monotonic = false
            lastTs = ts
            if (count == 0) { width = image.width; height = image.height }
            count++
        }
        assertEquals("decoded frame count must match the container", 5972, count)
        assertTrue("indices and timestamps must be non-decreasing", monotonic)
        assertEquals(1920, width)
        assertEquals(1080, height)
    }
}
