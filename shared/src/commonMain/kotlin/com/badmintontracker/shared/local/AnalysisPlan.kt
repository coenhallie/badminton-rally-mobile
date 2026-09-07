package com.badmintontracker.shared.local

import kotlin.math.roundToInt

/**
 * What the user asked to be computed.
 *
 * Deliberately not one toggle per view. The heatmap and the skeleton come from
 * the same pose pass, so offering them as two independently priced options
 * would invite a user to turn one off expecting to halve the wait, and nothing
 * would happen. They differ in what is KEPT, not in what is computed, so the
 * skeleton's extra cost is storage and it is described that way.
 */
enum class AnalysisMetric {
    /** Cut the rallies into playable clips. Re-encodes, so it is not free. */
    RALLY_CLIPS,

    /** Where the near player moved: the court heatmap. Needs pose. */
    PLAYER_MOVEMENT,

    /** Keep every joint of the near player for overlay playback. Needs pose, and about 11MB per 30 minutes of video. */
    SKELETON_PLAYBACK,
    ;

    val needsPose: Boolean get() = this == PLAYER_MOVEMENT || this == SKELETON_PLAYBACK
}

/**
 * What this phone actually manages, in milliseconds per frame.
 *
 * Measured rather than assumed, and updated after every completed run, because
 * the spread across devices is far wider than any single default could cover.
 * The seed values are real measurements from a Galaxy S23 so a first run has
 * something honest to say, and they are replaced the moment this phone has
 * produced its own.
 */
data class DeviceThroughput(
    /** Decode, colour conversion, TrackNet and the detector. */
    val baseMsPerFrame: Double = SEED_BASE_MS,
    /** Pose on top of the same decoded frame. */
    val poseMsPerFrame: Double = SEED_POSE_MS,
    /** Re-encoding one second of clip. */
    val cutMsPerClipSecond: Double = SEED_CUT_MS,
    /** True once this phone has measured itself. */
    val measured: Boolean = false,
) {
    companion object {
        const val SEED_BASE_MS = 235.0
        const val SEED_POSE_MS = 230.0
        const val SEED_CUT_MS = 120.0

        /**
         * How much slower a long run gets as the phone heats.
         *
         * Measured on an S23: pose at 960 climbed from 1385ms to 2310ms over
         * eleven minutes. An estimate quoted from cold numbers alone is
         * therefore optimistic by about this much on anything long, which is
         * why the estimate is a range and not a number.
         */
        const val THROTTLE = 1.6
    }
}

/** A span, because a single number here would be false precision. */
data class AnalysisEstimate(val fastSeconds: Double, val slowSeconds: Double) {

    /**
     * Rounded to units a person plans around.
     *
     * Nobody schedules their evening on "23.4 minutes", and quoting seconds
     * past a couple of minutes implies an accuracy this does not have.
     */
    fun describe(): String {
        fun unit(seconds: Double): String {
            val minutes = seconds / 60.0
            return when {
                minutes < 1.0 -> "under a minute"
                minutes < 90 -> "${minutes.roundToInt()} min"
                else -> "${(minutes / 60.0 * 10).roundToInt() / 10.0} h"
            }
        }
        if (slowSeconds < 60) return "under a minute"
        val fast = unit(fastSeconds)
        val slow = unit(slowSeconds)
        return if (fast == slow) "about $fast" else "$fast to $slow"
    }
}

/**
 * How long an analysis will take, given what was asked for.
 *
 * Frames rather than duration, because duration is the wrong unit: a 6-minute
 * 50fps video has more frames, and costs more to analyse, than an 8-minute
 * 25fps one. Pricing in duration is the mistake that made an earlier routing
 * threshold wrong.
 */
fun estimateAnalysis(
    frames: Int,
    fps: Double,
    metrics: Set<AnalysisMetric>,
    throughput: DeviceThroughput = DeviceThroughput(),
    /** Rough share of a match that ends up inside a rally, for the cutting cost. */
    rallyFraction: Double = TYPICAL_RALLY_FRACTION,
): AnalysisEstimate {
    if (frames <= 0) return AnalysisEstimate(0.0, 0.0)

    var perFrame = throughput.baseMsPerFrame
    if (metrics.any { it.needsPose }) perFrame += throughput.poseMsPerFrame

    var millis = frames * perFrame
    if (AnalysisMetric.RALLY_CLIPS in metrics && fps > 0) {
        // Cutting is priced per second of CLIP, not per frame of video: it
        // re-encodes only the rallies, which is a minority of a match.
        millis += (frames / fps) * rallyFraction * throughput.cutMsPerClipSecond
    }

    return AnalysisEstimate(
        fastSeconds = millis / 1000.0,
        slowSeconds = millis * DeviceThroughput.THROTTLE / 1000.0,
    )
}

/** Measured across the corpus: rallies are well under half of a recording. */
const val TYPICAL_RALLY_FRACTION = 0.35
