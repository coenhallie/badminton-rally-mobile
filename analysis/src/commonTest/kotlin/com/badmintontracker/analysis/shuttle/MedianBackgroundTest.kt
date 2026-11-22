package com.badmintontracker.analysis.shuttle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MedianBackgroundTest {

    private fun bruteForceMedian(frames: List<ByteArray>): ByteArray {
        val frameCount = frames.size
        val size = frames[0].size
        val out = ByteArray(size)
        val column = IntArray(frameCount)
        for (i in 0 until size) {
            for (f in 0 until frameCount) {
                column[f] = frames[f][i].toInt() and 0xFF
            }
            column.sort()
            val median = if (frameCount % 2 == 1) {
                column[frameCount / 2].toDouble()
            } else {
                (column[frameCount / 2 - 1] + column[frameCount / 2]) / 2.0
            }
            out[i] = median.toInt().toByte()
        }
        return out
    }

    @Test
    fun matches_brute_force_sort_for_odd_and_even_counts_across_tile_boundaries() {
        val rng = Random(42)
        val size = 5_000 // > 4096 tile size so multi-tile boundaries are exercised
        for (frameCount in listOf(1, 2, 3, 4, 5, 10, 11, 32)) {
            val frames = List(frameCount) { ByteArray(size) { rng.nextInt(256).toByte() } }
            val expected = bruteForceMedian(frames)

            assertContentEquals(expected, medianBackground(frames), "medianBackground mismatch for frameCount=$frameCount")

            val into = ByteArray(size)
            medianBackgroundInto(into, frameCount, size) { frame, index ->
                frames[frame][index].toInt() and 0xFF
            }
            assertContentEquals(expected, into, "medianBackgroundInto mismatch for frameCount=$frameCount")
        }
    }

    @Test
    fun even_counts_truncate_half_step_medians_across_unsigned_byte_range() {
        val a = byteArrayOf(0, 10, 127, 254.toByte(), 0, 255.toByte())
        val b = byteArrayOf(1, 11, 128.toByte(), 255.toByte(), 255.toByte(), 255.toByte())
        val expected = byteArrayOf(0, 10, 127, 254.toByte(), 127, 255.toByte())

        assertContentEquals(expected, medianBackground(listOf(a, b)))

        val into = ByteArray(a.size)
        val frames = listOf(a, b)
        medianBackgroundInto(into, 2, a.size) { f, i -> frames[f][i].toInt() and 0xFF }
        assertContentEquals(expected, into)
    }

    @Test
    fun empty_frame_length_returns_empty_array() {
        val out = medianBackground(listOf(ByteArray(0), ByteArray(0)))
        assertEquals(0, out.size)
    }

    @Test
    fun medianBackgroundInto_rejects_invalid_bounds() {
        assertFailsWith<IllegalArgumentException> {
            medianBackgroundInto(ByteArray(4), frameCount = 0, frameLength = 4) { _, _ -> 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            medianBackgroundInto(ByteArray(4), frameCount = -1, frameLength = 4) { _, _ -> 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            medianBackgroundInto(ByteArray(4), frameCount = 2, frameLength = -1) { _, _ -> 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            medianBackgroundInto(ByteArray(3), frameCount = 2, frameLength = 4) { _, _ -> 0 }
        }
    }
}
