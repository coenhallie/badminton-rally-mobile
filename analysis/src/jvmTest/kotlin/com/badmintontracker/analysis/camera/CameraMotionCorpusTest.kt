package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The camera tracker on a real 72.7 s handheld recording, measured against
 * the painted court lines and against an independent OpenCV port.
 *
 * BLOCKED on a corpus: skips loudly unless `tools/models/camera_motion_probe.py`
 * has already produced `~/shuttl-corpus/IMG_5892/` (samples.json, the
 * `y/<index>.y` raw Y planes, frame_times.json, paint.json,
 * opencv_motion.json - plus the pre-existing `H_ideal.json`).
 * `ANALYSIS_REQUIRE_CORPUS=1` turns the skip into a failure,
 * the convention `CorpusFixture.jvmShared.kt`'s `corpusIsRequired()` and the
 * other corpus tests use - not reused directly here because those fixtures
 * live on the test classpath and these do not: a 1.5 GB set of raw Y planes
 * has no business inside a JAR or a git checkout, so this test reads
 * `~/shuttl-corpus` straight off the filesystem instead.
 */
class CameraMotionCorpusTest {

    private val corpusDir = File(System.getProperty("user.home"), "shuttl-corpus/IMG_5892")
    private val sourceWidth = 1920
    private val sourceHeight = 1080

    @Test
    fun the_tracker_holds_the_paint_and_matches_opencv_on_a_real_recording() {
        val samplesFile = File(corpusDir, "samples.json")
        if (!samplesFile.exists()) {
            val message = "SKIPPED: corpus fixture '$samplesFile' is not present, so the camera " +
                "tracker was not measured against a real handheld recording. Run " +
                "tools/models/camera_motion_probe.py, which needs ~/shuttl-corpus/IMG_5892/ " +
                "(source.mov, H_ideal.json) to already exist."
            if (System.getenv("ANALYSIS_REQUIRE_CORPUS") == "1") error(message)
            println(message)
            return
        }

        val samples = parseSamples(samplesFile)
        val hIdealInv = invert3x3(parseMatrix(readJson(File(corpusDir, "H_ideal.json")).jsonObject.getValue("H")))
            ?: error("H_ideal.json's H is singular")
        val paintPoints = parsePaintPoints(File(corpusDir, "paint.json"))

        // Step 1: feed every sample through the tracker, in samples.json order.
        val tracker = CameraMotionTracker(0.1, sourceWidth, sourceHeight)
        var totalOfferNanos = 0L
        for (sample in samples) {
            val bytes = File(corpusDir, "y/${sample.index}.y").readBytes()
            val image = Luma.downsample(bytes, 0, sourceWidth, sourceHeight, sourceWidth, tracker.trackerWidth, tracker.trackerHeight)
            tracker.wants(sample.timestamp) shouldBe true
            val start = System.nanoTime()
            tracker.offer(sample.timestamp, image)
            totalOfferNanos += System.nanoTime() - start
        }
        val meanOfferMs = totalOfferNanos / 1_000_000.0 / samples.size
        println("mean ms per offer: %.3f over %d samples".format(meanOfferMs, samples.size))

        tracker.droppedSamples shouldBe 0
        val motion = tracker.finish() ?: error("the corpus video never reached the marking frame")

        // Step 2: the painted-line check. Each accepted sample's own full
        // resolution Y plane is re-read from disk (not the downsampled
        // tracker image the tracker itself worked from) so the line search
        // has real pixel detail to find a peak in.
        val indexByTimestamp = samples.associate { it.timestamp to it.index }
        val lineErrors = ArrayList<Double>()
        for (sample in motion.samples) {
            val index = indexByTimestamp[sample.timestamp]
                ?: error("accepted sample at t=${sample.timestamp} has no matching y-plane file")
            val grey = File(corpusDir, "y/$index.y").readBytes()
            for ((cx, cy) in paintPoints) {
                val markingPx = hIdealInv.apply(cx, cy) ?: continue
                val framePx = sample.referenceToFrame.apply(markingPx.x, markingPx.y) ?: continue
                val error = lineVerticalError(grey, sourceWidth, sourceHeight, framePx.x, framePx.y) ?: continue
                lineErrors.add(error)
            }
        }
        lineErrors.isEmpty() shouldBe false
        val lineMedian = percentile(lineErrors, 50.0)
        val lineP95 = percentile(lineErrors, 95.0)
        println(
            "painted-line vertical error: median %.3f px, p95 %.3f px, over %d (sample, point) pairs"
                .format(lineMedian, lineP95, lineErrors.size)
        )
        lineMedian shouldBeLessThanOrEqual 1.0
        lineP95 shouldBeLessThanOrEqual 2.0

        // Step 3: parity with opencv_motion.json, at the same paint points
        // (controller ruling - not at the image corners, which extrapolate
        // far past where either tracker has anything to track).
        val opencvSamples = parseOpenCvSamples(File(corpusDir, "opencv_motion.json")).sortedBy { it.timestamp }
        val opencvTimes = opencvSamples.map { it.timestamp }
        val halfFrameS = 0.5 / 30.0
        val worstPerSample = ArrayList<Double>()
        for (sample in motion.samples) {
            val nearest = nearestIndex(opencvTimes, sample.timestamp)
            if (abs(opencvTimes[nearest] - sample.timestamp) > halfFrameS) continue
            val referenceM = opencvSamples[nearest].m
            var worst = 0.0
            for ((cx, cy) in paintPoints) {
                val markingPx = hIdealInv.apply(cx, cy) ?: continue
                val ours = sample.referenceToFrame.apply(markingPx.x, markingPx.y) ?: continue
                val theirs = referenceM.apply(markingPx.x, markingPx.y) ?: continue
                worst = max(worst, hypot(ours.x - theirs.x, ours.y - theirs.y))
            }
            worstPerSample.add(worst)
        }
        (worstPerSample.size >= (0.95 * motion.samples.size).toInt()) shouldBe true
        val parityMedian = percentile(worstPerSample, 50.0)
        val parityP95 = percentile(worstPerSample, 95.0)
        println(
            "parity with opencv_motion.json at the paint points: median %.3f px, p95 %.3f px, over %d samples"
                .format(parityMedian, parityP95, worstPerSample.size)
        )
        parityMedian shouldBeLessThanOrEqual 1.0
        parityP95 shouldBeLessThanOrEqual 3.0
    }

    private data class Sample(val index: Int, val timestamp: Double)
    private data class OpenCvSample(val timestamp: Double, val m: Matrix3x3)

    private fun readJson(file: File) = Json.parseToJsonElement(file.readText())

    private fun parseMatrix(json: kotlinx.serialization.json.JsonElement): Matrix3x3 =
        json.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double } }

    private fun parseSamples(file: File): List<Sample> =
        readJson(file).jsonArray.map {
            val o = it.jsonObject
            Sample(o.getValue("index").jsonPrimitive.int, o.getValue("timestamp").jsonPrimitive.double)
        }

    private fun parsePaintPoints(file: File): List<Pair<Double, Double>> =
        readJson(file).jsonObject.getValue("points").jsonArray.map {
            val a = it.jsonArray
            a[0].jsonPrimitive.double to a[1].jsonPrimitive.double
        }

    private fun parseOpenCvSamples(file: File): List<OpenCvSample> =
        readJson(file).jsonObject.getValue("samples").jsonArray.map {
            val o = it.jsonObject
            OpenCvSample(o.getValue("timestamp").jsonPrimitive.double, parseMatrix(o.getValue("M")))
        }

    /** The index of the closest value to [target] in [sorted], which must be sorted ascending. */
    private fun nearestIndex(sorted: List<Double>, target: Double): Int {
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(sorted[lo - 1] - target) < abs(sorted[lo] - target)) return lo - 1
        return lo
    }

    /** Linear-interpolated percentile, numpy's default (`np.percentile`). */
    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = rank - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * The vertical distance from ([xPx], [yPx]) to the painted line's centre
     * nearby in [grey] (a full-resolution, single-byte-per-pixel Y plane,
     * [width] x [height]), or null when there is no line to find there.
     *
     * A port of /tmp/hm/cost/acc.py's `line_err`, the method the plan's
     * Task 5 brief names for this check: a grey-weighted centroid of the
     * brightest pixels in a +-12 px vertical window, counted only when that
     * window's peak clearly beats its wider surroundings (the same column's
     * own median over a +-40 px window, plus 40 grey levels). A point with no
     * such peak contributes NOTHING to the aggregate (null, not a fixed
     * penalty); a point too close to the image edge for a full window is
     * penalised at 99 px so it cannot silently vanish from the gate; a window
     * with a peak but a degenerate weighted centroid is penalised at 12 px,
     * the edge of the search window. Matches badminton-tracker's
     * `paint_line_vertical_error` in backend/scripts/verify_camera_motion.py
     * case for case.
     */
    private fun lineVerticalError(grey: ByteArray, width: Int, height: Int, xPx: Double, yPx: Double): Double? {
        if (!(xPx >= 0 && xPx < width && yPx >= 12 && yPx < height - 13)) return 99.0
        val x = xPx.roundToInt()
        fun at(row: Int): Double = (grey[row * width + x].toInt() and 0xFF).toDouble()
        val offsets = -12..12
        val column = offsets.map { dy -> at((yPx + dy).toInt().coerceIn(0, height - 1)) }
        val wideLo = max(0, yPx.toInt() - 40)
        val wideHi = min(height, yPx.toInt() + 40)
        val wideMedian = median((wideLo until wideHi).map { row -> at(row) })
        if (column.max() < wideMedian + 40) return null
        val columnMedian = median(column)
        val weights = column.map { max(0.0, it - columnMedian) }
        val maxWeight = weights.max()
        if (maxWeight <= 0) return 12.0
        val cleaned = weights.map { if (it < 0.5 * maxWeight) 0.0 else it }
        val numerator = cleaned.zip(offsets).sumOf { (w, o) -> w * o }
        return abs(numerator / cleaned.sum())
    }
}
