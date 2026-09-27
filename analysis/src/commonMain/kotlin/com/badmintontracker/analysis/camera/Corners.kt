package com.badmintontracker.analysis.camera

import kotlin.math.sqrt

/**
 * Shi-Tomasi corners, as OpenCV's `goodFeaturesToTrack(img, maxCorners,
 * quality, minDistance, blockSize = 3)` selects them. Design §4.3 step 1.
 *
 * The response is the smaller eigenvalue of the structure tensor summed over
 * a 3x3 block of Sobel gradients. A pixel is a candidate when its response is
 * at least [quality] times the image's strongest, it is the maximum of its
 * 3x3 neighbourhood, and it lies [border] pixels or more inside the image.
 * Candidates are taken strongest first, skipping any closer than
 * [minDistance] to one already taken.
 */
internal fun shiTomasi(
    img: GreyImage,
    maxCorners: Int = 150,
    quality: Double = 0.01,
    minDistance: Double = 8.0,
    border: Int = 10,
): List<Pair<Double, Double>> {
    val w = img.width
    val h = img.height
    val g = sobel(img)
    val xx = FloatArray(w * h)
    val xy = FloatArray(w * h)
    val yy = FloatArray(w * h)
    for (i in 0 until w * h) {
        xx[i] = g.gx[i] * g.gx[i]
        xy[i] = g.gx[i] * g.gy[i]
        yy[i] = g.gy[i] * g.gy[i]
    }
    val response = FloatArray(w * h)
    var strongest = 0f
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var a = 0f
        var b = 0f
        var c = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val i = (y + dy) * w + (x + dx)
            a += xx[i]; b += xy[i]; c += yy[i]
        }
        val half = (a + c) / 2f
        val disc = sqrt(((a - c) / 2f) * ((a - c) / 2f) + b * b)
        val r = half - disc
        response[y * w + x] = r
        if (r > strongest) strongest = r
    }
    if (strongest <= 0f) return emptyList()
    val threshold = (quality * strongest).toFloat()
    val candidates = ArrayList<Int>()
    for (y in maxOf(border, 1) until minOf(h - border, h - 1)) for (x in maxOf(border, 1) until minOf(w - border, w - 1)) {
        val r = response[y * w + x]
        if (r < threshold || r <= 0f) continue
        var isMax = true
        for (dy in -1..1) for (dx in -1..1) if (response[(y + dy) * w + (x + dx)] > r) isMax = false
        if (isMax) candidates.add(y * w + x)
    }
    // Strongest first; ties broken by position so every platform picks the same set.
    candidates.sortWith(compareByDescending<Int> { response[it] }.thenBy { it })
    val taken = ArrayList<Pair<Double, Double>>()
    val min2 = minDistance * minDistance
    for (i in candidates) {
        if (taken.size >= maxCorners) break
        val x = (i % w).toDouble()
        val y = (i / w).toDouble()
        if (taken.all { (tx, ty) -> (tx - x) * (tx - x) + (ty - y) * (ty - y) >= min2 }) taken.add(x to y)
    }
    return taken
}
