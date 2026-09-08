package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.shouldBe
import org.junit.Test

class MetricsFormatTest {

    @Test
    fun metres_show_two_decimals_and_angles_whole_degrees() {
        formatMetric(MetricKind.STANCE, 0.236) shouldBe "0.24 m"
        formatMetric(MetricKind.BEHIND_LINE, -0.5) shouldBe "-0.50 m"
        formatMetric(MetricKind.ELBOW_RIGHT, 173.6) shouldBe "174°"
        formatMetric(MetricKind.LEAN, -7.6) shouldBe "-8°"
        formatMetric(MetricKind.LEAN, 0.2) shouldBe "0°"
    }

    @Test
    fun the_tile_sets_metres_apart_from_their_unit_and_keeps_the_degree_sign_with_the_number() {
        metricText(MetricKind.STANCE, 0.236) shouldBe MetricText("0.24", " m")
        metricText(MetricKind.BEHIND_LINE, -0.5) shouldBe MetricText("-0.50", " m")
        // A degree sign set in the unit's smaller face beside a 26 sp numeral
        // shrinks to a speck that reads as a stray dot, so it stays with the number.
        metricText(MetricKind.ELBOW_RIGHT, 173.6) shouldBe MetricText("174°", "")
        metricText(MetricKind.KNEE_LEFT, null) shouldBe MetricText("–", "")
    }

    @Test
    fun the_graph_range_is_said_once_with_the_unit_on_the_upper_bound() {
        metricRangeLabel(MetricKind.STANCE) shouldBe "0.00 – 2.00 m"
        metricRangeLabel(MetricKind.ELBOW_LEFT) shouldBe "0° – 180°"
    }

    @Test
    fun an_absent_value_is_a_dash_not_a_stale_number() {
        formatMetric(MetricKind.STANCE, null) shouldBe "–"
        formatMetric(MetricKind.KNEE_LEFT, null) shouldBe "–"
    }

    @Test
    fun labels_name_the_side_until_the_racket_arm_is_chosen() {
        metricLabel(MetricKind.ELBOW_LEFT, null) shouldBe "Elbow L"
        metricLabel(MetricKind.ARM_RIGHT, null) shouldBe "Arm R"
        metricLabel(MetricKind.ELBOW_RIGHT, RacketArm.RIGHT) shouldBe "Elbow"
        metricLabel(MetricKind.ARM_LEFT, RacketArm.LEFT) shouldBe "Arm"
        // Knees keep their side: both legs matter in a lunge whichever arm serves.
        metricLabel(MetricKind.KNEE_LEFT, RacketArm.RIGHT) shouldBe "Knee L"
        metricLabel(MetricKind.STANCE, null) shouldBe "Stance"
        metricLabel(MetricKind.BEHIND_LINE, null) shouldBe "Behind line"
        metricLabel(MetricKind.LEAN, null) shouldBe "Lean"
        metricLabel(MetricKind.SHOULDERS, RacketArm.RIGHT) shouldBe "Shoulders"
        metricLabel(MetricKind.HIPS, null) shouldBe "Hips"
    }

    @Test
    fun tilts_are_angles_that_need_no_court() {
        formatMetric(MetricKind.SHOULDERS, -7.6) shouldBe "-8°"
        MetricKind.SHOULDERS.needsCourt shouldBe false
        MetricKind.HIPS.isAngle shouldBe true
    }

    @Test
    fun court_tiles_need_the_court_and_the_other_arm_hides_once_an_arm_is_chosen() {
        visibleKinds(hasCourt = true, racketArm = null) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE,
            MetricKind.ELBOW_LEFT, MetricKind.ELBOW_RIGHT, MetricKind.ARM_LEFT, MetricKind.ARM_RIGHT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN, MetricKind.SHOULDERS, MetricKind.HIPS,
        )
        visibleKinds(hasCourt = false, racketArm = RacketArm.RIGHT) shouldBe listOf(
            MetricKind.ELBOW_RIGHT, MetricKind.ARM_RIGHT, MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT,
            MetricKind.LEAN, MetricKind.SHOULDERS, MetricKind.HIPS,
        )
        visibleKinds(hasCourt = true, racketArm = RacketArm.LEFT) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE, MetricKind.ELBOW_LEFT, MetricKind.ARM_LEFT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN, MetricKind.SHOULDERS, MetricKind.HIPS,
        )
    }
}
