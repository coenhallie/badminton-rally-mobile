package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.MovementProfile
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class ExerciseAdvisorTest {

    private fun balancedProfile(
        countedRallies: Int = 8,
        rallyCoverage: Double = 0.85,
        totalRallySeconds: Double = 60.0,
        behind: Double? = 1.40,
        lateral: Double? = 0.05,
        spread: Double? = 0.35,
        drift: Double? = 0.10,
        front: Double = 0.20,
        mid: Double = 0.50,
        rear: Double = 0.30,
        left: Double = 0.50,
        right: Double = 0.50,
        medianPath: Double? = 18.0,
        medianPeak: Double? = 4.1,
        earlyPeak: Double? = 4.2,
        latePeak: Double? = 4.0,
        speedDrop: Double? = 0.05,
    ) = MovementProfile(
        countedRallies = countedRallies,
        rallyCoverage = rallyCoverage,
        totalRallySeconds = totalRallySeconds,
        overallBehindServiceLineM = behind,
        overallLateralFromCentreM = lateral,
        baseSpreadM = spread,
        baseDepthDriftM = drift,
        frontShare = front,
        midShare = mid,
        rearShare = rear,
        leftShare = left,
        rightShare = right,
        medianRallyPathM = medianPath,
        medianPeakSpeedMps = medianPeak,
        earlyPeakSpeedMps = earlyPeak,
        latePeakSpeedMps = latePeak,
        speedDropFraction = speedDrop,
    )

    @Test
    fun insufficient_data_gates() {
        val tooFew = adviseExercises(balancedProfile(countedRallies = 3, rallyCoverage = 0.90), null)
        tooFew.status shouldBe ExerciseReportStatus.INSUFFICIENT_DATA
        tooFew.suggestions.shouldBeEmpty()
        tooFew.summary.shouldNotBeNull()
        tooFew.message.shouldNotBeNull().apply {
            shouldContain("4")
            shouldContain("3")
        }

        val lowCoverage = adviseExercises(balancedProfile(countedRallies = 6, rallyCoverage = 0.49), null)
        lowCoverage.status shouldBe ExerciseReportStatus.INSUFFICIENT_DATA
        lowCoverage.suggestions.shouldBeEmpty()

        val zeroRallies = adviseExercises(MovementProfile.EMPTY, null)
        zeroRallies.status shouldBe ExerciseReportStatus.INSUFFICIENT_DATA
        zeroRallies.summary.shouldBeNull()

        val exactGate = adviseExercises(balancedProfile(countedRallies = 4, rallyCoverage = 0.50), null)
        exactGate.status shouldBe ExerciseReportStatus.BALANCED
    }

    @Test
    fun balanced_profile_returns_no_suggestions() {
        val report = adviseExercises(balancedProfile(), null)
        report.status shouldBe ExerciseReportStatus.BALANCED
        report.suggestions.shouldBeEmpty()
        report.message.shouldNotBeNull().shouldContain("8 rallies")
        val summary = report.summary.shouldNotBeNull()
        summary.countedRallies shouldBe 8
        summary.coveragePercent shouldBe 85
        summary.peakSpeedText shouldBe "4.1"
        (summary.frontPercent + summary.midPercent + summary.rearPercent) shouldBe 100
    }

    @Test
    fun deep_base_triggers_above_threshold_and_not_at_boundary() {
        adviseExercises(balancedProfile(behind = 2.00), null).status shouldBe ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(balancedProfile(behind = 2.01), null)
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.finding } shouldBe listOf(FindingKind.DEEP_BASE, FindingKind.DEEP_BASE)
        triggered.suggestions.map { it.exercise.id } shouldBe listOf(
            "base-mark-6-corner-shadow",
            "clear-to-base-multi-shuttle",
        )
        triggered.suggestions.first().evidence shouldContain "2.01 m"
    }

    @Test
    fun shallow_base_triggers_below_threshold_and_not_at_boundary() {
        adviseExercises(balancedProfile(behind = 0.75), null).status shouldBe ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(balancedProfile(behind = 0.74), null)
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.exercise.id } shouldBe listOf(
            "net-to-rear-recovery",
            "front-back-2-corner-feed",
        )
        triggered.suggestions.first().evidence shouldContain "0.74 m"
    }

    @Test
    fun lateral_bias_triggers_on_offset_or_share_and_not_at_boundary() {
        adviseExercises(balancedProfile(lateral = 0.40, left = 0.36, right = 0.64), null).status shouldBe
            ExerciseReportStatus.BALANCED

        val byOffset = adviseExercises(balancedProfile(lateral = 0.41, left = 0.40, right = 0.60), null)
        byOffset.status shouldBe ExerciseReportStatus.SUGGESTIONS
        byOffset.suggestions.map { it.exercise.id } shouldBe listOf(
            "opposite-side-corner-recovery",
            "centre-line-split-reset",
        )

        val byShare = adviseExercises(balancedProfile(lateral = 0.20, left = 0.65, right = 0.35), null)
        byShare.status shouldBe ExerciseReportStatus.SUGGESTIONS
        byShare.suggestions.first().finding shouldBe FindingKind.LATERAL_BIAS
    }

    @Test
    fun lateral_bias_copy_adapts_to_racket_arm() {
        val rightHanded = adviseExercises(
            balancedProfile(lateral = 0.55, left = 0.33, right = 0.67),
            RacketArm.RIGHT,
        )
        rightHanded.suggestions.first().evidence.apply {
            shouldContain("forehand side")
            shouldContain("backhand side")
        }

        val leftHanded = adviseExercises(
            balancedProfile(lateral = 0.55, left = 0.33, right = 0.67),
            RacketArm.LEFT,
        )
        leftHanded.suggestions.first().evidence.apply {
            shouldContain("backhand side")
            shouldContain("forehand side")
        }

        val unsetRight = adviseExercises(
            balancedProfile(lateral = 0.55, left = 0.33, right = 0.67),
            null,
        )
        unsetRight.suggestions.first().evidence shouldContain "right of centre"

        val unsetLeft = adviseExercises(
            balancedProfile(lateral = -0.55, left = 0.67, right = 0.33),
            null,
        )
        unsetLeft.suggestions.first().evidence shouldContain "left of centre"
    }

    @Test
    fun base_drift_late_requires_six_rallies_and_strict_threshold() {
        adviseExercises(balancedProfile(countedRallies = 5, drift = 0.80), null).status shouldBe
            ExerciseReportStatus.BALANCED
        adviseExercises(balancedProfile(countedRallies = 6, drift = 0.45), null).status shouldBe
            ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(balancedProfile(countedRallies = 6, drift = 0.46), null)
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.exercise.id } shouldBe listOf(
            "fatigue-6-corner-shadow",
            "split-squat-endurance",
        )
        triggered.suggestions.first().evidence shouldContain "0.46 m"
    }

    @Test
    fun inconsistent_base_triggers_above_threshold_with_single_card() {
        adviseExercises(balancedProfile(spread = 0.85), null).status shouldBe ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(balancedProfile(spread = 0.86), null)
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.exercise.id } shouldBe listOf("cone-touch-base-discipline")
        triggered.suggestions.first().evidence shouldContain "0.86 m"
    }

    @Test
    fun low_front_presence_requires_both_high_rear_and_low_front() {
        adviseExercises(balancedProfile(rear = 0.55, front = 0.10, mid = 0.35), null).status shouldBe
            ExerciseReportStatus.BALANCED
        adviseExercises(balancedProfile(rear = 0.60, front = 0.12, mid = 0.28), null).status shouldBe
            ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(balancedProfile(rear = 0.56, front = 0.11, mid = 0.33), null)
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.exercise.id } shouldBe listOf(
            "drop-and-follow-net-attack",
            "forecourt-lunge-net-kill",
        )
    }

    @Test
    fun late_speed_drop_checks_early_floor_rally_count_and_drop_threshold() {
        adviseExercises(
            balancedProfile(countedRallies = 6, earlyPeak = 2.4, latePeak = 1.6, speedDrop = 0.33),
            null,
        ).status shouldBe ExerciseReportStatus.BALANCED

        adviseExercises(
            balancedProfile(countedRallies = 6, earlyPeak = 3.0, latePeak = 2.43, speedDrop = 0.19),
            null,
        ).status shouldBe ExerciseReportStatus.BALANCED

        val triggered = adviseExercises(
            balancedProfile(countedRallies = 6, earlyPeak = 3.0, latePeak = 2.4, speedDrop = 0.20),
            null,
        )
        triggered.status shouldBe ExerciseReportStatus.SUGGESTIONS
        triggered.suggestions.map { it.exercise.id } shouldBe listOf(
            "repeat-burst-multi-shuttle",
            "lateral-bounds-split-jumps",
        )
        triggered.suggestions.first().evidence.apply {
            shouldContain("20%")
            shouldContain("3.0 m/s")
            shouldContain("2.4 m/s")
        }
    }

    @Test
    fun ranking_deduplication_and_four_card_cap() {
        // Trigger 5 rules simultaneously with distinct severities.
        val multi = balancedProfile(
            countedRallies = 8,
            behind = 2.50,      // DEEP_BASE severity = (0.50/0.50) + 1.0 = 2.0
            lateral = 0.90,     // LATERAL_BIAS severity = (0.50/0.25) + 1.0 = 3.0 (highest)
            drift = 0.75,       // BASE_DRIFT_LATE severity = (0.30/0.30) + 1.0 = 2.0 (tie with DEEP_BASE -> ordinal loses)
            spread = 1.00,      // INCONSISTENT_BASE severity = (0.15/0.35) + 1.0 = 1.43
            earlyPeak = 4.0,
            latePeak = 3.0,
            speedDrop = 0.25,   // LATE_SPEED_DROP severity = (0.05/0.12) + 1.0 = 1.42 (5th, dropped by cap)
        )

        val report = adviseExercises(multi, RacketArm.RIGHT)
        report.status shouldBe ExerciseReportStatus.SUGGESTIONS
        report.suggestions shouldHaveSize 4
        report.suggestions.map { it.exercise.id }.distinct() shouldHaveSize 4
        report.suggestions.map { it.finding } shouldBe listOf(
            FindingKind.LATERAL_BIAS,
            FindingKind.DEEP_BASE,
            FindingKind.BASE_DRIFT_LATE,
            FindingKind.INCONSISTENT_BASE,
        )
        // Primary exercise only when 2+ findings trigger.
        report.suggestions.map { it.exercise.id } shouldBe listOf(
            "opposite-side-corner-recovery",
            "base-mark-6-corner-shadow",
            "fatigue-6-corner-shadow",
            "cone-touch-base-discipline",
        )
    }

    @Test
    fun end_to_end_track_advice_works_and_handles_degenerate_input() {
        val fps = 20.0
        val samples = (0 until 4).flatMap { r ->
            val startFrame = r * 60
            // 2.30 m behind near service line (8.68 + 2.30 = 10.98)
            (0 until 40).map { f -> PlayerSample(startFrame + f, Point(3.05, 10.98)) }
        }
        val windows = (0 until 4).map { r -> RallyWindow(r + 1, r * 3.0, r * 3.0 + 2.0) }
        val track = PlayerTrack(samples, samples.size, emptyMap())

        val report = adviseExercisesForTrack(track, fps, windows, CourtSide.NEAR, RacketArm.RIGHT)
        report.status shouldBe ExerciseReportStatus.SUGGESTIONS
        report.suggestions.any { it.finding == FindingKind.DEEP_BASE } shouldBe true

        val badFps = adviseExercisesForTrack(track, 0.0, windows, CourtSide.NEAR, null)
        badFps.status shouldBe ExerciseReportStatus.INSUFFICIENT_DATA
    }

    @Test
    fun coverage_just_below_gate_never_prints_fifty_percent_contradiction() {
        val borderline = adviseExercises(balancedProfile(countedRallies = 6, rallyCoverage = 0.496), null)
        borderline.status shouldBe ExerciseReportStatus.INSUFFICIENT_DATA
        borderline.summary.shouldNotBeNull().coveragePercent shouldBe 49
        borderline.message.shouldNotBeNull().shouldContain("49% coverage")
    }

    @Test
    fun zone_percentages_always_sum_to_hundred_even_on_double_half_rounding() {
        val report = adviseExercises(balancedProfile(front = 0.505, mid = 0.0, rear = 0.495), null)
        val summary = report.summary.shouldNotBeNull()
        (summary.frontPercent + summary.midPercent + summary.rearPercent) shouldBe 100
    }
}
