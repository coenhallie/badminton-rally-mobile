package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.geometry.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The best placement of the court model found by search: [h] maps court
 * metres to work-image pixels, row-major, `h[8] = 1`, labelled so model
 * `(0,0)` is the top-left of the image ([canonical]). [score] is its 0.1 m
 * score, [lines] the candidate lines found and [quads] the quadrilaterals
 * they formed, for the log.
 */
internal class SearchResult(val h: DoubleArray, val score: Double, val lines: Int, val quads: Int)

/** The strongest this many lines of each direction pair up into quadrilaterals. */
private const val LINES_PER_FAMILY = 9

/** The corner-guided search looks wider: more lines, more of each direction. */
private const val NEAR_CANDIDATE_LINES = 60
private const val NEAR_LINES_PER_FAMILY = 14

/** A quad corner farther than this many image sizes from the centre is a near-parallel pair, not a court corner. */
private const val QUAD_REACH = 2.0

/** Two lines closer to parallel than this (the determinant of their unit normals) have no usable crossing. */
private const val PARALLEL_DET = 1e-3

/** The court's image area must lie in this range, as a multiple of the frame's. */
private const val MIN_AREA = 0.02
private const val MAX_AREA = 60.0

/** The net line must be at least this fraction of the frame width long. */
private const val MIN_NET = 0.08

/** Coarse and rescoring sample spacing, metres. */
private const val COARSE_STEP = 0.5
private const val FINE_STEP = 0.1

/** How many coarse placements are rescored. */
private const val RESCORED = 60

/** A sample within this many px of a line pixel is on a line. */
private const val ON_LINE = 1.5

/** A sample in frame and off every line costs this fraction of its weight. */
private const val OFF_LINE_COST = 0.5

/** A sample's weight (its image length) is capped at this many px, so the near end of the court cannot drown the rest. */
private const val MAX_WEIGHT = 40.0

/** A sample whose projective depth is at most this is behind the camera: the placement scores minus infinity. */
private const val MIN_DEPTH = 1e-6

/** [project]'s depth floor. */
private const val MIN_PROJECT_DEPTH = 1e-12

/**
 * The model sampled for scoring: positions, and whether the next sample is
 * on the same segment (a sample's weight is its image distance to that
 * next one, 0 for the last sample of a segment). Segment `k`'s samples are
 * `starts[k] until starts[k + 1]`; [maxGain] is the most it can add,
 * [MAX_WEIGHT] per weighed sample.
 */
private class ScoreSamples(step: Double) {
    val x: DoubleArray
    val y: DoubleArray
    val weighed: BooleanArray
    val starts: IntArray
    val maxGain: DoubleArray

    init {
        val s = CourtModel.samples(step)
        x = s.x
        y = s.y
        weighed = BooleanArray(x.size) { it + 1 < x.size && s.segment[it + 1] == s.segment[it] }
        val segments = CourtModel.SEGMENTS.size
        starts = IntArray(segments + 1) { k -> s.segment.indexOfFirst { it >= k }.let { i -> if (i < 0) x.size else i } }
        maxGain = DoubleArray(segments) { k -> MAX_WEIGHT * (starts[k + 1] - starts[k] - 1) }
    }

    val segments get() = maxGain.size
}

private val COARSE by lazy { ScoreSamples(COARSE_STEP) }
private val FINE by lazy { ScoreSamples(FINE_STEP) }

/**
 * `court_probe.py`'s `search()` then `canon()` (design §4.5): the placement
 * of the court model that best explains the work image's line pixels, or
 * null when there are not two line directions, no quadrilateral, or no
 * placement that passes [gatesPass].
 *
 * The strongest 9 lines of each direction pair up: two from one and two
 * from the other cross at four points, a quadrilateral, kept when strictly
 * convex and within two image sizes of the centre. Each quadrilateral is
 * tried as each of the model's 360 [CourtModel.RECTANGLES]; a placement that
 * passes the gates is scored against the distance transform of the line
 * pixels with samples 0.5 m apart, the best 60 are rescored 0.1 m apart and
 * the best of those is kept. Ties go to the lower rectangle index, then the
 * lower quad index: the probe's concatenation order.
 *
 * `ridge` is the work image's [ridges], taken as a parameter so a caller
 * that needs the same line pixels afterwards (acceptance's line support)
 * computes them once.
 *
 * `pruning = false` scores every placement in full instead of stopping once
 * it cannot make the cut; the result is the same to the bit (Task 10). Only
 * the test that checks so turns it off.
 */
internal fun searchCourt(work: GreyImage, ridge: RidgeMap = ridges(work), pruning: Boolean = true): SearchResult? {
    val w = work.width
    val h = work.height
    val lines = candidateLines(ridge)
    val families = lineFamilies(lines, w, h) ?: return null
    val quads = Quads.of(families, LINES_PER_FAMILY, w, h)
    if (quads.count == 0) return null
    val scorer = Scorer(ridge.distance, w, h)

    val top = TopScores(RESCORED)
    val hm = DoubleArray(9)
    val rects = CourtModel.RECTANGLES.size
    for (q in 0 until quads.count) {
        for (r in 0 until rects) {
            if (!placement(quads.homographies, r, hm, q * 9)) continue
            if (!gatesPass(hm, w, h)) continue
            // Once 60 are kept, a placement that cannot beat the lowest of
            // them would not be kept: its scoring may stop early.
            val cutoff = if (!pruning || top.size < RESCORED) Double.NEGATIVE_INFINITY else top.lowest
            top.offer(scorer.score(hm, COARSE, cutoff), r, q)
        }
    }
    if (top.size == 0) return null

    val order = top.ranked()
    var bestScore = Double.NEGATIVE_INFINITY
    var bestRect = -1
    var bestQuad = -1
    for (k in order) {
        val r = top.rect[k]
        val q = top.quad[k]
        placement(quads.homographies, r, hm, q * 9)
        // Only a higher score replaces the best: one sure to end below it can stop early.
        val s = scorer.score(hm, FINE, cutoff = if (pruning) bestScore else Double.NEGATIVE_INFINITY)
        if (s > bestScore) {
            bestScore = s
            bestRect = r
            bestQuad = q
        }
    }
    if (bestRect < 0) return null
    placement(quads.homographies, bestRect, hm, bestQuad * 9)
    return SearchResult(canonical(hm), bestScore, lines.size, quads.count)
}

/**
 * `court_probe.py`'s `search_near()` then `canon()` (design §4.8): the
 * search from four rough corner taps (work pixels, any order). 60 candidate
 * lines, the strongest 14 of each direction, no horizon filter (as the
 * probe), and only placements whose four court corners each lie within
 * `radius` of a tap, matched as a set over the court's eight corner orders;
 * the best 0.1 m score among them wins. Null when no placement is that near.
 * `ridge` as for [searchCourt]. `pruning = false` turns off the early score
 * stop and both corner shortcuts (the per-quad origin check and the corners
 * checked before the gates), for the test that checks they change nothing.
 */
internal fun searchCourtNear(
    work: GreyImage,
    cornersWork: List<Point>,
    radius: Double,
    ridge: RidgeMap = ridges(work),
    pruning: Boolean = true,
): SearchResult? {
    require(cornersWork.size == 4) { "${cornersWork.size} corners" }
    val w = work.width
    val h = work.height
    val lines = candidateLines(ridge, NEAR_CANDIDATE_LINES)
    val families = lineFamilies(lines, w, h, horizon = false) ?: return null
    val quads = Quads.of(families, NEAR_LINES_PER_FAMILY, w, h)
    if (quads.count == 0) return null
    val scorer = Scorer(ridge.distance, w, h)

    val tx = DoubleArray(4) { cornersWork[it].x }
    val ty = DoubleArray(4) { cornersWork[it].y }
    val kx = DoubleArray(4) { CourtModel.KEYPOINTS[it].x }
    val ky = DoubleArray(4) { CourtModel.KEYPOINTS[it].y }
    val cx = DoubleArray(4)
    val cy = DoubleArray(4)
    val hm = DoubleArray(9)
    val origins = Origins.of
    val originNear = BooleanArray(origins.count)
    var bestScore = Double.NEGATIVE_INFINITY
    var bestRect = -1
    var bestQuad = -1
    val rects = CourtModel.RECTANGLES.size
    for (q in 0 until quads.count) {
        if (pruning) origins.nearTaps(quads.homographies, q * 9, tx, ty, radius, originNear)
        rects@ for (r in 0 until rects) {
            if (pruning && !originNear[origins.group[r]]) continue
            if (!placement(quads.homographies, r, hm, q * 9)) continue
            // The corners first, each checked against the taps as it comes:
            // a corner farther than `radius` from every tap is that far from
            // its tap under every corner order, so [cornerMismatch] would
            // exceed `radius` too. Checked before the gates (both must pass,
            // neither has side effects); a placement that passes the gates
            // has no NaN corner, so this drops nothing the mismatch keeps.
            for (c in 0..3) {
                val z = hm[6] * kx[c] + hm[7] * ky[c] + hm[8]
                cx[c] = (hm[0] * kx[c] + hm[1] * ky[c] + hm[2]) / z
                cy[c] = (hm[3] * kx[c] + hm[4] * ky[c] + hm[5]) / z
                if (pruning && nearestTap(cx[c], cy[c], tx, ty) > radius) continue@rects
            }
            if (!gatesPass(hm, w, h)) continue
            if (cornerMismatch(cx, cy, tx, ty) > radius) continue
            // A score below the best so far cannot win, even on a tie-break: it may stop early.
            val s = scorer.score(hm, FINE, cutoff = if (pruning) bestScore else Double.NEGATIVE_INFINITY)
            if (s == Double.NEGATIVE_INFINITY) continue
            // Rect-major is the probe's order: a tie goes to the lower rectangle, then the lower quad.
            if (s > bestScore || (s == bestScore && (r < bestRect || (r == bestRect && q < bestQuad)))) {
                bestScore = s
                bestRect = r
                bestQuad = q
            }
        }
    }
    if (bestRect < 0) return null
    placement(quads.homographies, bestRect, hm, bestQuad * 9)
    return SearchResult(canonical(hm), bestScore, lines.size, quads.count)
}

/**
 * The distinct points of the unit square where the rectangles of
 * [CourtModel.RECTANGLES] put the court's `(0,0)` corner: the translation
 * `(a[2], a[5])` of each [CourtModel.RECTANGLE_TO_SQUARE] map, and per
 * rectangle the index of its own in [group].
 *
 * [placement] computes that corner's image position as
 * `(p0 a[2] + p1 a[5] + p2) / h33` per row, and projecting model `(0,0)`
 * through a finite placement adds only zeros to it and divides by 1, so
 * the corner-guided search's first corner is exactly the value [nearTaps]
 * computes for the rectangle's group, once per quad for every rectangle
 * sharing it. A placement with a non-finite entry fails the gates.
 */
private class Origins(val count: Int, val u: DoubleArray, val v: DoubleArray, val group: IntArray) {
    /** Per group, into `out`, false when the corner is farther than `radius` from every tap (NaN is not). */
    fun nearTaps(hq: DoubleArray, offset: Int, tx: DoubleArray, ty: DoubleArray, radius: Double, out: BooleanArray) {
        for (g in 0 until count) {
            val h33 = hq[offset + 6] * u[g] + hq[offset + 7] * v[g] + hq[offset + 8]
            val x = (hq[offset] * u[g] + hq[offset + 1] * v[g] + hq[offset + 2]) / h33
            val y = (hq[offset + 3] * u[g] + hq[offset + 4] * v[g] + hq[offset + 5]) / h33
            out[g] = !(nearestTap(x, y, tx, ty) > radius)
        }
    }

    companion object {
        val of: Origins by lazy {
            val us = ArrayList<Double>()
            val vs = ArrayList<Double>()
            val group = IntArray(CourtModel.RECTANGLES.size) { r ->
                val a = CourtModel.RECTANGLE_TO_SQUARE[r]
                val found = us.indices.firstOrNull { us[it].toRawBits() == a[2].toRawBits() && vs[it].toRawBits() == a[5].toRawBits() }
                found ?: us.size.also { us.add(a[2]); vs.add(a[5]) }
            }
            Origins(us.size, us.toDoubleArray(), vs.toDoubleArray(), group)
        }
    }
}

/** The corner orders the probe's `search_near()` tries: the court rectangle's eight symmetries. */
private val CORNER_ORDERS = arrayOf(
    intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 0, 1),
    intArrayOf(0, 3, 2, 1), intArrayOf(3, 0, 1, 2), intArrayOf(1, 2, 3, 0), intArrayOf(2, 1, 0, 3),
)

/**
 * The farthest any projected corner is from its tap, under the best of
 * [CORNER_ORDERS]. Compared squared, one square root at the end: the
 * rounded square root is monotone, so the max and min pick the same values
 * either way (a NaN still wins both).
 */
private fun cornerMismatch(cx: DoubleArray, cy: DoubleArray, tx: DoubleArray, ty: DoubleArray): Double {
    var best = Double.POSITIVE_INFINITY
    for (perm in CORNER_ORDERS) {
        var worst = 0.0
        for (i in 0..3) {
            val dx = cx[perm[i]] - tx[i]
            val dy = cy[perm[i]] - ty[i]
            worst = max(worst, dx * dx + dy * dy)
        }
        best = min(best, worst)
    }
    return sqrt(best)
}

/** The distance from `(x, y)` to the nearest tap, computed as [cornerMismatch] does; NaN for a NaN corner. */
private fun nearestTap(x: Double, y: Double, tx: DoubleArray, ty: DoubleArray): Double {
    var best = Double.POSITIVE_INFINITY
    for (i in 0..3) {
        val dx = x - tx[i]
        val dy = y - ty[i]
        best = min(best, dx * dx + dy * dy)
    }
    return sqrt(best)
}

/**
 * The quadrilaterals two line families form, and the unit-square homography
 * of each (9 entries apiece in [homographies]), computed once per quad.
 */
private class Quads(val count: Int, val homographies: DoubleArray) {
    companion object {
        fun of(families: LineFamilies, perFamily: Int, width: Int, height: Int): Quads {
            // Strongest first; a stable sort, so equal votes keep their order, as the probe's sorted().
            val a = families.first.sortedByDescending { it.votes }.take(perFamily)
            val b = families.second.sortedByDescending { it.votes }.take(perFamily)
            val pairsA = a.size * (a.size - 1) / 2
            val pairsB = b.size * (b.size - 1) / 2
            val out = DoubleArray(9 * pairsA * pairsB)
            val quad = DoubleArray(8)
            val corner = DoubleArray(2)
            val na = Normals(a)
            val nb = Normals(b)
            var count = 0
            for (i in a.indices) for (j in i + 1 until a.size) {
                for (k in b.indices) for (l in k + 1 until b.size) {
                    if (!crossing(na, i, nb, k, corner)) continue
                    quad[0] = corner[0]; quad[1] = corner[1]
                    if (!crossing(na, i, nb, l, corner)) continue
                    quad[2] = corner[0]; quad[3] = corner[1]
                    if (!crossing(na, j, nb, l, corner)) continue
                    quad[4] = corner[0]; quad[5] = corner[1]
                    if (!crossing(na, j, nb, k, corner)) continue
                    quad[6] = corner[0]; quad[7] = corner[1]
                    if (!withinReach(quad, width, height) || !strictlyConvex(quad)) continue
                    if (!squareToQuad(quad, out, outOffset = 9 * count)) continue
                    count++
                }
            }
            return Quads(count, out)
        }
    }
}

/** Lines' normals `(cos theta, sin theta)` and distances `rho`, the trigonometry done once per line. */
private class Normals(lines: List<CandidateLine>) {
    val c = DoubleArray(lines.size) { cos(lines[it].theta) }
    val s = DoubleArray(lines.size) { sin(lines[it].theta) }
    val rho = DoubleArray(lines.size) { lines[it].rho }
}

/** `court_probe.py`'s `inter()`: where line `i` of `p` and line `j` of `q` cross, false for a near-parallel pair. */
private fun crossing(p: Normals, i: Int, q: Normals, j: Int, out: DoubleArray): Boolean {
    val c1 = p.c[i]; val s1 = p.s[i]
    val c2 = q.c[j]; val s2 = q.s[j]
    val det = c1 * s2 - s1 * c2
    if (abs(det) < PARALLEL_DET) return false
    out[0] = (p.rho[i] * s2 - s1 * q.rho[j]) / det
    out[1] = (c1 * q.rho[j] - p.rho[i] * c2) / det
    return true
}

private fun withinReach(quad: DoubleArray, width: Int, height: Int): Boolean {
    for (c in 0..3) {
        if (abs(quad[2 * c] - width / 2.0) > QUAD_REACH * width) return false
        if (abs(quad[2 * c + 1] - height / 2.0) > QUAD_REACH * height) return false
    }
    return true
}

/** `court_probe.py`'s `convex()`: every turn along the boundary the same strict way. */
private fun strictlyConvex(quad: DoubleArray): Boolean {
    var positive = 0
    var negative = 0
    for (i in 0..3) {
        val i1 = (i + 1) % 4
        val i2 = (i + 2) % 4
        val ex = quad[2 * i1] - quad[2 * i]
        val ey = quad[2 * i1 + 1] - quad[2 * i + 1]
        val fx = quad[2 * i2] - quad[2 * i1]
        val fy = quad[2 * i2 + 1] - quad[2 * i1 + 1]
        val turn = ex * fy - ey * fx
        if (turn > 0) positive++ else if (turn < 0) negative++
    }
    return positive == 4 || negative == 4
}

/**
 * Heckbert's closed-form projective map of the unit square onto the quad
 * `[x0,y0, x1,y1, x2,y2, x3,y3]`, taking `(0,0), (1,0), (1,1), (0,1)` to its
 * corners in that order, written row-major into `out` at `outOffset`.
 * False when three corners are collinear.
 */
internal fun squareToQuad(quad: DoubleArray, out: DoubleArray, outOffset: Int = 0): Boolean {
    val x0 = quad[0]; val y0 = quad[1]
    val x1 = quad[2]; val y1 = quad[3]
    val x2 = quad[4]; val y2 = quad[5]
    val x3 = quad[6]; val y3 = quad[7]
    val sx = x0 - x1 + x2 - x3
    val sy = y0 - y1 + y2 - y3
    val dx1 = x1 - x2; val dx2 = x3 - x2
    val dy1 = y1 - y2; val dy2 = y3 - y2
    val den = dx1 * dy2 - dx2 * dy1
    if (den == 0.0 || !den.isFinite()) return false
    val g = (sx * dy2 - dx2 * sy) / den
    val hh = (dx1 * sy - sx * dy1) / den
    out[outOffset] = x1 - x0 + g * x1
    out[outOffset + 1] = x3 - x0 + hh * x3
    out[outOffset + 2] = x0
    out[outOffset + 3] = y1 - y0 + g * y1
    out[outOffset + 4] = y3 - y0 + hh * y3
    out[outOffset + 5] = y0
    out[outOffset + 6] = g
    out[outOffset + 7] = hh
    out[outOffset + 8] = 1.0
    return true
}

/**
 * The placement of rectangle `rect` ([CourtModel.RECTANGLES]) on a quad:
 * the quad's unit-square homography (`hq` at `hqOffset`) times the
 * rectangle's [CourtModel.RECTANGLE_TO_SQUARE] map, into `out`, scaled so
 * `out[8] = 1`. That is the probe's `batch_H` solution (it fixes
 * `h33 = 1` too), and the scale matters: its gates and scoring read the
 * sign of the projective depth. False when `h33` is (numerically) zero,
 * where the probe's solve is singular.
 */
internal fun placement(hq: DoubleArray, rect: Int, out: DoubleArray, hqOffset: Int = 0): Boolean {
    val a = CourtModel.RECTANGLE_TO_SQUARE[rect]
    var scale = 0.0
    for (row in 0..2) {
        val p0 = hq[hqOffset + 3 * row]
        val p1 = hq[hqOffset + 3 * row + 1]
        val p2 = hq[hqOffset + 3 * row + 2]
        out[3 * row] = p0 * a[0] + p1 * a[3]
        out[3 * row + 1] = p0 * a[1] + p1 * a[4]
        out[3 * row + 2] = p0 * a[2] + p1 * a[5] + p2
        scale = max(scale, max(abs(out[3 * row]), max(abs(out[3 * row + 1]), abs(out[3 * row + 2]))))
    }
    val h33 = out[8]
    if (!h33.isFinite() || abs(h33) <= 1e-12 * scale) return false
    for (i in 0..7) out[i] /= h33
    out[8] = 1.0
    return true
}

/**
 * `court_probe.py`'s `gates()` for one placement `h` (court metres to
 * pixels, scaled so `h[8] = 1`: the depth test reads the sign): all four
 * court corners in front of the camera, the court's image area between
 * 2% and 6000% of the `width x height` frame, and the net line at least 8%
 * of the width long.
 */
internal fun gatesPass(h: DoubleArray, width: Int, height: Int): Boolean {
    val w = CourtModel.W
    val l = CourtModel.L
    val z0 = h[8]
    val z1 = h[6] * w + h[8]
    val z2 = h[6] * w + h[7] * l + h[8]
    val z3 = h[7] * l + h[8]
    if (!(z0 > 0 && z1 > 0 && z2 > 0 && z3 > 0)) return false
    val x0 = h[2] / z0; val y0 = h[5] / z0
    val x1 = (h[0] * w + h[2]) / z1; val y1 = (h[3] * w + h[5]) / z1
    val x2 = (h[0] * w + h[1] * l + h[2]) / z2; val y2 = (h[3] * w + h[4] * l + h[5]) / z2
    val x3 = (h[1] * l + h[2]) / z3; val y3 = (h[4] * l + h[5]) / z3
    val area = 0.5 * abs(x0 * y1 - x1 * y0 + x1 * y2 - x2 * y1 + x2 * y3 - x3 * y2 + x3 * y0 - x0 * y3)
    val frame = width.toDouble() * height
    if (!(area > MIN_AREA * frame && area < MAX_AREA * frame)) return false
    val ny = l / 2
    val za = h[7] * ny + h[8]
    val zb = h[6] * w + h[7] * ny + h[8]
    val dx = (h[0] * w + h[1] * ny + h[2]) / zb - (h[1] * ny + h[2]) / za
    val dy = (h[3] * w + h[4] * ny + h[5]) / zb - (h[4] * ny + h[5]) / za
    return sqrt(dx * dx + dy * dy) > MIN_NET * width
}

/**
 * Slack on each segment's bound, px: a multiple of [roundingScale] plus an
 * absolute amount, many orders of magnitude above the rounding it covers.
 */
private const val BOUND_SLACK_RELATIVE = 1e-9
private const val BOUND_SLACK_PX = 1e-3

/**
 * `court_probe.py`'s `score()` for placements against one distance
 * transform `dist` of a `width x height` image.
 */
private class Scorer(private val dist: FloatArray, private val width: Int, private val height: Int) {
    /** Per segment `k`, a bound on the weight its samples in frame can sum to. */
    private val gain = DoubleArray(CourtModel.SEGMENTS.size)

    /** Per segment `k`, a bound on what segments `k` onwards can still add. */
    private val remaining = DoubleArray(CourtModel.SEGMENTS.size + 1)

    /** Per segment, true when none of its samples can be in frame or behind the camera: it adds exactly 0. */
    private val outside = BooleanArray(CourtModel.SEGMENTS.size)

    /**
     * The score of placement `h`: each sample, weighted by its image
     * distance to the next sample on the same segment (capped at
     * [MAX_WEIGHT]), adds its weight when within [ON_LINE] px of a line
     * pixel and costs half of it when in frame and not; out of frame it
     * counts 0. In frame is `0 <= x < w - 1, 0 <= y < h - 1`, the distance
     * read at the pixel the position truncates to. A sample behind the
     * camera makes the whole placement minus infinity (the probe's `-1e9`),
     * and ends the loop.
     *
     * With a finite `cutoff`, scoring stops with minus infinity as soon as
     * the score is sure to end below `cutoff`: the caller keeps only scores
     * of at least `cutoff`, so it keeps the same placements. The samples are
     * summed in the same order either way, so a score that is returned is
     * the same number. Sure means the total so far plus [bound]'s bound on
     * the segments still to come plus what is left of the current segment's
     * bound (the in-frame weight it has not used yet) is below `cutoff`;
     * that sum only drops when a sample in frame is off every line.
     */
    fun score(h: DoubleArray, samples: ScoreSamples, cutoff: Double = Double.NEGATIVE_INFINITY): Double {
        val xs = samples.x
        val ys = samples.y
        val weighed = samples.weighed
        val starts = samples.starts
        val h0 = h[0]; val h1 = h[1]; val h2 = h[2]
        val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
        val h6 = h[6]; val h7 = h[7]; val h8 = h[8]
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        val bounded = cutoff > Double.NEGATIVE_INFINITY
        if (bounded) {
            if (!bound(h, samples)) return Double.NEGATIVE_INFINITY
            if (remaining[0] < cutoff) return Double.NEGATIVE_INFINITY
        }
        var total = 0.0
        for (k in 0 until samples.segments) {
            // A segment that adds exactly 0 and cannot end the loop is skipped.
            if (!(bounded && outside[k])) {
                val end = starts[k + 1]
                var z = h6 * xs[starts[k]] + h7 * ys[starts[k]] + h8
                if (z <= MIN_DEPTH) return Double.NEGATIVE_INFINITY
                var px = (h0 * xs[starts[k]] + h1 * ys[starts[k]] + h2) / z
                var py = (h3 * xs[starts[k]] + h4 * ys[starts[k]] + h5) / z
                var used = 0.0
                for (i in starts[k] until end) {
                    // The segment's last sample is not weighed: nothing reads its next point.
                    var nx = 0.0
                    var ny = 0.0
                    if (i + 1 < end) {
                        val mx = xs[i + 1]
                        val my = ys[i + 1]
                        z = h6 * mx + h7 * my + h8
                        if (z <= MIN_DEPTH) return Double.NEGATIVE_INFINITY
                        nx = (h0 * mx + h1 * my + h2) / z
                        ny = (h3 * mx + h4 * my + h5) / z
                    }
                    if (weighed[i] && px >= 0.0 && px < maxX && py >= 0.0 && py < maxY) {
                        val dx = nx - px
                        val dy = ny - py
                        val weight = min(sqrt(dx * dx + dy * dy), MAX_WEIGHT)
                        used += weight
                        if (dist[py.toInt() * width + px.toInt()] <= ON_LINE) {
                            total += weight
                        } else {
                            total += -OFF_LINE_COST * weight
                            if (bounded && total + (remaining[k + 1] + (gain[k] - used)) < cutoff) {
                                return Double.NEGATIVE_INFINITY
                            }
                        }
                    }
                    px = nx
                    py = ny
                }
            }
            if (bounded && total + remaining[k + 1] < cutoff) return Double.NEGATIVE_INFINITY
        }
        return total
    }

    /**
     * Fills [remaining] for placement `h`; false when a segment end is
     * behind the camera (the score is minus infinity then anyway).
     *
     * A segment's samples project, in order, onto the straight image
     * segment between its first and last sample, so their weights sum to at
     * most that segment's length, and to at most [MAX_WEIGHT] each. Only
     * samples in frame count, and they run consecutively along it: they add
     * at most the length inside the frame plus one capped weight reaching
     * out of it, and nothing when it misses the frame. The slack covers the
     * rounding of the projected points (which sit a few ulps of
     * [roundingScale] off their exact positions) and of the running total;
     * the frame is widened by it too. A NaN bound never prunes.
     *
     * A segment that misses the widened frame has no sample in frame, and
     * when both its ends are in front of the camera by more than the
     * rounding of a depth, so is every sample between them (depth is affine
     * along it): it is marked [outside].
     */
    private fun bound(h: DoubleArray, samples: ScoreSamples): Boolean {
        val xs = samples.x
        val ys = samples.y
        val segments = samples.segments
        val slack = BOUND_SLACK_RELATIVE * roundingScale(h) + BOUND_SLACK_PX
        val inFront = MIN_DEPTH + BOUND_SLACK_RELATIVE * ((abs(h[6]) + abs(h[7])) * CourtModel.L + abs(h[8]))
        val maxX = (width - 1).toDouble()
        val maxY = (height - 1).toDouble()
        remaining[segments] = 0.0
        for (k in segments - 1 downTo 0) {
            val a = samples.starts[k]
            val b = samples.starts[k + 1] - 1
            val za = h[6] * xs[a] + h[7] * ys[a] + h[8]
            val zb = h[6] * xs[b] + h[7] * ys[b] + h[8]
            if (za <= MIN_DEPTH || zb <= MIN_DEPTH) return false
            val ax = (h[0] * xs[a] + h[1] * ys[a] + h[2]) / za
            val ay = (h[3] * xs[a] + h[4] * ys[a] + h[5]) / za
            val bx = (h[0] * xs[b] + h[1] * ys[b] + h[2]) / zb
            val by = (h[3] * xs[b] + h[4] * ys[b] + h[5]) / zb
            val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val inFrame = clippedLength(ax, ay, bx, by, length, maxX, maxY, slack)
            outside[k] = inFrame == 0.0 && za > inFront && zb > inFront
            val most = if (inFrame == 0.0) 0.0 else min(min(samples.maxGain[k], length), inFrame + MAX_WEIGHT)
            gain[k] = most + slack
            remaining[k] = remaining[k + 1] + gain[k]
        }
        return true
    }
}

/**
 * A bound on the magnitude of the terms that make up a projected court
 * point through `h`, px: the rounding error of a projected sample is a few
 * ulps of this. Model coordinates are within `[0, L]`, and the depth over
 * the court is smallest at a corner; infinity when a corner is not in front.
 */
private fun roundingScale(h: DoubleArray): Double {
    val w = CourtModel.W
    val l = CourtModel.L
    val zMin = min(min(h[8], h[6] * w + h[8]), min(h[6] * w + h[7] * l + h[8], h[7] * l + h[8]))
    if (!(zMin > 0.0)) return Double.POSITIVE_INFINITY
    val numerator = (abs(h[0]) + abs(h[1]) + abs(h[3]) + abs(h[4])) * l + abs(h[2]) + abs(h[5])
    val depth = (abs(h[6]) + abs(h[7])) * l + abs(h[8])
    return (numerator + numerator / zMin * depth) / zMin
}

/**
 * The length of image segment `a`-`b` (of length `length`) inside the
 * frame `[0, maxX] x [0, maxY]` widened by `margin` on every side, by
 * Liang-Barsky clipping: 0 when it misses, NaN for a NaN end.
 */
private fun clippedLength(
    ax: Double, ay: Double, bx: Double, by: Double, length: Double,
    maxX: Double, maxY: Double, margin: Double,
): Double {
    val lo = -margin
    val hiX = maxX + margin
    val hiY = maxY + margin
    if (ax >= lo && ax <= hiX && ay >= lo && ay <= hiY && bx >= lo && bx <= hiX && by >= lo && by <= hiY) return length
    if ((ax < lo && bx < lo) || (ax > hiX && bx > hiX) || (ay < lo && by < lo) || (ay > hiY && by > hiY)) return 0.0
    val dx = bx - ax
    val dy = by - ay
    var t0 = 0.0
    var t1 = 1.0
    for (edge in 0..3) {
        val p: Double
        val q: Double
        when (edge) {
            0 -> { p = -dx; q = ax + margin }
            1 -> { p = dx; q = maxX + margin - ax }
            2 -> { p = -dy; q = ay + margin }
            else -> { p = dy; q = maxY + margin - ay }
        }
        if (p == 0.0) {
            if (q < 0.0) return 0.0
        } else {
            val r = q / p
            if (p < 0.0) t0 = max(t0, r) else t1 = min(t1, r)
        }
    }
    if (t0 > t1) return 0.0
    return (t1 - t0) * length
}

/**
 * The best [capacity] coarse scores seen, as a fixed-size min-heap: the root
 * is the next entry to be evicted, the lowest score (on a tie, the one later
 * in the probe's rect-major order).
 */
private class TopScores(private val capacity: Int) {
    val score = DoubleArray(capacity)
    val rect = IntArray(capacity)
    val quad = IntArray(capacity)
    var size = 0
        private set

    /** True when entry `i` ranks below entry `j`. */
    private fun below(i: Int, j: Int) = below(score[i], rect[i], quad[i], score[j], rect[j], quad[j])

    private fun below(s1: Double, r1: Int, q1: Int, s2: Double, r2: Int, q2: Int): Boolean =
        s1 < s2 || (s1 == s2 && (r1 > r2 || (r1 == r2 && q1 > q2)))

    /** The lowest score kept, once [capacity] are. */
    val lowest: Double get() = score[0]

    fun offer(s: Double, r: Int, q: Int) {
        if (size < capacity) {
            score[size] = s; rect[size] = r; quad[size] = q
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!below(i, parent)) break
                swap(i, parent)
                i = parent
            }
            return
        }
        if (!below(score[0], rect[0], quad[0], s, r, q)) return
        score[0] = s; rect[0] = r; quad[0] = q
        var i = 0
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var least = i
            if (left < size && below(left, least)) least = left
            if (right < size && below(right, least)) least = right
            if (least == i) break
            swap(i, least)
            i = least
        }
    }

    /** Entry indices best first. */
    fun ranked(): List<Int> = (0 until size).sortedWith { i, j ->
        when {
            below(j, i) -> -1
            below(i, j) -> 1
            else -> 0
        }
    }

    private fun swap(i: Int, j: Int) {
        val s = score[i]; score[i] = score[j]; score[j] = s
        val r = rect[i]; rect[i] = rect[j]; rect[j] = r
        val q = quad[i]; quad[i] = quad[j]; quad[j] = q
    }
}

/**
 * `court_probe.py`'s `canon()`: `h` relabelled so model `(0,0)` is the
 * top-left of the image - mirrored in x if `(0,0)` projects right of
 * `(W,0)`, then in y if it projects below `(0,L)` - and scaled so
 * `h[8] = 1`. Row-major, court metres to pixels. Plain division, as the
 * probe's, with no depth check.
 */
internal fun canonical(h: DoubleArray): DoubleArray {
    val w = CourtModel.W
    val l = CourtModel.L
    val m = h.copyOf()
    // H * [[-1,0,W],[0,1,0],[0,0,1]]: column 0 negated, column 2 gains W * column 0.
    if (m[2] / m[8] > (m[0] * w + m[2]) / (m[6] * w + m[8])) {
        for (row in 0..2) {
            val c0 = m[3 * row]
            m[3 * row] = -c0
            m[3 * row + 2] += w * c0
        }
    }
    // H * [[1,0,0],[0,-1,L],[0,0,1]]: column 1 negated, column 2 gains L * column 1.
    if (m[5] / m[8] > (m[4] * l + m[5]) / (m[7] * l + m[8])) {
        for (row in 0..2) {
            val c1 = m[3 * row + 1]
            m[3 * row + 1] = -c1
            m[3 * row + 2] += l * c1
        }
    }
    val s = m[8]
    for (i in 0..8) m[i] /= s
    return m
}

/** `(x, y)` in court metres through the row-major homography `h`; null at or behind the camera (depth `<= 1e-12`). */
internal fun project(h: DoubleArray, x: Double, y: Double): Point? {
    val z = h[6] * x + h[7] * y + h[8]
    if (z <= MIN_PROJECT_DEPTH) return null
    return Point((h[0] * x + h[1] * y + h[2]) / z, (h[3] * x + h[4] * y + h[5]) / z)
}
