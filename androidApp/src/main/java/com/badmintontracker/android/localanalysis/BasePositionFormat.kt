package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.RallyBase
import java.util.Locale
import kotlin.math.abs

/**
 * A court position as a coach would say it: against the near service line and
 * the centre line, which are the two lines the near player plays around.
 *
 * "Behind" means toward the near baseline, so a positive number is the usual
 * case and "in front of" only appears for a base inside the service box.
 * Left and right are the player's own, facing the net, which for the near
 * player is also the camera's left and right.
 *
 * Locale-fixed for the same reason as `formatMetric`: the test pins the digits.
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

private fun metres(m: Double): String = String.format(Locale.US, "%.2f m", m)

/** Below this the two-decimal string would read "0.00 m", which is not a direction. */
private const val HALF_CM = 0.005
