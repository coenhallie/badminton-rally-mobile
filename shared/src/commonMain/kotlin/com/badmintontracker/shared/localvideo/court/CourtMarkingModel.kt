package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.shared.model.CourtKeypoints

data class CourtPoint(val x: Float, val y: Float)

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
}

data class CourtMarkingState(
    val videoWidth: Int,
    val videoHeight: Int,
    val points: List<CourtPoint> = emptyList(),
) {
    val isComplete: Boolean get() = points.size == CourtMarkingSpec.TOTAL
    val nextIndex: Int get() = points.size

    /** Same mapping as desktop handleCanvasClick: display coords -> source pixels. */
    fun place(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float): CourtMarkingState {
        if (isComplete) return this
        val scaleX = videoWidth / displayWidth
        val scaleY = videoHeight / displayHeight
        return copy(points = points + CourtPoint(displayX * scaleX, displayY * scaleY))
    }

    fun undo(): CourtMarkingState = if (points.isEmpty()) this else copy(points = points.dropLast(1))

    fun clear(): CourtMarkingState = copy(points = emptyList())

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
         */
        fun restored(
            videoWidth: Int,
            videoHeight: Int,
            keypoints: CourtKeypoints?,
        ): CourtMarkingState {
            val empty = CourtMarkingState(videoWidth, videoHeight)
            val k = keypoints ?: return empty
            val ordered = listOf(
                k.topLeft, k.topRight, k.bottomRight, k.bottomLeft,
                k.netLeft, k.netRight,
                k.serviceLineNearLeft, k.serviceLineNearRight,
                k.serviceLineFarLeft, k.serviceLineFarRight,
                k.centerNear, k.centerFar,
            )
            if (ordered.any { it.size < 2 }) return empty
            return empty.copy(points = ordered.map { CourtPoint(it[0], it[1]) })
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
        )
    }
}
