package com.badmintontracker.android.localanalysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.min

/**
 * How long pose inference costs per frame on this phone, at both export sizes.
 *
 * This is the number the gates plan says decides whether Phase 2 on device is
 * viable at all, and it has never been taken on a phone: StageTimingTest covers
 * decode, colour conversion, resize, TrackNet and heatmap decoding, and stops
 * there. Phase 1 already costs about 235ms a frame, so what matters is not
 * pose's cost alone but the total, against real video lengths.
 *
 * Reported per elapsed minute rather than as a mean, because the thing being
 * measured is partly thermal: a phone that starts at 200ms and ends at 600ms
 * has a mean that describes neither end. A projection from the last bucket is
 * the honest one for a long video.
 *
 * Both 960 and 640 are timed. Input size is the one remaining lever that does
 * not trade away the accuracy of the metrics being asked for, and
 * `export_yolo.py` writes both sizes specifically so the trade can be measured
 * rather than assumed.
 *
 * The model is side-loaded rather than bundled: pose is 43.5MB against the
 * whole Phase 1 set's 28.5MB, and ModelCatalog deliberately leaves it out of
 * the APK. Push it first, or this skips:
 *
 *   adb push tools/models/onnx/pose.fp16.onnx     /data/local/tmp/
 *   adb push tools/models/onnx/pose.640.fp16.onnx /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class PoseTimingTest {

    private val NL = System.lineSeparator()

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    private fun model(name: String): File? =
        File("/data/local/tmp/$name").takeIf { it.isFile && it.canRead() }

    @Test
    fun time_pose_at_both_export_sizes() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)
        val big = model("pose.fp16.onnx")
        val small = model("pose.640.fp16.onnx")
        assumeTrue("SKIPPED: pose models absent from /data/local/tmp", big != null || small != null)

        val report = StringBuilder("pose throughput on this device").append(NL)
        listOfNotNull(big?.let { 960 to it }, small?.let { 640 to it }).forEach { (size, file) ->
            val one = measure(size, file, vid!!)
            // Logged per size rather than once at the end: a run that is
            // interrupted after the first size should still leave that
            // size's numbers behind rather than nothing.
            Log.i("PoseTiming", one)
            report.append(one)
        }
        println(report)
    }

    private fun measure(size: Int, model: File, video: File): String {
        val session = OnnxSession(model.path)
        // Read the shape off the graph rather than assuming it: an export whose
        // size does not match its filename would otherwise be timed as if it did.
        val shape = session.inputShape()
        val input = FloatArray(3 * size * size)
        val letterboxed = ByteArray(size * size * 3)

        // Per elapsed minute, so throttling is visible instead of averaged away.
        val buckets = mutableMapOf<Int, MutableList<Long>>()
        val started = System.nanoTime()
        var frames = 0

        try {
            VideoFrameSource(video).forEachFrame(FRAMES) { _, _, image ->
                val scale = min(size.toDouble() / image.width, size.toDouble() / image.height)
                val fitW = (image.width * scale).toInt()
                val fitH = (image.height * scale).toInt()
                java.util.Arrays.fill(letterboxed, 114.toByte())
                FramePreprocessor.toRgbResizedInto(
                    image, letterboxed, size, (size - fitW) / 2, (size - fitH) / 2, fitW, fitH,
                )
                FramePreprocessor.toChwTensor(letterboxed, size, size, input)

                // Only the forward pass is timed. Preprocessing is already
                // costed by StageTimingTest and is shared with Phase 1.
                val t0 = System.nanoTime()
                session.run(input, longArrayOf(1, 3, size.toLong(), size.toLong()))
                val elapsed = System.nanoTime() - t0

                val minute = ((System.nanoTime() - started) / 60_000_000_000L).toInt()
                buckets.getOrPut(minute) { mutableListOf() }.add(elapsed)
                frames++
            }
        } finally {
            session.close()
        }

        fun medianMs(v: List<Long>) = v.sorted()[v.size / 2] / 1_000_000.0
        val all = buckets.values.flatten()
        val first = buckets[buckets.keys.min()]!!
        val last = buckets[buckets.keys.max()]!!

        return buildString {
            append(NL).append("  input $size (graph says ${shape.joinToString("x")})").append(NL)
            append("    frames                %d".format(frames)).append(NL)
            append("    median                %.1f ms/frame".format(medianMs(all))).append(NL)
            buckets.toSortedMap().forEach { (minute, v) ->
                append("      minute %-2d            %.1f ms  (n=%d)".format(minute, medianMs(v), v.size)).append(NL)
            }
            append("    throttle last/first   %.2fx".format(medianMs(last) / medianMs(first))).append(NL)
            // Phase 1 is already paid; this is what pose ADDS on top of it.
            val perFrame = medianMs(last)
            append("    with Phase 1's 235ms  %.1f ms/frame total".format(perFrame + PHASE1_MS)).append(NL)
            listOf("median 1.0 min" to 1.0, "mean 2.6 min" to 2.6, "longest 8.0 min" to 8.0).forEach { (label, mins) ->
                // 30fps assumed; a 50fps video of the same length costs 1.67x this.
                val minutes = (perFrame + PHASE1_MS) * mins * 60 * 30 / 60_000.0
                append("      %-16s -> %.0f min to analyse".format(label, minutes)).append(NL)
            }
        }
    }

    /**
     * Whether an accelerator moves pose at all.
     *
     * Worth its own run because it is the only lever that costs no accuracy:
     * sampling rate, input size and model size all trade something, and an
     * execution provider trades nothing. Acceleration gave nothing for TrackNet,
     * but that is a small graph; pose is 43MB, and the arithmetic is far enough
     * off that even a 3x win would change the design.
     *
     * Short and unbucketed on purpose: this is a first-order comparison to
     * decide what deserves a full thermal run, not the number itself.
     */
    @Test
    fun compare_execution_providers_at_640() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)
        val model = model("pose.640.fp16.onnx")
        assumeTrue("SKIPPED: pose 640 absent", model != null)

        val out = StringBuilder("pose 640 by execution provider").append(NL)
        OnnxSession.Provider.entries.forEach { provider ->
            val result = runCatching { providerMedian(provider, model!!, vid!!) }
            out.append(
                result.fold(
                    { "  %-8s %8.1f ms/frame".format(provider, it) },
                    { "  %-8s FAILED: %s".format(provider, it.message?.take(90)) },
                ),
            ).append(NL)
        }
        Log.i("PoseTiming", out.toString())
        println(out)
    }

    /**
     * What each model size costs on this phone, at the accuracy-preferred 960.
     *
     * The companion measurement, compare_pose_accuracy.py, prices the same
     * sizes in court centimetres. Neither number decides alone: a model that is
     * four times faster and loses five centimetres is a good trade for a
     * heatmap binned far coarser than that, and a bad one for peak-speed work.
     */
    @Test
    fun compare_model_sizes_at_960() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)
        val candidates = listOfNotNull(
            model("posen.960.fp16.onnx")?.let { "nano" to it },
            model("poses.960.fp16.onnx")?.let { "small" to it },
            model("pose.fp16.onnx")?.let { "medium" to it },
        )
        assumeTrue("SKIPPED: no pose models in /data/local/tmp", candidates.isNotEmpty())

        val out = StringBuilder("pose 960 by model size (cool phone, unbucketed)").append(NL)
        candidates.forEach { (label, file) ->
            val ms = runCatching { sizeMedian(file, vid!!) }
            out.append(
                ms.fold(
                    { "  %-7s %8.1f ms/frame".format(label, it) },
                    { "  %-7s FAILED: %s".format(label, it.message?.take(90)) },
                ),
            ).append(NL)
        }
        Log.i("PoseTiming", out.toString())
        println(out)
    }

    private fun sizeMedian(model: File, video: File): Double {
        val size = 960
        val session = OnnxSession(model.path)
        val input = FloatArray(3 * size * size)
        val letterboxed = ByteArray(size * size * 3)
        val samples = mutableListOf<Long>()
        try {
            VideoFrameSource(video).forEachFrame(PROVIDER_FRAMES) { _, _, image ->
                val scale = min(size.toDouble() / image.width, size.toDouble() / image.height)
                val fitW = (image.width * scale).toInt()
                val fitH = (image.height * scale).toInt()
                java.util.Arrays.fill(letterboxed, 114.toByte())
                FramePreprocessor.toRgbResizedInto(
                    image, letterboxed, size, (size - fitW) / 2, (size - fitH) / 2, fitW, fitH,
                )
                FramePreprocessor.toChwTensor(letterboxed, size, size, input)
                val t0 = System.nanoTime()
                session.run(input, longArrayOf(1, 3, size.toLong(), size.toLong()))
                samples.add(System.nanoTime() - t0)
            }
        } finally {
            session.close()
        }
        val warm = samples.drop(5)
        return warm.sorted()[warm.size / 2] / 1_000_000.0
    }

    private fun providerMedian(provider: OnnxSession.Provider, model: File, video: File): Double {
        val size = 640
        val session = OnnxSession(model.path, provider)
        val input = FloatArray(3 * size * size)
        val letterboxed = ByteArray(size * size * 3)
        val samples = mutableListOf<Long>()
        try {
            VideoFrameSource(video).forEachFrame(PROVIDER_FRAMES) { _, _, image ->
                val scale = min(size.toDouble() / image.width, size.toDouble() / image.height)
                val fitW = (image.width * scale).toInt()
                val fitH = (image.height * scale).toInt()
                java.util.Arrays.fill(letterboxed, 114.toByte())
                FramePreprocessor.toRgbResizedInto(
                    image, letterboxed, size, (size - fitW) / 2, (size - fitH) / 2, fitW, fitH,
                )
                FramePreprocessor.toChwTensor(letterboxed, size, size, input)
                val t0 = System.nanoTime()
                session.run(input, longArrayOf(1, 3, size.toLong(), size.toLong()))
                samples.add(System.nanoTime() - t0)
            }
        } finally {
            session.close()
        }
        // Drop the first few: the first inference on a provider pays graph
        // compilation and would be reported as its steady-state cost.
        val warm = samples.drop(5)
        return warm.sorted()[warm.size / 2] / 1_000_000.0
    }

    private companion object {
        const val PROVIDER_FRAMES = 25
        /** Enough to cross a minute boundary and show throttling, without a 20-minute test. */
        const val FRAMES = 400

        /** Phase 1's measured cost on this phone, from the throughput work. */
        const val PHASE1_MS = 235.0
    }
}
