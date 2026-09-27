package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith

class LumaPlaneTest {

    /**
     * A Y plane as a decoder lays it out: [rowStride] bytes per row, the bytes
     * past [width] padding, and no padding after the last row (a real decoder's
     * buffer often ends at the last pixel). Pixel `(x, y)` is `10 * y + x`; the
     * padding is 255 so any read of it shows in an average.
     */
    private fun plane(width: Int, height: Int, rowStride: Int, lead: Int = 0): ByteBuffer {
        val bytes = ByteArray(lead + rowStride * (height - 1) + width) { 255.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            bytes[lead + y * rowStride + x] = (10 * y + x).toByte()
        }
        return ByteBuffer.wrap(bytes).apply { position(lead) }
    }

    @Test
    fun row_padding_is_skipped_and_every_pixel_read_as_is() {
        val image = lumaFrom(plane(4, 3, rowStride = 8), 4, 3, 8, 1, ByteArray(64), 4, 3)

        image.width shouldBe 4
        image.height shouldBe 3
        image.pixels.toList() shouldBe listOf(
            0f, 1f, 2f, 3f,
            10f, 11f, 12f, 13f,
            20f, 21f, 22f, 23f,
        )
    }

    @Test
    fun a_smaller_output_averages_whole_source_pixels_and_never_the_padding() {
        val image = lumaFrom(plane(4, 2, rowStride = 6), 4, 2, 6, 1, ByteArray(64), 2, 1)

        // (0 + 1 + 10 + 11) / 4 and (2 + 3 + 12 + 13) / 4.
        image.pixels.toList() shouldBe listOf(5.5f, 7.5f)
    }

    @Test
    fun the_plane_starts_at_the_buffer_position_and_the_position_is_left_alone() {
        val buffer = plane(3, 2, rowStride = 4, lead = 5)

        val image = lumaFrom(buffer, 3, 2, 4, 1, ByteArray(64), 3, 2)

        image.pixels.toList() shouldBe listOf(0f, 1f, 2f, 10f, 11f, 12f)
        // The detector and the colour conversion read the same Image after this.
        buffer.position() shouldBe 5
    }

    @Test
    fun bytes_above_127_read_unsigned() {
        val buffer = ByteBuffer.wrap(byteArrayOf(200.toByte(), 255.toByte()))

        lumaFrom(buffer, 2, 1, 2, 1, ByteArray(2), 2, 1).pixels.toList() shouldBe listOf(200f, 255f)
    }

    @Test
    fun a_pixel_stride_other_than_one_is_refused_by_name() {
        val e = assertFailsWith<IllegalArgumentException> {
            lumaFrom(plane(4, 2, rowStride = 8), 4, 2, 8, 2, ByteArray(64), 4, 2)
        }
        e.message!! shouldContain "pixelStride 2"
    }

    @Test
    fun a_buffer_shorter_than_the_plane_is_refused_rather_than_underflowing() {
        val short = ByteBuffer.wrap(ByteArray(10))

        val e = assertFailsWith<IllegalArgumentException> {
            lumaFrom(short, 4, 3, 4, 1, ByteArray(64), 4, 3)
        }
        e.message!! shouldContain "12"
    }

    @Test
    fun a_scratch_array_too_small_for_the_plane_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            lumaFrom(plane(4, 3, rowStride = 8), 4, 3, 8, 1, ByteArray(8), 4, 3)
        }
    }
}
