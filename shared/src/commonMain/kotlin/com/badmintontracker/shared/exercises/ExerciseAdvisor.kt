package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.MIN_RALLY_SECONDS
import com.badmintontracker.analysis.player.MIN_TREND_RALLIES
import com.badmintontracker.analysis.player.MovementProfile
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.analysis.player.movementProfile
import com.badmintontracker.shared.local.metres
import com.badmintontracker.shared.prefs.RacketArm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Tunable thresholds and quality gates for deterministic singles rules. */
object ExerciseThresholds {
    const val MIN_RALLIES: Int = 4
    const val MIN_COVERAGE: Double = 0.50
    const val MAX_SUGGESTIONS: Int = 4
    const val DEEP_BASE_M: Double = 2.00
    const val SHALLOW_BASE_M: Double = 0.75
    const val LATERAL_OFFSET_M: Double = 0.40
    const val LATERAL_SKEW_SHARE: Double = 0.65
    const val BASE_DRIFT_M: Double = 0.45
    const val BASE_SPREAD_M: Double = 0.85
    const val REAR_HEAVY_SHARE: Double = 0.55
    const val FRONT_LOW_SHARE: Double = 0.12
    const val MIN_EARLY_SPEED_MPS: Double = 2.5
    const val SPEED_DROP_FRACTION: Double = 0.20
}

private data class TriggeredFinding(
    val kind: FindingKind,
    val severity: Double,
    val evidence: String,
    val primaryId: String,
    val secondaryId: String?,
)

/**
 * Evaluates a precomputed [MovementProfile] against the 7 singles rules and
 * returns an [ExerciseReport] with quality gating, ranking, and deduplication.
 */
fun adviseExercises(profile: MovementProfile, racketArm: RacketArm?): ExerciseReport {
    val coveragePercent = displayCoveragePercent(profile.rallyCoverage)
    val summary = if (profile.countedRallies > 0) buildSummary(profile, coveragePercent) else null

    if (profile.countedRallies < ExerciseThresholds.MIN_RALLIES ||
        profile.rallyCoverage < ExerciseThresholds.MIN_COVERAGE
    ) {
        val message = if (profile.countedRallies == 0) {
            ExercisesCopy.NO_COUNTED_RALLIES
        } else {
            ExercisesCopy.insufficientData(
                rallies = profile.countedRallies,
                coveragePercent = coveragePercent,
                minRallies = ExerciseThresholds.MIN_RALLIES,
                minCoveragePercent = (ExerciseThresholds.MIN_COVERAGE * 100.0).roundToInt(),
            )
        }
        return ExerciseReport(
            status = ExerciseReportStatus.INSUFFICIENT_DATA,
            message = message,
            summary = summary,
            suggestions = emptyList(),
        )
    }

    val findings = ArrayList<TriggeredFinding>()
    val rallies = profile.countedRallies

    // 1. DEEP_BASE
    val behind = profile.overallBehindServiceLineM
    if (behind != null && behind > ExerciseThresholds.DEEP_BASE_M) {
        val severity = (behind - ExerciseThresholds.DEEP_BASE_M) / 0.50 + 1.0
        findings += TriggeredFinding(
            kind = FindingKind.DEEP_BASE,
            severity = severity,
            evidence = "Base averaged ${metres(behind)} behind the service line across $rallies rallies " +
                "(typical singles base is about 1.0 to 1.8 m).",
            primaryId = "base-mark-6-corner-shadow",
            secondaryId = "clear-to-base-multi-shuttle",
        )
    }

    // 2. SHALLOW_BASE
    if (behind != null && behind < ExerciseThresholds.SHALLOW_BASE_M) {
        val severity = (ExerciseThresholds.SHALLOW_BASE_M - behind) / 0.40 + 1.0
        val depthPhrase = if (behind < 0.0) {
            "${metres(-behind)} in front of the service line"
        } else {
            "${metres(behind)} behind the service line"
        }
        findings += TriggeredFinding(
            kind = FindingKind.SHALLOW_BASE,
            severity = severity,
            evidence = "Base averaged $depthPhrase across $rallies rallies, leaving the rear corners exposed.",
            primaryId = "net-to-rear-recovery",
            secondaryId = "front-back-2-corner-feed",
        )
    }

    // 3. LATERAL_BIAS
    val lateral = profile.overallLateralFromCentreM
    val dominantShare = max(profile.leftShare, profile.rightShare)
    val offsetTriggered = lateral != null && abs(lateral) > ExerciseThresholds.LATERAL_OFFSET_M
    val shareTriggered = dominantShare >= ExerciseThresholds.LATERAL_SKEW_SHARE
    if (offsetTriggered || shareTriggered) {
        val offsetExcess = if (offsetTriggered) (abs(lateral) - ExerciseThresholds.LATERAL_OFFSET_M) / 0.25 else 0.0
        val skewExcess = if (shareTriggered) (dominantShare - ExerciseThresholds.LATERAL_SKEW_SHARE) / 0.12 else 0.0
        val severity = max(offsetExcess, skewExcess) + 1.0
        val rightDominant = if (offsetTriggered) lateral > 0.0 else profile.rightShare >= profile.leftShare
        val dominantPct = ((if (rightDominant) profile.rightShare else profile.leftShare) * 100.0).roundToInt()
        val evidence = lateralEvidence(lateral, rightDominant, dominantPct, racketArm)
        findings += TriggeredFinding(
            kind = FindingKind.LATERAL_BIAS,
            severity = severity,
            evidence = evidence,
            primaryId = "opposite-side-corner-recovery",
            secondaryId = "centre-line-split-reset",
        )
    }

    // 4. BASE_DRIFT_LATE
    val drift = profile.baseDepthDriftM
    if (rallies >= MIN_TREND_RALLIES && drift != null && drift > ExerciseThresholds.BASE_DRIFT_M) {
        val severity = (drift - ExerciseThresholds.BASE_DRIFT_M) / 0.30 + 1.0
        findings += TriggeredFinding(
            kind = FindingKind.BASE_DRIFT_LATE,
            severity = severity,
            evidence = "Base drifted ${metres(drift)} deeper between early rallies and late rallies.",
            primaryId = "fatigue-6-corner-shadow",
            secondaryId = "split-squat-endurance",
        )
    }

    // 5. INCONSISTENT_BASE
    val spread = profile.baseSpreadM
    if (spread != null && spread > ExerciseThresholds.BASE_SPREAD_M) {
        val severity = (spread - ExerciseThresholds.BASE_SPREAD_M) / 0.35 + 1.0
        findings += TriggeredFinding(
            kind = FindingKind.INCONSISTENT_BASE,
            severity = severity,
            evidence = "Rally base positions varied by ${metres(spread)} around the match median.",
            primaryId = "cone-touch-base-discipline",
            secondaryId = null,
        )
    }

    // 6. LOW_FRONT_PRESENCE
    if (profile.rearShare > ExerciseThresholds.REAR_HEAVY_SHARE &&
        profile.frontShare < ExerciseThresholds.FRONT_LOW_SHARE
    ) {
        val rearExcess = (profile.rearShare - ExerciseThresholds.REAR_HEAVY_SHARE) / 0.15
        val frontExcess = (ExerciseThresholds.FRONT_LOW_SHARE - profile.frontShare) / 0.08
        val severity = max(rearExcess, frontExcess) + 1.0
        val rearPct = (profile.rearShare * 100.0).roundToInt()
        val frontPct = (profile.frontShare * 100.0).roundToInt()
        findings += TriggeredFinding(
            kind = FindingKind.LOW_FRONT_PRESENCE,
            severity = severity,
            evidence = "Spent $rearPct% of rally time in the rear court and $frontPct% in the front court.",
            primaryId = "drop-and-follow-net-attack",
            secondaryId = "forecourt-lunge-net-kill",
        )
    }

    // 7. LATE_SPEED_DROP
    val early = profile.earlyPeakSpeedMps
    val late = profile.latePeakSpeedMps
    val drop = profile.speedDropFraction
    if (rallies >= MIN_TREND_RALLIES &&
        early != null &&
        late != null &&
        drop != null &&
        early >= ExerciseThresholds.MIN_EARLY_SPEED_MPS &&
        drop >= ExerciseThresholds.SPEED_DROP_FRACTION
    ) {
        val severity = (drop - ExerciseThresholds.SPEED_DROP_FRACTION) / 0.12 + 1.0
        val dropPct = (drop * 100.0).roundToInt()
        findings += TriggeredFinding(
            kind = FindingKind.LATE_SPEED_DROP,
            severity = severity,
            evidence = "Peak movement speed (0.5 s window) dropped $dropPct% from early rallies " +
                "(${oneDecimal(early)} m/s) to late rallies (${oneDecimal(late)} m/s).",
            primaryId = "repeat-burst-multi-shuttle",
            secondaryId = "lateral-bounds-split-jumps",
        )
    }

    if (findings.isEmpty()) {
        return ExerciseReport(
            status = ExerciseReportStatus.BALANCED,
            message = ExercisesCopy.balanced(rallies),
            summary = summary,
            suggestions = emptyList(),
        )
    }

    val ranked = findings.sortedWith(
        compareByDescending<TriggeredFinding> { it.severity }.thenBy { it.kind.ordinal },
    )
    val seenIds = HashSet<String>()
    val suggestions = ArrayList<ExerciseSuggestion>(ExerciseThresholds.MAX_SUGGESTIONS)

    for (f in ranked) {
        if (suggestions.size >= ExerciseThresholds.MAX_SUGGESTIONS) break
        if (seenIds.add(f.primaryId)) {
            suggestions += ExerciseSuggestion(
                exercise = ExerciseCatalog.byId(f.primaryId),
                finding = f.kind,
                evidence = f.evidence,
                severity = f.severity,
            )
        }
    }

    if (ranked.size == 1) {
        val only = ranked.first()
        val secondary = only.secondaryId
        if (secondary != null && seenIds.add(secondary)) {
            suggestions += ExerciseSuggestion(
                exercise = ExerciseCatalog.byId(secondary),
                finding = only.kind,
                evidence = only.evidence,
                severity = only.severity,
            )
        }
    }

    return ExerciseReport(
        status = ExerciseReportStatus.SUGGESTIONS,
        message = null,
        summary = summary,
        suggestions = suggestions,
    )
}

/**
 * End-to-end entry point for Android and iOS panels (explicit parameters with
 * no defaults so Swift calls it directly).
 */
fun adviseExercisesForTrack(
    track: PlayerTrack,
    fps: Double,
    windows: List<RallyWindow>,
    side: CourtSide,
    racketArm: RacketArm?,
): ExerciseReport {
    val profile = movementProfile(
        track = track,
        fps = fps,
        windows = windows,
        side = side,
        minSeconds = MIN_RALLY_SECONDS,
    )
    return adviseExercises(profile, racketArm)
}

private fun displayCoveragePercent(coverage: Double): Int {
    val rounded = (coverage * 100.0).roundToInt().coerceIn(0, 100)
    val minPct = (ExerciseThresholds.MIN_COVERAGE * 100.0).roundToInt()
    return if (coverage < ExerciseThresholds.MIN_COVERAGE) minOf(rounded, minPct - 1) else rounded
}

private fun buildSummary(profile: MovementProfile, coveragePercent: Int): MovementSummary {
    val (frontPct, midPct, rearPct) = roundedZonePercents(
        profile.frontShare,
        profile.midShare,
        profile.rearShare,
    )
    return MovementSummary(
        countedRallies = profile.countedRallies,
        coveragePercent = coveragePercent,
        peakSpeedText = profile.medianPeakSpeedMps?.let(::oneDecimal),
        frontPercent = frontPct,
        midPercent = midPct,
        rearPercent = rearPct,
    )
}

/**
 * Largest-remainder rounding of the three zone shares so displayed percentages
 * always sum to 100 (or 0 when all shares are zero) without double half-up drift.
 */
private fun roundedZonePercents(front: Double, mid: Double, rear: Double): Triple<Int, Int, Int> {
    val total = front + mid + rear
    if (total <= 0.0) return Triple(0, 0, 0)
    val scaled = doubleArrayOf(front / total * 100.0, mid / total * 100.0, rear / total * 100.0)
    val floors = IntArray(3) { scaled[it].toInt().coerceIn(0, 100) }
    var remainder = 100 - (floors[0] + floors[1] + floors[2])
    val order = listOf(0, 1, 2).sortedByDescending { scaled[it] - floors[it] }
    for (idx in order) {
        if (remainder <= 0) break
        floors[idx] += 1
        remainder -= 1
    }
    return Triple(floors[0], floors[1], floors[2])
}

private fun lateralEvidence(
    lateral: Double?,
    rightDominant: Boolean,
    dominantPct: Int,
    racketArm: RacketArm?,
): String {
    val offset = lateral?.let { metres(abs(it)) }
    return when (racketArm) {
        RacketArm.RIGHT, RacketArm.LEFT -> {
            val forehandDominant = (racketArm == RacketArm.RIGHT) == rightDominant
            val crowded = if (forehandDominant) "forehand side" else "backhand side"
            val open = if (forehandDominant) "backhand side" else "forehand side"
            if (offset != null && abs(lateral) > ExerciseThresholds.LATERAL_OFFSET_M) {
                "Base sat $offset toward the $crowded ($dominantPct% of rally time there), leaving the $open open."
            } else {
                "Spent $dominantPct% of rally time on the $crowded, leaving the $open open."
            }
        }
        null -> {
            val dir = if (rightDominant) "right of centre" else "left of centre"
            val half = if (rightDominant) "on the right" else "on the left"
            if (offset != null && abs(lateral) > ExerciseThresholds.LATERAL_OFFSET_M) {
                "Base sat $offset $dir ($dominantPct% of rally time $half)."
            } else {
                "Spent $dominantPct% of rally time $half half of the court."
            }
        }
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = (abs(value) * 10.0).roundToLong()
    val whole = tenths / 10
    val frac = tenths % 10
    val sign = if (value < 0.0) "-" else ""
    return "$sign$whole.$frac"
}
