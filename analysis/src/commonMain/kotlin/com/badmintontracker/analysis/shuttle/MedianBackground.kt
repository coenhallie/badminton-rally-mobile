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
    if (size == 0) return ByteArray(0)
    val frameCount = frames.size
    if (frameCount == 1) return frames[0].copyOf()

    val out = ByteArray(size)
    val hist = IntArray(256)
    val half = frameCount / 2
    val odd = (frameCount and 1) == 1
    val tileSize = (2 * 1024 * 1024 / frameCount).coerceIn(1, 4096)

    if (tileSize == 1) {
        for (i in 0 until size) {
            hist.fill(0)
            for (f in 0 until frameCount) {
                hist[frames[f][i].toInt() and 0xFF]++
            }
            out[i] = histogramMedian(hist, half, odd).toByte()
        }
        return out
    }

    val tileBuf = ByteArray(tileSize * frameCount)
    var tileStart = 0
    while (tileStart < size) {
        val len = minOf(tileSize, size - tileStart)
        for (f in 0 until frameCount) {
            val frame = frames[f]
            var j = 0
            while (j < len) {
                tileBuf[j * frameCount + f] = frame[tileStart + j]
                j++
            }
        }
        var j = 0
        while (j < len) {
            hist.fill(0)
            val colBase = j * frameCount
            var f = 0
            while (f < frameCount) {
                hist[tileBuf[colBase + f].toInt() and 0xFF]++
                f++
            }
            out[tileStart + j] = histogramMedian(hist, half, odd).toByte()
            j++
        }
        tileStart += len
    }
    return out
}

/**
 * [medianBackground] over frames this function never holds.
 *
 * Same arithmetic, reached through an accessor instead of a list, and written
 * into [out] instead of allocating. It exists because the caller's frames may
 * already be somewhere this module should not copy them out of: the iOS
 * platform layer holds its 300 samples in one Swift-owned buffer, and the
 * `List<ByteArray>` form above would make Kotlin build a second 132MB copy of
 * them, live at the same time as the first, at the exact step Android's own
 * decode layer records as having killed a 2GB device.
 *
 * [sampleAt] is called `frameCount * frameLength` times - roughly 130 million
 * for a 300-sample background - so it must be a plain read. Anything that
 * allocates per call belongs on the other side of it.
 */
fun medianBackgroundInto(
    out: ByteArray,
    frameCount: Int,
    frameLength: Int,
    sampleAt: (frame: Int, index: Int) -> Int,
) {
    require(frameCount > 0) { "no frames to reduce" }
    require(frameLength >= 0) { "frameLength must be non-negative: $frameLength" }
    require(out.size >= frameLength) { "output shorter than a frame" }

    val hist = IntArray(256)
    val half = frameCount / 2
    val odd = (frameCount and 1) == 1
    for (i in 0 until frameLength) {
        hist.fill(0)
        for (f in 0 until frameCount) {
            hist[sampleAt(f, i) and 0xFF]++
        }
        out[i] = histogramMedian(hist, half, odd).toByte()
    }
}

private fun histogramMedian(hist: IntArray, half: Int, odd: Boolean): Int {
    if (odd) {
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc > half) return v
        }
        return 255
    }
    val lowTarget = half - 1
    var acc = 0
    var low = -1
    for (v in 0 until 256) {
        acc += hist[v]
        if (low < 0 && acc > lowTarget) low = v
        if (acc > half) return (low + v) ushr 1
    }
    return 255
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
