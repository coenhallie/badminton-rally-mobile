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
    }

    @Test
    fun court_tiles_need_the_court_and_the_other_arm_hides_once_an_arm_is_chosen() {
        visibleKinds(hasCourt = true, racketArm = null) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE,
            MetricKind.ELBOW_LEFT, MetricKind.ELBOW_RIGHT, MetricKind.ARM_LEFT, MetricKind.ARM_RIGHT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
        visibleKinds(hasCourt = false, racketArm = RacketArm.RIGHT) shouldBe listOf(
            MetricKind.ELBOW_RIGHT, MetricKind.ARM_RIGHT, MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
        visibleKinds(hasCourt = true, racketArm = RacketArm.LEFT) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE, MetricKind.ELBOW_LEFT, MetricKind.ARM_LEFT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
    }
}
