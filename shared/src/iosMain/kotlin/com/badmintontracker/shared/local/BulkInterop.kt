package com.badmintontracker.shared.local

import com.badmintontracker.analysis.shuttle.HEATMAP_MAX_AREA
import com.badmintontracker.analysis.shuttle.HEATMAP_THRESHOLD
import com.badmintontracker.analysis.shuttle.HeatmapCoord
import com.badmintontracker.analysis.shuttle.heatmapToCoord
import com.badmintontracker.analysis.shuttle.medianBackgroundInto
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * Bulk buffers across the Swift boundary, by pointer.
 *
 * Two functions in `:analysis` take arrays of pixels: [heatmapToCoord] takes a
 * `FloatArray` of 147,456 floats, and the median background reduces 300 frames
 * of 442,368 bytes each. Both are the shared implementations the whole port exists to
 * reuse - the design's section 3.1 is explicit that reimplementing the blob
 * detector in Swift is the one thing this stage must not do.
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
 * milliseconds.
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
        val count = width * height
        val copy = FloatArray(count)
        copy.usePinned { memcpy(it.addressOf(0), heatmap, (count * 4).toULong()) }
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
        // Read through an accessor rather than copied into Kotlin ByteArrays.
        // The list form would build a second 132MB copy of what Swift already
        // holds, live at the same time as the first and at the same moment as
        // three ONNX sessions - which is the memory profile Android's own
        // decode layer records as having killed a 2GB device.
        val median = ByteArray(frameLength)
        medianBackgroundInto(median, frameCount, frameLength) { frame, index ->
            frames[frame.toLong() * frameLength + index].toInt() and 0xFF
        }
        median.usePinned { memcpy(out, it.addressOf(0), frameLength.toULong()) }
    }

    /**
     * Reads one float out of a Swift-owned buffer.
     *
     * Present only so a test can prove the pointer arithmetic above lines up
     * with what Swift laid down; nothing in the pipeline calls it.
     */
    fun floatAt(buffer: CPointer<FloatVar>, index: Int): Float = buffer[index]
}
