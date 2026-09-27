package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class ImageOpsTest {
    @Test fun luma_uses_bt601_weights() {
        val img = RgbImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        val y = img.luma()
        y.pixels[0].toDouble() shouldBe (0.299 * 255 plusOrMinus 1e-3)
        y.pixels[1].toDouble() shouldBe (0.587 * 255 plusOrMinus 1e-3)
    }

    @Test fun long_side_size_keeps_aspect_and_never_upscales() {
        longSideSize(1920, 1080, 960) shouldBe (960 to 540)
        longSideSize(1080, 1920, 960) shouldBe (540 to 960)
        longSideSize(640, 360, 960) shouldBe (640 to 360)
    }

    @Test fun distance_transform_is_exact_euclidean() {
        val w = 9; val h = 7
        val mask = BooleanArray(w * h); mask[3 * w + 4] = true
        val d = distanceTransform(mask, w, h)
        for (y in 0 until h) for (x in 0 until w)
            d[y * w + x].toDouble() shouldBe (hypot((x - 4).toDouble(), (y - 3).toDouble()) plusOrMinus 1e-4)
    }

    @Test fun distance_transform_without_pixels_is_infinite() {
        distanceTransform(BooleanArray(4), 2, 2).all { it == Float.MAX_VALUE } shouldBe true
    }

    @Test fun sobel2_of_a_quadratic_is_its_curvature() {
        // f = x^2 has dxx = 2 (Sobel scales the [1,-2,1] by the [1,2,1] sum = 4) and dyy = dxy = 0 inside.
        val w = 12; val h = 10
        val img = GreyImage(w, h, FloatArray(w * h) { val x = it % w; (x * x).toFloat() })
        val d = sobel2(img)
        d.dxx[5 * w + 6].toDouble() shouldBe (8.0 plusOrMinus 1e-4)
        d.dyy[5 * w + 6].toDouble() shouldBe (0.0 plusOrMinus 1e-4)
        d.dxy[5 * w + 6].toDouble() shouldBe (0.0 plusOrMinus 1e-4)
    }

    @Test fun gaussian_blur_preserves_a_constant_and_the_mean() {
        val img = GreyImage(20, 20, FloatArray(400) { if (it == 210) 400f else 0f })
        val b = gaussianBlur(img, 1.8)
        b.pixels.sum().toDouble() shouldBe (400.0 plusOrMinus 0.5)
        gaussianBlur(GreyImage(5, 5, FloatArray(25) { 7f }), 3.0).pixels.all { kotlin.math.abs(it - 7f) < 1e-4 } shouldBe true
    }

    @Test fun box_std_of_a_checkerboard_is_half_the_step() {
        val w = 50; val h = 50
        val img = GreyImage(w, h, FloatArray(w * h) { if ((it % w + it / w) % 2 == 0) 100f else 0f })
        val (mean, sd) = boxMeanStd(img, 41)
        mean[25 * w + 25].toDouble() shouldBe (50.0 plusOrMinus 0.2)
        sd[25 * w + 25].toDouble() shouldBe (50.0 plusOrMinus 0.2)
    }

    @Test fun short_components_are_dropped() {
        val w = 40; val h = 10
        val m = BooleanArray(w * h)
        for (x in 2..35) m[5 * w + x] = true   // long: diagonal 34
        m[1 * w + 1] = true; m[1 * w + 2] = true // short: diagonal ~2.2
        val kept = keepLongComponents(m, w, h, 10.0)
        kept[5 * w + 20] shouldBe true
        kept[1 * w + 1] shouldBe false
    }

    @Test fun court_model_has_twelve_segments_and_360_rectangles() {
        CourtModel.SEGMENTS.size shouldBe 12
        CourtModel.CENTRE_SEGMENTS.size shouldBe 12
        CourtModel.RECTANGLES.size shouldBe 360
        CourtModel.KEYPOINTS.size shouldBe 12
        CourtModel.CENTRE_SEGMENTS[0].ay shouldBe (0.02 plusOrMinus 1e-12)
        CourtModel.CENTRE_SEGMENTS[2].ay shouldBe (13.4 / 2 - 1.98 - 0.02 plusOrMinus 1e-12)
    }
}
