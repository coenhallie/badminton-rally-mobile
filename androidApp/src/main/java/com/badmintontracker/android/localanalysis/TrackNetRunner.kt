package com.badmintontracker.android.localanalysis

import android.content.Context
import com.badmintontracker.analysis.shuttle.ShuttleSample
import com.badmintontracker.analysis.shuttle.heatmapToCoord
import com.badmintontracker.analysis.shuttle.medianBackground
import java.io.File

/**
 * The shuttle track, produced the way production produces it.
 *
 * Port of `TrackNetInference.track_video` (`inference.py:145-192`). Three
 * stages, and design section 5.4 is explicit that an earlier draft omitted all
 * three and that none is optional:
 *
 *  1. A median background over sampled frames, not the first frame. A median
 *     removes everything that moves; one frame contains a player mid-swing.
 *  2. Blob detection with an area filter and a weighted centroid, not an
 *     argmax. That lives in `:analysis` as `heatmapToCoord`, ported and held
 *     to production by golden vectors.
 *  3. InpaintNet gap-filling over the finished trajectory - run by
 *     [InpaintNetRunner], separately, because it is trajectory-level rather
 *     than frame-level.
 */
class TrackNetRunner(
    private val context: Context,
    private val source: VideoFrameSource,
) {
    companion object {
        const val WIDTH = 512
        const val HEIGHT = 288

        /** 8 sequence frames plus one background frame, 3 channels each. */
        const val SEQ_LEN = 8
        const val CHANNELS = (SEQ_LEN + 1) * 3

        private const val PLANE = WIDTH * HEIGHT
    }

    /**
     * @param sourceWidth source-video pixels, used only to scale the result
     *   back out of model space (`inference.py:154-155`, 178-180).
     */
    fun track(
        sourceWidth: Int,
        sourceHeight: Int,
        maxFrames: Int = Int.MAX_VALUE,
        onProgress: (Float) -> Unit = {},
    ): Map<Int, ShuttleSample> {
        val background = computeBackground()
        val session = OnnxSession(ModelCatalog.path(context, Model.TRACKNET))
        val positions = HashMap<Int, ShuttleSample>()

        val wScale = sourceWidth.toDouble() / WIDTH
        val hScale = sourceHeight.toDouble() / HEIGHT

        // One sequence's input, reused. 27 planes at 512x288 is 5.6MB as
        // floats, and allocating that per sequence would churn the heap
        // thousands of times over a match.
        val input = FloatArray(CHANNELS * PLANE)
        background.copyInto(input, 0)

        val resized = ByteArray(PLANE * 3)
        val seqIndices = IntArray(SEQ_LEN)
        var inSeq = 0
        val total = source.frameCount()

        fun flush() {
            if (inSeq == 0) return
            // Pad a short trailing sequence by repeating its last frame, as
            // production does. The padded planes are computed but their
            // outputs are discarded below: emitting them would fabricate
            // shuttle positions past the end of the video.
            for (i in inSeq until SEQ_LEN) {
                val src = (1 + inSeq - 1) * 3 * PLANE
                input.copyInto(input, (1 + i) * 3 * PLANE, src, src + 3 * PLANE)
            }
            val out = session.run(input, longArrayOf(1, CHANNELS.toLong(), HEIGHT.toLong(), WIDTH.toLong()))
            for (i in 0 until inSeq) {
                val heatmap = FloatArray(PLANE)
                out.copyInto(heatmap, 0, i * PLANE, (i + 1) * PLANE)
                val c = heatmapToCoord(heatmap, WIDTH, HEIGHT)
                positions[seqIndices[i]] =
                    if (c.visible) ShuttleSample(c.x * wScale, c.y * hScale, visible = true)
                    else ShuttleSample.INVISIBLE
            }
            inSeq = 0
        }

        session.use {
            source.forEachFrame(maxFrames) { index, _, image ->
                FramePreprocessor.toRgbResized(image, resized, WIDTH, HEIGHT)
                // Background occupies planes 0..2, so frame i starts at
                // (1 + i) * 3. Ordering matters: production concatenates
                // [background, frames], and reversing it feeds the model a
                // sequence where the background is where a frame should be.
                FramePreprocessor.toChwTensorAt(resized, WIDTH, HEIGHT, input, (1 + inSeq) * 3 * PLANE)
                seqIndices[inSeq] = index
                inSeq++
                if (inSeq == SEQ_LEN) {
                    flush()
                    if (total > 0) onProgress((index + 1).toFloat() / total)
                }
            }
            flush()
        }
        return positions
    }

    /**
     * The median background plane, already scaled to [0,1] and in CHW.
     *
     * Sampled by frame index rather than timestamp, and truncating rather than
     * rounding, both matching `_compute_median_background`. Getting either
     * wrong changes which frames form the background, which changes the
     * background, which changes every heatmap.
     */
    private fun computeBackground(): FloatArray {
        val frames = source.sampleFramesForBackground().map { bitmap ->
            val rgb = ByteArray(bitmap.width * bitmap.height * 3)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgb[i * 3] = ((p shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                rgb[i * 3 + 2] = (p and 0xFF).toByte()
            }
            val out = ByteArray(PLANE * 3)
            FramePreprocessor.resize(rgb, bitmap.width, bitmap.height, out, WIDTH, HEIGHT)
            bitmap.recycle()
            out
        }
        require(frames.isNotEmpty()) { "no frames sampled for the background" }
        val median = medianBackground(frames)
        val plane = FloatArray(3 * PLANE)
        FramePreprocessor.toChwTensor(median, WIDTH, HEIGHT, plane)
        return plane
    }
}
