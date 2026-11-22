package com.badmintontracker.shared.local

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.camera.Luma
import com.badmintontracker.analysis.court.RgbImage
import com.badmintontracker.analysis.shuttle.HEATMAP_MAX_AREA
import com.badmintontracker.analysis.shuttle.HEATMAP_THRESHOLD
import com.badmintontracker.analysis.shuttle.HeatmapCoord
import com.badmintontracker.analysis.shuttle.heatmapToCoord
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Bulk buffers across the Swift boundary, by pointer.
 *
 * Three functions in `:analysis` take arrays of pixels: [heatmapToCoord] takes
 * a `FloatArray` of 147,456 floats, the median background reduces 300 frames
 * of 442,368 bytes each, and the camera tracker's [Luma.downsample]
 * ([lumaDownsample] here) reads a 1080p luma plane of about 2 MB ten times a
 * second of video. All three are the shared implementations the whole port
 * exists to reuse - the design's section 3.1 is explicit that reimplementing
 * the blob detector in Swift is the one thing this stage must not do, and the
 * camera design keeps one tracker for both phones for the same reason.
 *
 * They cannot be called from Swift as they stand. `KotlinFloatArray` is
 * constructed from Swift one element at a time through a boxing closure, and
 * this pipeline would make roughly 900 million of those calls over a match.
 * That is not a slow path, it is an impossible one, and it is exactly the kind
 * of pressure that ends with a second implementation in Swift "for performance".
 *
 * So the boundary moves: Swift passes a raw pointer to memory it already holds,
 * and the copy into Kotlin's heap happens here as a `memcpy` on the native
 * side. One 590KB copy per sequence against an inference that costs hundreds of
 * milliseconds. [lumaDownsample] copies nothing at all: it reads the locked
 * plane through the pointer and returns only the 480x270 tracker image.
 *
 * **The caller owns the memory and must keep it alive across the call.** Every
 * function here reads its pointer and returns before Swift's buffer goes out of
 * scope, which is what `withUnsafeBufferPointer` at the call site guarantees.
 */
@OptIn(ExperimentalForeignApi::class)
object BulkInterop {

    /**
     * [heatmapToCoord] over a `Float` buffer Swift owns.
     *
     * [heatmap] must point at at least `width * height` floats, contiguously.
     */
    fun heatmapToCoord(
        heatmap: CPointer<FloatVar>,
        width: Int,
        height: Int,
        threshold: Float = HEATMAP_THRESHOLD,
        maxArea: Int = HEATMAP_MAX_AREA,
    ): HeatmapCoord {
        require(width > 0 && height > 0) { "heatmap dimensions must be positive: ${width}x$height" }
        val countLong = width.toLong() * height.toLong()
        require(countLong <= Int.MAX_VALUE / 4) { "heatmap buffer size overflow: ${width}x$height" }
        val count = countLong.toInt()
        val copy = FloatArray(count)
        copy.usePinned { memcpy(it.addressOf(0), heatmap, (countLong * 4L).toULong()) }
        return heatmapToCoord(copy, width, height, threshold, maxArea)
    }

    /**
     * The median background over [frameCount] frames laid end to end in one
     * buffer, writing the result into [out].
     *
     * One contiguous buffer rather than a list of pointers because that is the
     * shape the caller already has: the background pass appends each resized
     * frame to a single array, exactly as it would have to for a list, and a
     * list of pointers would additionally have to survive the ObjC bridge.
     *
     * [out] must point at at least [frameLength] bytes.
     */
    fun medianBackgroundInto(
        frames: CPointer<ByteVar>,
        frameCount: Int,
        frameLength: Int,
        out: CPointer<ByteVar>,
    ) {
        require(frameCount > 0) { "frameCount must be positive: $frameCount" }
        require(frameLength >= 0) { "frameLength must be non-negative: $frameLength" }
        if (frameLength == 0) return
        val totalBytes = frameCount.toLong() * frameLength.toLong()
        require(totalBytes >= 0L && totalBytes / frameCount.toLong() == frameLength.toLong()) {
            "frames buffer size overflow: $frameCount x $frameLength"
        }
        if (frameCount == 1) {
            memcpy(out, frames, frameLength.toULong())
            return
        }

        // Read directly from the raw UByte pointer in cache-friendly tiles (no
        // second 132MB copy and no non-inline boxing lambda across 132.7M samples),
        // reducing each column with a 256-bucket counting histogram that matches
        // `np.median(...).astype(np.uint8)` truncation exactly.
        val src = frames.reinterpret<UByteVar>()
        val dst = out.reinterpret<UByteVar>()
        val hist = IntArray(256)
        val half = frameCount / 2
        val odd = (frameCount and 1) == 1
        val stride = frameLength.toLong()
        val tileSize = (2 * 1024 * 1024 / frameCount).coerceIn(1, 4096)

        if (tileSize == 1) {
            for (i in 0 until frameLength) {
                hist.fill(0)
                for (f in 0 until frameCount) {
                    hist[src[f.toLong() * stride + i].toInt()]++
                }
                dst[i] = histogramMedian(hist, half, odd).toUByte()
            }
            return
        }

        val tileBuf = ByteArray(tileSize * frameCount)
        var tileStart = 0
        while (tileStart < frameLength) {
            val len = minOf(tileSize, frameLength - tileStart)
            for (f in 0 until frameCount) {
                val frameBase = f.toLong() * stride + tileStart
                var j = 0
                while (j < len) {
                    tileBuf[j * frameCount + f] = src[frameBase + j].toByte()
                    j++
                }
            }
            var j = 0
            while (j < len) {
                hist.fill(0)
                val colBase = j * frameCount
                var f = 0
                while (f < frameCount) {
                    hist[tileBuf[colBase + f].toInt() and 0xFF]++
                    f++
                }
                dst[tileStart + j] = histogramMedian(hist, half, odd).toUByte()
                j++
            }
            tileStart += len
        }
    }

    private fun histogramMedian(hist: IntArray, half: Int, odd: Boolean): Int {
        if (odd) {
            var acc = 0
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc > half) return v
            }
            return 255
        }
        val lowTarget = half - 1
        var acc = 0
        var low = -1
        for (v in 0 until 256) {
            acc += hist[v]
            if (low < 0 && acc > lowTarget) low = v
            if (acc > half) return (low + v) ushr 1
        }
        return 255
    }

    /**
     * [Luma.downsample] over a luma plane Swift has locked: the camera
     * tracker's input, read in place.
     *
     * [plane] is the decoded `CVPixelBuffer`'s plane 0
     * (`CVPixelBufferGetBaseAddressOfPlane(buf, 0)`), its rows
     * [rowStride] bytes apart (`CVPixelBufferGetBytesPerRowOfPlane(buf, 0)`),
     * which is at least [width] and is often more. The buffer must stay locked
     * for the call. Reading through the pointer rather than copying first is
     * the point: at 1080p the plane is 2 MB, and a `ByteArray` of it built
     * from Swift would cross the bridge one element at a time, ten times a
     * second of video. What comes back is the tracker-size image, 480x270.
     *
     * `@Throws` so that a failure reaches Swift as an error it can catch: the
     * camera is an extra, and losing it must not end the run, let alone the
     * process.
     */
    @Throws(Throwable::class)
    fun lumaDownsample(
        plane: CPointer<ByteVar>,
        width: Int,
        height: Int,
        rowStride: Int,
        outWidth: Int,
        outHeight: Int,
    ): GreyImage {
        require(width > 0 && height > 0) { "source dimensions must be positive: ${width}x$height" }
        require(outWidth > 0 && outHeight > 0) { "output dimensions must be positive: ${outWidth}x$outHeight" }
        require(rowStride >= width) { "rowStride $rowStride smaller than width $width" }
        val outPixels = outWidth.toLong() * outHeight.toLong()
        require(outPixels <= Int.MAX_VALUE) { "output image size overflow: ${outWidth}x$outHeight" }
        return Luma.downsampleWith(width, height, outWidth, outHeight) { x, y ->
            plane[y.toLong() * rowStride + x].toInt() and 0xFF
        }
    }

    /**
     * The court finder's input from an RGBA8 bitmap Swift has drawn a frame
     * into: [pixels] holds [height] rows [bytesPerRow] apart (at least
     * `4 * width`), each pixel red, green, blue, alpha. Alpha is ignored, a
     * video frame is opaque. Read through the pointer into one ARGB int per
     * pixel, the layout [RgbImage] takes on both platforms.
     *
     * `@Throws` so a bad size reaches Swift as an error it can catch: the
     * marking screen must stay usable when the finder cannot run.
     */
    @Throws(Throwable::class)
    fun rgbImage(pixels: CPointer<ByteVar>, width: Int, height: Int, bytesPerRow: Int): RgbImage {
        require(width > 0 && height > 0) { "empty image $width x $height" }
        require(bytesPerRow.toLong() >= 4L * width.toLong()) { "$bytesPerRow bytes per row for $width pixels" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE) { "image size overflow: ${width}x$height" }
        val argb = IntArray(pixelCount.toInt())
        for (y in 0 until height) {
            val row = y.toLong() * bytesPerRow.toLong()
            val out = y * width
            for (x in 0 until width) {
                val at = row + 4L * x
                val r = pixels[at].toInt() and 0xFF
                val g = pixels[at + 1].toInt() and 0xFF
                val b = pixels[at + 2].toInt() and 0xFF
                argb[out + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(width, height, argb)
    }

    /**
     * Reads one float out of a Swift-owned buffer.
     *
     * Present only so a test can prove the pointer arithmetic above lines up
     * with what Swift laid down; nothing in the pipeline calls it.
     */
    fun floatAt(buffer: CPointer<FloatVar>, index: Int): Float = buffer[index]
}
