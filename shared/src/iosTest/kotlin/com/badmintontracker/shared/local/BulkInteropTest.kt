package com.badmintontracker.shared.local

import com.badmintontracker.analysis.camera.Luma
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The luma plane read in place, through a pointer, averages exactly what the
 * shared downsample does over the same bytes in a `ByteArray`.
 *
 * The two differ only in how a pixel is read, and that read is where an iOS bug
 * would live: a row stride taken as the width, or a byte read signed. So the
 * plane here has padding past each row filled with a value the pattern never
 * uses, and a pattern that crosses 127.
 */
@OptIn(ExperimentalForeignApi::class)
class BulkInteropTest {

    private fun assertMatchesShared(width: Int, height: Int, rowStride: Int, outWidth: Int, outHeight: Int) {
        val size = rowStride * height
        val bytes = ByteArray(size) { i ->
            val x = i % rowStride
            val y = i / rowStride
            // Padding is 0xFF; the pattern stays in 0..250 and covers the
            // unsigned half of the byte.
            if (x >= width) 0xFF.toByte() else ((x * 37 + y * 101 + x * y * 7) % 251).toByte()
        }
        val plane = nativeHeap.allocArray<ByteVar>(size)
        try {
            bytes.forEachIndexed { i, b -> plane[i] = b }
            val got = BulkInterop.lumaDownsample(plane, width, height, rowStride, outWidth, outHeight)
            val want = Luma.downsample(bytes, 0, width, height, rowStride, outWidth, outHeight)
            assertEquals(outWidth, got.width)
            assertEquals(outHeight, got.height)
            assertContentEquals(want.pixels, got.pixels)
        } finally {
            nativeHeap.free(plane.rawValue)
        }
    }

    @Test
    fun a_padded_plane_averages_as_the_shared_downsample_does_at_a_non_integer_ratio() =
        assertMatchesShared(width = 37, height = 23, rowStride = 48, outWidth = 10, outHeight = 7)

    @Test
    fun a_plane_already_at_tracker_size_is_read_pixel_for_pixel() =
        assertMatchesShared(width = 37, height = 23, rowStride = 48, outWidth = 37, outHeight = 23)

    @Test
    fun a_plane_read_unsigned_keeps_values_above_127() {
        val plane = nativeHeap.allocArray<ByteVar>(4)
        try {
            plane[0] = 200.toByte(); plane[1] = 255.toByte(); plane[2] = 0; plane[3] = 128.toByte()
            val got = BulkInterop.lumaDownsample(plane, 2, 2, 2, 2, 2)
            assertContentEquals(floatArrayOf(200f, 255f, 0f, 128f), got.pixels)
        } finally {
            nativeHeap.free(plane.rawValue)
        }
    }

    /**
     * A 2x2 RGBA buffer as a `CGContext` lays it down, rows [bytesPerRow]
     * apart, read into the court finder's ARGB ints: red, green, blue and a
     * colour whose channels differ, alpha ignored. Padding past each row is
     * filled with a value no pixel uses, so a stride taken as the width shows.
     */
    private fun assertReadsRgba(bytesPerRow: Int) {
        val rows = listOf(
            listOf(255, 0, 0, 255, 0, 255, 0, 255),
            listOf(0, 0, 255, 255, 10, 20, 30, 255),
        )
        val size = bytesPerRow * rows.size
        val buffer = nativeHeap.allocArray<ByteVar>(size)
        try {
            for (i in 0 until size) buffer[i] = 0x77
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, v -> buffer[y * bytesPerRow + x] = v.toByte() } }
            val image = BulkInterop.rgbImage(buffer, 2, 2, bytesPerRow)
            assertEquals(2, image.width)
            assertEquals(2, image.height)
            assertContentEquals(
                intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF0A141E.toInt()),
                image.argb,
            )
        } finally {
            nativeHeap.free(buffer.rawValue)
        }
    }

    @Test
    fun rgba_rows_become_argb_pixels() = assertReadsRgba(bytesPerRow = 8)

    @Test
    fun padded_rgba_rows_read_the_same() = assertReadsRgba(bytesPerRow = 12)
}
