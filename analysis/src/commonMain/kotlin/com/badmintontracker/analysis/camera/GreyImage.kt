package com.badmintontracker.analysis.camera

import kotlin.math.floor

/**
 * A greyscale image for the camera tracker, row-major, one grey level
 * (0..255, not normalised) per pixel.
 *
 * Floats rather than bytes because every stage after the downsample
 * (blur, gradients, bilinear sampling) produces fractions, and keeping one
 * type avoids a conversion at each of them.
 */
class GreyImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width >= 1 && height >= 1) { "empty image $width x $height" }
        require(pixels.size == width * height) { "${pixels.size} pixels for $width x $height" }
    }

    /** Bilinear sample at a sub-pixel position, the border replicated outward. */
    fun sample(x: Double, y: Double): Double {
        val cx = x.coerceIn(0.0, (width - 1).toDouble())
        val cy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(cx).toInt().coerceAtMost(width - 1)
        val y0 = floor(cy).toInt().coerceAtMost(height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val a = pixels[y0 * width + x0]
        val b = pixels[y0 * width + x1]
        val c = pixels[y1 * width + x0]
        val d = pixels[y1 * width + x1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}
