package com.badmintontracker.analysis.shuttle

/**
 * Pixel-wise median over sampled frames, as `_compute_median_background` does
 * (`inference.py:193-220`).
 *
 * A median removes everything that moves; a single frame contains a player
 * mid-swing. Design section 5.4 calls these "not interchangeable inputs", and
 * this plane is the extra three channels TrackNet's `bg_mode = "concat"` takes.
 *
 * [frames] are interleaved 8-bit samples, all the same length.
 *
 * The result TRUNCATES, matching `np.median(...).astype(np.uint8)`. For an even
 * number of frames numpy averages the two middle values, so the median is
 * routinely fractional and rounding instead would shift the background by a
 * count on roughly half the pixels.
 */
fun medianBackground(frames: List<ByteArray>): ByteArray {
    require(frames.isNotEmpty()) { "no frames to reduce" }
    val size = frames[0].size
    require(frames.all { it.size == size }) { "frames differ in length" }

    val out = ByteArray(size)
    val column = IntArray(frames.size)
    for (i in 0 until size) {
        for (f in frames.indices) column[f] = frames[f][i].toInt() and 0xFF
        column.sort()
        val n = column.size
        val median = if (n % 2 == 1) {
            column[n / 2].toDouble()
        } else {
            (column[n / 2 - 1] + column[n / 2]) / 2.0
        }
        out[i] = median.toInt().toByte()
    }
    return out
}

/**
 * The frame indices production samples for its median background.
 *
 * `np.linspace(0, total - 1, min(total, max_bg_samples), dtype=int)` then
 * `np.unique` (`inference.py:200-201`). `dtype=int` on linspace **truncates**
 * rather than rounds, so this truncates: rounding would shift which frames form
 * the background, which changes the background, which changes every heatmap.
 *
 * Here rather than in either app's decode layer, which is where it started.
 * This is production-parity arithmetic, not decoding: it belongs with
 * [medianBackground], which consumes what it selects, it can be tested without
 * a device, and it has to be the same arithmetic on both phones or the two
 * platforms compute their backgrounds from different frames.
 */
fun backgroundSampleIndices(totalFrames: Int, maxSamples: Int = MAX_BACKGROUND_SAMPLES): List<Int> {
    val count = minOf(totalFrames, maxSamples)
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val step = (totalFrames - 1).toDouble() / (count - 1).toDouble()
    return (0 until count).map { (it * step).toInt() }.distinct()
}

/** `max_bg_samples: int = 300` (`inference.py:111`). */
const val MAX_BACKGROUND_SAMPLES = 300
