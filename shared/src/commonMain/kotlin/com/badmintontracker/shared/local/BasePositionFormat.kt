package com.badmintontracker.shared.local

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.RallyBase
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A court position as a coach would say it: against the player's own service
 * line and the centre line, the two lines a player plays around.
 *
 * "Behind" means toward the player's own baseline, so a positive number is the
 * usual case and "in front of" only appears for a base inside the service box.
 * Left and right are the player's own, facing the net: for the near player the
 * camera's left and right, for the far player, who faces the camera, the
 * mirror of them. Measuring the far player against the near service line put
 * aSASa's far base "7.27 m in front of the service line, 0.88 m right of
 * centre" when he stood 3.52 m behind his own, left of centre.
 *
 * Shared rather than written once per platform: this is a sentence a coach
 * reads on both phones, and a left/right convention or a rounding rule that
 * drifted between them would be a disagreement about the same measurement.
 * The digits come from [metres], which is [metricText]'s own rounding.
 */
fun describeBase(p: Point, side: CourtSide): String {
    val behind: Double
    val across: Double
    if (side == CourtSide.NEAR) {
        behind = p.y - (Court.LENGTH / 2 + Court.SERVICE_LINE)
        across = p.x - Court.WIDTH_DOUBLES / 2
    } else {
        behind = (Court.LENGTH / 2 - Court.SERVICE_LINE) - p.y
        across = Court.WIDTH_DOUBLES / 2 - p.x
    }
    val depth = when {
        abs(behind) < HALF_CM -> "on the service line"
        behind > 0 -> "${metres(behind)} behind the service line"
        else -> "${metres(-behind)} in front of the service line"
    }
    val lateral = when {
        abs(across) < HALF_CM -> "on the centre line"
        across < 0 -> "${metres(-across)} left of centre"
        else -> "${metres(across)} right of centre"
    }
    return "$depth, $lateral"
}

/** The near player's base: what every caller meant before there were two sides. */
fun describeBase(p: Point): String = describeBase(p, CourtSide.NEAR)

/** One list row: the rally, where its base was, and how much of the rally that rests on. */
fun describeRally(base: RallyBase, side: CourtSide): String =
    "Rally ${base.index} · ${describeBase(base.position, side)} · found in ${(base.coverage * 100).toInt()}% of frames"

/** A near player's row. */
fun describeRally(base: RallyBase): String = describeRally(base, CourtSide.NEAR)

/**
 * A rally's dot on the Base panel's canvas, and the size its number measures
 * at there. All values are in the canvas's own drawing units, which are pixels
 * on Android and points on iOS; the placement below never mixes in a unit of
 * its own, so either works as long as one call uses one.
 */
data class RallyMark(val dotX: Float, val dotY: Float, val labelWidth: Float, val labelHeight: Float)

/**
 * Where one rally's number goes: its top-left corner, in the canvas units the
 * marks came in. [mark] is the number's position in the list handed to
 * [placeRallyLabels], not the rally's own index.
 */
data class PlacedLabel(val mark: Int, val left: Float, val top: Float)

/**
 * Where each rally's number is drawn on the Base panel's court, so that every
 * number sits beside its own dot and nowhere else.
 *
 * Each number is tried in eight slots around its dot, in order: right (the
 * default, level with the dot), left, above and below, then the diagonals
 * below-left, below-right, above-left and above-right, each [gap] from the
 * dot's edge (a diagonal slot's nearest corner sits at 45 degrees, the same
 * distance from the dot's centre). A slot is taken when the number stays
 * inside the canvas and keeps at least that same [gap] from every number
 * already placed, from every other dot, and from the whole-match ring,
 * treated as a solid disc because a number inside the ring reads as the
 * ring's. Keeping the gap from other dots
 * as well as from other numbers is what stops a number reading as its
 * neighbour's: it is never closer to another dot than to its own.
 *
 * The marks are placed in the order given, which the panel gives as rally
 * order. The diagonals are what a crowded dot falls back on: on `IMG_5892`
 * a dot 8pt under another, with the ring below it and a number either side,
 * has only its below-left corner free. Only when all eight slots collide is
 * a later rally's number omitted rather than drawn over an earlier one; the
 * list under the court still names every rally, and a number drawn where it
 * collides says less than none.
 *
 * This replaced `rallyLabelX`, which only ever moved a number right and
 * pushed it past the ring whenever its row crossed the ring's, so a dot level
 * with the ring on its left had its number thrown to the ring's far side, and
 * two dots a stride apart drew their numbers over each other.
 *
 * Shared for the reason the sentences above it are: two canvases drawing the
 * same picture must not disagree about it. Neither platform adds placement of
 * its own; each measures its text and draws where this says.
 *
 * @param ringReach the ring's outer edge from its centre, or 0 when there is
 *   no whole-match marker to avoid - a match of one rally has none.
 */
fun placeRallyLabels(
    marks: List<RallyMark>,
    dotRadius: Float,
    gap: Float,
    ringX: Float,
    ringY: Float,
    ringReach: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): List<PlacedLabel> {
    val placed = ArrayList<PlacedLabel>(marks.size)
    marks.forEachIndexed { i, mark ->
        val w = mark.labelWidth
        val h = mark.labelHeight
        val beside = dotRadius + gap
        val corner = beside / SQRT_2
        val slots = listOf(
            mark.dotX + beside to mark.dotY - h / 2,
            mark.dotX - beside - w to mark.dotY - h / 2,
            mark.dotX - w / 2 to mark.dotY - beside - h,
            mark.dotX - w / 2 to mark.dotY + beside,
            mark.dotX - corner - w to mark.dotY + corner,
            mark.dotX + corner to mark.dotY + corner,
            mark.dotX - corner - w to mark.dotY - corner - h,
            mark.dotX + corner to mark.dotY - corner - h,
        )
        val free = slots.firstOrNull { (left, top) ->
            left >= 0f && top >= 0f && left + w <= canvasWidth && top + h <= canvasHeight &&
                (ringReach <= 0f || !boxNear(left, top, w, h, ringX, ringY, ringReach + gap)) &&
                marks.indices.none { j -> j != i && boxNear(left, top, w, h, marks[j].dotX, marks[j].dotY, beside) } &&
                placed.none { other ->
                    val o = marks[other.mark]
                    left < other.left + o.labelWidth + gap - TOUCHING && other.left < left + w + gap - TOUCHING &&
                        top < other.top + o.labelHeight + gap - TOUCHING && other.top < top + h + gap - TOUCHING
                }
        }
        if (free != null) placed.add(PlacedLabel(i, free.first, free.second))
    }
    return placed
}

/**
 * Whether a box comes within [reach] of a point. A box exactly [reach] away
 * is clear: every slot is exactly the gap from its own dot, so a second dot on
 * the same spot must not block the slots the first one's number may use.
 */
private fun boxNear(left: Float, top: Float, w: Float, h: Float, x: Float, y: Float, reach: Float): Boolean {
    val dx = x - x.coerceIn(left, left + w)
    val dy = y - y.coerceIn(top, top + h)
    return sqrt(dx * dx + dy * dy) < reach - TOUCHING
}

/**
 * Float rounding allowance, in canvas units, for "exactly the gap apart":
 * a thousandth of a pixel or point, far under anything the eye can see.
 */
private const val TOUCHING = 1e-3f

private val SQRT_2 = sqrt(2f)

/** Below this the two-decimal string would read "0.00 m", which is not a direction. */
private const val HALF_CM = 0.005

