package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import kotlin.math.abs

/**
 * Where the camera was at one moment: [referenceToFrame] maps court-marking
 * frame pixels to this frame's pixels, both in decoded source pixels, with
 * `h22 == 1.0`.
 */
data class CameraSample(val timestamp: Double, val referenceToFrame: Matrix3x3)

/**
 * The camera over a whole video, as the tracker sampled it. Design §4.4.
 *
 * Build through [of], which sorts and drops duplicate timestamps.
 */
data class CameraMotion(val referenceTimestamp: Double, val samples: List<CameraSample>) {
    init {
        for (i in 1 until samples.size) {
            require(samples[i].timestamp > samples[i - 1].timestamp) { "samples must be sorted and distinct" }
        }
    }

    /**
     * The camera at [timestamp], or null when it is not known there. In order:
     * an exact sample; a blend of two neighbours no more than
     * [MAX_BLEND_GAP_S] apart; the nearest sample within [MAX_HOLD_S]; null.
     */
    fun at(timestamp: Double): Matrix3x3? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].timestamp < timestamp) lo = mid + 1 else hi = mid
        }
        val after = samples[lo].takeIf { it.timestamp >= timestamp }
        val before = if (after == null) samples[lo] else samples.getOrNull(lo - 1)
        if (after != null && abs(after.timestamp - timestamp) <= EXACT_S) return after.referenceToFrame
        if (before != null && abs(before.timestamp - timestamp) <= EXACT_S) return before.referenceToFrame
        if (before != null && after != null && after.timestamp - before.timestamp <= MAX_BLEND_GAP_S) {
            val w = (timestamp - before.timestamp) / (after.timestamp - before.timestamp)
            return blend(before.referenceToFrame, after.referenceToFrame, w)
        }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        return if (abs(nearest.timestamp - timestamp) <= MAX_HOLD_S) nearest.referenceToFrame else null
    }

    private fun blend(a: Matrix3x3, b: Matrix3x3, w: Double): Matrix3x3 {
        val m = List(3) { r -> List(3) { c -> a[r][c] * (1 - w) + b[r][c] * w } }
        val s = m[2][2]
        return List(3) { r -> List(3) { c -> if (r == 2 && c == 2) 1.0 else m[r][c] / s } }
    }

    companion object {
        const val EXACT_S = 1e-6
        const val MAX_BLEND_GAP_S = 0.5
        const val MAX_HOLD_S = 0.15

        /** Sorted by timestamp; where two share one (within [EXACT_S]), the first is kept. */
        fun of(referenceTimestamp: Double, samples: List<CameraSample>): CameraMotion {
            val sorted = samples.withIndex().sortedWith(compareBy({ it.value.timestamp }, { it.index })).map { it.value }
            val kept = ArrayList<CameraSample>()
            for (s in sorted) if (kept.isEmpty() || s.timestamp - kept.last().timestamp > EXACT_S) kept.add(s)
            return CameraMotion(referenceTimestamp, kept)
        }
    }
}
