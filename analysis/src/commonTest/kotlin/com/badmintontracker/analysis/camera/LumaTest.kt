package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LumaTest {
    @Test fun a_16_by_9_source_is_480_by_270() {
        Luma.trackerSize(1920, 1080) shouldBe (480 to 270)
        Luma.trackerSize(3840, 2160) shouldBe (480 to 270)
        Luma.trackerSize(1280, 720) shouldBe (480 to 270)
    }

    @Test fun a_portrait_source_keeps_its_long_side_vertical() {
        Luma.trackerSize(1080, 1920) shouldBe (270 to 480)
    }

    @Test fun a_source_already_small_is_used_as_is() {
        Luma.trackerSize(320, 240) shouldBe (320 to 240)
    }

    @Test fun an_integer_ratio_averages_whole_blocks() {
        // 4x2 source, rows padded to stride 6, down to 2x1: each output pixel is a 2x2 block.
        val stride = 6
        val plane = ByteArray(stride * 2)
        val values = arrayOf(intArrayOf(10, 20, 200, 250), intArrayOf(30, 40, 255, 255))
        for (y in 0 until 2) for (x in 0 until 4) plane[y * stride + x] = values[y][x].toByte()
        val out = Luma.downsample(plane, 0, 4, 2, stride, 2, 1)
        out.width shouldBe 2
        out.pixels[0].toDouble() shouldBe (25.0 plusOrMinus 1e-6)
        out.pixels[1].toDouble() shouldBe (240.0 plusOrMinus 1e-6)
    }

    @Test fun a_non_integer_ratio_covers_every_source_pixel_once() {
        // 5 wide to 2 wide: columns 0..1 then 2..4.
        val plane = byteArrayOf(0, 10, 20, 30, 40)
        val out = Luma.downsample(plane, 0, 5, 1, 5, 2, 1)
        out.pixels[0].toDouble() shouldBe (5.0 plusOrMinus 1e-6)
        out.pixels[1].toDouble() shouldBe (30.0 plusOrMinus 1e-6)
    }

    @Test fun bytes_above_127_read_unsigned() {
        val out = Luma.downsample(byteArrayOf(0xFF.toByte()), 0, 1, 1, 1, 1, 1)
        out.pixels[0].toDouble() shouldBe (255.0 plusOrMinus 1e-6)
    }

    @Test fun tracker_pixel_centres_map_to_source_pixel_centres() {
        val s = Luma.trackerToSource(1920, 1080, 480, 270)
        // Tracker pixel 0 covers source pixels 0..3, whose centre is 1.5.
        s[0][0] * 0.0 + s[0][2] shouldBe (1.5 plusOrMinus 1e-9)
        s[1][1] * 0.0 + s[1][2] shouldBe (1.5 plusOrMinus 1e-9)
        s[0][0] shouldBe (4.0 plusOrMinus 1e-9)
    }

    @Test fun bilinear_sampling_interpolates_and_replicates_the_border() {
        val img = GreyImage(2, 2, floatArrayOf(0f, 10f, 20f, 30f))
        img.sample(0.5, 0.5) shouldBe (15.0 plusOrMinus 1e-9)
        img.sample(-3.0, -3.0) shouldBe (0.0 plusOrMinus 1e-9)
        img.sample(9.0, 9.0) shouldBe (30.0 plusOrMinus 1e-9)
    }
}
