package com.badmintontracker.shared.playback

/**
 * The video card's time chip: "0:03 / 0:40", the position against the length,
 * or the position alone ("0:03") until the player knows the length. The length
 * is what tells a coach how much of a rally is left, and the three-pixel
 * progress bar under the frame was the only other place that said it.
 *
 * Shared so the two platforms' chips read alike.
 */
fun playbackClock(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "${clock(positionMs)} / ${clock(durationMs)}" else clock(positionMs)

/** "0:48", "12:05": minutes unpadded, seconds two digits, as the card's chip always printed it. */
private fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
