package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Minimum counted rallies before computing first-third vs last-third trends. */
const val MIN_TREND_RALLIES: Int = 6

/** Depth from the net line separating front court from mid court, in metres. */
const val FRONT_ZONE_MAX_M: Double = 2.2

/** Depth from the net line separating mid court from rear court, in metres. */
const val MID_ZONE_MAX_M: Double = 4.5

/** Fixed resampling step for reproducible per-rally path length, in seconds. */
const val PATH_STEP_S: Double = 0.25

/** Lower bound of the sliding speed window, in seconds. */
const val SPEED_WINDOW_MIN_S: Double = 0.45

/** Upper bound of the sliding speed window, in seconds. */
const val SPEED_WINDOW_MAX_S: Double = 0.65

/** Physical speed ceiling matching SidePath, rejecting tracking glitches. */
const val MAX_PLAUSIBLE_SPEED_MPS: Double = 8.0

/**
 * Player-relative positioning and movement metrics derived from a stored track
 * and bounded rally windows.
 *
 * Every coordinate is measured from the shown player's own half facing the net:
 * depth grows from the net toward the player's baseline, and lateral offset is
 * negative to the player's left and positive to the player's right.
 */
data class MovementProfile(
    /** Number of bounded rallies meeting [MIN_RALLY_SECONDS] of tracked samples. */
    val countedRallies: Int,
    /** Share of frames inside bounded rally windows that had a valid ankle sample (0..1). */
    val rallyCoverage: Double,
    /** Total credited seconds inside bounded rallies (capped at 0.5 s per gap). */
    val totalRallySeconds: Double,
    /** Overall median position behind the player's own service line (metres), null if none. */
    val overallBehindServiceLineM: Double?,
    /** Overall median lateral offset from centre line (+ right, - left, metres), null if none. */
    val overallLateralFromCentreM: Double?,
    /** Median Euclidean distance of per-rally bases from the overall base (metres), null if < 2 rallies. */
    val baseSpreadM: Double?,
    /**
     * Last-third median base depth minus first-third median base depth (metres).
     * Positive means the base retreated deeper later in the video.
     * Null when [countedRallies] < [MIN_TREND_RALLIES].
     */
    val baseDepthDriftM: Double?,
    /** Fraction of credited rally time in front court (depth from net < 2.2 m), 0..1. */
    val frontShare: Double,
    /** Fraction of credited rally time in mid court (2.2 m <= depth <= 4.5 m), 0..1. */
    val midShare: Double,
    /** Fraction of credited rally time in rear court (depth from net > 4.5 m), 0..1. */
    val rearShare: Double,
    /** Fraction of credited rally time on the player's own left half (lateral < 0), 0..1. */
    val leftShare: Double,
    /** Fraction of credited rally time on the player's own right half (lateral >= 0), 0..1. */
    val rightShare: Double,
    /** Median per-rally path length resampled at 0.25 s (metres), null if no counted rallies. */
    val medianRallyPathM: Double?,
    /** Median across counted rallies of each rally's peak 0.5 s windowed speed (m/s), null if none. */
    val medianPeakSpeedMps: Double?,
    /** First-third median peak speed (m/s), null if < 6 rallies or insufficient speed windows. */
    val earlyPeakSpeedMps: Double?,
    /** Last-third median peak speed (m/s), paired with [earlyPeakSpeedMps]. */
    val latePeakSpeedMps: Double?,
    /** Fractional speed drop `(early - late) / early`; positive means slower later. */
    val speedDropFraction: Double?,
) {
    companion object {
        val EMPTY = MovementProfile(
            countedRallies = 0,
            rallyCoverage = 0.0,
            totalRallySeconds = 0.0,
            overallBehindServiceLineM = null,
            overallLateralFromCentreM = null,
            baseSpreadM = null,
            baseDepthDriftM = null,
            frontShare = 0.0,
            midShare = 0.0,
            rearShare = 0.0,
            leftShare = 0.0,
            rightShare = 0.0,
            medianRallyPathM = null,
            medianPeakSpeedMps = null,
            earlyPeakSpeedMps = null,
            latePeakSpeedMps = null,
            speedDropFraction = null,
        )
    }
}

/**
 * Computes a [MovementProfile] from a stored [track], frame rate [fps], bounded
 * [windows], and which court end [side] the track belongs to.
 */
fun movementProfile(
    track: PlayerTrack,
    fps: Double,
    windows: List<RallyWindow>,
    side: CourtSide,
    minSeconds: Double = MIN_RALLY_SECONDS,
): MovementProfile {
    if (fps <= 0.0 || !fps.isFinite() || track.samples.isEmpty()) return MovementProfile.EMPTY
    val rallyFrames = RallyFrames.of(windows, fps)
    if (rallyFrames.isEmpty) return MovementProfile.EMPTY

    val sorted = track.samples.sortedBy { it.frame }
    val bases = basePositions(track, fps, windows, minSeconds)
    val rallyCoverage = rallyFrames.coverage(track).coerceIn(0.0, 1.0)

    // Zone dwell shares, time-weighted with the same gap cap as CourtOccupancy.
    var totalSec = 0.0
    var frontSec = 0.0
    var midSec = 0.0
    var rearSec = 0.0
    var leftSec = 0.0
    var rightSec = 0.0
    val maxFrames = max(1.0, CourtOccupancy.MAX_GAP_S * fps)
    sorted.forEachIndexed { i, sample ->
        val rallyEnd = rallyFrames.endOf(sample.frame) ?: return@forEachIndexed
        val next = sorted.getOrNull(i + 1)
        var frames = if (next == null) 1.0 else min(maxFrames, max(1, next.frame - sample.frame).toDouble())
        frames = min(frames, (rallyEnd - sample.frame + 1).toDouble())
        val dt = frames / fps
        if (dt <= 0.0) return@forEachIndexed
        val depth = depthFromNet(sample.courtPosition, side)
        val lateral = lateralFromCentre(sample.courtPosition, side)
        totalSec += dt
        when {
            depth < FRONT_ZONE_MAX_M -> frontSec += dt
            depth <= MID_ZONE_MAX_M -> midSec += dt
            else -> rearSec += dt
        }
        if (lateral < 0.0) leftSec += dt else rightSec += dt
    }

    val frontShare = if (totalSec > 0.0) frontSec / totalSec else 0.0
    val midShare = if (totalSec > 0.0) midSec / totalSec else 0.0
    val rearShare = if (totalSec > 0.0) rearSec / totalSec else 0.0
    val leftShare = if (totalSec > 0.0) leftSec / totalSec else 0.0
    val rightShare = if (totalSec > 0.0) rightSec / totalSec else 0.0

    val overall = bases.overall
    val overallBehind = overall?.let { behindServiceLine(it, side) }
    val overallLateral = overall?.let { lateralFromCentre(it, side) }
    val baseSpread = if (overall != null && bases.rallies.size >= 2) {
        median(bases.rallies.map { r -> hypot(r.position.x - overall.x, r.position.y - overall.y) })
    } else {
        null
    }

    val nRallies = bases.rallies.size
    val baseDepthDrift = if (nRallies >= MIN_TREND_RALLIES) {
        val k = nRallies / 3
        val earlyDepths = bases.rallies.take(k).map { behindServiceLine(it.position, side) }
        val lateDepths = bases.rallies.takeLast(k).map { behindServiceLine(it.position, side) }
        median(lateDepths) - median(earlyDepths)
    } else {
        null
    }

    // Per-rally path length (0.25 s) and peak speed (0.5 s) over the exact same qualifying rallies.
    val minSamples = max(1, ceil(minSeconds * fps).toInt())
    val rallyPaths = ArrayList<Double>(nRallies)
    val rallyPeaks = ArrayList<Double?>(nRallies)
    for (w in windows.sortedBy { it.startSeconds }) {
        if (!w.isBounded) continue
        val frames = w.frames(fps)
        if (frames.isEmpty()) continue
        val from = lowerBound(sorted, frames.first)
        val to = upperBound(sorted, frames.last)
        if (to - from < minSamples) continue
        val unique = dedupeByFrame(sorted, from, to)
        rallyPaths += rallyPathM(unique, fps)
        rallyPeaks += rallyPeakSpeedMps(unique, fps)
    }

    val medianPath = if (rallyPaths.isEmpty()) null else median(rallyPaths)
    val validPeaks = rallyPeaks.filterNotNull()
    val medianPeak = if (validPeaks.isEmpty()) null else median(validPeaks)

    var earlyPeak: Double? = null
    var latePeak: Double? = null
    var speedDrop: Double? = null
    if (rallyPeaks.size >= MIN_TREND_RALLIES) {
        val k = rallyPeaks.size / 3
        val earlyList = rallyPeaks.take(k).filterNotNull()
        val lateList = rallyPeaks.takeLast(k).filterNotNull()
        if (earlyList.size >= 2 && lateList.size >= 2) {
            val e = median(earlyList)
            val l = median(lateList)
            earlyPeak = e
            latePeak = l
            if (e > 0.0) speedDrop = (e - l) / e
        }
    }

    return MovementProfile(
        countedRallies = nRallies,
        rallyCoverage = rallyCoverage,
        totalRallySeconds = totalSec,
        overallBehindServiceLineM = overallBehind,
        overallLateralFromCentreM = overallLateral,
        baseSpreadM = baseSpread,
        baseDepthDriftM = baseDepthDrift,
        frontShare = frontShare,
        midShare = midShare,
        rearShare = rearShare,
        leftShare = leftShare,
        rightShare = rightShare,
        medianRallyPathM = medianPath,
        medianPeakSpeedMps = medianPeak,
        earlyPeakSpeedMps = earlyPeak,
        latePeakSpeedMps = latePeak,
        speedDropFraction = speedDrop,
    )
}

private fun depthFromNet(p: Point, side: CourtSide): Double = when (side) {
    CourtSide.NEAR -> p.y - Court.LENGTH / 2.0
    CourtSide.FAR -> Court.LENGTH / 2.0 - p.y
}

private fun behindServiceLine(p: Point, side: CourtSide): Double =
    depthFromNet(p, side) - Court.SERVICE_LINE

private fun lateralFromCentre(p: Point, side: CourtSide): Double = when (side) {
    CourtSide.NEAR -> p.x - Court.WIDTH_DOUBLES / 2.0
    CourtSide.FAR -> Court.WIDTH_DOUBLES / 2.0 - p.x
}

private fun dedupeByFrame(sorted: List<PlayerSample>, from: Int, to: Int): List<PlayerSample> {
    val out = ArrayList<PlayerSample>(to - from)
    var lastFrame = Int.MIN_VALUE
    for (i in from until to) {
        val s = sorted[i]
        if (s.frame != lastFrame) {
            out += s
            lastFrame = s.frame
        }
    }
    return out
}

private fun rallyPathM(samples: List<PlayerSample>, fps: Double): Double {
    if (samples.size < 2) return 0.0
    var anchor = samples[0]
    var prev = samples[0]
    var total = 0.0
    for (i in 1 until samples.size) {
        val cur = samples[i]
        val gapS = (cur.frame - prev.frame) / fps
        if (gapS > CourtOccupancy.MAX_GAP_S) {
            anchor = cur
        } else if ((cur.frame - anchor.frame) / fps >= PATH_STEP_S) {
            total += hypot(
                cur.courtPosition.x - anchor.courtPosition.x,
                cur.courtPosition.y - anchor.courtPosition.y,
            )
            anchor = cur
        }
        prev = cur
    }
    return total
}

private fun rallyPeakSpeedMps(samples: List<PlayerSample>, fps: Double): Double? {
    if (samples.size < 2) return null
    var best: Double? = null
    var runStart = 0
    while (runStart < samples.size) {
        var runEnd = runStart + 1
        while (runEnd < samples.size && (samples[runEnd].frame - samples[runEnd - 1].frame) / fps <= CourtOccupancy.MAX_GAP_S) {
            runEnd++
        }
        var j = runStart
        for (i in runStart until runEnd) {
            if (j < i) j = i
            while (j < runEnd && (samples[j].frame - samples[i].frame) / fps < SPEED_WINDOW_MIN_S) {
                j++
            }
            if (j >= runEnd) break
            val dt = (samples[j].frame - samples[i].frame) / fps
            if (dt > 0.0 && dt <= SPEED_WINDOW_MAX_S) {
                val dist = hypot(
                    samples[j].courtPosition.x - samples[i].courtPosition.x,
                    samples[j].courtPosition.y - samples[i].courtPosition.y,
                )
                val v = dist / dt
                if (v <= MAX_PLAUSIBLE_SPEED_MPS) {
                    best = if (best == null) v else max(best, v)
                }
            }
        }
        runStart = runEnd
    }
    return best
}

private fun lowerBound(sorted: List<PlayerSample>, targetFrame: Int): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid].frame < targetFrame) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun upperBound(sorted: List<PlayerSample>, targetFrame: Int): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid].frame <= targetFrame) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}
