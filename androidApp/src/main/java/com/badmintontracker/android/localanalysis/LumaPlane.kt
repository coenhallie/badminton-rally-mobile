package com.badmintontracker.android.localanalysis

import android.media.Image
import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.camera.Luma
import java.nio.ByteBuffer

/**
 * The camera tracker's input from a decoded frame: the Y plane, averaged down.
 * Design `2026-09-27-camera-motion-design.md` §4.1.
 *
 * The plane is copied out in one bulk read into [scratch] and averaged from
 * there, because `Luma.downsample` is common code and reads a `ByteArray`, not
 * a `ByteBuffer`. [scratch] is reused across frames so a 1080p source does not
 * allocate a 2 MB array ten times a second. It is the only reuse: every call
 * returns a new [GreyImage], because the tracker holds on to the images it is
 * given before the marking frame.
 *
 * Decode thread only, like the tracker it feeds.
 */
internal class LumaPlane {
    private var scratch = ByteArray(0)

    /** Plane 0 of a `YUV_420_888` [image], at [outWidth] x [outHeight]. */
    fun downsample(image: Image, outWidth: Int, outHeight: Int): GreyImage {
        val y = image.planes[0]
        val needed = planeBytes(image.width, image.height, y.rowStride)
        if (scratch.size < needed) scratch = ByteArray(needed)
        return lumaFrom(
            y.buffer, image.width, image.height, y.rowStride, y.pixelStride,
            scratch, outWidth, outHeight,
        )
    }
}

/**
 * Bytes a plane of this size occupies. The last row counts only its pixels:
 * a decoder's buffer often ends there, with no row padding after it, so
 * asking for `rowStride * height` would underflow on a valid frame.
 */
private fun planeBytes(width: Int, height: Int, rowStride: Int): Int = rowStride * (height - 1) + width

/**
 * [LumaPlane.downsample] without the `Image`, so the layout handling is testable
 * on the JVM.
 *
 * Reads from [buffer]'s current position through a duplicate, so the buffer's
 * own position is untouched: the detector and the colour conversion read the
 * same `Image` in the same frame.
 */
internal fun lumaFrom(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    scratch: ByteArray,
    outWidth: Int,
    outHeight: Int,
): GreyImage {
    // YUV_420_888 guarantees 1 for Y. Anything else is a decoder this code has
    // never seen, and averaging interleaved bytes as if they were luma would
    // feed the tracker a picture that is not the frame.
    require(pixelStride == 1) { "Y plane pixelStride $pixelStride, expected 1" }
    require(rowStride >= width) { "Y plane rowStride $rowStride is narrower than the width $width" }
    val needed = planeBytes(width, height, rowStride)
    require(buffer.remaining() >= needed) {
        "Y plane holds ${buffer.remaining()} bytes, a $width x $height plane at rowStride $rowStride needs $needed"
    }
    require(scratch.size >= needed) { "scratch holds ${scratch.size} bytes, the plane needs $needed" }
    buffer.duplicate().get(scratch, 0, needed)
    return Luma.downsample(scratch, 0, width, height, rowStride, outWidth, outHeight)
}
