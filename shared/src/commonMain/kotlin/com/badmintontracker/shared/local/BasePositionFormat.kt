package com.badmintontracker.shared.local

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.RallyBase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A court position as a coach would say it: against the near service line and
 * the centre line, which are the two lines the near player plays around.
 *
 * "Behind" means toward the near baseline, so a positive number is the usual
 * case and "in front of" only appears for a base inside the service box.
 * Left and right are the player's own, facing the net, which for the near
 * player is also the camera's left and right.
 *
 * Shared rather than written once per platform: this is a sentence a coach
 * reads on both phones, and a left/right convention or a rounding rule that
 * drifted between them would be a disagreement about the same measurement.
 * The digits come from [metres], which is [metricText]'s own rounding.
 */
fun describeBase(p: Point): String {
    val behind = p.y - (Court.LENGTH / 2 + Court.SERVICE_LINE)
    val across = p.x - Court.WIDTH_DOUBLES / 2
    val depth = when {
        abs(behind) < HALF_CM -> "on the service line"
        behind > 0 -> "${metres(behind)} behind the service line"
        else -> "${metres(-behind)} in front of the service line"
    }
    val side = when {
        abs(across) < HALF_CM -> "on the centre line"
        across < 0 -> "${metres(-across)} left of centre"
        else -> "${metres(across)} right of centre"
    }
    return "$depth, $side"
}

/** One list row: the rally, where its base was, and how much of the rally that rests on. */
fun describeRally(base: RallyBase): String =
    "Rally ${base.index} · ${describeBase(base.position)} · found in ${(base.coverage * 100).toInt()}% of frames"

/**
 * Where a rally's number is drawn, in x, so it clears its own dot and the
 * whole-match ring both.
 *
 * The number used to be placed at the dot's centre plus the RING's radius on
 * both platforms, which clears the dot but says nothing about where the ring
 * is: a rally whose base sits within a radius of the whole-match marker had
 * its number drawn inside a ring centred on a different point, reading as that
 * ring's label rather than as the dot's. Now the offset is the dot's own, and
 * the ring only pushes a number out when that number's row actually crosses
 * it - so most labels sit closer to their dot than before, and none sits in
 * the ring.
 *
 * Shared for the reason the sentences above it are: two canvases drawing the
 * same picture must not disagree about it. All values are in the canvas's own
 * units, which are density pixels on Android and points on iOS.
 *
 * @param ringReach the ring's outer edge from its centre, or 0 when there is
 *   no whole-match marker to avoid - a match of one rally has none.
 */
fun rallyLabelX(
    dotX: Float,
    dotY: Float,
    dotRadius: Float,
    gap: Float,
    ringX: Float,
    ringY: Float,
    ringReach: Float,
): Float {
    val besideTheDot = dotX + dotRadius + gap
    val dy = abs(dotY - ringY)
    if (ringReach <= 0f || dy >= ringReach) return besideTheDot
    // Half the ring's horizontal chord at this row, so a label level with the
    // ring's centre is pushed the full radius and one just inside its top
    // edge is barely pushed at all.
    val halfChord = sqrt(ringReach * ringReach - dy * dy)
    return max(besideTheDot, ringX + halfChord + gap)
}

/** Below this the two-decimal string would read "0.00 m", which is not a direction. */
private const val HALF_CM = 0.005
