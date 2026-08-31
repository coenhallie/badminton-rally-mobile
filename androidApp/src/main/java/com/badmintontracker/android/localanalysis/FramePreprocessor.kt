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

    /** Interleaved RGB bytes to the CHW float tensor, scaled by 1/255. */
    fun toChwTensor(rgb: ByteArray, width: Int, height: Int, out: FloatArray) {
        val plane = width * height
        require(out.size >= plane * 3) { "tensor too small" }
        for (i in 0 until plane) {
            out[i] = (rgb[i * 3].toInt() and 0xFF) / 255f
            out[plane + i] = (rgb[i * 3 + 1].toInt() and 0xFF) / 255f
            out[2 * plane + i] = (rgb[i * 3 + 2].toInt() and 0xFF) / 255f
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
