package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * OpenCV's `BORDER_REFLECT_101` (its `GaussianBlur`/`Sobel`/`blur` default):
 * the edge pixel is not repeated, so index `-1` reflects to `1` and `n` to
 * `n - 2`. The modulo form handles any out-of-range `i`, not just `+-1`,
 * because [gaussianBlur]'s radius can exceed the image's own size.
 */
private fun reflect101(i: Int, n: Int): Int {
    if (n == 1) return 0
    val period = 2 * (n - 1)
    var m = i % period
    if (m < 0) m += period
    return if (m < n) m else period - m
}

/**
 * The area-average box rule of `Luma.downsampleWith`, mirrored for a float
 * source: output pixel `(ox, oy)` averages source columns
 * `floor(ox*W/w) until max(that+1, floor((ox+1)*W/w))`, rows likewise, so a
 * non-integer ratio still averages whole pixels and none is skipped.
 */
internal fun areaDownsample(src: GreyImage, outWidth: Int, outHeight: Int): GreyImage {
    val width = src.width
    val height = src.height
    val x0 = IntArray(outWidth) { (it.toLong() * width / outWidth).toInt() }
    val x1 = IntArray(outWidth) { max(x0[it] + 1, ((it + 1).toLong() * width / outWidth).toInt()) }
    val out = FloatArray(outWidth * outHeight)
    for (oy in 0 until outHeight) {
        val y0 = (oy.toLong() * height / outHeight).toInt()
        val y1 = max(y0 + 1, ((oy + 1).toLong() * height / outHeight).toInt())
        for (ox in 0 until outWidth) {
            var sum = 0.0
            for (y in y0 until y1) for (x in x0[ox] until x1[ox]) sum += src.pixels[y * width + x]
            out[oy * outWidth + ox] = (sum / ((y1 - y0) * (x1[ox] - x0[ox]))).toFloat()
        }
    }
    return GreyImage(outWidth, outHeight, out)
}

/**
 * The work image's size for a source of this size: capped to `longSide` on
 * its long edge, aspect kept, never upscaled (a source already smaller than
 * `longSide` is returned unchanged, as `court_probe.py`'s `detect()` never
 * grows the frame it was given).
 */
internal fun longSideSize(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
    val long = max(width, height)
    if (long <= longSide) return width to height
    val scale = longSide.toDouble() / long
    return if (width >= height) {
        longSide to max(1, round(height * scale).toInt())
    } else {
        max(1, round(width * scale).toInt()) to longSide
    }
}

/**
 * Separable Gaussian blur, radius `ceil(3 * sigma)`, `BORDER_REFLECT_101`
 * border - the same result as `cv2.GaussianBlur(img, (0, 0), sigma)`.
 */
internal fun gaussianBlur(src: GreyImage, sigma: Double): GreyImage {
    require(sigma > 0.0) { "sigma must be positive: $sigma" }
    val radius = ceil(3.0 * sigma).toInt().coerceAtLeast(0)
    val kernel = DoubleArray(2 * radius + 1)
    var sum = 0.0
    for (k in -radius..radius) {
        val v = exp(-(k * k).toDouble() / (2.0 * sigma * sigma))
        kernel[k + radius] = v
        sum += v
    }
    for (i in kernel.indices) kernel[i] /= sum

    val w = src.width
    val h = src.height
    val horizontal = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var acc = 0.0
            for (k in -radius..radius) acc += kernel[k + radius] * src.pixels[row + reflect101(x + k, w)]
            horizontal[row + x] = acc.toFloat()
        }
    }
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = 0.0
            for (k in -radius..radius) acc += kernel[k + radius] * horizontal[reflect101(y + k, h) * w + x]
            out[y * w + x] = acc.toFloat()
        }
    }
    return GreyImage(w, h, out)
}

/** The three second-order partials of an image, one value per pixel. */
internal class SecondDerivatives(val dxx: FloatArray, val dyy: FloatArray, val dxy: FloatArray)

private fun convX(src: FloatArray, w: Int, h: Int, k0: Float, k1: Float, k2: Float): FloatArray {
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val xm1 = reflect101(x - 1, w)
            val xp1 = reflect101(x + 1, w)
            out[row + x] = k0 * src[row + xm1] + k1 * src[row + x] + k2 * src[row + xp1]
        }
    }
    return out
}

private fun convY(src: FloatArray, w: Int, h: Int, k0: Float, k1: Float, k2: Float): FloatArray {
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val ym1 = reflect101(y - 1, h) * w
        val y0 = y * w
        val yp1 = reflect101(y + 1, h) * w
        for (x in 0 until w) {
            out[y0 + x] = k0 * src[ym1 + x] + k1 * src[y0 + x] + k2 * src[yp1 + x]
        }
    }
    return out
}

/**
 * OpenCV `Sobel(src, CV_32F, ..., ksize=3)`'s three second-order partials:
 * `dxx` is `[1,-2,1]` in x smoothed by `[1,2,1]` in y, `dyy` the transpose,
 * `dxy` is `[-1,0,1]` in both x and y. `BORDER_REFLECT_101` border.
 */
internal fun sobel2(src: GreyImage): SecondDerivatives {
    val w = src.width
    val h = src.height
    val dxx = convY(convX(src.pixels, w, h, 1f, -2f, 1f), w, h, 1f, 2f, 1f)
    val dyy = convY(convX(src.pixels, w, h, 1f, 2f, 1f), w, h, 1f, -2f, 1f)
    val dxy = convY(convX(src.pixels, w, h, -1f, 0f, 1f), w, h, -1f, 0f, 1f)
    return SecondDerivatives(dxx, dyy, dxy)
}

private fun boxSum1D(line: DoubleArray, n: Int, radius: Int): DoubleArray {
    val out = DoubleArray(n)
    var sum = 0.0
    for (k in -radius..radius) sum += line[reflect101(k, n)]
    out[0] = sum
    for (i in 1 until n) {
        sum += line[reflect101(i + radius, n)] - line[reflect101(i - radius - 1, n)]
        out[i] = sum
    }
    return out
}

private fun boxSum2D(data: DoubleArray, w: Int, h: Int, radius: Int): DoubleArray {
    val rowPass = DoubleArray(w * h)
    val rowBuf = DoubleArray(w)
    for (y in 0 until h) {
        for (x in 0 until w) rowBuf[x] = data[y * w + x]
        val summed = boxSum1D(rowBuf, w, radius)
        for (x in 0 until w) rowPass[y * w + x] = summed[x]
    }
    val out = DoubleArray(w * h)
    val colBuf = DoubleArray(h)
    for (x in 0 until w) {
        for (y in 0 until h) colBuf[y] = rowPass[y * w + x]
        val summed = boxSum1D(colBuf, h, radius)
        for (y in 0 until h) out[y * w + x] = summed[y]
    }
    return out
}

/**
 * Local mean and standard deviation over a `size x size` box (`size` odd),
 * `BORDER_REFLECT_101` border - `cv2.blur`'s mean and `sqrt(E[x^2] - E[x]^2)`
 * clamped at zero for its std, the pair `ridge()` normalises contrast by.
 */
internal fun boxMeanStd(src: GreyImage, size: Int): Pair<FloatArray, FloatArray> {
    require(size >= 1 && size % 2 == 1) { "size must be odd and >= 1: $size" }
    val w = src.width
    val h = src.height
    val radius = size / 2
    val data = DoubleArray(w * h) { src.pixels[it].toDouble() }
    val sq = DoubleArray(w * h) { data[it] * data[it] }
    val sumX = boxSum2D(data, w, h, radius)
    val sumX2 = boxSum2D(sq, w, h, radius)
    val area = size.toDouble() * size.toDouble()
    val mean = FloatArray(w * h)
    val std = FloatArray(w * h)
    for (i in 0 until w * h) {
        val m = sumX[i] / area
        val variance = sumX2[i] / area - m * m
        mean[i] = m.toFloat()
        std[i] = sqrt(max(variance, 0.0)).toFloat()
    }
    return mean to std
}

/**
 * The 1-D lower-envelope distance transform of Felzenszwalb & Huttenlocher
 * ("Distance Transforms of Sampled Functions"), over squared distances. `f`
 * holds `0` at a source pixel and a very large sentinel elsewhere; the result
 * is the squared distance, per index, to the nearest zero.
 */
private fun dt1d(f: DoubleArray): DoubleArray {
    val n = f.size
    val d = DoubleArray(n)
    val v = IntArray(n)
    val z = DoubleArray(n + 1)
    var k = 0
    v[0] = 0
    z[0] = Double.NEGATIVE_INFINITY
    z[1] = Double.POSITIVE_INFINITY
    for (q in 1 until n) {
        var s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * (q - v[k]))
        while (s <= z[k]) {
            k--
            s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * (q - v[k]))
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = Double.POSITIVE_INFINITY
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val vk = v[k]
        d[q] = (q - vk).toDouble() * (q - vk) + f[vk]
    }
    return d
}

private const val DT_NO_PIXEL = 1e20
private const val DT_NO_PIXEL_THRESHOLD = 1e19

/**
 * The exact Euclidean distance to the nearest `true` pixel of `mask`, by two
 * passes of [dt1d] over squared distances - columns, then rows - as
 * `cv2.distanceTransform(mask, DIST_L2, 3)` computes exactly (not the 3x3/5x5
 * approximate masks OpenCV uses for other mask sizes). A mask with no `true`
 * pixel at all gives [Float.MAX_VALUE] everywhere.
 */
internal fun distanceTransform(mask: BooleanArray, width: Int, height: Int): FloatArray {
    val f = DoubleArray(width * height) { if (mask[it]) 0.0 else DT_NO_PIXEL }

    val columns = DoubleArray(width * height)
    val colBuf = DoubleArray(height)
    for (x in 0 until width) {
        for (y in 0 until height) colBuf[y] = f[y * width + x]
        val transformed = dt1d(colBuf)
        for (y in 0 until height) columns[y * width + x] = transformed[y]
    }

    val out = FloatArray(width * height)
    val rowBuf = DoubleArray(width)
    for (y in 0 until height) {
        for (x in 0 until width) rowBuf[x] = columns[y * width + x]
        val transformed = dt1d(rowBuf)
        for (x in 0 until width) {
            val sq = transformed[x]
            out[y * width + x] = if (sq >= DT_NO_PIXEL_THRESHOLD) Float.MAX_VALUE else sqrt(sq).toFloat()
        }
    }
    return out
}

/**
 * `mask`, with every 8-connected component whose bounding-box diagonal is
 * shorter than `minDiagonal` cleared - `cv2.connectedComponentsWithStats`'s
 * role in `ridge()`, dropping ridge fragments too short to be a court line.
 * Iterative flood fill (an explicit stack), so a component spanning most of
 * a large image cannot overflow the call stack.
 */
internal fun keepLongComponents(mask: BooleanArray, width: Int, height: Int, minDiagonal: Double): BooleanArray {
    val n = width * height
    val visited = BooleanArray(n)
    val result = BooleanArray(n)
    val stack = IntArray(n)
    val members = IntArray(n)
    for (start in 0 until n) {
        if (!mask[start] || visited[start]) continue
        var sp = 0
        var memberCount = 0
        stack[sp++] = start
        visited[start] = true
        members[memberCount++] = start
        var minX = start % width
        var maxX = minX
        var minY = start / width
        var maxY = minY
        while (sp > 0) {
            val idx = stack[--sp]
            val x = idx % width
            val y = idx / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                val nidx = ny * width + nx
                if (mask[nidx] && !visited[nidx]) {
                    visited[nidx] = true
                    stack[sp++] = nidx
                    members[memberCount++] = nidx
                    if (nx < minX) minX = nx
                    if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny
                    if (ny > maxY) maxY = ny
                }
            }
        }
        val bw = (maxX - minX + 1).toDouble()
        val bh = (maxY - minY + 1).toDouble()
        if (hypot(bw, bh) >= minDiagonal) {
            for (i in 0 until memberCount) result[members[i]] = true
        }
    }
    return result
}
