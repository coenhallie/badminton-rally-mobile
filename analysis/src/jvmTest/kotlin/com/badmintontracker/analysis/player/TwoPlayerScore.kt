package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * One side of a corpus video's two-player reference (design
 * `docs/plans/2026-09-29-two-player-tracking-design.md` §3.2).
 *
 * [positions] is keyed by 0-based decode order. [empty] says nobody plays on
 * this side, so any sample there is a false positive.
 */
data class SideReference(val empty: Boolean, val positions: Map<Int, Point>)

/** §3.3's figures for one side of one run. */
data class SideScore(
    val samples: Int,
    val right: Int,
    val wrong: Int,
    val unknown: Int,
    val falsePositives: Int,
    val medianM: Double?,
    val p95M: Double?,
    /** Samples over pose frames, both without camera-lost frames. */
    val coverage: Double,
)

/** Within this of the reference, a sample is the right person. */
const val RIGHT_PERSON_M = 1.0

/**
 * Scores one side's samples against its reference.
 *
 * The one frame join every two-player tool makes: pose-file frame `k` (the
 * worker numbers from 1) is reference frame `k - 1`. [excluded] holds
 * pose-file frames whose camera was lost; they leave every count, and
 * [excludedPoseFrames] of them leave the coverage denominator.
 */
fun scoreSide(
    samples: List<PlayerSample>,
    framesWithPose: Int,
    reference: SideReference,
    excluded: Set<Int> = emptySet(),
    excludedPoseFrames: Int = 0,
): SideScore {
    val counted = samples.filter { it.frame !in excluded }
    var right = 0
    var wrong = 0
    var unknown = 0
    var falsePositives = 0
    val errors = ArrayList<Double>()
    for (sample in counted) {
        if (reference.empty) {
            falsePositives++
            continue
        }
        val truth = reference.positions[sample.frame - 1]
        if (truth == null) {
            unknown++
            continue
        }
        val error = hypot(sample.courtPosition.x - truth.x, sample.courtPosition.y - truth.y)
        if (error <= RIGHT_PERSON_M) {
            right++
            errors.add(error)
        } else {
            wrong++
        }
    }
    val poseFrames = framesWithPose - excludedPoseFrames
    return SideScore(
        samples = counted.size,
        right = right,
        wrong = wrong,
        unknown = unknown,
        falsePositives = falsePositives,
        medianM = errors.takeIf { it.isNotEmpty() }?.let { percentile(it, 50.0) },
        p95M = errors.takeIf { it.isNotEmpty() }?.let { percentile(it, 95.0) },
        coverage = if (poseFrames <= 0) 0.0 else counted.size.toDouble() / poseFrames,
    )
}

/** Linear-interpolated percentile, numpy's default (`np.percentile`). */
fun percentile(values: List<Double>, p: Double): Double {
    val sorted = values.sorted()
    if (sorted.size == 1) return sorted[0]
    val rank = p / 100.0 * (sorted.size - 1)
    val lower = floor(rank).toInt()
    val upper = ceil(rank).toInt()
    if (lower == upper) return sorted[lower]
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
}
