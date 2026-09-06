package com.badmintontracker.analysis.rally

import kotlin.math.max
import kotlin.math.min

/**
 * Clip padding, in seconds.
 *
 * Detected rally bounds are first-shot to last-shot contact, and both ends cut
 * off footage the viewer needs. A serve is not a direction reversal, so the
 * first detected shot is the RETURN of serve and the serve is always outside
 * the window. The window ends at the last contact, so the shuttle is still
 * airborne and the outcome is never on screen.
 */
const val CLIP_PRE_ROLL_S: Double = 2.0
const val CLIP_POST_ROLL_S: Double = 1.5

data class ClipWindow(val rally: Rally, val clipStart: Double, val clipEnd: Double) {
    val durationSeconds: Double get() = clipEnd - clipStart
}

/**
 * Widen each rally by pre and post roll.
 *
 * Padding may only consume dead air BETWEEN rallies: it never reaches into a
 * neighbour's detected window, so no two clips duplicate footage. It also never
 * shrinks a window below what was detected, which matters when the incoming
 * rallies themselves overlap - and `refineRallies` does produce overlapping
 * rallies, so this is a live case rather than a defensive one. Returns a new
 * list sorted by start; inputs are not mutated.
 *
 * Padding applies at cut time only. The analytical rally bounds are unchanged,
 * so metrics are unaffected; the clip row stores the padded bounds because
 * those describe the file the apps actually play.
 */
fun padRallyWindows(
    rallies: List<Rally>,
    videoDuration: Double?,
    preRoll: Double = CLIP_PRE_ROLL_S,
    postRoll: Double = CLIP_POST_ROLL_S,
): List<ClipWindow> {
    val ordered = rallies.sortedBy { it.startTimestamp }
    return ordered.mapIndexed { i, r ->
        var clipStart = r.startTimestamp - preRoll
        var clipEnd = r.endTimestamp + postRoll

        if (i > 0) clipStart = max(clipStart, ordered[i - 1].endTimestamp)
        if (i + 1 < ordered.size) clipEnd = min(clipEnd, ordered[i + 1].startTimestamp)

        clipStart = max(0.0, min(clipStart, r.startTimestamp))
        clipEnd = max(clipEnd, r.endTimestamp)
        if (videoDuration != null && videoDuration > 0) clipEnd = min(clipEnd, videoDuration)

        ClipWindow(r, clipStart, clipEnd)
    }
}
