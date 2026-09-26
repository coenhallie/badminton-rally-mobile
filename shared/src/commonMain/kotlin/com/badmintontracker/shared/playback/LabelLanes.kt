package com.badmintontracker.shared.playback

/** Where one chip goes in a [LaneFit]: which lane, and how far along it. */
data class LanePlacement(
    /** 0 is the top lane. */
    val lane: Int,
    /** The chip's leading edge, from the strip's leading edge. */
    val x: Float,
)

data class LaneFit(
    /** 0, 1 or 2: how many lanes the strip needs. */
    val lanes: Int,
    /** The wider lane, which is what the strip scrolls through when it is wider than the row. */
    val width: Float,
    /** One per chip, in the order the widths were given. */
    val placements: List<LanePlacement>,
)

/**
 * Lays the replay page's label chips into a strip of at most two lanes.
 *
 * The chips used to wrap onto as many lines as they needed, and the 2026-08-30
 * label scope design records why they did not scroll sideways instead: on one
 * line, past four or five labels the one you want is off the edge. But wrapping
 * without limit makes an account with a dozen clip labels pay for them in video
 * and notes on every rally. Two lanes are the compromise: twice as many chips in
 * view before anything scrolls, and a fixed height the page can plan around.
 *
 * Chips are dealt alternately - first to the top lane, second to the bottom, third
 * to the top - so the label order still reads left to right, and each lane keeps
 * its own cursor so a wide chip in one lane leaves no gap in the other. Chips that
 * fit on one line stay on one line: the three default labels should look on a phone
 * as they did before there were lanes.
 *
 * Shared, and pure, for the reason TransportRowLayout is: both platforms draw the
 * same strip from the same numbers. Units are whatever the caller measures in, as
 * long as every argument uses the same one.
 */
object LabelLanes {
    const val MAX_LANES = 2

    /**
     * @param widths each chip's width, in the order the labels are drawn.
     * @param spacing between neighbouring chips in a lane.
     * @param availableWidth the width the strip has before it must scroll.
     */
    fun fit(widths: List<Float>, spacing: Float, availableWidth: Float): LaneFit {
        if (widths.isEmpty()) return LaneFit(lanes = 0, width = 0f, placements = emptyList())
        val oneLine = widths.sum() + spacing * (widths.size - 1)
        val lanes = if (oneLine <= availableWidth || widths.size == 1) 1 else MAX_LANES
        val cursors = FloatArray(lanes)
        val placements = widths.mapIndexed { index, width ->
            val lane = index % lanes
            val x = cursors[lane]
            cursors[lane] = x + width + spacing
            LanePlacement(lane, x)
        }
        // A cursor sits one spacing past the last chip; the lane ends at the chip.
        val width = cursors.max() - spacing
        return LaneFit(lanes, width, placements)
    }
}
