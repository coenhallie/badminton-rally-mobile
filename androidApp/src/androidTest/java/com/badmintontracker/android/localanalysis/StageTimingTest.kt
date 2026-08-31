package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.shuttle.heatmapToCoord
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Where the per-frame cost actually goes.
 *
 * A first end-to-end run measured 1815ms per frame, which projects to three
 * hours for a three-minute video. Optimising before measuring would be
 * guessing at which of four stages is responsible, so this times them apart:
 * decode, YUV to RGB, resize, TrackNet inference, and heatmap postprocessing.
 */
@RunWith(AndroidJUnit4::class)
class StageTimingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val NL = System.lineSeparator()

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    @Test
    fun time_each_stage_separately() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        val w = 1920
        val h = 1080
        val rgbFull = ByteArray(w * h * 3)
        val resized = ByteArray(512 * 288 * 3)

        var decodeNs = 0L
        var toRgbNs = 0L
        var resizeNs = 0L
        var fusedNs = 0L
        var frames = 0

        val source = VideoFrameSource(vid!!)
        var last = System.nanoTime()
        source.forEachFrame(48) { _, _, image ->
            val gotFrame = System.nanoTime()
            decodeNs += gotFrame - last
            FramePreprocessor.toRgb(image, rgbFull)
            val converted = System.nanoTime()
            toRgbNs += converted - gotFrame
            FramePreprocessor.resize(rgbFull, image.width, image.height, resized, 512, 288)
            val twoStep = System.nanoTime()
            resizeNs += twoStep - converted
            FramePreprocessor.toRgbResized(image, resized, 512, 288)
            fusedNs += System.nanoTime() - twoStep
            frames++
            last = System.nanoTime()
        }

        // TrackNet on a fixed input, so inference is timed without decode.
        // Both provider configurations, because "accelerated" is a claim until
        // it is a number: NNAPI can silently decline every operator and leave
        // the session exactly as slow as plain CPU.
        val input = FloatArray(27 * 512 * 288) { Random(0).nextFloat() }
        val shape = longArrayOf(1, 27, 288, 512)
        val timings = LinkedHashMap<String, Long>()
        var out = FloatArray(0)
        for (provider in OnnxSession.Provider.values()) {
            val label = provider.name.lowercase()
            OnnxSession(ModelCatalog.path(context, Model.TRACKNET), provider = provider).use { s2 ->
                s2.run(input, shape) // warm up
                val start = System.nanoTime()
                val reps = 3
                repeat(reps) { out = s2.run(input, shape) }
                timings[label] = (System.nanoTime() - start) / reps
            }
        }
        val inferenceNs = timings.values.min()

        // Postprocessing, per heatmap plane.
        val plane = FloatArray(512 * 288)
        out.copyInto(plane, 0, 0, 512 * 288)
        val postStart = System.nanoTime()
        repeat(8) { heatmapToCoord(plane, 512, 288) }
        val postNs = (System.nanoTime() - postStart) / 8

        fun ms(ns: Long) = ns / 1_000_000.0
        val report = buildString {
            append("STAGE TIMING per frame (${frames} frames decoded)").append(NL)
            append("  decode            %8.1f ms".format(ms(decodeNs / frames))).append(NL)
            append("  yuv -> rgb (full) %8.1f ms".format(ms(toRgbNs / frames))).append(NL)
            append("  resize            %8.1f ms".format(ms(resizeNs / frames))).append(NL)
            append("  fused convert+resize %5.1f ms".format(ms(fusedNs / frames))).append(NL)
            timings.forEach { (label, ns) ->
                append("  tracknet %-9s%8.1f ms /8 frames  (%.1f ms per frame)"
                    .format(label, ms(ns), ms(ns) / 8)).append(NL)
            }
            append("  heatmap -> coord  %8.1f ms".format(ms(postNs))).append(NL)
            append("  ---").append(NL)
            append("  sum per frame     %8.1f ms".format(
                ms(decodeNs / frames) + ms(fusedNs / frames) +
                    ms(inferenceNs) / 8 + ms(postNs)))
        }
        println(report)
        assertTrue(frames > 0)
    }
}
