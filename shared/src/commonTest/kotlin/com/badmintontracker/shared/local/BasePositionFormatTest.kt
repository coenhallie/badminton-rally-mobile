package com.badmintontracker.shared.local

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.RallyBase
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
    fun the_far_player_is_measured_from_his_own_service_line() {
        // aSASa's far base (the emulator, 2026-09-29): court y 1.20, in the
        // far half whose service line is at 6.70 - 1.98 = 4.72. He is 3.52 m
        // behind it, toward the far baseline, not "in front of" the near one.
        describeBase(Point(3.94, 1.20), CourtSide.FAR) shouldBe
            "3.52 m behind the service line, 0.89 m left of centre"
    }

    @Test
    fun the_far_player_s_left_and_right_are_his_own_facing_the_net() {
        // He faces the camera, so his right is the court's lower x.
        describeBase(Point(2.55, 5.22), CourtSide.FAR) shouldBe
            "0.50 m in front of the service line, 0.50 m right of centre"
    }

    @Test
    fun the_near_player_is_what_the_one_argument_form_always_said() {
        describeBase(Point(2.90, 9.85), CourtSide.NEAR) shouldBe describeBase(Point(2.90, 9.85))
    }

    @Test
    fun a_far_rally_row_uses_the_far_player_s_own_lines() {
        describeRally(RallyBase(2, Point(3.94, 1.20), samples = 120, coverage = 0.8), CourtSide.FAR) shouldBe
            "Rally 2 · 3.52 m behind the service line, 0.89 m left of centre · found in 80% of frames"
    }

    @Test
    fun a_rally_row_carries_the_rally_number_and_the_coverage() {
        describeRally(RallyBase(3, Point(2.90, 9.85), samples = 300, coverage = 0.987)) shouldBe
            "Rally 3 · 1.17 m behind the service line, 0.15 m left of centre · found in 98% of frames"
    }
}
