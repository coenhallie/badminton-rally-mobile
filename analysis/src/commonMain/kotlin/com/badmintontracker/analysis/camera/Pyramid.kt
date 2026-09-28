package com.badmintontracker.analysis.camera

/** Gradient images, one float per pixel, in grey levels per pixel. */
internal class Gradients(val gx: FloatArray, val gy: FloatArray)

/**
 * Blur with `[1 4 6 4 1] / 16` in both directions (border replicated) and
 * keep every second pixel. The output is `(w + 1) / 2` by `(h + 1) / 2`, and
 * output pixel `(x, y)` sits at input pixel `(2x, 2y)`, so a coordinate
 * halves exactly from one level to the next. Design §4.3 step 2.
 *
 * Runs on every tracked sample, so the horizontal pass is done only at the
 * even columns the vertical pass reads, and the replicated border is a table
 * of clamped indices built once per pass rather than a clamp per tap. Each
 * output is the same five-term float sum, in the same order, as blurring every
 * column and clamping every tap gives.
 */
internal fun pyrDown(src: GreyImage): GreyImage {
    val w = src.width
    val h = src.height
    val p = src.pixels
    val ow = (w + 1) / 2
    val oh = (h + 1) / 2
    // Horizontal: tmp holds column 2 * ox of every input row, at ox.
    val xs = clampedTaps(ow, w)
    val tmp = FloatArray(ow * h)
    for (y in 0 until h) {
        val row = y * w
        val tmpRow = y * ow
        for (ox in 0 until ow) {
            val t = 5 * ox
            tmp[tmpRow + ox] = (p[row + xs[t]] + 4 * p[row + xs[t + 1]] + 6 * p[row + xs[t + 2]] +
                4 * p[row + xs[t + 3]] + p[row + xs[t + 4]]) / 16f
        }
    }
    // Vertical, at every second row.
    val ys = clampedTaps(oh, h)
    val out = FloatArray(ow * oh)
    for (oy in 0 until oh) {
        val t = 5 * oy
        val r0 = ys[t] * ow
        val r1 = ys[t + 1] * ow
        val r2 = ys[t + 2] * ow
        val r3 = ys[t + 3] * ow
        val r4 = ys[t + 4] * ow
        val outRow = oy * ow
        for (ox in 0 until ow) {
            out[outRow + ox] = (tmp[r0 + ox] + 4 * tmp[r1 + ox] + 6 * tmp[r2 + ox] + 4 * tmp[r3 + ox] + tmp[r4 + ox]) / 16f
        }
    }
    return GreyImage(ow, oh, out)
}

/** For each of [outSize] outputs `o`, the five taps `2o - 2 .. 2o + 2` clamped into `0 until size`. */
private fun clampedTaps(outSize: Int, size: Int): IntArray {
    val taps = IntArray(5 * outSize)
    for (o in 0 until outSize) for (k in 0 until 5) taps[5 * o + k] = (2 * o + k - 2).coerceIn(0, size - 1)
    return taps
}

/** [levels] images, the first being [base]. */
internal fun pyramid(base: GreyImage, levels: Int): List<GreyImage> {
    val out = ArrayList<GreyImage>(levels)
    out.add(base)
    while (out.size < levels) out.add(pyrDown(out.last()))
    return out
}

/** Scharr derivatives divided by 32, border replicated: a ramp of slope s reads s. */
internal fun scharr(img: GreyImage): Gradients {
    val w = img.width
    val h = img.height
    val p = img.pixels
    fun at(x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    val gx = FloatArray(w * h)
    val gy = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        gx[y * w + x] = (3 * (at(x + 1, y - 1) - at(x - 1, y - 1)) + 10 * (at(x + 1, y) - at(x - 1, y)) +
            3 * (at(x + 1, y + 1) - at(x - 1, y + 1))) / 32f
        gy[y * w + x] = (3 * (at(x - 1, y + 1) - at(x - 1, y - 1)) + 10 * (at(x, y + 1) - at(x, y - 1)) +
            3 * (at(x + 1, y + 1) - at(x + 1, y - 1))) / 32f
    }
    return Gradients(gx, gy)
}

/** Sobel derivatives (unscaled), border replicated, for corner selection. */
internal fun sobel(img: GreyImage): Gradients {
    val w = img.width
    val h = img.height
    val p = img.pixels
    fun at(x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    val gx = FloatArray(w * h)
    val gy = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        gx[y * w + x] = (at(x + 1, y - 1) - at(x - 1, y - 1)) + 2 * (at(x + 1, y) - at(x - 1, y)) +
            (at(x + 1, y + 1) - at(x - 1, y + 1))
        gy[y * w + x] = (at(x - 1, y + 1) - at(x - 1, y - 1)) + 2 * (at(x, y + 1) - at(x, y - 1)) +
            (at(x + 1, y + 1) - at(x + 1, y - 1))
    }
    return Gradients(gx, gy)
}
