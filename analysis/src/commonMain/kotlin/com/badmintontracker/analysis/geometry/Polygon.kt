package com.badmintontracker.analysis.geometry

import kotlin.math.abs
import kotlin.math.max

/**
 * Ray-casting point-in-polygon, replacing cv2.pointPolygonTest.
 *
 * The `(yi > p.y) != (yj > p.y)` test is half-open in y deliberately: a ray
 * passing exactly through a vertex must count one crossing, not two.
 *
 * Matches `pointPolygonTest(..., measureDist=False) >= 0`, so a point exactly
 * on an edge counts as inside, as it does in the worker.
 *
 * Named `containsPoint` rather than `contains` on purpose. `List` already has
 * a member `contains(element)`, a member always beats an extension, and the
 * compiler does not warn: an extension named `contains` compiles fine, is
 * never called, and every ROI test silently answers "is this exact Point one
 * of the four vertices" - which is false for every real shuttle position.
 */
fun List<Point>.containsPoint(p: Point): Boolean {
    if (size < 3) return false
    var inside = false
    var j = size - 1
    for (i in indices) {
        val (xi, yi) = this[i]
        val (xj, yj) = this[j]
        if ((yi > p.y) != (yj > p.y)) {
            val xCross = xi + (p.y - yi) / (yj - yi) * (xj - xi)
            if (p.x <= xCross) inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Scale every vertex away from the polygon's centroid.
 *
 * Mirrors the worker's uniform ROI margins: 1.02 for the court polygon and
 * 1.15 for the rally-gating one. The shuttle ROI is NOT uniform - it expands
 * x by 1.40 but clamps every vertex above the centroid to y = 0 - so it does
 * not go through here.
 */
fun List<Point>.expandedAbout(centroidFactor: Double): List<Point> {
    if (isEmpty()) return this
    val cx = sumOf { it.x } / size
    val cy = sumOf { it.y } / size
    return map { Point(cx + (it.x - cx) * centroidFactor, cy + (it.y - cy) * centroidFactor) }
}

/**
 * Is this pair of net endpoints usable as a court-side divider?
 *
 * Port of `valid_net_line`. A degenerate line fails silently and
 * catastrophically rather than loudly: with dx == 0 the y-at-x interpolation
 * is skipped and every position compares against a midline of 0, so the whole
 * frame classifies as one side. That single condition disables the identity
 * tracker's strongest anchor while the startup log still claims real keypoints.
 */
fun validNetLine(left: Point?, right: Point?, width: Double, height: Double): Boolean {
    if (left == null || right == null) return false
    for (p in listOf(left, right)) {
        if (!p.x.isFinite() || !p.y.isFinite() || p.x < 0 || p.y < 0) return false
    }
    if (left.x == 0.0 && left.y == 0.0 && right.x == 0.0 && right.y == 0.0) return false
    // The net spans the court's width, so its endpoints must be meaningfully
    // separated horizontally. 1% of frame width, floored at 2px for tiny inputs.
    val minDx = if (width > 0) max(2.0, 0.01 * width) else 2.0
    if (abs(right.x - left.x) < minDx) return false
    if (width > 0 && (left.x > width || right.x > width)) return false
    if (height > 0 && (left.y > height || right.y > height)) return false
    return true
}
