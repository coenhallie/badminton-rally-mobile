package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import kotlin.math.max

/**
 * The camera tracker's input: the luma plane, area-averaged down so its long
 * side is [TRACKER_LONG_SIDE]. Design §4.1.
 *
 * Luma, not RGB: the tracker needs texture, the Y plane is already decoded on
 * both phones, and reading one plane is a third of the work of three.
 */
object Luma {
    const val TRACKER_LONG_SIDE = 480

    /** The tracker's image size for a source of this size. Orientation is kept. */
    fun trackerSize(width: Int, height: Int): Pair<Int, Int> {
        require(width >= 1 && height >= 1)
        val long = max(width, height)
        if (long <= TRACKER_LONG_SIDE) return width to height
        return if (width >= height) {
            TRACKER_LONG_SIDE to max(1, (height.toLong() * TRACKER_LONG_SIDE / width).toInt())
        } else {
            max(1, (width.toLong() * TRACKER_LONG_SIDE / height).toInt()) to TRACKER_LONG_SIDE
        }
    }

    /**
     * Area average of an 8-bit plane. Row `y` starts at `offset + y * rowStride`;
     * bytes read unsigned.
     */
    fun downsample(
        plane: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        outWidth: Int,
        outHeight: Int,
    ): GreyImage = downsampleWith(width, height, outWidth, outHeight) { x, y ->
        plane[offset + y * rowStride + x].toInt() and 0xFF
    }

    /**
     * The same average over any pixel source, for a caller whose plane is not a
     * ByteArray (iOS reads a locked CVPixelBuffer through a pointer).
     *
     * Output pixel `(ox, oy)` averages source columns
     * `floor(ox * W / w) until max(that + 1, floor((ox + 1) * W / w))`, and rows
     * likewise, so every source pixel counts once and a non-integer ratio still
     * averages whole pixels.
     */
    inline fun downsampleWith(
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        read: (x: Int, y: Int) -> Int,
    ): GreyImage {
        val x0 = IntArray(outWidth) { (it.toLong() * width / outWidth).toInt() }
        val x1 = IntArray(outWidth) { max(x0[it] + 1, ((it + 1).toLong() * width / outWidth).toInt()) }
        val out = FloatArray(outWidth * outHeight)
        for (oy in 0 until outHeight) {
            val y0 = (oy.toLong() * height / outHeight).toInt()
            val y1 = max(y0 + 1, ((oy + 1).toLong() * height / outHeight).toInt())
            for (ox in 0 until outWidth) {
                var sum = 0L
                for (y in y0 until y1) for (x in x0[ox] until x1[ox]) sum += read(x, y)
                out[oy * outWidth + ox] = sum.toFloat() / ((y1 - y0) * (x1[ox] - x0[ox]))
            }
        }
        return GreyImage(outWidth, outHeight, out)
    }

    /**
     * Tracker pixels to source pixels, by pixel centres:
     * `x_src = (x_t + 0.5) * sx - 0.5`. Design §4.1.
     */
    fun trackerToSource(width: Int, height: Int, trackerWidth: Int, trackerHeight: Int): Matrix3x3 {
        val sx = width.toDouble() / trackerWidth
        val sy = height.toDouble() / trackerHeight
        return listOf(
            listOf(sx, 0.0, 0.5 * sx - 0.5),
            listOf(0.0, sy, 0.5 * sy - 0.5),
            listOf(0.0, 0.0, 1.0),
        )
    }
}
