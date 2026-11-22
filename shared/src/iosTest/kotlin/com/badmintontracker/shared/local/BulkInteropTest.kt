package com.badmintontracker.shared.local

import com.badmintontracker.analysis.camera.Luma
import com.badmintontracker.analysis.shuttle.medianBackground
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun medianBackgroundInto_matches_shared_across_tile_boundaries() {
        val rng = Random(123)
        val frameLength = 5_000 // spans > 4096 tile boundary
        for (frameCount in listOf(1, 2, 4, 5)) {
            val framesList = List(frameCount) { ByteArray(frameLength) { rng.nextInt(256).toByte() } }
            val expected = medianBackground(framesList)
            val src = nativeHeap.allocArray<ByteVar>(frameCount * frameLength)
            val out = nativeHeap.allocArray<ByteVar>(frameLength)
            try {
                for (f in 0 until frameCount) {
                    val base = f * frameLength
                    val frame = framesList[f]
                    for (i in 0 until frameLength) src[base + i] = frame[i]
                }
                BulkInterop.medianBackgroundInto(src, frameCount, frameLength, out)
                val got = ByteArray(frameLength) { out[it] }
                assertContentEquals(expected, got, "mismatch for frameCount=$frameCount")
            } finally {
                nativeHeap.free(src.rawValue)
                nativeHeap.free(out.rawValue)
            }
        }
    }

    @Test
    fun bounds_and_overflow_checks_reject_invalid_arguments() {
        val bytes = nativeHeap.allocArray<ByteVar>(16)
        val outBytes = nativeHeap.allocArray<ByteVar>(16)
        val floats = nativeHeap.allocArray<FloatVar>(16)
        try {
            // heatmapToCoord
            assertFailsWith<IllegalArgumentException> { BulkInterop.heatmapToCoord(floats, width = 0, height = 4) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.heatmapToCoord(floats, width = 4, height = -1) }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.heatmapToCoord(floats, width = Int.MAX_VALUE / 2, height = 2)
            }

            // medianBackgroundInto
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.medianBackgroundInto(bytes, frameCount = 0, frameLength = 4, out = outBytes)
            }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.medianBackgroundInto(bytes, frameCount = -1, frameLength = 4, out = outBytes)
            }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.medianBackgroundInto(bytes, frameCount = 2, frameLength = -1, out = outBytes)
            }
            // zero frameLength is a safe no-op
            BulkInterop.medianBackgroundInto(bytes, frameCount = 2, frameLength = 0, out = outBytes)

            // lumaDownsample
            assertFailsWith<IllegalArgumentException> { BulkInterop.lumaDownsample(bytes, 0, 4, 4, 2, 2) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.lumaDownsample(bytes, 4, -1, 4, 2, 2) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.lumaDownsample(bytes, 4, 4, 3, 2, 2) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.lumaDownsample(bytes, 4, 4, 4, 0, 2) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.lumaDownsample(bytes, 4, 4, 4, 2, -1) }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.lumaDownsample(bytes, 4, 4, 4, 100_000, 100_000)
            }

            // rgbImage
            assertFailsWith<IllegalArgumentException> { BulkInterop.rgbImage(bytes, 0, 2, 8) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.rgbImage(bytes, 2, -1, 8) }
            assertFailsWith<IllegalArgumentException> { BulkInterop.rgbImage(bytes, 2, 2, 7) }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.rgbImage(bytes, width = Int.MAX_VALUE / 2, height = 1, bytesPerRow = 100)
            }
            assertFailsWith<IllegalArgumentException> {
                BulkInterop.rgbImage(bytes, width = 100_000, height = 100_000, bytesPerRow = 400_000)
            }
        } finally {
            nativeHeap.free(bytes.rawValue)
            nativeHeap.free(outBytes.rawValue)
            nativeHeap.free(floats.rawValue)
        }
    }
}
