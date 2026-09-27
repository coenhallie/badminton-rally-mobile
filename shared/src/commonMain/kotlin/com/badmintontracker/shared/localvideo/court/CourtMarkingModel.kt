package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.analysis.court.COURT_LINE_SEGMENTS
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.homography
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis

data class CourtPoint(val x: Float, val y: Float)

/**
 * The marking as it stood before one edit: its points and the time of the
 * frame they were drawn on, which is what [CourtMarkingState.undo] puts back.
 *
 * The time travels with the points because an edit can replace marks drawn on
 * another frame: Clear or Find court over restored marks. Undoing either
 * brings those marks back, and they must come back with their own time,
 * including none for marks saved without one, not with the frame on screen.
 */
data class CourtMarkingStep(
    val points: List<CourtPoint>,
    val markedAtSeconds: Double?,
)

/** Constants copied verbatim from desktop CourtSetup.vue (order, labels, colors). */
object CourtMarkingSpec {
    const val TOTAL = 12
    val shortLabels = listOf(
        "TL", "TR", "BR", "BL", "NL", "NR", "SNL", "SNR", "SFL", "SFR", "CTN", "CTF",
    )
    val fullLabels = listOf(
        "Top-Left Corner", "Top-Right Corner", "Bottom-Right Corner", "Bottom-Left Corner",
        "Net-Left", "Net-Right",
        "Service Near-Left", "Service Near-Right",
        "Service Far-Left", "Service Far-Right",
        "Center-Near", "Center-Far",
    )
    val colors = listOf(
        0xFFFF4444, 0xFF44FF44, 0xFF4444FF, 0xFFFFFF44,   // corners: red, green, blue, yellow
        0xFFFF00FF, 0xFF00FFFF,                            // net: magenta, cyan
        0xFFFF8800, 0xFF88FF00,                            // service near: orange, lime
        0xFF0088FF, 0xFFFF0088,                            // service far: azure, rose
        0xFFFFFFFF, 0xFF888888,                            // center line: white, gray
    )

    /**
     * Said for as long as the screen still shows the marks a previous run used,
     * which it opens on whenever the entry has any - see [CourtMarkingState.restored].
     *
     * Twelve dots that the coach did not just place otherwise read as work he
     * did, and the guide beside them still asks him to "tap each court landmark
     * in the order shown" while a complete marking ignores every tap. It matters
     * most where the saved marks are wrong: a court marked on an iPhone before
     * the 2026-09-08 tap-coordinate fix is off by the letterbox, and Clear is
     * the only way out of it.
     */
    const val SAVED_MARKS_NOTE =
        "These marks come from this video's last analysis. Clear to mark the court again."

    /** The instruction line while the court finder looks at the frame. */
    const val FINDING = "Finding the court..."

    /** The note after the court finder placed all twelve points. */
    const val FOUND_NOTE =
        "Found the court automatically. Check the lines sit on the court's lines; tap a point to move it."

    /** The note when the court finder found nothing it would stand behind. */
    const val NOT_FOUND_NOTE =
        "Couldn't find the court. Tap the four corners and the rest will be placed for you."

    /** The instruction line while four tapped corners are being completed. */
    const val ALIGNING = "Lining the points up with the court..."

    /** The note after four tapped corners were completed to twelve points. */
    const val ALIGNED_NOTE = "Lined up with the court's lines. Check them; tap a point to move it."

    /** The note when four tapped corners could not be completed. */
    const val ALIGN_FAILED_NOTE = "Couldn't line the points up. Tap the rest."

    /** The button that runs the court finder on demand. */
    const val FIND_COURT = "Find court"

    /** The instruction line while the point at [index] is selected to be moved. */
    fun moveInstruction(index: Int): String = "Tap the new spot for ${fullLabels[index]}"
}

data class CourtMarkingState(
    val videoWidth: Int,
    val videoHeight: Int,
    val points: List<CourtPoint> = emptyList(),
    /**
     * When in the video the frame [points] were drawn on was taken, seconds,
     * or null for restored marks saved without a time. Written into the marks
     * as `marked_at_s`, the frame the camera tracker anchors to.
     */
    val markedAtSeconds: Double? = null,
    /**
     * When in the video the frame on screen was taken, seconds: what a mark
     * placed now is drawn on.
     *
     * Differs from [markedAtSeconds] only while restored marks are shown,
     * because saved marks belong to the frame they were drawn on, not to the
     * one this screen happened to load. Once none of them is left (Clear, or
     * Undo down to nothing) the marking is on this frame again. Undoing some
     * and placing others mixes the two frames; that is not modelled, because
     * both screens load the same frame every time for the same file, so the
     * two times only differ for marks saved without one.
     */
    val frameSeconds: Double? = markedAtSeconds,
    /**
     * The marking before each edit made on this screen, oldest first: a
     * placed point, a moved point, a Clear, an applied detection. [undo] pops
     * it. Empty for marks just restored, which [undo] then takes apart point
     * by point as it always has. Selecting a point is not an edit.
     */
    val history: List<CourtMarkingStep> = emptyList(),
    /**
     * The index of the point a tap on a complete marking picked up to move,
     * or null. Every edit clears it, so it never outlives the point it names.
     */
    val selected: Int? = null,
) {
    val isComplete: Boolean get() = points.size == CourtMarkingSpec.TOTAL
    val nextIndex: Int get() = points.size

    /** Same mapping as desktop handleCanvasClick: display coords -> source pixels. */
    fun place(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float): CourtMarkingState {
        if (isComplete) return this
        return edited(points + toSource(displayX, displayY, displayWidth, displayHeight), markedAtSeconds)
    }

    /**
     * A tap on the frame, in display coordinates, with [hitRadiusDisplay] the
     * reach of a point in the same units.
     *
     * An incomplete marking places the next point ([place]). A complete one
     * edits by selecting and moving: with nothing selected, the tap selects
     * the nearest point within the radius, or does nothing; with a point
     * selected, a tap within the radius of that point puts it down where it
     * was, and a tap anywhere else, even on another point, moves it there.
     * Distances are measured in source pixels, the radius scaled by the same
     * x scale [place] uses, so a point's reach on screen is the same at any
     * size the frame is shown.
     */
    fun tap(
        displayX: Float,
        displayY: Float,
        displayWidth: Float,
        displayHeight: Float,
        hitRadiusDisplay: Float,
    ): CourtMarkingState {
        if (!isComplete) return place(displayX, displayY, displayWidth, displayHeight)
        val at = toSource(displayX, displayY, displayWidth, displayHeight)
        val radius = hitRadiusDisplay * (videoWidth / displayWidth)
        val picked = selected?.takeIf { it in points.indices }
        if (picked == null) {
            val nearest = points.indices
                .map { it to distanceSquared(points[it], at) }
                .filter { (_, d) -> d <= radius * radius }
                .minByOrNull { (_, d) -> d }
            return if (nearest == null) this else copy(selected = nearest.first)
        }
        if (distanceSquared(points[picked], at) <= radius * radius) return copy(selected = null)
        return edited(points.toMutableList().also { it[picked] = at }, markedAtSeconds)
    }

    /**
     * Reverses the last edit ([history]); with none, drops the last point, the
     * only undo restored marks have. Back to no points, the marking is on the
     * frame on screen again (see [frameSeconds]).
     */
    fun undo(): CourtMarkingState {
        val last = history.lastOrNull() ?: return if (points.isEmpty()) this else withPoints(points.dropLast(1))
        return copy(
            points = last.points,
            markedAtSeconds = if (last.points.isEmpty()) frameSeconds else last.markedAtSeconds,
            history = history.dropLast(1),
            selected = null,
        )
    }

    /** Removes every point, as one step [undo] reverses. Nothing to clear is not a step. */
    fun clear(): CourtMarkingState =
        if (points.isEmpty()) withPoints(emptyList()) else edited(emptyList(), frameSeconds)

    /**
     * Replaces the marking with the twelve [points] a court finder placed, in
     * table order, as one step [undo] reverses. The finder looked at the frame
     * on screen, so the marks are on [frameSeconds] whatever they replace.
     */
    fun withDetected(points: List<CourtPoint>): CourtMarkingState {
        require(points.size == CourtMarkingSpec.TOTAL) {
            "A detected court has ${CourtMarkingSpec.TOTAL} points, got ${points.size}"
        }
        return edited(points, frameSeconds)
    }

    /**
     * Whether the step from [previous] to this one placed the fourth corner:
     * the moment to try completing the court from the four taps. A tap to
     * four, not any arrival at four: an Undo back to four is how the coach
     * declines the completion, so it must not ask again.
     */
    fun justReachedFourCorners(previous: CourtMarkingState): Boolean =
        previous.points.size == 3 && points.size == 4

    /**
     * The court's line segments in source pixels, drawn through this marking:
     * [COURT_LINE_SEGMENTS] taken from court metres to pixels by the inverse
     * of the homography the analysis will fit to these marks. A mark that
     * misses its paint shows as a line off the court's lines. Empty unless the
     * marking is complete and the marks have a homography with every
     * endpoint at a finite pixel.
     */
    fun courtLines(): List<Pair<CourtPoint, CourtPoint>> {
        if (!isComplete) return emptyList()
        val pixelToCourt = toCourtKeypoints().toAnalysis().homography() ?: return emptyList()
        val courtToPixel = invert3x3(pixelToCourt) ?: return emptyList()
        return COURT_LINE_SEGMENTS.map { (a, b) ->
            val pa = courtToPixel.apply(a.x, a.y) ?: return emptyList()
            val pb = courtToPixel.apply(b.x, b.y) ?: return emptyList()
            CourtPoint(pa.x.toFloat(), pa.y.toFloat()) to CourtPoint(pb.x.toFloat(), pb.y.toFloat())
        }
    }

    private fun toSource(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float): CourtPoint {
        val scaleX = videoWidth / displayWidth
        val scaleY = videoHeight / displayHeight
        return CourtPoint(displayX * scaleX, displayY * scaleY)
    }

    /** An edit: the new [points] and their time, with the current marking pushed onto [history]. */
    private fun edited(points: List<CourtPoint>, markedAtSeconds: Double?): CourtMarkingState = copy(
        points = points,
        markedAtSeconds = markedAtSeconds,
        history = history + CourtMarkingStep(this.points, this.markedAtSeconds),
        selected = null,
    )

    /** [kept] points without an undo step (an undo with no history), back on the frame on screen when none are left. */
    private fun withPoints(kept: List<CourtPoint>): CourtMarkingState = copy(
        points = kept,
        markedAtSeconds = if (kept.isEmpty()) frameSeconds else markedAtSeconds,
        selected = null,
    )

    companion object {
        /**
         * The state that would have produced [keypoints], so a second run over
         * a video starts from the marks the first one used.
         *
         * The app persists an entry's keypoints before either pipeline starts
         * (AnalyzeCoordinator.startAnalysis for a cloud run, the screen itself
         * for a device one), but nothing read them back: every re-run - a cloud
         * run after a device one, a device run at a different metric set, a
         * retry that fell through to court marking - meant tapping all twelve
         * points again, on a frame whose landmarks are only as repeatable as
         * the hand placing them.
         *
         * A malformed pair yields a fresh state rather than a partial marking:
         * the points are positional, so eleven of twelve would silently label
         * every mark after the missing one as its neighbour.
         *
         * @param frameSeconds when in the video the frame on screen was taken.
         *   Restored marks keep their own time instead, including none: marks
         *   saved without one are left for the platform to place (see
         *   `LocalAnalysisCoordinator`), not stamped with a frame they may not
         *   have been drawn on.
         */
        fun restored(
            videoWidth: Int,
            videoHeight: Int,
            keypoints: CourtKeypoints?,
            frameSeconds: Double?,
        ): CourtMarkingState {
            val empty = CourtMarkingState(
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                markedAtSeconds = frameSeconds,
                frameSeconds = frameSeconds,
            )
            val k = keypoints ?: return empty
            val ordered = listOf(
                k.topLeft, k.topRight, k.bottomRight, k.bottomLeft,
                k.netLeft, k.netRight,
                k.serviceLineNearLeft, k.serviceLineNearRight,
                k.serviceLineFarLeft, k.serviceLineFarRight,
                k.centerNear, k.centerFar,
            )
            if (ordered.any { it.size < 2 }) return empty
            return empty.copy(
                points = ordered.map { CourtPoint(it[0], it[1]) },
                markedAtSeconds = k.markedAtSeconds,
            )
        }
    }

    /** Identical field mapping to desktop saveAndProceed(). */
    fun toCourtKeypoints(): CourtKeypoints {
        check(isComplete) { "Court marking incomplete: ${points.size}/${CourtMarkingSpec.TOTAL}" }
        fun p(i: Int) = listOf(points[i].x, points[i].y)
        return CourtKeypoints(
            topLeft = p(0), topRight = p(1), bottomRight = p(2), bottomLeft = p(3),
            netLeft = p(4), netRight = p(5),
            serviceLineNearLeft = p(6), serviceLineNearRight = p(7),
            serviceLineFarLeft = p(8), serviceLineFarRight = p(9),
            centerNear = p(10), centerFar = p(11),
            markedAtSeconds = markedAtSeconds,
        )
    }
}

private fun distanceSquared(a: CourtPoint, b: CourtPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}
