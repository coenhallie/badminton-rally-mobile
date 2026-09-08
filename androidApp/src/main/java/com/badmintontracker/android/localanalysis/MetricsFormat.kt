package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.analysis.player.Side
import com.badmintontracker.shared.prefs.RacketArm
import java.util.Locale
import kotlin.math.roundToInt

/** One frame's measurements, stamped with the pose's container timestamp. */
data class MetricSample(val timestamp: Double, val metrics: PoseMetrics)

/** The glyph for a measurement this frame does not have. An en dash, not a hyphen: it must not read as a minus. */
const val ABSENT_METRIC = "–"

/**
 * A measurement as the tile sets it: the number, and the unit that follows it
 * in a smaller face. Joined, the two are [formatMetric].
 *
 * The degree sign stays with the number. It is already a small mark, and set
 * in the unit's face beside a 26 sp numeral it shrinks to a speck that reads
 * as a stray dot; "m" is a letter and takes the smaller face as the mock sets
 * it.
 */
data class MetricText(val value: String, val unit: String)

/**
 * Metres to two decimals, degrees whole. Locale-fixed: a decimal comma on a
 * German phone would make "0,24 m" and "0.24 m" different numbers to the
 * test that pins them.
 */
fun metricText(kind: MetricKind, value: Double?): MetricText = when {
    value == null -> MetricText(ABSENT_METRIC, "")
    kind.isAngle -> MetricText("${value.roundToInt()}°", "")
    else -> MetricText(String.format(Locale.US, "%.2f", value), " m")
}

/** [metricText] as one string, for the places that set it in one face. */
fun formatMetric(kind: MetricKind, value: Double?): String =
    metricText(kind, value).let { it.value + it.unit }

/**
 * The graph's fixed vertical extent, said once in the card's header rather
 * than drawn into the plot: "0.00 – 2.00 m", "0 – 180°". The unit is set once,
 * on the upper bound, as a range is written.
 */
fun metricRangeLabel(kind: MetricKind): String {
    val range = kind.range
    return "${metricText(kind, range.start).value} – ${formatMetric(kind, range.endInclusive)}"
}

/**
 * The tile's label. The side is dropped from the arm kinds once the racket
 * arm is chosen, because then there is only one "Elbow" on screen; the knees
 * keep theirs, both legs matter in a lunge whichever arm serves.
 */
fun metricLabel(kind: MetricKind, racketArm: RacketArm?): String {
    val armChosen = racketArm != null
    fun sided(base: String, side: Side?, dropWhenChosen: Boolean): String = when {
        side == null -> base
        dropWhenChosen && armChosen -> base
        side == Side.LEFT -> "$base L"
        else -> "$base R"
    }
    return when (kind) {
        MetricKind.STANCE -> "Stance"
        MetricKind.BEHIND_LINE -> "Behind line"
        MetricKind.ELBOW_LEFT, MetricKind.ELBOW_RIGHT -> sided("Elbow", kind.side, dropWhenChosen = true)
        MetricKind.ARM_LEFT, MetricKind.ARM_RIGHT -> sided("Arm", kind.side, dropWhenChosen = true)
        MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT -> sided("Knee", kind.side, dropWhenChosen = false)
        MetricKind.LEAN -> "Lean"
        MetricKind.SHOULDERS -> "Shoulders"
        MetricKind.HIPS -> "Hips"
    }
}

/** Which tiles to show, in order: court-plane first, then the racket arm's kinds, both knees, the lean and the two tilts. */
fun visibleKinds(hasCourt: Boolean, racketArm: RacketArm?): List<MetricKind> =
    MetricKind.entries.filter { kind ->
        when {
            kind.needsCourt -> hasCourt
            kind == MetricKind.KNEE_LEFT || kind == MetricKind.KNEE_RIGHT || kind.side == null -> true
            racketArm == null -> true
            else -> (kind.side == Side.LEFT) == (racketArm == RacketArm.LEFT)
        }
    }
