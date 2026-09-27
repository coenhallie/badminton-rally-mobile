package com.badmintontracker.analysis.camera

/** Gradient images, one float per pixel, in grey levels per pixel. */
internal class Gradients(val gx: FloatArray, val gy: FloatArray)

/**
 * Blur with `[1 4 6 4 1] / 16` in both directions (border replicated) and
 * keep every second pixel. The output is `(w + 1) / 2` by `(h + 1) / 2`, and
 * output pixel `(x, y)` sits at input pixel `(2x, 2y)`, so a coordinate
 * halves exactly from one level to the next. Design §4.3 step 2.
 */
internal fun pyrDown(src: GreyImage): GreyImage {
    val w = src.width
    val h = src.height
    val p = src.pixels
    val tmp = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            fun at(xx: Int) = p[row + xx.coerceIn(0, w - 1)]
            tmp[row + x] = (at(x - 2) + 4 * at(x - 1) + 6 * at(x) + 4 * at(x + 1) + at(x + 2)) / 16f
        }
    }
    val ow = (w + 1) / 2
    val oh = (h + 1) / 2
    val out = FloatArray(ow * oh)
    for (oy in 0 until oh) {
        val y = oy * 2
        for (ox in 0 until ow) {
            val x = ox * 2
            fun at(yy: Int) = tmp[yy.coerceIn(0, h - 1) * w + x]
            out[oy * ow + ox] = (at(y - 2) + 4 * at(y - 1) + 6 * at(y) + 4 * at(y + 1) + at(y + 2)) / 16f
        }
    }
    return GreyImage(ow, oh, out)
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
