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

    /**
     * The visible [frameWidth] x [frameHeight] picture in plane 0 of a
     * `YUV_420_888` [image], at [outWidth] x [outHeight]. The frame size is the
     * container's, the size the tracker was built with; see [visibleRegion]
     * for how the picture is found inside a larger decoder buffer.
     */
    fun downsample(image: Image, frameWidth: Int, frameHeight: Int, outWidth: Int, outHeight: Int): GreyImage {
        val y = image.planes[0]
        val crop = image.cropRect?.let { Region(it.left, it.top, it.width(), it.height()) }
        val region = visibleRegion(image.width, image.height, crop, frameWidth, frameHeight)
        val needed = planeBytes(region.width, region.height, y.rowStride)
        if (scratch.size < needed) scratch = ByteArray(needed)
        return lumaFrom(y.buffer, region, y.rowStride, y.pixelStride, scratch, outWidth, outHeight)
    }
}

/** A rectangle of a decoded plane, in its pixels. */
internal data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where the picture is inside a decoded buffer of [imageWidth] x [imageHeight].
 *
 * Decoders align their buffers: 1080p commonly arrives as 1920x1088 with
 * eight junk rows below the picture, and a decoder may also place the picture
 * at an offset. So a [crop] the decoder set is the picture. Without one, or
 * with the full-buffer rectangle `Image.getCropRect()` reports when none was
 * set, the picture is the top-left [frameWidth] x [frameHeight].
 *
 * Refuses a picture of any other size than the frame's: the tracker maps its
 * pixels to source pixels through the container's size, so reading one would
 * put every mark in the wrong place rather than merely blur it.
 */
internal fun visibleRegion(
    imageWidth: Int,
    imageHeight: Int,
    crop: Region?,
    frameWidth: Int,
    frameHeight: Int,
): Region {
    val decoderSet = crop != null && crop.width > 0 && crop.height > 0 &&
        crop != Region(0, 0, imageWidth, imageHeight)
    val region = if (decoderSet) crop!! else Region(0, 0, frameWidth, frameHeight)
    require(region.width == frameWidth && region.height == frameHeight) {
        "the decoder's picture is ${region.width}x${region.height}, the container says ${frameWidth}x$frameHeight"
    }
    require(
        region.left >= 0 && region.top >= 0 &&
            region.left + region.width <= imageWidth && region.top + region.height <= imageHeight,
    ) { "the picture $region does not fit the ${imageWidth}x$imageHeight buffer" }
    return region
}

/**
 * Bytes a plane of this size occupies. The last row counts only its pixels:
 * a decoder's buffer often ends there, with no row padding after it, so
 * asking for `rowStride * height` would underflow on a valid frame.
 */
private fun planeBytes(width: Int, height: Int, rowStride: Int): Int = rowStride * (height - 1) + width

/**
 * [LumaPlane.downsample] without the `Image`, so the layout handling is testable
 * on the JVM: the [region] of a Y plane that starts at [buffer]'s position.
 *
 * Reads through a duplicate, so the buffer's own position is untouched: the
 * detector and the colour conversion read the same `Image` in the same frame.
 */
internal fun lumaFrom(
    buffer: ByteBuffer,
    region: Region,
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
    val (left, top, width, height) = region
    require(rowStride >= left + width) { "Y plane rowStride $rowStride is narrower than the picture $region" }
    val offset = top * rowStride + left
    val needed = planeBytes(width, height, rowStride)
    require(buffer.remaining() >= offset + needed) {
        "Y plane holds ${buffer.remaining()} bytes, the picture $region at rowStride $rowStride needs ${offset + needed}"
    }
    require(scratch.size >= needed) { "scratch holds ${scratch.size} bytes, the plane needs $needed" }
    val read = buffer.duplicate()
    read.position(read.position() + offset)
    read.get(scratch, 0, needed)
    return Luma.downsample(scratch, 0, width, height, rowStride, outWidth, outHeight)
}
