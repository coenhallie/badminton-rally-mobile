package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.geometry.COURT_KEYPOINT_POSITIONS
import com.badmintontracker.analysis.geometry.Point
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** One line segment in the court model plane, metres. */
internal class Segment(val ax: Double, val ay: Double, val bx: Double, val by: Double)

/** Sample points along [CourtModel.SEGMENTS], with the source segment of each. */
internal class ModelSamples(val x: DoubleArray, val y: DoubleArray, val segment: IntArray)

/**
 * The BWF doubles court in metres, mirroring `court_probe.py`'s module-level
 * constants. The model plane has `(0,0)` at `top_left` of
 * `COURT_KEYPOINT_POSITIONS` - the near (top-of-frame) baseline corner - x
 * growing across the net (sideline to sideline, width [W]) and y growing down
 * the court's length, through the net at `y = L / 2`, to the far baseline at
 * `y = L`.
 */
internal object CourtModel {
    const val W = 6.1
    const val L = 13.4
    const val S = 1.98
    const val LS = 0.76

    /** Half the gap between the doubles and singles sidelines. */
    const val O = (6.1 - 5.18) / 2

    /** The six across lines (both baselines, both doubles long service lines, both short service lines), y in metres. */
    val ACROSS = doubleArrayOf(0.0, LS, L / 2 - S, L / 2 + S, L - LS, L)

    /** The four along lines (both sidelines, both singles sidelines), x in metres. */
    val ALONG = doubleArrayOf(0.0, O, W - O, W)

    /**
     * The court's 12 line segments used by search: 6 across (indices 0-5), 4
     * along (indices 6-9), then the two centre-line halves either side of the
     * net (indices 10-11). Endpoints are the BWF dimension positions - the
     * boundary between the two areas a line divides, not the painted line's
     * own centre; see [CENTRE_SEGMENTS] for that.
     */
    val SEGMENTS: List<Segment> = buildList {
        for (y in ACROSS) add(Segment(0.0, y, W, y))
        for (x in ALONG) add(Segment(x, 0.0, x, L))
        add(Segment(W / 2, 0.0, W / 2, L / 2 - S))
        add(Segment(W / 2, L / 2 + S, W / 2, L))
    }

    /**
     * The same 12 segments, with the across and along lines each moved
     * inward by a half line-width (`h = 0.02`, BWF lines are 40mm wide) so
     * they sit on the painted line's own centre rather than the boundary
     * between the two areas the line divides - a line belongs to the area
     * it bounds. The two centre-line halves are unchanged (they already run
     * along `x = W / 2`, a line with no width of its own to correct for).
     * Used to profile the frame for refinement (design §4.6), where the
     * pixels searched are the paint itself, not the court-area boundary the
     * search stage works from.
     */
    val CENTRE_SEGMENTS: List<Segment> = run {
        val h = 0.02
        val acrossC = doubleArrayOf(h, LS + h, L / 2 - S - h, L / 2 + S + h, L - LS - h, L - h)
        val alongC = doubleArrayOf(h, O + h, W - O - h, W - h)
        buildList {
            for (y in acrossC) add(Segment(0.0, y, W, y))
            for (x in alongC) add(Segment(x, 0.0, x, L))
            add(Segment(W / 2, 0.0, W / 2, L / 2 - S))
            add(Segment(W / 2, L / 2 + S, W / 2, L))
        }
    }

    /** The 12 keypoints marked manually or found by search, model plane, metres. */
    val KEYPOINTS: List<Point> = COURT_KEYPOINT_POSITIONS

    /** Evenly-spaced points along every segment, `step` metres apart, both ends included. */
    fun samples(step: Double): ModelSamples {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val segs = ArrayList<Int>()
        for ((k, seg) in SEGMENTS.withIndex()) {
            val len = hypot(seg.bx - seg.ax, seg.by - seg.ay)
            val n = max(2, floor(len / step).toInt() + 1)
            for (i in 0 until n) {
                val t = i.toDouble() / (n - 1)
                xs.add(seg.ax + (seg.bx - seg.ax) * t)
                ys.add(seg.ay + (seg.by - seg.ay) * t)
                segs.add(k)
            }
        }
        return ModelSamples(xs.toDoubleArray(), ys.toDoubleArray(), segs.toIntArray())
    }

    /**
     * Every way to pick two across lines and two along lines and read their
     * intersection rectangle's four corners in one of four corner-orders -
     * the search hypothesis space of design §4.5. `C(6,2) * C(4,2) * 4 = 360`
     * entries, each `[x0,y0,x1,y1,x2,y2,x3,y3]`.
     */
    val RECTANGLES: List<DoubleArray> = buildList {
        for (a1 in 0..4) for (a2 in (a1 + 1)..5) {
            for (b1 in 0..2) for (b2 in (b1 + 1)..3) {
                val x1 = ALONG[b1]; val x2 = ALONG[b2]
                val y1 = ACROSS[a1]; val y2 = ACROSS[a2]
                add(doubleArrayOf(x1, y1, x2, y1, x2, y2, x1, y2))
                add(doubleArrayOf(x1, y1, x1, y2, x2, y2, x2, y1))
                add(doubleArrayOf(x2, y1, x1, y1, x1, y2, x2, y2))
                add(doubleArrayOf(x1, y2, x1, y1, x2, y1, x2, y2))
            }
        }
    }
}
