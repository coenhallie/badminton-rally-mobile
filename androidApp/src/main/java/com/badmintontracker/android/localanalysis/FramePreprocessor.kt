package com.badmintontracker.android.localanalysis

import android.media.Image

/**
 * Decoded frame to the tensor TrackNet expects.
 *
 * Reproduces production's path (`inference.py`): resize to 512x288 with
 * bilinear interpolation, convert to RGB, scale by 1/255, emit CHW. Design
 * section 5.4 calls this an unmeasured fidelity surface feeding every model on
 * every frame, so every step here matches a specific line over there rather
 * than being "close enough".
 *
 * The awkward part is that Android and OpenCV do not start from the same
 * pixels. MediaCodec hands back YUV_420_888 and OpenCV hands back BGR already
 * converted by FFmpeg, so this path performs a colour conversion production
 * does not have. That conversion, not the resize, is where a difference is
 * most likely, which is what FramePreprocessorTest measures.
 */
object FramePreprocessor {

    const val WIDTH = 512
    const val HEIGHT = 288

    /**
     * How to interpret the decoder's YUV. Measured, not assumed, and the
     * answer is not the one the file asks for.
     *
     * Two guesses came first and both were wrong. BT.601 full range was off by
     * a mean of 6.55/255. The corpus video declares `color_space=bt709` and
     * `color_range=tv`, so BT.709 limited looked like the obvious correction -
     * and it is better, at 3.45, but still not right.
     *
     * Measuring all four against production's own output over 24 frames
     * settles it:
     *
     * | variant        | mean   | max   |
     * |----------------|--------|-------|
     * | BT601_FULL     | 6.55   | 19    |
     * | **BT601_LIMITED** | **1.56** | **4** |
     * | BT709_FULL     | 8.05   | 33    |
     * | BT709_LIMITED  | 3.45   | 22    |
     *
     * So production decodes this BT.709 file with BT.601 coefficients.
     * OpenCV's `VideoCapture` converts YUV to BGR without passing the
     * container's colour space to swscale, which falls back to BT.601. The
     * file's own metadata is therefore the wrong guide, and matching the
     * cloud's IMPLEMENTATION beats matching the standard it claims to follow -
     * which is the whole job of a parity port.
     *
     * Keep the default at BT601_LIMITED for as long as the cloud decodes with
     * OpenCV. If that ever changes to something colour-aware, this becomes
     * wrong in the same quiet way, and
     * `report_every_colour_conversion_against_production` is the test that
     * would say so.
     */
    enum class YuvMatrix(
        val kr: Double,
        val kb: Double,
        /** True for studio swing, where Y is 16..235 and chroma 16..240. */
        val limitedRange: Boolean,
    ) {
        BT601_FULL(0.299, 0.114, false),
        BT601_LIMITED(0.299, 0.114, true),
        BT709_FULL(0.2126, 0.0722, false),
        BT709_LIMITED(0.2126, 0.0722, true),
    }

    /**
     * YUV_420_888 to RGB.
     *
     * Coefficients derived from [matrix] rather than hardcoded, so the four
     * combinations are one parameter apart and a future video declaring
     * something else is a configuration change rather than an edit.
     */
    fun toRgb(image: Image, out: ByteArray, matrix: YuvMatrix = YuvMatrix.BT601_LIMITED) {
        val w = image.width
        val h = image.height
        require(out.size >= w * h * 3) { "output buffer too small" }

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer
        val yRow = y.rowStride
        val uRow = u.rowStride
        val vRow = v.rowStride
        val uPix = u.pixelStride
        val vPix = v.pixelStride

        // Standard derivation from the luma coefficients, so BT.601 and BT.709
        // differ only in kr/kb rather than in two hand-copied constant sets.
        val kr = matrix.kr
        val kb = matrix.kb
        val kg = 1.0 - kr - kb
        val rv = 2.0 * (1.0 - kr)
        val bu = 2.0 * (1.0 - kb)
        val gu = -2.0 * (1.0 - kb) * kb / kg
        val gv = -2.0 * (1.0 - kr) * kr / kg
        // Studio swing expands 16..235 luma and 16..240 chroma to 0..255.
        val yScale = if (matrix.limitedRange) 255.0 / 219.0 else 1.0
        val yOffset = if (matrix.limitedRange) 16.0 else 0.0
        val cScale = if (matrix.limitedRange) 255.0 / 224.0 else 1.0

        var o = 0
        for (row in 0 until h) {
            val uvRow = row / 2
            for (col in 0 until w) {
                val uvCol = col / 2
                val yy = ((yBuf.get(row * yRow + col).toInt() and 0xFF) - yOffset) * yScale
                val uu = ((uBuf.get(uvRow * uRow + uvCol * uPix).toInt() and 0xFF) - 128) * cScale
                val vv = ((vBuf.get(uvRow * vRow + uvCol * vPix).toInt() and 0xFF) - 128) * cScale
                out[o++] = clamp(yy + rv * vv)
                out[o++] = clamp(yy + gu * uu + gv * vv)
                out[o++] = clamp(yy + bu * uu)
            }
        }
    }

    /**
     * Bilinear resize matching `cv2.resize`'s INTER_LINEAR.
     *
     * The pixel-centre convention is the whole point: OpenCV maps a
     * destination pixel to `(dst + 0.5) * scale - 0.5`, not `dst * scale`. The
     * naive form is off by half a source pixel everywhere, which is a
     * sub-pixel shift on every frame feeding every model - exactly the kind of
     * quiet drift section 5.4 warns about.
     */
    fun resize(src: ByteArray, srcW: Int, srcH: Int, dst: ByteArray, dstW: Int, dstH: Int) {
        val scaleX = srcW.toDouble() / dstW
        val scaleY = srcH.toDouble() / dstH
        var o = 0
        for (row in 0 until dstH) {
            val fy = ((row + 0.5) * scaleY - 0.5).coerceAtLeast(0.0)
            val y0 = fy.toInt().coerceAtMost(srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val wy = fy - y0
            for (col in 0 until dstW) {
                val fx = ((col + 0.5) * scaleX - 0.5).coerceAtLeast(0.0)
                val x0 = fx.toInt().coerceAtMost(srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val wx = fx - x0
                for (c in 0 until 3) {
                    val p00 = (src[(y0 * srcW + x0) * 3 + c].toInt() and 0xFF).toDouble()
                    val p01 = (src[(y0 * srcW + x1) * 3 + c].toInt() and 0xFF).toDouble()
                    val p10 = (src[(y1 * srcW + x0) * 3 + c].toInt() and 0xFF).toDouble()
                    val p11 = (src[(y1 * srcW + x1) * 3 + c].toInt() and 0xFF).toDouble()
                    val top = p00 + (p01 - p00) * wx
                    val bot = p10 + (p11 - p10) * wx
                    dst[o++] = clamp(top + (bot - top) * wy)
                }
            }
        }
    }

    /**
     * Convert and resize in one pass, sampling only the pixels the output
     * needs.
     *
     * Converting the whole 1920x1080 frame and then resizing measured 262ms
     * per frame on an S23 - the second largest cost in the pipeline after
     * inference - and most of that work is thrown away: the bilinear resize
     * reads four source pixels per output pixel, so a 512x288 target needs at
     * most 590k of the 2.07M converted.
     *
     * Equivalent to convert-then-resize because the YUV to RGB transform is
     * per-pixel and interpolation is linear, so the order does not matter. The
     * one real difference is rounding: this interpolates in floating point and
     * rounds once at the end, where the two-step version rounds to bytes
     * first. That makes this marginally more accurate, not less, and
     * `android_decode_matches_production_within_the_threshold` is what
     * confirms the result still tracks production.
     */
    fun toRgbResized(
        image: Image,
        dst: ByteArray,
        dstW: Int,
        dstH: Int,
        matrix: YuvMatrix = YuvMatrix.BT601_LIMITED,
    ) {
        val srcW = image.width
        val srcH = image.height
        require(dst.size >= dstW * dstH * 3) { "output buffer too small" }

        val kr = matrix.kr
        val kb = matrix.kb
        val kg = 1.0 - kr - kb
        val rv = 2.0 * (1.0 - kr)
        val bu = 2.0 * (1.0 - kb)
        val gu = -2.0 * (1.0 - kb) * kb / kg
        val gv = -2.0 * (1.0 - kr) * kr / kg
        val yScale = if (matrix.limitedRange) 255.0 / 219.0 else 1.0
        val yOffset = if (matrix.limitedRange) 16.0 else 0.0
        val cScale = if (matrix.limitedRange) 255.0 / 224.0 else 1.0

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer
        val yRow = y.rowStride
        val uRow = u.rowStride
        val vRow = v.rowStride
        val uPix = u.pixelStride
        val vPix = v.pixelStride

        val rgb = DoubleArray(3)
        fun sample(px: Int, py: Int, into: DoubleArray) {
            val yy = ((yBuf.get(py * yRow + px).toInt() and 0xFF) - yOffset) * yScale
            val uvRow = py / 2
            val uvCol = px / 2
            val uu = ((uBuf.get(uvRow * uRow + uvCol * uPix).toInt() and 0xFF) - 128) * cScale
            val vv = ((vBuf.get(uvRow * vRow + uvCol * vPix).toInt() and 0xFF) - 128) * cScale
            into[0] = yy + rv * vv
            into[1] = yy + gu * uu + gv * vv
            into[2] = yy + bu * uu
        }

        val scaleX = srcW.toDouble() / dstW
        val scaleY = srcH.toDouble() / dstH
        val p00 = DoubleArray(3)
        val p01 = DoubleArray(3)
        val p10 = DoubleArray(3)
        val p11 = DoubleArray(3)
        var o = 0
        for (row in 0 until dstH) {
            // OpenCV's pixel-centre mapping, same as [resize].
            val fy = ((row + 0.5) * scaleY - 0.5).coerceAtLeast(0.0)
            val y0 = fy.toInt().coerceAtMost(srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val wy = fy - y0
            for (col in 0 until dstW) {
                val fx = ((col + 0.5) * scaleX - 0.5).coerceAtLeast(0.0)
                val x0 = fx.toInt().coerceAtMost(srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val wx = fx - x0
                sample(x0, y0, p00)
                sample(x1, y0, p01)
                sample(x0, y1, p10)
                sample(x1, y1, p11)
                for (c in 0 until 3) {
                    val top = p00[c] + (p01[c] - p00[c]) * wx
                    val bot = p10[c] + (p11[c] - p10[c]) * wx
                    rgb[c] = top + (bot - top) * wy
                }
                dst[o++] = clamp(rgb[0])
                dst[o++] = clamp(rgb[1])
                dst[o++] = clamp(rgb[2])
            }
        }
    }

    /**
     * As [toRgbResized], but writing a smaller image into a larger canvas at
     * an offset and leaving the rest untouched.
     *
     * This is the letterbox the detector needs: Ultralytics scales to fit and
     * pads the short axis rather than stretching to a square, because the
     * model was trained that way and stretching moves every box.
     */
    fun toRgbResizedInto(
        image: Image,
        canvas: ByteArray,
        canvasSize: Int,
        offsetX: Int,
        offsetY: Int,
        fitW: Int,
        fitH: Int,
        matrix: YuvMatrix = YuvMatrix.BT601_LIMITED,
    ) {
        val scaled = ByteArray(fitW * fitH * 3)
        toRgbResized(image, scaled, fitW, fitH, matrix)
        for (row in 0 until fitH) {
            val src = row * fitW * 3
            val dst = ((offsetY + row) * canvasSize + offsetX) * 3
            scaled.copyInto(canvas, dst, src, src + fitW * 3)
        }
    }

    /** Interleaved RGB bytes to the CHW float tensor, scaled by 1/255. */
    fun toChwTensor(rgb: ByteArray, width: Int, height: Int, out: FloatArray) =
        toChwTensorAt(rgb, width, height, out, 0)

    /**
     * As [toChwTensor], writing at [offset].
     *
     * TrackNet's input is one 27-plane buffer holding a background and eight
     * frames, so each frame is written in place rather than into its own array
     * and copied. At 5.6MB per sequence the copies would dominate.
     */
    fun toChwTensorAt(rgb: ByteArray, width: Int, height: Int, out: FloatArray, offset: Int) {
        val plane = width * height
        require(out.size >= offset + plane * 3) { "tensor too small" }
        for (i in 0 until plane) {
            out[offset + i] = (rgb[i * 3].toInt() and 0xFF) / 255f
            out[offset + plane + i] = (rgb[i * 3 + 1].toInt() and 0xFF) / 255f
            out[offset + 2 * plane + i] = (rgb[i * 3 + 2].toInt() and 0xFF) / 255f
        }
    }

    private fun clamp(v: Double): Byte {
        val i = (v + 0.5).toInt()
        return when {
            i < 0 -> 0
            i > 255 -> 255.toByte()
            else -> i.toByte()
        }
    }
}
