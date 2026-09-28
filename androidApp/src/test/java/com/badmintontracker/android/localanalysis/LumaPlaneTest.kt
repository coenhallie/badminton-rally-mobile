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
        val image = lumaFrom(plane(4, 3, rowStride = 8), Region(0, 0, 4, 3), 8, 1, ByteArray(64), 4, 3)

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
        val image = lumaFrom(plane(4, 2, rowStride = 6), Region(0, 0, 4, 2), 6, 1, ByteArray(64), 2, 1)

        // (0 + 1 + 10 + 11) / 4 and (2 + 3 + 12 + 13) / 4.
        image.pixels.toList() shouldBe listOf(5.5f, 7.5f)
    }

    @Test
    fun the_plane_starts_at_the_buffer_position_and_the_position_is_left_alone() {
        val buffer = plane(3, 2, rowStride = 4, lead = 5)

        val image = lumaFrom(buffer, Region(0, 0, 3, 2), 4, 1, ByteArray(64), 3, 2)

        image.pixels.toList() shouldBe listOf(0f, 1f, 2f, 10f, 11f, 12f)
        // The detector and the colour conversion read the same Image after this.
        buffer.position() shouldBe 5
    }

    @Test
    fun bytes_above_127_read_unsigned() {
        val buffer = ByteBuffer.wrap(byteArrayOf(200.toByte(), 255.toByte()))

        lumaFrom(buffer, Region(0, 0, 2, 1), 2, 1, ByteArray(2), 2, 1).pixels.toList() shouldBe listOf(200f, 255f)
    }

    @Test
    fun a_pixel_stride_other_than_one_is_refused_by_name() {
        val e = assertFailsWith<IllegalArgumentException> {
            lumaFrom(plane(4, 2, rowStride = 8), Region(0, 0, 4, 2), 8, 2, ByteArray(64), 4, 2)
        }
        e.message!! shouldContain "pixelStride 2"
    }

    @Test
    fun a_buffer_shorter_than_the_plane_is_refused_rather_than_underflowing() {
        val short = ByteBuffer.wrap(ByteArray(10))

        val e = assertFailsWith<IllegalArgumentException> {
            lumaFrom(short, Region(0, 0, 4, 3), 4, 1, ByteArray(64), 4, 3)
        }
        e.message!! shouldContain "12"
    }

    @Test
    fun a_1088_row_decoder_buffer_reads_as_the_1080_row_frame_under_it() {
        // What a Qualcomm decoder hands over for 1080p: 16-row aligned, the
        // eight rows past the picture junk. The container says 1080.
        val frame = ByteArray(1920 * 1080) { ((it % 1920 + it / 1920) % 251).toByte() }
        val padded = ByteArray(1920 * 1088) { 255.toByte() }.also { frame.copyInto(it) }

        val region = visibleRegion(1920, 1088, crop = null, frameWidth = 1920, frameHeight = 1080)
        val fromPadded = lumaFrom(
            ByteBuffer.wrap(padded), region, 1920, 1, ByteArray(1920 * 1088), 480, 270,
        )
        val fromFrame = lumaFrom(
            ByteBuffer.wrap(frame), Region(0, 0, 1920, 1080), 1920, 1, ByteArray(1920 * 1080), 480, 270,
        )

        region shouldBe Region(0, 0, 1920, 1080)
        fromPadded.pixels.toList() shouldBe fromFrame.pixels.toList()
    }

    @Test
    fun a_crop_rect_the_size_of_the_whole_buffer_is_no_crop_at_all() {
        // Image.getCropRect() returns the full bounds when the decoder set none.
        visibleRegion(1920, 1088, Region(0, 0, 1920, 1088), 1920, 1080) shouldBe Region(0, 0, 1920, 1080)
    }

    @Test
    fun a_crop_rect_with_an_offset_is_the_frame() {
        visibleRegion(1936, 1100, Region(8, 10, 1920, 1080), 1920, 1080) shouldBe Region(8, 10, 1920, 1080)

        // Pixel (x, y) is 10 * y + x; the 3 x 2 crop at (2, 1) of a 6 x 4 plane.
        val image = lumaFrom(plane(6, 4, rowStride = 8), Region(2, 1, 3, 2), 8, 1, ByteArray(64), 3, 2)

        image.pixels.toList() shouldBe listOf(12f, 13f, 14f, 22f, 23f, 24f)
    }

    @Test
    fun a_visible_region_other_than_the_frame_the_tracker_expects_is_refused() {
        // A crop of another size: the tracker's pixel-to-source mapping is
        // built on the container's size, so reading it would misplace marks.
        assertFailsWith<IllegalArgumentException> {
            visibleRegion(1920, 1088, Region(0, 0, 1280, 720), 1920, 1080)
        }.message!! shouldContain "1280x720"
        // No crop, and a buffer smaller than the frame.
        assertFailsWith<IllegalArgumentException> {
            visibleRegion(1280, 720, crop = null, frameWidth = 1920, frameHeight = 1080)
        }
    }

    @Test
    fun a_crop_reaching_past_the_end_of_the_buffer_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            lumaFrom(plane(6, 4, rowStride = 8), Region(4, 3, 3, 2), 8, 1, ByteArray(64), 3, 2)
        }
    }

    @Test
    fun a_scratch_array_too_small_for_the_plane_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            lumaFrom(plane(4, 3, rowStride = 8), Region(0, 0, 4, 3), 8, 1, ByteArray(8), 4, 3)
        }
    }
}
