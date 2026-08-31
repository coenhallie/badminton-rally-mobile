package com.badmintontracker.analysis

/** Default frame rate assumed when a source reports none. Mirrors the cloud worker. */
const val DEFAULT_FPS: Double = 30.0

data class FpsResult(val fps: Double, val substituted: Boolean)

/**
 * Coerce a probed frame rate to something usable.
 *
 * Port of `normalize_fps` in the cloud worker. An unusable rate is quietly
 * destructive in two ways: every rally detector bails on its `fps <= 0` guard,
 * and the speed loop divides by it. Substituting loudly is better than either.
 */
fun normalizeFps(value: Double?, default: Double = DEFAULT_FPS): FpsResult {
    if (value == null || !value.isFinite() || value <= 0.0) {
        return FpsResult(default, substituted = true)
    }
    return FpsResult(value, substituted = false)
}
