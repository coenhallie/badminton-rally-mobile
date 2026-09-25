package com.badmintontracker.shared.playback

/**
 * How the transport row is spaced at a given width. One unit is a dp on Android
 * and a point on iOS.
 */
data class TransportRowMetrics(
    /** Space kept clear on each side of the controls. The speed readout sits in the trailing one. */
    val gutter: Float,
    /** Between neighbouring controls. */
    val gap: Float,
    /** Inside a skip pill, on each side of its content. */
    val pillPadding: Float,
    /** False when the chevrons had to go so that the skip interval could stay readable. */
    val showPillIcons: Boolean,
)

/**
 * Fits the transport row - skip pill, frame circle, play circle, frame circle,
 * skip pill - and the speed readout beside it into the width a screen gives it.
 *
 * The readout is drawn in the row's trailing gutter rather than as a sixth control,
 * and the leading gutter is made to match, because that is the only arrangement
 * that keeps the play button in the middle of the screen. The cost is width, and a
 * 360 dp phone does not have it to spare: five controls at the mock's spacing
 * already fill all but 12 dp a side. So the row gives way in steps, and always in
 * this order - gaps and pill padding first, then the pills' chevrons - because a
 * clipped "10s" is a defect and a missing chevron is not.
 *
 * Shared, and pure, for the reason SkipMath is: both platforms draw the same row
 * from the same numbers, and a layout that depends on the screen cannot be checked
 * by looking at one phone.
 */
object TransportRowLayout {
    const val CIRCLE = 48f
    const val PLAY_CIRCLE = 60f
    const val PILL_ICON = 14f
    const val PILL_ICON_GAP = 6f

    /** The mock's gutter, and the least the row is ever given. */
    const val MIN_GUTTER = 24f

    /** Clear space on each side of the readout inside its gutter. */
    const val READOUT_INSET = 8f

    private class Step(val gap: Float, val pillPadding: Float, val showPillIcons: Boolean)

    /** Loosest first. The first is the row exactly as the mock draws it. */
    private val steps = listOf(
        Step(gap = 8f, pillPadding = 16f, showPillIcons = true),
        Step(gap = 6f, pillPadding = 12f, showPillIcons = true),
        Step(gap = 6f, pillPadding = 12f, showPillIcons = false),
        Step(gap = 4f, pillPadding = 8f, showPillIcons = false),
    )

    /**
     * @param availableWidth the full width the row is laid out in.
     * @param readoutWidth the widest the speed readout can be ("0.25×"), not
     *   its current width: the row must not re-space itself when the speed changes.
     * @param pillLabelWidth the width of the skip interval's text ("10s") as
     *   measured, so a large font scale is accounted for rather than guessed.
     * @param signedPillLabelWidth the width of the wider of "-10s" and "+10s": what a
     *   pill says once its chevron is gone, because without either the two pills
     *   would be told apart only by which side of the screen they are on.
     */
    fun fit(
        availableWidth: Float,
        readoutWidth: Float,
        pillLabelWidth: Float,
        signedPillLabelWidth: Float,
    ): TransportRowMetrics {
        val gutter = maxOf(MIN_GUTTER, readoutWidth + 2 * READOUT_INSET)
        val room = availableWidth - 2 * gutter
        val step = steps.firstOrNull { controlsWidth(it, pillLabelWidth, signedPillLabelWidth) <= room } ?: steps.last()
        return TransportRowMetrics(gutter, step.gap, step.pillPadding, step.showPillIcons)
    }

    /** The width the five controls take at [metrics], for a caller that wants to check a fit. */
    fun controlsWidth(metrics: TransportRowMetrics, pillLabelWidth: Float, signedPillLabelWidth: Float): Float =
        controlsWidth(Step(metrics.gap, metrics.pillPadding, metrics.showPillIcons), pillLabelWidth, signedPillLabelWidth)

    /** What a skip pill says: the bare interval beside a chevron, the signed one without. */
    fun pillLabel(skipSeconds: Int, forward: Boolean, showPillIcons: Boolean): String = when {
        showPillIcons -> "${skipSeconds}s"
        forward -> "+${skipSeconds}s"
        else -> "-${skipSeconds}s"
    }

    private fun controlsWidth(step: Step, pillLabelWidth: Float, signedPillLabelWidth: Float): Float {
        val pillContent = if (step.showPillIcons) pillLabelWidth + PILL_ICON + PILL_ICON_GAP else signedPillLabelWidth
        val pill = pillContent + 2 * step.pillPadding
        return 2 * pill + 2 * CIRCLE + PLAY_CIRCLE + 4 * step.gap
    }
}
