package com.badmintontracker.android.localanalysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.badmintontracker.analysis.camera.CameraMotionTracker
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What following the camera adds to the pass, on the decoder the pass uses.
 *
 * The tracker's own cost is known from the JVM; what only a device can say is
 * the cost of reaching the Y plane of a real `MediaCodec` image and averaging
 * it down, and whether the decoder's plane layout is one [LumaPlane] accepts.
 * Timed per sample (what one offer costs) and per frame (what the pass pays on
 * average, since only one frame in each 0.1 s is offered).
 */
@RunWith(AndroidJUnit4::class)
class CameraTrackingTimingTest {

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    @Test
    fun follows_the_corpus_camera_and_reports_the_cost() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        val source = VideoFrameSource(vid!!)
        val meta = source.metadata()
        // The legacy marking time, as the coordinator passes for marks drawn
        // before they recorded their own.
        val tracker = CameraMotionTracker(0.1, meta.width, meta.height)
        val luma = LumaPlane()

        var frames = 0
        var samples = 0
        var downsampleNs = 0L
        var offerNs = 0L
        source.forEachFrame(FRAMES) { _, seconds, image ->
            frames++
            if (!tracker.wants(seconds)) return@forEachFrame
            // What this decoder hands over: an aligned buffer (1088 rows for
            // 1080) is read through its crop, so say what the crop is.
            if (samples == 0) {
                Log.i(TAG, "decoded ${image.width}x${image.height}, crop ${image.cropRect}, container ${meta.width}x${meta.height}")
            }
            val started = System.nanoTime()
            val grey = luma.downsample(image, meta.width, meta.height, tracker.trackerWidth, tracker.trackerHeight)
            val downsampled = System.nanoTime()
            tracker.offer(seconds, grey)
            offerNs += System.nanoTime() - downsampled
            downsampleNs += downsampled - started
            samples++
        }
        val motion = tracker.finish()

        fun ms(ns: Long, n: Int) = if (n == 0) 0.0 else ns / 1_000_000.0 / n
        Log.i(
            TAG,
            "%d frames, %d samples (%d followed, %d dropped) of %dx%d at %dx%d: per sample %.2f ms (downsample %.2f, track %.2f), per frame %.2f ms".format(
                frames, samples, tracker.acceptedSamples, tracker.droppedSamples,
                meta.width, meta.height, tracker.trackerWidth, tracker.trackerHeight,
                ms(downsampleNs + offerNs, samples), ms(downsampleNs, samples), ms(offerNs, samples),
                ms(downsampleNs + offerNs, frames),
            ),
        )
        assertNotNull("the marking frame was never followed", motion)
    }

    private companion object {
        const val TAG = "CameraTiming"
        const val FRAMES = 300
    }
}
