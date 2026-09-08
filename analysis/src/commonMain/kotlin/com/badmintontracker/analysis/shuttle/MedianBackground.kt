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
    medianBackgroundInto(out, frames.size, size) { frame, index ->
        frames[frame][index].toInt() and 0xFF
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
    require(out.size >= frameLength) { "output shorter than a frame" }

    val column = IntArray(frameCount)
    for (i in 0 until frameLength) {
        for (f in 0 until frameCount) column[f] = sampleAt(f, i)
        column.sort()
        val median = if (frameCount % 2 == 1) {
            column[frameCount / 2].toDouble()
        } else {
            (column[frameCount / 2 - 1] + column[frameCount / 2]) / 2.0
        }
        out[i] = median.toInt().toByte()
    }
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
