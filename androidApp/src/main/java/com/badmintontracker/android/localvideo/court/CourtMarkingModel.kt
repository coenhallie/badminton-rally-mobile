package com.badmintontracker.android.localvideo.court

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
