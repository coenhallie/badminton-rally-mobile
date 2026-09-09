package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MetricGraphTest {

    /** 30 fps: frames a thirtieth of a second apart, a gap once they are more than two and a half frames apart. */
    private val gapS = 2.5 / 30.0

    private fun sample(t: Double, elbow: Double?) =
        MetricSample(t, PoseMetrics.NONE.copy(elbowRightDeg = elbow))

    private fun segments(series: List<MetricSample>, startS: Double, endS: Double) =
        graphSegments(series, MetricKind.ELBOW_RIGHT, startS, endS, gapS)

    @Test
    fun a_run_that_starts_before_the_window_is_drawn_across_the_left_edge() {
        val series = listOf(sample(0.90, 100.0), sample(0.95, 110.0), sample(1.00, 120.0))

        val result = segments(series, startS = 0.92, endS = 1.50)

        // The sample before the window is kept so the line crosses the edge
        // instead of starting inside the box; the caller clips it.
        result.polylines shouldBe listOf(
            listOf(GraphPoint(0.90, 100.0), GraphPoint(0.95, 110.0), GraphPoint(1.00, 120.0)),
        )
        result.points shouldBe emptyList()
    }

    @Test
    fun a_run_that_ends_after_the_window_is_drawn_across_the_right_edge() {
        val series = listOf(sample(1.40, 100.0), sample(1.45, 110.0), sample(1.50, 120.0))

        val result = segments(series, startS = 1.00, endS = 1.42)

        result.polylines shouldBe listOf(listOf(GraphPoint(1.40, 100.0), GraphPoint(1.45, 110.0)))
        result.points shouldBe emptyList()
    }

    @Test
    fun an_absent_value_splits_the_curve_and_leaves_its_isolated_neighbours_as_points() {
        val series = listOf(
            sample(1.00, 100.0),
            sample(1.05, null),
            sample(1.10, 120.0),
            sample(1.15, null),
            sample(1.20, 130.0),
            sample(1.25, 140.0),
        )

        val result = segments(series, startS = 0.99, endS = 1.30)

        // 1.00 and 1.10 each stand between absent frames, so they are points;
        // 1.20 and 1.25 are present and close, so they are a line.
        result.points shouldBe listOf(GraphPoint(1.00, 100.0), GraphPoint(1.10, 120.0))
        result.polylines shouldBe listOf(listOf(GraphPoint(1.20, 130.0), GraphPoint(1.25, 140.0)))
    }

    @Test
    fun samples_further_apart_than_two_and_a_half_frames_do_not_join() {
        val series = listOf(sample(1.00, 100.0), sample(1.20, 110.0))

        val result = segments(series, startS = 0.90, endS = 1.40)

        result.polylines shouldBe emptyList()
        result.points shouldBe listOf(GraphPoint(1.00, 100.0), GraphPoint(1.20, 110.0))
    }

    @Test
    fun an_empty_series_draws_nothing() {
        val result = segments(emptyList(), startS = 0.0, endS = 2.0)

        result.polylines shouldBe emptyList()
        result.points shouldBe emptyList()
    }

    @Test
    fun a_window_entirely_before_or_after_the_series_draws_nothing() {
        val series = listOf(sample(1.00, 100.0), sample(1.05, 110.0), sample(1.10, 120.0))

        // After: the last sample is read to anchor a crossing line, but it is
        // outside the window, so it must not appear as a point inside it.
        segments(series, startS = 2.00, endS = 3.00) shouldBe GraphSegments.EMPTY
        segments(series, startS = -1.00, endS = 0.50) shouldBe GraphSegments.EMPTY
    }
}
