package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import com.badmintontracker.analysis.geometry.COURT_KEYPOINT_POSITIONS
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.calculateHomography
import com.badmintontracker.analysis.geometry.courtPositions
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.maxResidualM
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.time.TimeSource

/**
 * What [CourtFinder] found in a frame.
 *
 * When found, [keypoints] are the 12 table points (`COURT_KEYPOINT_POSITIONS`)
 * in source pixels, labelled as the table labels them (model `(0,0)` is the
 * top-left of the image), and [homography] maps court metres to source
 * pixels; [reason] is `"ok"`. When not, both are null and [reason] names the
 * first acceptance check that failed (design §4.7), for the log, not the
 * user. [residualPx] is the refinement's RMS line residual scaled to a
 * 1920 px long side - the value the 1.5 px acceptance check reads - whenever
 * refinement ran, found or not. [millis] is the whole call's wall time.
 */
data class CourtFinding(
    val keypoints: CourtKeypoints?,
    val homography: Matrix3x3?,
    val reason: String,
    val residualPx: Double?,
    val millis: Long,
) {
    val found: Boolean get() = keypoints != null
}

/**
 * The court's 12 line segments in court metres, the ones search fits (the
 * internal `CourtModel.SEGMENTS`, in its order: the 6 across lines, the 4
 * along lines, then the two centre-line halves either side of the net), for
 * the marking screens to draw through a marking's homography. Endpoints are
 * the BWF dimension positions, the same plane the 12 keypoints live in, so a
 * court drawn through well-placed marks sits on the marks themselves.
 */
val COURT_LINE_SEGMENTS: List<Pair<Point, Point>> =
    CourtModel.SEGMENTS.map { Point(it.ax, it.ay) to Point(it.bx, it.by) }

/** The search runs on an image this long on its long side. */
private const val WORK_LONG_SIDE = 960

/** Refinement runs at source resolution, capped to this long side. */
private const val FIT_LONG_SIDE = 1920

/** The window tolerance refinement starts from after a search, px. */
private const val SEARCH_TOLERANCE = 12.0

/** ... and from the four taps themselves, when the corner-guided search finds nothing near. */
private const val TAP_TOLERANCE = 30.0

/** A corner-guided placement's corners must each be within this fraction of the work long side of a tap. */
private const val TAP_RADIUS = 0.05

/** Acceptance (design §4.7). */
private const val MAX_LAST_STEP_PX = 0.1
private const val MAX_RESIDUAL_PX = 1.5
private const val SUPPORT_POINTS = 200
private const val SUPPORT_ON_LINE_PX = 1.5
private const val SUPPORT_FRACTION = 0.4
private const val SUPPORT_MIN_VISIBLE = 0.05
private const val MIN_SUPPORTED_LINES = 2
private const val MAX_MODEL_RESIDUAL_M = 0.05

/**
 * Finds the badminton court in a frame (design §4): the public face of the
 * `court` package. Pure computation, no I/O; both functions may take a
 * second or more on a phone, so call them off the main thread.
 */
object CourtFinder {
    /**
     * `court_probe.py`'s `detect()` then `accept()`: search the work image
     * (long side 960) for the placement of the court model that best explains
     * its line pixels, refine it on the fit image (source resolution, long
     * side capped at 1920) and accept it only if every check of design §4.7
     * holds. Never throws: an exception becomes a not-found finding with the
     * reason `"error: <message>"`.
     */
    fun find(image: RgbImage): CourtFinding = timed { mark ->
        val frames = Frames(image)
        val ridge = ridges(frames.work)
        val search = searchCourt(frames.work, ridge)
            ?: return@timed notFound("no hypothesis", mark)
        val h0 = frames.workToFit(search.h)
        accept(frames, ridge, refineCourt(frames.fit, h0, SEARCH_TOLERANCE), mark)
    }

    /**
     * `court_probe.py`'s `assist()` then `accept()` (design §4.8): complete
     * the court from four taps, `corners` in source pixels in the order
     * tapped (top-left, top-right, bottom-right, bottom-left). The search of
     * [find], widened, keeps only placements whose corners each lie within 5%
     * of the work long side of a tap (any order); when none does, the taps
     * themselves seed the refinement with a 30 px starting tolerance. The
     * same refinement and acceptance as [find] follow. Never throws.
     */
    fun fromCorners(image: RgbImage, corners: List<Point>): CourtFinding = timed { mark ->
        if (corners.size != 4) return@timed notFound("expected 4 corners, got ${corners.size}", mark)
        val frames = Frames(image)
        val ridge = ridges(frames.work)
        val tapsWork = corners.map { frames.sourceToWork(it) }
        val radius = TAP_RADIUS * max(frames.work.width, frames.work.height)
        val near = searchCourtNear(frames.work, tapsWork, radius, ridge)
        val h0: DoubleArray
        val tolerance0: Double
        if (near != null) {
            h0 = frames.workToFit(near.h)
            tolerance0 = SEARCH_TOLERANCE
        } else {
            val tapsFit = corners.map { frames.sourceToFit(it) }
            val fitted = calculateHomography(CourtModel.KEYPOINTS.take(4), tapsFit)
                ?: return@timed notFound("no hypothesis", mark)
            h0 = DoubleArray(9) { fitted[it / 3][it % 3] / fitted[2][2] }
            tolerance0 = TAP_TOLERANCE
        }
        accept(frames, ridge, refineCourt(frames.fit, h0, tolerance0), mark)
    }
}

/**
 * The luma of the source at the two resolutions the detector reads, and the
 * pixel-centre maps between them and the source:
 * `x_dst = (x_src + 0.5) * (w_dst / w_src) - 0.5`, per axis.
 */
private class Frames(image: RgbImage) {
    val sourceWidth = image.width
    val sourceHeight = image.height
    val work: GreyImage
    val fit: GreyImage

    init {
        val luma = image.luma()
        work = downsampled(luma, WORK_LONG_SIDE)
        fit = downsampled(luma, FIT_LONG_SIDE)
    }

    fun workToFit(h: DoubleArray) = rescale(h, work.width, work.height, fit.width, fit.height)
    fun fitToWork(h: DoubleArray) = rescale(h, fit.width, fit.height, work.width, work.height)
    fun fitToSource(h: DoubleArray) = rescale(h, fit.width, fit.height, sourceWidth, sourceHeight)
    fun sourceToWork(p: Point) = rescale(p, sourceWidth, sourceHeight, work.width, work.height)
    fun sourceToFit(p: Point) = rescale(p, sourceWidth, sourceHeight, fit.width, fit.height)

    private fun downsampled(luma: GreyImage, longSide: Int): GreyImage {
        val (w, h) = longSideSize(luma.width, luma.height, longSide)
        return if (w == luma.width && h == luma.height) luma else areaDownsample(luma, w, h)
    }
}

/** `h` (court metres to pixels of a `fw x fh` frame) re-expressed in pixels of a `tw x th` frame, `h[8] = 1`. */
private fun rescale(h: DoubleArray, fw: Int, fh: Int, tw: Int, th: Int): DoubleArray {
    val sx = tw.toDouble() / fw
    val sy = th.toDouble() / fh
    val ox = 0.5 * sx - 0.5
    val oy = 0.5 * sy - 0.5
    val out = DoubleArray(9)
    for (c in 0..2) {
        out[c] = sx * h[c] + ox * h[6 + c]
        out[3 + c] = sy * h[3 + c] + oy * h[6 + c]
        out[6 + c] = h[6 + c]
    }
    val s = out[8]
    for (i in 0..8) out[i] /= s
    return out
}

private fun rescale(p: Point, fw: Int, fh: Int, tw: Int, th: Int): Point {
    val sx = tw.toDouble() / fw
    val sy = th.toDouble() / fh
    return Point((p.x + 0.5) * sx - 0.5, (p.y + 0.5) * sy - 0.5)
}

/**
 * Design §4.7, in order, on a refinement in fit pixels: each failure is its
 * own reason, the last "labels resolve differently" ([resolvesAsTheTable]).
 * The accepted homography is relabelled ([canonical]) and mapped to source
 * pixels before the keypoints are read from it.
 */
private fun accept(frames: Frames, ridge: RidgeMap, ref: Refinement?, mark: TimeSource.Monotonic.ValueTimeMark): CourtFinding {
    if (ref == null || ref.h.any { !it.isFinite() }) return notFound("refinement failed", mark)
    val residual = ref.rmsPx * FIT_LONG_SIDE / max(frames.fit.width, frames.fit.height)
    if (!gatesPass(ref.h, frames.fit.width, frames.fit.height)) return notFound("refinement failed", mark, residual)
    if (ref.lastStepPx > MAX_LAST_STEP_PX) {
        return notFound("not converged (last step ${fixed(ref.lastStepPx, 3)} px)", mark, residual)
    }
    if (residual > MAX_RESIDUAL_PX) return notFound("residual ${fixed(residual, 2)} px", mark, residual)

    val fitH = canonical(ref.h)
    val sourceH = frames.fitToSource(fitH)
    val points = CourtModel.KEYPOINTS.map { project(sourceH, it.x, it.y) }
    val inside = points.all { p ->
        p != null && p.x >= 0.0 && p.x < frames.sourceWidth && p.y >= 0.0 && p.y < frames.sourceHeight
    }
    if (!inside) return notFound("keypoint outside the frame", mark, residual)

    val (across, along) = lineSupport(ridge, frames.fitToWork(fitH))
    if (across < MIN_SUPPORTED_LINES || along < MIN_SUPPORTED_LINES) {
        return notFound("line support $across/$along", mark, residual)
    }

    val p = points.map { it!! }
    val keypoints = CourtKeypoints(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11])
    val readBack = keypoints.homography() ?: return notFound("model residual (no homography)", mark, residual)
    val modelResidual = keypoints.maxResidualM(readBack)
    if (modelResidual == null || modelResidual > MAX_MODEL_RESIDUAL_M) {
        val shown = modelResidual?.let { "${fixed(it, 3)} m" } ?: "at infinity"
        return notFound("model residual $shown", mark, residual)
    }
    // Last, so no frame refused today changes its reason.
    if (!keypoints.resolvesAsTheTable()) return notFound("labels resolve differently", mark, residual)
    val matrix = listOf(
        listOf(sourceH[0], sourceH[1], sourceH[2]),
        listOf(sourceH[3], sourceH[4], sourceH[5]),
        listOf(sourceH[6], sourceH[7], sourceH[8]),
    )
    return CourtFinding(keypoints, matrix, "ok", residual, mark.elapsedNow().inWholeMilliseconds)
}

/**
 * Whether `courtPositions()` puts every point where the table does
 * (`COURT_KEYPOINT_POSITIONS`, design §4.7.5). The finder labels by the
 * table, but the analysis matches the service-line and centre pairs to the
 * court by which side of the marked net their pixels fall on, not by their
 * labels. Keypoints on which the two disagree would be fitted to another
 * court than the one found. The model residual check reads the positions
 * the same way, so it catches a disagreement only when the swap also breaks
 * the fit; this says it directly.
 */
internal fun CourtKeypoints.resolvesAsTheTable(): Boolean = courtPositions() == COURT_KEYPOINT_POSITIONS

/**
 * `court_probe.py`'s `line_support()`: how many across lines (the six of
 * [CourtModel.SEGMENTS] 0-5) and along lines (the rest, the centre-line
 * halves included) have at least 40% of their in-frame image length within
 * 1.5 px of a work-image line pixel. `h` maps court metres to work pixels.
 * Each segment is cut into 199 pieces by 200 evenly spaced points; a piece
 * counts as in frame when its first point is in `[0, w-1) x [0, h-1)`, and
 * as on a line when the distance transform read at that point's pixel is
 * within 1.5 px. A segment with less than 5% of the work long side in frame
 * is not counted either way.
 */
private fun lineSupport(ridge: RidgeMap, h: DoubleArray): Pair<Int, Int> {
    val w = ridge.width
    val ht = ridge.height
    val dist = ridge.distance
    val minVisible = SUPPORT_MIN_VISIBLE * max(w, ht)
    val px = DoubleArray(SUPPORT_POINTS)
    val py = DoubleArray(SUPPORT_POINTS)
    var across = 0
    var along = 0
    for ((k, s) in CourtModel.SEGMENTS.withIndex()) {
        for (i in 0 until SUPPORT_POINTS) {
            val t = i.toDouble() / (SUPPORT_POINTS - 1)
            val mx = s.ax + (s.bx - s.ax) * t
            val my = s.ay + (s.by - s.ay) * t
            val z = h[6] * mx + h[7] * my + h[8]
            px[i] = (h[0] * mx + h[1] * my + h[2]) / z
            py[i] = (h[3] * mx + h[4] * my + h[5]) / z
        }
        var visible = 0.0
        var on = 0.0
        for (i in 0 until SUPPORT_POINTS - 1) {
            val x = px[i]
            val y = py[i]
            if (!(x >= 0.0 && x < w - 1 && y >= 0.0 && y < ht - 1)) continue
            val piece = hypot(px[i + 1] - x, py[i + 1] - y)
            if (!piece.isFinite()) continue
            visible += piece
            if (dist[y.toInt() * w + x.toInt()] <= SUPPORT_ON_LINE_PX) on += piece
        }
        if (visible < minVisible) continue
        if (on / visible >= SUPPORT_FRACTION) {
            if (k < CourtModel.ACROSS.size) across++ else along++
        }
    }
    return across to along
}

private fun notFound(reason: String, mark: TimeSource.Monotonic.ValueTimeMark, residualPx: Double? = null) =
    CourtFinding(null, null, reason, residualPx, mark.elapsedNow().inWholeMilliseconds)

/** Runs `body` with a start mark; any throwable becomes a not-found finding. */
private inline fun timed(body: (TimeSource.Monotonic.ValueTimeMark) -> CourtFinding): CourtFinding {
    val mark = TimeSource.Monotonic.markNow()
    return try {
        body(mark)
    } catch (e: Throwable) {
        notFound("error: ${e.message ?: e::class.simpleName ?: "unknown"}", mark)
    }
}

/** `x` to `digits` decimals, for reasons (common Kotlin has no `String.format`). */
private fun fixed(x: Double, digits: Int): String {
    if (!x.isFinite()) return x.toString()
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val n = round(abs(x) * scale).toLong()
    val sign = if (x < 0 && n != 0L) "-" else ""
    val whole = n / scale
    val frac = (n % scale).toString().padStart(digits, '0')
    return if (digits == 0) "$sign$whole" else "$sign$whole.$frac"
}
