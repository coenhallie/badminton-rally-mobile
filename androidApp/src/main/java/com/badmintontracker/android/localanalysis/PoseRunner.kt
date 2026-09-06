package com.badmintontracker.android.localanalysis

import android.media.Image
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.PoseFrame
import com.badmintontracker.analysis.player.PosePerson
import java.io.Closeable
import kotlin.math.min

/**
 * Pose keypoints for the people in one frame.
 *
 * `yolo26n-pose` at 960, chosen by measurement rather than by model card. On an
 * S23 the medium model the cloud uses costs about 1570ms a frame cold and 2200
 * settled, which is eight hours for an eight-minute video; nano is 230ms. Nano's
 * one real weakness is far-player coverage, 44% of frames against 93% near, and
 * this pipeline tracks only the near player, so that weakness does not apply.
 *
 * 960 rather than 640 even though 640 is twice as fast and the difference is
 * immaterial for a heatmap. At 640 the worst joint is the wrist, which is the
 * fastest-moving joint on a racket arm and the one a coach looks at.
 *
 * Unlike [DetectorRunner] there is no NMS here. This export is end-to-end: the
 * graph emits a fixed 300 rows with NMS already applied, sorted by confidence,
 * so the work is a threshold and un-letterboxing rather than anchor decoding.
 * The layout was confirmed against Ultralytics' own output on a real frame
 * rather than assumed, and agrees to about a pixel.
 */
class PoseRunner(modelPath: String) : Closeable {

    private val session = OnnxSession(modelPath)
    private val input = FloatArray(3 * SIZE * SIZE)
    private val letterboxed = ByteArray(SIZE * SIZE * 3)

    fun detect(frame: Int, image: Image): PoseFrame {
        val srcW = image.width
        val srcH = image.height
        // Ultralytics letterboxes: scale to fit, pad the short axis, centred.
        val scale = min(SIZE.toDouble() / srcW, SIZE.toDouble() / srcH)
        val fitW = (srcW * scale).toInt()
        val fitH = (srcH * scale).toInt()
        val padX = (SIZE - fitW) / 2
        val padY = (SIZE - fitH) / 2

        java.util.Arrays.fill(letterboxed, PAD)
        FramePreprocessor.toRgbResizedInto(image, letterboxed, SIZE, padX, padY, fitW, fitH)
        FramePreprocessor.toChwTensor(letterboxed, SIZE, SIZE, input)

        val out = session.run(input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        return PoseFrame(frame, decode(out, scale, padX.toDouble(), padY.toDouble()))
    }

    private fun decode(out: FloatArray, scale: Double, padX: Double, padY: Double): List<PosePerson> {
        val rows = out.size / STRIDE
        val people = ArrayList<PosePerson>()
        for (r in 0 until rows) {
            val base = r * STRIDE
            val confidence = out[base + 4]
            // Sorted descending, and the tail is zero padding, so the first row
            // below the threshold ends the list rather than merely being skipped.
            if (confidence < CONFIDENCE) break
            if (out[base + 5].toInt() != PERSON_CLASS) continue

            val keypoints = ArrayList<Point>(Coco.COUNT)
            val keypointConfidence = ArrayList<Float>(Coco.COUNT)
            for (k in 0 until Coco.COUNT) {
                val kb = base + KEYPOINTS_OFFSET + k * 3
                keypoints.add(
                    Point(
                        (out[kb] - padX) / scale,
                        (out[kb + 1] - padY) / scale,
                    ),
                )
                keypointConfidence.add(out[kb + 2])
            }
            people.add(PosePerson(confidence, keypoints, keypointConfidence))
        }
        return people
    }

    override fun close() = session.close()

    companion object {
        const val SIZE = 960

        /** Ultralytics' letterbox grey. */
        private const val PAD: Byte = 114.toByte()

        /** Ultralytics' default, and low enough that the crowd is filtered by court position instead. */
        private const val CONFIDENCE = 0.25f

        private const val PERSON_CLASS = 0

        /** x1, y1, x2, y2, confidence, class, then 17 x (x, y, confidence). */
        private const val KEYPOINTS_OFFSET = 6
        private const val STRIDE = KEYPOINTS_OFFSET + Coco.COUNT * 3
    }
}
