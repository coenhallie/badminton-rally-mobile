package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
 * A true area average, `cv2.INTER_AREA`'s rule: output pixel `ox` covers the
 * continuous source interval `[ox*W/w, (ox+1)*W/w)` and each source pixel
 * `[x, x+1)` is weighted by its overlap with it; rows likewise (separable,
 * vertical pass first into one row of doubles, so the only buffers are that
 * row and the two weight tables).
 *
 * This no longer mirrors `Luma.downsampleWith`'s whole-pixel box rule
 * (`floor(ox*W/w) until floor((ox+1)*W/w)`): at a non-integer ratio that
 * rule's boxes sit off the pixel-centre map `x_src = (x_out + 0.5) * s - 0.5`
 * the detector maps keypoints back with (a mean shift of -1/3 source px at
 * 1280 -> 960), and the fit image's shift would reach the keypoints. Exact
 * overlap keeps that map for any ratio (each output's weight centroid is
 * within a fraction of a pixel of it and the errors cancel, no shift), and
 * at an integer ratio `k` its weights are all `1/k`, so it is the box mean
 * there: to the bit when `k` is a power of two (1080p and 4K to the 960 work
 * and 1920 fit images), within float rounding otherwise.
 */
internal fun areaDownsample(src: GreyImage, outWidth: Int, outHeight: Int): GreyImage {
    val width = src.width
    val height = src.height
    val xs = OverlapWeights(width, outWidth)
    val ys = OverlapWeights(height, outHeight)
    val row = DoubleArray(width)
    val out = FloatArray(outWidth * outHeight)
    for (oy in 0 until outHeight) {
        row.fill(0.0)
        for (j in ys.start[oy] until ys.start[oy + 1]) {
            val wy = ys.weight[j]
            val base = ys.index[j] * width
            for (x in 0 until width) row[x] += wy * src.pixels[base + x]
        }
        for (ox in 0 until outWidth) {
            var sum = 0.0
            for (i in xs.start[ox] until xs.start[ox + 1]) sum += xs.weight[i] * row[xs.index[i]]
            out[oy * outWidth + ox] = sum.toFloat()
        }
    }
    return GreyImage(outWidth, outHeight, out)
}

/**
 * Per output pixel `o` of `n`, the source pixels of `size` overlapping
 * `[o*size/n, (o+1)*size/n)` and their overlaps normalised to sum to 1:
 * entries `start[o] until start[o+1]` of [index] and [weight].
 */
private class OverlapWeights(size: Int, n: Int) {
    val start = IntArray(n + 1)
    val index: IntArray
    val weight: DoubleArray

    init {
        val idx = ArrayList<Int>(size + n)
        val wts = ArrayList<Double>(size + n)
        for (o in 0 until n) {
            start[o] = idx.size
            val a = (o.toLong() * size).toDouble() / n
            val b = ((o + 1).toLong() * size).toDouble() / n
            val first = floor(a).toInt()
            val last = min(size - 1, ceil(b).toInt() - 1)
            var total = 0.0
            for (k in first..last) {
                val overlap = min(b, k + 1.0) - max(a, k.toDouble())
                if (overlap <= 0.0) continue
                idx.add(k)
                wts.add(overlap)
                total += overlap
            }
            for (i in start[o] until idx.size) wts[i] = wts[i] / total
        }
        start[n] = idx.size
        index = idx.toIntArray()
        weight = wts.toDoubleArray()
    }
}

/**
 * The work image's size for a source of this size: capped to `longSide` on
 * its long edge, aspect kept, never upscaled. `court_probe.py`'s `detect()`
 * always resizes to exactly `WORK` (960), upscaling a smaller frame; not
 * upscaling here is a deliberate departure from the probe, per this task's
 * brief.
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
 * border, per this task's brief. `cv2.GaussianBlur(img, (0, 0), sigma)` picks
 * a wider kernel for a float image (about a 4-sigma radius), so this is close
 * to but not numerically identical to OpenCV's own result.
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
 * passes of [dt1d] over squared distances - columns, then rows - per this
 * task's brief. `court_probe.py` calls `cv2.distanceTransform(mask, DIST_L2,
 * 3)`, which is only a 3x3-mask chamfer approximation (about 4.5% low on
 * the diagonal); this port is intentionally more accurate, not a match to
 * that approximation. A mask with no `true` pixel at all gives
 * [Float.MAX_VALUE] everywhere.
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
