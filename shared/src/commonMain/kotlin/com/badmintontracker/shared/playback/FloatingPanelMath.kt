package com.badmintontracker.shared.playback

/** A panel's top-left corner inside the area it floats over, in that area's own units. */
data class PanelPosition(val x: Float, val y: Float)

/**
 * Where a floating panel was left, as a fraction of how far it can travel on each
 * axis: 0 is hard against the leading or top edge, 1 against the trailing or bottom.
 *
 * A fraction rather than a position because it is remembered between sessions, and
 * the area is not the same size twice: a position saved on one phone, or before the
 * panel's chips wrapped onto another line, would otherwise come back off screen.
 */
data class PanelAnchor(val x: Float, val y: Float) {
    companion object {
        /**
         * The top trailing corner of the area. The area a player floats the window
         * in already starts under the video card's chips and stops above the
         * transport, so its corner is clear of both on any screen - which a
         * fraction down from the top was not, on a landscape phone short enough
         * that the window has almost no travel.
         */
        val DEFAULT = PanelAnchor(x = 1f, y = 0f)
    }
}

/**
 * The arithmetic of a draggable panel that must stay on screen. Pure, so that
 * "cannot be dragged off the edge" is a tested fact on both platforms rather than
 * something each gesture handler gets right on its own - the same reason
 * DrawerDragMath and SkipMath exist.
 */
object FloatingPanelMath {

    /** [position] moved so that the whole panel is inside the area. A panel larger than the area pins to its origin. */
    fun clamp(position: PanelPosition, panelWidth: Float, panelHeight: Float, areaWidth: Float, areaHeight: Float): PanelPosition =
        PanelPosition(
            x = position.x.coerceIn(0f, travel(areaWidth, panelWidth)),
            y = position.y.coerceIn(0f, travel(areaHeight, panelHeight)),
        )

    /** Where a panel anchored at [anchor] sits. */
    fun position(anchor: PanelAnchor, panelWidth: Float, panelHeight: Float, areaWidth: Float, areaHeight: Float): PanelPosition =
        PanelPosition(
            x = anchor.x.coerceIn(0f, 1f) * travel(areaWidth, panelWidth),
            y = anchor.y.coerceIn(0f, 1f) * travel(areaHeight, panelHeight),
        )

    /** The anchor that [position] amounts to. An axis with no travel reports 0 rather than dividing by it. */
    fun anchor(position: PanelPosition, panelWidth: Float, panelHeight: Float, areaWidth: Float, areaHeight: Float): PanelAnchor {
        val clamped = clamp(position, panelWidth, panelHeight, areaWidth, areaHeight)
        val travelX = travel(areaWidth, panelWidth)
        val travelY = travel(areaHeight, panelHeight)
        return PanelAnchor(
            x = if (travelX > 0f) clamped.x / travelX else 0f,
            y = if (travelY > 0f) clamped.y / travelY else 0f,
        )
    }

    private fun travel(area: Float, panel: Float): Float = (area - panel).coerceAtLeast(0f)
}
