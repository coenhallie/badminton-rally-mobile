package com.badmintontracker.android.localanalysis

import android.content.Context
import android.media.Image
import com.badmintontracker.analysis.shuttle.ShuttleDetection
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * The badminton detector, for the shuttle positions TrackNet misses.
 *
 * The cloud's per-frame track prefers TrackNet and falls back to this, and on
 * one real capture the fallback supplied 1,472 of 4,355 positions - a third.
 * Without it the fusion track and the TrackNet track are the same thing, and
 * rally counts cannot be compared with the cloud's.
 *
 * Classes are `0: person, 1: racket, 2: shuttle`; only the shuttle is used
 * here. The cloud matches its class by name against a list including
 * "shuttle", "shuttlecock", "birdie" and "ball"
 * (`modal_supabase_processor.py:2290`), which on this model resolves to index
 * 2.
 */
class DetectorRunner(context: Context) : Closeable {

    private val session = OnnxSession(ModelCatalog.path(context, Model.DETECTOR))

    companion object {
        const val SIZE = 640
        private const val SHUTTLE_CLASS = 2
        private const val CLASSES = 3

        /** Ultralytics' letterbox fill. */
        private const val PAD = 114.toByte()

        /**
         * Confidence floor. Ultralytics' predict default, which is what the
         * cloud gets by calling the model with no `conf` argument.
         */
        const val CONFIDENCE = 0.25f

        /** Ultralytics' default NMS IoU threshold. */
        const val IOU = 0.45f
    }

    private val input = FloatArray(3 * SIZE * SIZE)
    private val letterboxed = ByteArray(SIZE * SIZE * 3)

    /**
     * Detections in SOURCE pixel coordinates, centre points.
     *
     * The cloud stores a detection's `x`/`y` as the box centre, and the fusion
     * gates compare those against the court polygon, so returning corners here
     * would put every detection in the wrong place.
     */
    fun detect(image: Image): List<ShuttleDetection> {
        val srcW = image.width
        val srcH = image.height
        // Ultralytics letterboxes: scale to fit, pad the short axis, centred.
        // Resizing to a square instead would stretch the image and move every
        // box, since the model was trained on letterboxed input.
        val scale = min(SIZE.toDouble() / srcW, SIZE.toDouble() / srcH)
        val fitW = (srcW * scale).toInt()
        val fitH = (srcH * scale).toInt()
        val padX = (SIZE - fitW) / 2
        val padY = (SIZE - fitH) / 2

        java.util.Arrays.fill(letterboxed, PAD)
        FramePreprocessor.toRgbResizedInto(
            image, letterboxed, SIZE, padX, padY, fitW, fitH,
        )
        FramePreprocessor.toChwTensor(letterboxed, SIZE, SIZE, input)

        val out = session.run(input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        // Output is (1, 4 + classes, anchors), channels-first: all the cx
        // values, then all the cy values, and so on.
        val anchors = out.size / (4 + CLASSES)
        val raw = ArrayList<FloatArray>()
        for (a in 0 until anchors) {
            val score = out[(4 + SHUTTLE_CLASS) * anchors + a]
            if (score < CONFIDENCE) continue
            val cx = out[a]
            val cy = out[anchors + a]
            val w = out[2 * anchors + a]
            val h = out[3 * anchors + a]
            raw.add(floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score))
        }

        return nms(raw).map { b ->
            // Undo the letterbox, then the scale, to land back in source pixels.
            val cx = ((b[0] + b[2]) / 2 - padX) / scale
            val cy = ((b[1] + b[3]) / 2 - padY) / scale
            ShuttleDetection(
                x = cx.coerceIn(0.0, srcW.toDouble()),
                y = cy.coerceIn(0.0, srcH.toDouble()),
                confidence = b[4].toDouble(),
            )
        }
    }

    /** Greedy non-maximum suppression, highest confidence first. */
    private fun nms(boxes: List<FloatArray>): List<FloatArray> {
        val sorted = boxes.sortedByDescending { it[4] }
        val kept = ArrayList<FloatArray>()
        for (b in sorted) {
            if (kept.none { iou(it, b) > IOU }) kept.add(b)
        }
        return kept
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2])
        val y2 = min(a[3], b[3])
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() = session.close()
}
