package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.BodySide
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.shared.prefs.RacketArm
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
 * Metres to two decimals, degrees whole.
 *
 * The decimal point is written out rather than taken from a platform
 * formatter, for two reasons that pull the same way: a decimal comma on a
 * German phone would make "0,24 m" and "0.24 m" different numbers to the test
 * that pins them, and `String.format` does not exist in commonMain at all.
 *
 * The magnitude is what rounds, and the sign is put back afterwards, which is
 * `%.2f`'s HALF_UP rather than [roundToInt]'s ties-toward-positive-infinity.
 * They differ only on an exact half - and only for negatives, where ties
 * toward positive infinity would round -0.125 m to -0.12 and 0.125 m to 0.13,
 * so the same measurement in front of and behind the service line would round
 * two different ways.
 */
fun metricText(kind: MetricKind, value: Double?): MetricText = when {
    value == null -> MetricText(ABSENT_METRIC, "")
    // Whole degrees keep [roundToInt] because that is what this has always
    // done and what the committed expectations record: -7.6 is "-8".
    kind.isAngle -> MetricText("${value.roundToInt()}°", "")
    else -> MetricText(twoDecimals(value), " m")
}

/** The magnitude to two decimals with the sign restored - `%.2f`'s HALF_UP. */
private fun twoDecimals(value: Double): String {
    val hundredths = (abs(value) * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0')
    // "-0.00" for a value just under zero is deliberate and is what %.2f
    // prints: the sign is a fact about the measurement, not about the digits.
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.$fraction"
}

/**
 * A distance in metres, two decimals, as [metricText] sets one.
 *
 * Shared with it rather than written again beside it, because the rounding is
 * the interesting part: [twoDecimals] rounds the magnitude, and the two places
 * that print a signed distance from a court line are exactly where ties toward
 * positive infinity would round the same measurement two different ways either
 * side of that line.
 */
fun metres(m: Double): String = "${twoDecimals(m)} m"

/** [metricText] as one string, for the places that set it in one face. */
fun formatMetric(kind: MetricKind, value: Double?): String =
    metricText(kind, value).let { it.value + it.unit }

/**
 * The graph's fixed vertical extent, said once in the card's header rather
 * than drawn into the plot: "0.00 – 2.00 m", "0 – 180°". The unit is set once,
 * on the upper bound, as a range is written.
 */
fun metricRangeLabel(kind: MetricKind): String =
    "${metricText(kind, kind.rangeStart).value} – ${formatMetric(kind, kind.rangeEnd)}"

/**
 * The tile's label. The side is dropped from the arm kinds once the racket
 * arm is chosen, because then there is only one "Elbow" on screen; the knees
 * keep theirs, both legs matter in a lunge whichever arm serves.
 */
fun metricLabel(kind: MetricKind, racketArm: RacketArm?): String {
    val armChosen = racketArm != null
    fun sided(base: String, side: BodySide?, dropWhenChosen: Boolean): String = when {
        side == null -> base
        dropWhenChosen && armChosen -> base
        side == BodySide.LEFT -> "$base L"
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
            else -> (kind.side == BodySide.LEFT) == (racketArm == RacketArm.LEFT)
        }
    }

/**
 * One plotted sample: when it was measured and what it measured. Not a `Pair`,
 * which reaches Swift as a `KotlinPair` whose members are `id` and would need
 * unwrapping at every point of every draw loop.
 */
data class GraphPoint(val timestamp: Double, val value: Double)

/**
 * What to draw for one metric over one window: a polyline per run of samples
 * that are present and close together, and a point per sample that joins
 * neither neighbour. Coordinates are (timestamp, value), not pixels.
 */
data class GraphSegments(
    val polylines: List<List<GraphPoint>>,
    val points: List<GraphPoint>,
) {
    companion object {
        val EMPTY = GraphSegments(emptyList(), emptyList())
    }
}

/**
 * What to draw for [kind] between [startS] and [endS].
 *
 * The walk starts one sample before the window and ends one sample after it,
 * so a run that straddles an edge is drawn as a line crossing that edge
 * instead of stopping short of it; the caller clips to the box. Values are
 * never coerced into the kind's range: a clipped line says the value left the
 * range, a coerced one would say it sat on the boundary.
 *
 * A lone sample becomes a point only when it is inside the window. One that
 * falls outside it is there to anchor a crossing line, and drawing it as a
 * point would put a mark outside the window the caller asked for.
 */
fun graphSegments(
    series: List<MetricSample>,
    kind: MetricKind,
    startS: Double,
    endS: Double,
    gapS: Double,
): GraphSegments {
    if (series.isEmpty()) return GraphSegments.EMPTY

    // First sample at or after the window's left edge.
    var lo = 0
    var hi = series.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (series[mid].timestamp < startS) lo = mid + 1 else hi = mid
    }

    val polylines = mutableListOf<List<GraphPoint>>()
    val points = mutableListOf<GraphPoint>()
    var run = mutableListOf<GraphPoint>()

    fun flush() {
        if (run.size >= 2) {
            polylines += run.toList()
        } else if (run.size == 1) {
            val point = run[0]
            if (point.timestamp >= startS && point.timestamp <= endS) points += point
        }
        run = mutableListOf()
    }

    var i = maxOf(0, lo - 1)
    var prev: MetricSample? = null
    while (i < series.size) {
        val s = series[i]
        val value = kind.of(s.metrics)
        val prevValue = prev?.let { kind.of(it.metrics) }
        val joinsPrev = value != null && prevValue != null && s.timestamp - prev.timestamp <= gapS
        if (value == null) {
            flush()
        } else {
            if (!joinsPrev) flush()
            run += GraphPoint(s.timestamp, value)
        }
        prev = s
        // Consume exactly one sample past the right edge, then stop.
        if (s.timestamp > endS) break
        i++
    }
    flush()

    return GraphSegments(polylines, points)
}
