package com.badmintontracker.shared.playback

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FloatingPanelMathTest {

    // A landscape phone, and a panel a quarter of its width.
    private val areaW = 900f
    private val areaH = 400f
    private val panelW = 240f
    private val panelH = 150f

    private fun clamp(x: Float, y: Float) = FloatingPanelMath.clamp(PanelPosition(x, y), panelW, panelH, areaW, areaH)

    @Test
    fun a_position_inside_the_area_is_left_alone() {
        clamp(100f, 50f) shouldBe PanelPosition(100f, 50f)
    }

    @Test
    fun a_panel_cannot_be_dragged_off_any_edge() {
        clamp(-40f, -40f) shouldBe PanelPosition(0f, 0f)
        clamp(5000f, 5000f) shouldBe PanelPosition(areaW - panelW, areaH - panelH)
        clamp(-1f, 5000f) shouldBe PanelPosition(0f, areaH - panelH)
    }

    @Test
    fun a_panel_bigger_than_the_area_pins_to_the_origin() {
        FloatingPanelMath.clamp(PanelPosition(30f, 30f), 1200f, 600f, areaW, areaH) shouldBe PanelPosition(0f, 0f)
    }

    @Test
    fun the_default_anchor_is_the_top_trailing_corner_of_the_area() {
        val position = FloatingPanelMath.position(PanelAnchor.DEFAULT, panelW, panelH, areaW, areaH)
        position shouldBe PanelPosition(areaW - panelW, 0f)
    }

    @Test
    fun the_default_anchor_stays_in_the_corner_however_little_travel_there_is() {
        // A landscape phone: the area between the chips and the transport is barely
        // taller than the window.
        FloatingPanelMath.position(PanelAnchor.DEFAULT, panelW, 190f, 800f, 200f) shouldBe PanelPosition(800f - panelW, 0f)
    }

    @Test
    fun an_anchor_round_trips_through_a_position() {
        val anchor = PanelAnchor(0.25f, 0.75f)
        val position = FloatingPanelMath.position(anchor, panelW, panelH, areaW, areaH)
        FloatingPanelMath.anchor(position, panelW, panelH, areaW, areaH) shouldBe anchor
    }

    @Test
    fun an_anchor_keeps_the_panel_on_screen_when_the_area_changes_size() {
        // Saved hard against the trailing edge on a wide screen, restored on a
        // narrower one: still hard against the edge, not off it.
        val anchor = FloatingPanelMath.anchor(PanelPosition(areaW - panelW, 0f), panelW, panelH, areaW, areaH)
        val restored = FloatingPanelMath.position(anchor, panelW, panelH, 600f, 300f)
        restored shouldBe PanelPosition(600f - panelW, 0f)
    }

    @Test
    fun an_anchor_keeps_the_panel_on_screen_when_the_panel_grows() {
        // A label was added and the chips wrapped onto another line.
        val restored = FloatingPanelMath.position(PanelAnchor(1f, 1f), panelW, 260f, areaW, areaH)
        restored shouldBe PanelPosition(areaW - panelW, areaH - 260f)
    }

    @Test
    fun a_stored_anchor_outside_zero_to_one_is_pulled_back_in() {
        FloatingPanelMath.position(PanelAnchor(4f, -2f), panelW, panelH, areaW, areaH) shouldBe
            PanelPosition(areaW - panelW, 0f)
    }

    @Test
    fun an_axis_with_no_travel_reports_zero_rather_than_dividing_by_it() {
        FloatingPanelMath.anchor(PanelPosition(10f, 10f), areaW, areaH, areaW, areaH) shouldBe PanelAnchor(0f, 0f)
    }
}
