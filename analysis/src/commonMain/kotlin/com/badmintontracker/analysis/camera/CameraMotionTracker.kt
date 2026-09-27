package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.analysis.geometry.multiply3x3
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Follows the camera through one decode pass. Design §4.
 *
 * Construct with the marking time `T` and the source size; call [wants] for
 * every decoded frame and [offer] with the downsampled luma when it says yes;
 * call [finish] after the last frame. Single-threaded: the decode thread owns
 * it.
 */
class CameraMotionTracker(
    private val markingTimeS: Double,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) {
    val trackerWidth: Int
    val trackerHeight: Int
    private val toSource: Matrix3x3
    private val fromSource: Matrix3x3

    init {
        val (w, h) = Luma.trackerSize(sourceWidth, sourceHeight)
        trackerWidth = w
        trackerHeight = h
        toSource = Luma.trackerToSource(sourceWidth, sourceHeight, w, h)
        fromSource = invert3x3(toSource)!!
    }

    var acceptedSamples: Int = 0
        private set
    var droppedSamples: Int = 0
        private set

    private var lastBucket = Long.MIN_VALUE
    private var templates: AnchoredTemplates? = null
    private var referenceTimestamp = 0.0
    private var gaveUp = false
    private val pending = ArrayList<Pair<Double, GreyImage>>()
    private val samples = ArrayList<CameraSample>()
    private var guess: Matrix3x3 = IDENTITY

    private fun bucket(t: Double): Long = floor(t / SAMPLE_INTERVAL_S + 1e-9).toLong()

    /**
     * Whether the frame at [timestamp] should be offered: the first frame of
     * each 0.1 s bucket, and the marking frame (the first at or after `T`)
     * whether or not it starts a bucket.
     */
    fun wants(timestamp: Double): Boolean {
        if (gaveUp) return false
        if (templates == null && timestamp >= markingTimeS - CameraMotion.EXACT_S) return true
        return bucket(timestamp) > lastBucket
    }

    /** One sampled frame's luma at tracker size. */
    fun offer(timestamp: Double, image: GreyImage) {
        require(image.width == trackerWidth && image.height == trackerHeight) {
            "expected $trackerWidth x $trackerHeight, got ${image.width} x ${image.height}"
        }
        if (bucket(timestamp) > lastBucket) lastBucket = bucket(timestamp)
        if (gaveUp) return
        if (templates == null) {
            if (timestamp < markingTimeS - CameraMotion.EXACT_S) {
                pending.add(timestamp to image)
                return
            }
            val reference = pyramid(image, PYRAMID_LEVELS)
            val corners = shiTomasi(image)
            if (corners.size < MIN_CORNERS) {
                gaveUp = true
                pending.clear()
                return
            }
            templates = AnchoredTemplates(reference, corners)
            referenceTimestamp = timestamp
            samples.add(CameraSample(timestamp, IDENTITY))
            acceptedSamples++
            // Earlier frames, nearest the marking frame first, each guessing
            // from the one after it; then the forward pass resumes from identity.
            guess = IDENTITY
            for ((t, img) in pending.sortedByDescending { it.first }) track(t, img)
            pending.clear()
            guess = IDENTITY
            return
        }
        track(timestamp, image)
    }

    private fun track(timestamp: Double, image: GreyImage) {
        val t = templates ?: return
        val target = pyramid(image, PYRAMID_LEVELS)
        val guesses = DoubleArray(t.points.size * 2)
        t.points.forEachIndexed { i, (x, y) ->
            val g = guess.apply(x, y)
            guesses[2 * i] = g?.x ?: x
            guesses[2 * i + 1] = g?.y ?: y
        }
        val (found, ok) = t.track(target, guesses)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        t.points.forEachIndexed { i, (x, y) ->
            if (ok[i]) {
                src.add(Point(x, y))
                dst.add(Point(found[2 * i], found[2 * i + 1]))
            }
        }
        val fit = if (src.size >= MIN_INLIERS) ransacHomography(src, dst) else null
        if (fit == null || fit.inliers < MIN_INLIERS || !plausible(fit.homography)) {
            droppedSamples++
            return
        }
        guess = fit.homography
        val inSource = normalise(multiply3x3(toSource, multiply3x3(fit.homography, fromSource)))!!
        samples.add(CameraSample(timestamp, inSource))
        acceptedSamples++
    }

    /** Design §4.3 step 5: every image corner moves at most a quarter of the width, and no flip or collapse. */
    private fun plausible(h: Matrix3x3): Boolean {
        val limit = MAX_CORNER_SHIFT * trackerWidth
        for ((x, y) in listOf(0.0 to 0.0, trackerWidth.toDouble() to 0.0,
            trackerWidth.toDouble() to trackerHeight.toDouble(), 0.0 to trackerHeight.toDouble())) {
            val p = h.apply(x, y) ?: return false
            if (hypot(p.x - x, p.y - y) > limit) return false
        }
        val det = h[0][0] * h[1][1] - h[0][1] * h[1][0]
        return det in 0.5..2.0
    }

    /** The motion so far, or null when no marking frame was ever tracked. */
    fun finish(): CameraMotion? {
        if (templates == null) return null
        return CameraMotion.of(referenceTimestamp, samples)
    }

    companion object {
        const val SAMPLE_INTERVAL_S = 0.1
        const val MIN_INLIERS = 40
        const val MIN_CORNERS = 40
        const val MAX_CORNER_SHIFT = 0.25
        private val IDENTITY: Matrix3x3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    }
}
