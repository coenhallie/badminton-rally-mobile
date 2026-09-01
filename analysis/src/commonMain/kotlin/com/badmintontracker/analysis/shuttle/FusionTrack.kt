package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.containsPoint
import kotlin.math.max
import kotlin.math.sqrt

/** One YOLO shuttle detection: a position and the confidence behind it. */
data class ShuttleDetection(val x: Double, val y: Double, val confidence: Double)

/**
 * The per-frame TrackNet/YOLO fusion the cloud's detection loop produces.
 *
 * This is the track the cloud's shot-gap detector reads, stored as
 * `skeleton_frames[].shuttle_position`, and it is NOT the filtered track in
 * `shuttle_positions`. Two differences matter: a far more permissive court ROI,
 * and a YOLO fallback that contributes about a third of the positions on real
 * footage (2,883 TrackNet against 1,472 YOLO on one capture).
 *
 * Port of the shuttle-selection block in `_run_detection_only_loop`
 * (`modal_supabase_processor.py:2296-2360`). TrackNet is preferred; YOLO is
 * consulted only when TrackNet offers nothing that survives the gates.
 *
 * **The static-cluster mechanism in that loop is inert, and this reproduces
 * that rather than fixing it.** Clusters are pruned to `count >= 3`
 * unconditionally at the end of every frame (`:2356`), while a cluster is
 * created with count 1 and can only be incremented by another candidate in the
 * same frame, so none ever survives to the next one. The gate that actually
 * fires is the minimum-movement check against the previous accepted position.
 * Reproducing an inert mechanism looks odd; diverging from it would change
 * which frames carry a shuttle, and therefore which rallies exist.
 */
fun buildFusionTrack(
    trackNet: Map<Int, ShuttleSample>,
    detections: Map<Int, List<ShuttleDetection>>,
    fps: Double,
    videoWidth: Int,
    videoHeight: Int,
    courtCorners: List<Point>?,
    totalFrames: Int,
): Map<Int, ShuttleSample> {
    val fpsScale = if (fps > 0) 30.0 / fps else 1.0
    val longEdge = max(videoWidth, videoHeight).toDouble()
    // Same truncated thresholds as the filtered track: the worker computes
    // both with int().
    val minMove = max(2, (0.007 * longEdge * fpsScale).toInt())

    val roi = courtCorners?.let { shuttleRoi(it) }
    fun inCourt(x: Double, y: Double) = roi == null || roi.containsPoint(Point(x, y))

    val out = LinkedHashMap<Int, ShuttleSample>(totalFrames)
    var prev: Point? = null

    for (frame in 0 until totalFrames) {
        var accepted: Point? = null

        // TrackNet first.
        val tn = trackNet[frame]
        if (tn != null && tn.visible && inCourt(tn.x, tn.y)) {
            val last = prev
            if (last == null || dist(tn.x, tn.y, last) >= minMove) {
                accepted = Point(tn.x, tn.y)
            }
        }

        // YOLO only if TrackNet gave nothing, and highest confidence first.
        if (accepted == null) {
            for (d in detections[frame].orEmpty().sortedByDescending { it.confidence }) {
                if (!inCourt(d.x, d.y)) continue
                val last = prev
                if (last != null && dist(d.x, d.y, last) < minMove) continue
                accepted = Point(d.x, d.y)
                break
            }
        }

        out[frame] = accepted?.let { ShuttleSample(it.x, it.y, visible = true) }
            ?: ShuttleSample.INVISIBLE
        if (accepted != null) prev = accepted
    }
    return out
}

/**
 * The permissive shuttle ROI (`modal_supabase_processor.py:2193-2203`).
 *
 * Widened 40% horizontally, and every vertex above the centroid pinned to
 * y = 0 rather than expanded - the shuttle spends much of a rally above the
 * court, so the region opens to the top of the frame. Truncated to whole
 * pixels at each stage, as the worker's `astype(np.int32)` does.
 */
private fun shuttleRoi(corners: List<Point>): List<Point> {
    // The 2% court margin first, exactly as _compute_court_polygon.
    val cx0 = corners.sumOf { it.x } / corners.size
    val cy0 = corners.sumOf { it.y } / corners.size
    val court = corners.map {
        Point(
            (cx0 + (it.x - cx0) * 1.02).toInt().toDouble(),
            (cy0 + (it.y - cy0) * 1.02).toInt().toDouble(),
        )
    }
    val cx = court.sumOf { it.x } / court.size
    val cy = court.sumOf { it.y } / court.size
    return court.map {
        val x = cx + (it.x - cx) * 1.40
        val y = if (it.y < cy) 0.0 else cy + (it.y - cy) * 1.40
        Point(x.toInt().toDouble(), y.toInt().toDouble())
    }
}

private fun dist(x: Double, y: Double, p: Point): Double =
    sqrt((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y))
