package com.badmintontracker.analysis.shuttle

/** A shuttle coordinate in heatmap space, or absence. */
data class HeatmapCoord(val x: Double, val y: Double, val visible: Boolean) {
    companion object {
        val NOT_VISIBLE = HeatmapCoord(0.0, 0.0, visible = false)
    }
}

/** Threshold above which a heatmap pixel joins a blob. `inference.py:477`. */
const val HEATMAP_THRESHOLD: Float = 0.5f

/**
 * Blobs larger than this are rejected outright. `inference.py:478`.
 *
 * This is the constant that makes the algorithm something argmax cannot
 * express: a frame whose only activation is large returns NOT VISIBLE, where
 * an argmax would confidently follow the spurious blob.
 */
const val HEATMAP_MAX_AREA: Int = 100

/**
 * A TrackNet heatmap to a shuttle coordinate.
 *
 * Port of `_heatmap_to_coord` (`inference.py:477-505`). Design section 5.4
 * spells out why this is not an argmax, and the three behaviours that depend
 * on it: oversized activations are rejected rather than followed, the result
 * is sub-pixel, and a frame can legitimately report no shuttle at all.
 *
 * Connectivity is 8, matching `cv2.connectedComponentsWithStats`'s default. A
 * 4-connected implementation splits blobs on their diagonals, which changes
 * both which blob wins and whether it passes the area filter.
 */
fun heatmapToCoord(
    heatmap: FloatArray,
    width: Int,
    height: Int,
    threshold: Float = HEATMAP_THRESHOLD,
    maxArea: Int = HEATMAP_MAX_AREA,
): HeatmapCoord {
    require(width > 0 && height > 0) { "heatmap dimensions must be positive: ${width}x$height" }
    val countLong = width.toLong() * height.toLong()
    require(countLong <= Int.MAX_VALUE) { "heatmap size overflow: ${width}x$height" }
    val count = countLong.toInt()
    return heatmapToCoord(
        heatmap = heatmap,
        width = width,
        height = height,
        offset = 0,
        labels = IntArray(count),
        queue = IntArray(count),
        threshold = threshold,
        maxArea = maxArea,
    )
}

/**
 * Zero-allocation overload of [heatmapToCoord] reading [width] * [height]
 * floats from [heatmap] starting at [offset], using caller-provided [labels]
 * and [queue] scratch buffers (each at least `width * height` ints).
 */
fun heatmapToCoord(
    heatmap: FloatArray,
    width: Int,
    height: Int,
    offset: Int = 0,
    labels: IntArray,
    queue: IntArray,
    threshold: Float = HEATMAP_THRESHOLD,
    maxArea: Int = HEATMAP_MAX_AREA,
): HeatmapCoord {
    require(width > 0 && height > 0) { "heatmap dimensions must be positive: ${width}x$height" }
    val countLong = width.toLong() * height.toLong()
    require(countLong <= Int.MAX_VALUE) { "heatmap size overflow: ${width}x$height" }
    val count = countLong.toInt()
    require(offset >= 0 && offset.toLong() + countLong <= heatmap.size.toLong()) {
        "heatmap smaller than ${width}x$height at offset $offset"
    }
    require(labels.size >= count) { "labels scratch smaller than ${width}x$height" }
    require(queue.size >= count) { "queue scratch smaller than ${width}x$height" }

    // -1 unvisited, -2 below threshold, >= 0 label.
    for (i in 0 until count) {
        labels[i] = if (heatmap[offset + i] > threshold) -1 else -2
    }

    var bestArea = 0
    var bestTotalWeight = 0.0
    var bestSumX = 0.0
    var bestSumY = 0.0
    var label = 0

    for (seed in 0 until count) {
        if (labels[seed] != -1) continue
        // Flood fill iteratively: a recursive fill overflows the stack on a
        // large activation, which is precisely the case this must survive to
        // reject. Accumulate the heatmap-weighted centroid directly during BFS
        // to avoid a second full-image scan.
        var head = 0
        var tail = 0
        queue[tail++] = seed
        labels[seed] = label
        var area = 0
        var totalWeight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        while (head < tail) {
            val p = queue[head++]
            area++
            val px = p % width
            val py = p / width
            val w = heatmap[offset + p].toDouble()
            totalWeight += w
            sumX += px * w
            sumY += py * w
            for (dy in -1..1) {
                val ny = py + dy
                if (ny < 0 || ny >= height) continue
                val rowOffset = ny * width
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    if (nx < 0 || nx >= width) continue
                    val q = rowOffset + nx
                    if (labels[q] == -1) {
                        labels[q] = label
                        queue[tail++] = q
                    }
                }
            }
        }
        // Strictly greater: a blob of exactly maxArea is KEPT. `area > max_area`
        // in the source, and the boundary is load-bearing.
        if (area <= maxArea && area > bestArea) {
            bestArea = area
            bestTotalWeight = totalWeight
            bestSumX = sumX
            bestSumY = sumY
        }
        label++
    }

    if (bestArea == 0 || bestTotalWeight <= 0.0) return HeatmapCoord.NOT_VISIBLE
    return HeatmapCoord(bestSumX / bestTotalWeight, bestSumY / bestTotalWeight, visible = true)
}
