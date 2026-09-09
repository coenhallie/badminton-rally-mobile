package com.badmintontracker.shared.local

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.RallyBase
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class BasePositionFormatTest {

    @Test
    fun a_base_behind_the_service_line_and_left_of_centre() {
        // Court y 9.85 is 1.17 m behind the near service line at 8.68; x 2.90
        // is 0.15 m to the left of the centre line at 3.05.
        describeBase(Point(2.90, 9.85)) shouldBe "1.17 m behind the service line, 0.15 m left of centre"
    }

    @Test
    fun a_base_inside_the_service_box_says_in_front() {
        describeBase(Point(3.55, 8.18)) shouldBe "0.50 m in front of the service line, 0.50 m right of centre"
    }

    @Test
    fun on_the_lines_is_said_rather_than_printed_as_zero_metres() {
        describeBase(Point(3.05, 8.68)) shouldBe "on the service line, on the centre line"
        describeBase(Point(3.054, 8.676)) shouldBe "on the service line, on the centre line"
    }

    @Test
    fun a_label_far_from_the_ring_sits_beside_its_own_dot() {
        // 200pt away in y: the ring is nowhere near this row, so the number
        // clears the dot's 5pt radius and no more.
        rallyLabelX(
            dotX = 100f, dotY = 300f, dotRadius = 5f, gap = 2f,
            ringX = 100f, ringY = 100f, ringReach = 11.25f,
        ) shouldBe 107f
    }

    @Test
    fun a_label_level_with_the_ring_is_pushed_past_its_full_width() {
        // The dot sits ON the whole-match marker, which is the case that drew
        // the number inside the ring: pushed to the ring's right edge plus the
        // gap, never to the dot's own edge.
        rallyLabelX(
            dotX = 100f, dotY = 100f, dotRadius = 5f, gap = 2f,
            ringX = 100f, ringY = 100f, ringReach = 11.25f,
        ) shouldBe 113.25f
    }

    @Test
    fun a_label_near_the_rings_edge_is_pushed_by_the_chord_not_the_radius() {
        // 9pt above the centre of an 11.25pt ring: half the chord there is
        // sqrt(11.25^2 - 9^2) = 6.75.
        rallyLabelX(
            dotX = 100f, dotY = 91f, dotRadius = 5f, gap = 2f,
            ringX = 100f, ringY = 100f, ringReach = 11.25f,
        ) shouldBe (108.75f plusOrMinus 0.001f)
    }

    @Test
    fun a_label_left_of_the_ring_still_clears_it() {
        // A dot 8pt to the LEFT of the marker: beside-the-dot would be 99,
        // still inside the ring, so the ring's own right edge wins.
        rallyLabelX(
            dotX = 92f, dotY = 100f, dotRadius = 5f, gap = 2f,
            ringX = 100f, ringY = 100f, ringReach = 11.25f,
        ) shouldBe 113.25f
    }

    @Test
    fun with_no_whole_match_marker_there_is_nothing_to_avoid() {
        rallyLabelX(
            dotX = 100f, dotY = 100f, dotRadius = 5f, gap = 2f,
            ringX = 0f, ringY = 0f, ringReach = 0f,
        ) shouldBe 107f
    }

    @Test
    fun a_rally_row_carries_the_rally_number_and_the_coverage() {
        describeRally(RallyBase(3, Point(2.90, 9.85), samples = 300, coverage = 0.987)) shouldBe
            "Rally 3 · 1.17 m behind the service line, 0.15 m left of centre · found in 98% of frames"
    }
}
