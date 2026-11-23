package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.analytics.CourtSideNames
import com.badmintontracker.shared.analytics.profileArm
import com.badmintontracker.shared.exercises.ExerciseReport
import com.badmintontracker.shared.exercises.ExerciseReportStatus
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.FindingKind
import com.badmintontracker.shared.exercises.adviseExercisesForTrack
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

class ExercisesPanelTest {

    private fun track(samples: Int) = PlayerTrack(
        samples = (0 until samples).map { PlayerSample(it, Point(3.05, 10.0)) },
        framesWithPose = samples,
        rejections = emptyMap(),
    )

    @Test
    fun unloaded_analysis_says_so_plainly() {
        exercisesMessage(
            track = null,
            windows = emptyList(),
            report = null,
            measured = true,
        ) shouldBe ExercisesCopy.NOT_LOADED
    }

    @Test
    fun empty_track_uses_heatmaps_own_wording() {
        val empty = PlayerTrack(emptyList(), 0, emptyMap())
        exercisesMessage(
            track = empty,
            windows = listOf(RallyWindow(1, 0.0, 4.0)),
            report = null,
            measured = true,
        ) shouldBe whyEmpty(empty)
    }

    @Test
    fun unbounded_windows_report_missing_windows() {
        exercisesMessage(
            track = track(100),
            windows = listOf(RallyWindow(1, 0.0, 0.0)),
            report = null,
            measured = true,
        ) shouldBe ExercisesCopy.NO_BOUNDED_WINDOWS
    }

    @Test
    fun waits_for_measurement_without_flashing() {
        exercisesMessage(
            track = track(100),
            windows = listOf(RallyWindow(1, 0.0, 4.0)),
            report = null,
            measured = false,
        ).shouldBeNull()
    }

    @Test
    fun measured_null_report_reports_no_counted_rallies() {
        exercisesMessage(
            track = track(100),
            windows = listOf(RallyWindow(1, 0.0, 4.0)),
            report = null,
            measured = true,
        ) shouldBe ExercisesCopy.NO_COUNTED_RALLIES
    }

    @Test
    fun measured_report_hands_rendering_to_panel_for_every_status() {
        val windows = listOf(RallyWindow(1, 0.0, 4.0))
        for (status in ExerciseReportStatus.entries) {
            val report = ExerciseReport(status = status, message = "msg", summary = null, suggestions = emptyList())
            exercisesMessage(track = track(100), windows = windows, report = report, measured = true).shouldBeNull()
        }
    }

    @Test
    fun per_side_arm_isolation_uses_each_sides_own_profile_arm() {
        val fps = 20.0
        val windows = (0 until 4).map { r -> RallyWindow(r + 1, r * 3.0, r * 3.0 + 2.0) }
        // Near biased to player right (+0.60 m from x=3.05 -> x=3.65).
        val nearSamples = (0 until 4).flatMap { r ->
            (0 until 40).map { f -> PlayerSample(r * 60 + f, Point(3.65, 10.0)) }
        }
        // Far biased to player right facing net (x = 3.05 - 0.60 = 2.45).
        val farSamples = (0 until 4).flatMap { r ->
            (0 until 40).map { f -> PlayerSample(r * 60 + f, Point(2.45, 3.4)) }
        }
        val names = CourtSideNames(
            near = "Marco",
            far = "Jip",
            nearArm = RacketArm.RIGHT,
            farArm = RacketArm.LEFT,
        )

        val nearReport = adviseExercisesForTrack(
            track = PlayerTrack(nearSamples, nearSamples.size, emptyMap()),
            fps = fps,
            windows = windows,
            side = CourtSide.NEAR,
            racketArm = profileArm(CourtSide.NEAR, names),
        )
        val farReport = adviseExercisesForTrack(
            track = PlayerTrack(farSamples, farSamples.size, emptyMap()),
            fps = fps,
            windows = windows,
            side = CourtSide.FAR,
            racketArm = profileArm(CourtSide.FAR, names),
        )

        nearReport.suggestions.first().finding shouldBe FindingKind.LATERAL_BIAS
        nearReport.suggestions.first().evidence shouldContain "forehand side"
        farReport.suggestions.first().finding shouldBe FindingKind.LATERAL_BIAS
        farReport.suggestions.first().evidence shouldContain "backhand side"
    }
}
