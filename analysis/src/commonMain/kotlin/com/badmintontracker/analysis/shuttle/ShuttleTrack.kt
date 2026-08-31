package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.containsPoint
import com.badmintontracker.analysis.geometry.expandedAbout
import kotlin.math.max
import kotlin.math.sqrt

data class ShuttleSample(val x: Double, val y: Double, val visible: Boolean) {
    companion object {
        val INVISIBLE = ShuttleSample(0.0, 0.0, visible = false)
    }
}

private const val STATIC_COUNT_THRESHOLD = 3

private class Cluster(var x: Double, var y: Double, var count: Int)

/**
 * Court ROI plus static-cluster rejection over a raw shuttle track.
 *
 * Port of `_build_shuttle_positions_dict`. Note this is the FILTERED track:
 * the cloud's raw per-frame track uses a different, more permissive ROI, and
 * that divergence is the cause of the trimmed rally tails described in the
 * clipping audit. This port deliberately implements only the filtered one, and
 * the raw track is simply the unfiltered input.
 *
 * Thresholds scale with resolution and with frame rate. The 30/fps factor
 * matters: at 60fps a shuttle covers half the pixels per frame, so a fixed
 * threshold would call real movement static.
 */
fun buildFilteredTrack(
    raw: Map<Int, ShuttleSample>,
    fps: Double,
    videoWidth: Int,
    videoHeight: Int,
    courtCorners: List<Point>?,
): Map<Int, ShuttleSample> {
    val fpsScale = if (fps > 0) 30.0 / fps else 1.0
    val longEdge = max(videoWidth, videoHeight).toDouble()
    // Integer thresholds, not the raw products. The worker truncates both
    // with int(), and the gap is not cosmetic: at 1920x1080 and 60fps the
    // min-move threshold is 6, not 6.72, and a shuttle crossing 6.0 to 6.72
    // pixels per frame reads as movement under the real threshold but as a
    // static false positive under the unrounded one. Measured on a synthetic
    // 40-frame track in that band: 40 visible frames versus 11.
    val staticDist = max(4, (0.013 * longEdge * fpsScale).toInt())
    val minMove = max(2, (0.007 * longEdge * fpsScale).toInt())

    val roi = courtCorners?.let { courtRoi(it) }

    val clusters = ArrayList<Cluster>()
    var prev: Point? = null
    val out = LinkedHashMap<Int, ShuttleSample>(raw.size)

    for (frame in raw.keys.sorted()) {
        val s = raw.getValue(frame)
        if (!s.visible) {
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }
        val p = Point(s.x, s.y)

        if (roi != null && !roi.containsPoint(p)) {
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        val hit = clusters.firstOrNull { dist(p, it.x, it.y) < staticDist }
        if (hit != null) {
            hit.count += 1
            hit.x = (hit.x * (hit.count - 1) + p.x) / hit.count
            hit.y = (hit.y * (hit.count - 1) + p.y) / hit.count
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        val last = prev
        if (last != null && dist(p, last.x, last.y) < minMove) {
            val near = clusters.firstOrNull { dist(p, it.x, it.y) < staticDist * 2 }
            if (near != null) near.count += 1 else clusters.add(Cluster(p.x, p.y, 1))
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        // Prune only on an accepted position. Pruning every frame is the defect
        // that leaves this mechanism dead in both of the cloud's per-frame
        // loops: a cluster is created with count 1 and removed in the same
        // iteration, so it can never reach the threshold.
        clusters.retainAll { it.count >= STATIC_COUNT_THRESHOLD }

        out[frame] = ShuttleSample(p.x, p.y, visible = true)
        prev = p
    }
    return out
}

/**
 * The court corners as the rally ROI: a 2% margin, then a further 15%.
 *
 * Both stages truncate to whole pixels because the worker rounds each one
 * through `astype(np.int32)`, and the second expansion is taken about the
 * centroid of the already-truncated polygon rather than the original. Keeping
 * doubles throughout would move the boundary by up to a pixel, which decides
 * inclusion for any position sitting on the ROI edge.
 */
private fun courtRoi(corners: List<Point>): List<Point> =
    corners.expandedAbout(1.02).truncated().expandedAbout(1.15).truncated()

private fun List<Point>.truncated(): List<Point> =
    map { Point(it.x.toInt().toDouble(), it.y.toInt().toDouble()) }

private fun dist(p: Point, x: Double, y: Double): Double =
    sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y))
