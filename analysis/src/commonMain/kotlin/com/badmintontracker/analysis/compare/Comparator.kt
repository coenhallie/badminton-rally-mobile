package com.badmintontracker.analysis.compare

import com.badmintontracker.analysis.rally.RALLY_OVERLAP_THRESHOLD
import com.badmintontracker.analysis.rally.overlapsByFraction
import com.badmintontracker.analysis.result.AnalysisResult
import com.badmintontracker.analysis.result.SerializedRally
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Level 1: raw model output. Did conversion and decode preserve the model's
 * behaviour? Visibility agreement, and the positional delta where both sides
 * saw the shuttle.
 */
@Serializable
data class ShuttleAgreement(
    @SerialName("both_visible") val bothVisible: Int,
    @SerialName("local_only") val localOnly: Int,
    @SerialName("cloud_only") val cloudOnly: Int,
    @SerialName("neither") val neither: Int,
    /** Null when the two sides never both saw the shuttle. Zero would read as agreement. */
    @SerialName("median_pixel_delta") val medianPixelDelta: Double?,
    @SerialName("p95_pixel_delta") val p95PixelDelta: Double?,
)

/**
 * Level 2: derived tracks. Did the same track produce the same rallies?
 */
@Serializable
data class RallyAgreement(
    @SerialName("local_count") val localCount: Int,
    @SerialName("cloud_count") val cloudCount: Int,
    @SerialName("matched") val matched: Int,
    @SerialName("median_start_delta_seconds") val medianStartDeltaSeconds: Double?,
    @SerialName("median_end_delta_seconds") val medianEndDeltaSeconds: Double?,
    @SerialName("unmatched_local") val unmatchedLocal: List<SerializedRally>,
    @SerialName("unmatched_cloud") val unmatchedCloud: List<SerializedRally>,
)

@Serializable
data class ComparisonReport(
    @SerialName("level1") val level1: ShuttleAgreement,
    @SerialName("level2") val level2: RallyAgreement,
) {
    fun toJson(): String = FORMAT.encodeToString(serializer(), this)

    private companion object {
        val FORMAT = Json { explicitNulls = true; encodeDefaults = true }
    }
}

/**
 * Diff a local analysis against the cloud's for the same video.
 *
 * Level 3, the golden comparison of Phase 1 metrics end to end, is
 * Phase1PipelineTest rather than a function here: it needs a corpus and a
 * pipeline run, not two finished results.
 */
fun compare(local: AnalysisResult, cloud: AnalysisResult): ComparisonReport =
    ComparisonReport(
        level1 = compareShuttle(local, cloud),
        level2 = compareRallies(local, cloud),
    )

private fun compareShuttle(local: AnalysisResult, cloud: AnalysisResult): ShuttleAgreement {
    var both = 0
    var localOnly = 0
    var cloudOnly = 0
    var neither = 0
    val deltas = ArrayList<Double>()

    // Union of frames, so a frame present on one side only still counts. Taking
    // one side's keys would silently score a truncated track as agreeing.
    for (key in local.shuttlePositions.keys + cloud.shuttlePositions.keys) {
        val l = local.shuttlePositions[key]
        val c = cloud.shuttlePositions[key]
        val lVisible = l?.visible == true
        val cVisible = c?.visible == true
        when {
            lVisible && cVisible -> {
                both++
                val dx = l!!.x - c!!.x
                val dy = l.y - c.y
                deltas.add(sqrt(dx * dx + dy * dy))
            }
            lVisible -> localOnly++
            cVisible -> cloudOnly++
            else -> neither++
        }
    }
    return ShuttleAgreement(
        bothVisible = both,
        localOnly = localOnly,
        cloudOnly = cloudOnly,
        neither = neither,
        medianPixelDelta = deltas.percentile(0.5),
        p95PixelDelta = deltas.percentile(0.95),
    )
}

private fun compareRallies(local: AnalysisResult, cloud: AnalysisResult): RallyAgreement {
    val cloudRallies = cloud.rallies.sortedBy { it.startTimestamp }
    val localRallies = local.rallies.sortedBy { it.startTimestamp }
    val takenCloud = HashSet<Int>()
    val startDeltas = ArrayList<Double>()
    val endDeltas = ArrayList<Double>()
    val unmatchedLocal = ArrayList<SerializedRally>()

    for (l in localRallies) {
        // Same predicate unionRallies uses. One definition of rally identity,
        // so the union cannot merge a pair this reports as unmatched.
        val match = cloudRallies.withIndex().firstOrNull { (i, c) ->
            i !in takenCloud && overlapsByFraction(
                l.startTimestamp, l.endTimestamp,
                c.startTimestamp, c.endTimestamp,
                RALLY_OVERLAP_THRESHOLD,
            )
        }
        if (match == null) {
            unmatchedLocal.add(l)
        } else {
            takenCloud.add(match.index)
            startDeltas.add(abs(l.startTimestamp - match.value.startTimestamp))
            endDeltas.add(abs(l.endTimestamp - match.value.endTimestamp))
        }
    }

    return RallyAgreement(
        localCount = localRallies.size,
        cloudCount = cloudRallies.size,
        matched = takenCloud.size,
        medianStartDeltaSeconds = startDeltas.percentile(0.5),
        medianEndDeltaSeconds = endDeltas.percentile(0.5),
        unmatchedLocal = unmatchedLocal,
        unmatchedCloud = cloudRallies.filterIndexed { i, _ -> i !in takenCloud },
    )
}

/**
 * Nearest-rank percentile, or null on an empty sample.
 *
 * Nearest-rank rather than interpolating so a reported value is always one
 * that actually occurred - useful when the question is "how far off was the
 * worst frame", not "what is the shape of the distribution".
 */
private fun List<Double>.percentile(fraction: Double): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
