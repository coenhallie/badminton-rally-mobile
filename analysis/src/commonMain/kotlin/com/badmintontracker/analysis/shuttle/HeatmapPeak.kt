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
    require(heatmap.size >= width * height) { "heatmap smaller than ${width}x$height" }

    // -1 unvisited, -2 below threshold, >= 0 label.
    val labels = IntArray(width * height) { if (heatmap[it] > threshold) -1 else -2 }
    val queue = IntArray(width * height)

    var bestLabel = -1
    var bestArea = 0
    var label = 0

    for (seed in labels.indices) {
        if (labels[seed] != -1) continue
        // Flood fill iteratively: a recursive fill overflows the stack on a
        // large activation, which is precisely the case this must survive to
        // reject.
        var head = 0
        var tail = 0
        queue[tail++] = seed
        labels[seed] = label
        var area = 0
        while (head < tail) {
            val p = queue[head++]
            area++
            val px = p % width
            val py = p / width
            for (dy in -1..1) {
                val ny = py + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    if (nx < 0 || nx >= width) continue
                    val q = ny * width + nx
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
            bestLabel = label
        }
        label++
    }

    if (bestLabel < 0) return HeatmapCoord.NOT_VISIBLE

    // Heatmap-weighted centroid of the winning blob, which is what makes the
    // result sub-pixel rather than a pixel index.
    var totalWeight = 0.0
    var sumX = 0.0
    var sumY = 0.0
    for (p in labels.indices) {
        if (labels[p] != bestLabel) continue
        val w = heatmap[p].toDouble()
        totalWeight += w
        sumX += (p % width) * w
        sumY += (p / width) * w
    }
    if (totalWeight <= 0.0) return HeatmapCoord.NOT_VISIBLE
    return HeatmapCoord(sumX / totalWeight, sumY / totalWeight, visible = true)
}
