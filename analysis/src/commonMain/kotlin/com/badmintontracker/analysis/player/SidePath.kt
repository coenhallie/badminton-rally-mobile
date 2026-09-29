package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One person on one side of the net in one frame, who passed the gates.
 *
 * [outsideM] is how far their ground point lies outside that half's court
 * lines, in metres (0 inside): [outsideLinesM].
 */
data class SideCandidate(
    val frame: Int,
    val court: Point,
    val confidence: Float,
    val outsideM: Double,
)

/**
 * How far a court position lies outside a half's lines, in metres; 0 inside.
 *
 * FAR is the half from the top baseline (y = 0) to the net, NEAR the half from
 * the net to the bottom baseline, both the doubles width wide.
 */
fun outsideLinesM(court: Point, side: CourtSide): Double {
    val (lo, hi) = if (side == CourtSide.FAR) 0.0 to Court.LENGTH / 2 else Court.LENGTH / 2 to Court.LENGTH
    return maxOf(0.0, -court.x, court.x - Court.WIDTH_DOUBLES, lo - court.y, court.y - hi)
}

/**
 * Which of each frame's candidates is this side's player: one path over the
 * whole run (design `docs/plans/2026-09-29-two-player-tracking-design.md` §5).
 *
 * Deciding frame by frame, the most confident person on a side wins every
 * frame they are seen, whoever they are. On the corpus that handed the far
 * side to the near player whenever she walked to the net with her ankles
 * across it (IMG_5892, five frames the audit confirmed), and, given every
 * detection, to an umpire standing a metre off the court. A path remembers
 * who the side's player was a moment ago.
 *
 * Costs, lowest total wins:
 *
 *  - every chosen candidate earns [REWARD], less than `-ln(confidence)`, plus
 *    [OUTSIDE_PER_M] for each metre it stands further outside the lines than
 *    the frame's least-outside candidate. Relative, so a candidate with
 *    nobody competing pays nothing for where it stands: the gates already
 *    judged whether that is on the court, and a lone candidate is kept
 *    exactly as the per-frame pick kept it. With a player inside the lines
 *    beside them, an umpire a metre out pays for that metre on every frame;
 *  - joining two candidates up to [MAX_SKIP_S] apart costs [SKIP_PER_FRAME]
 *    for each frame skipped plus [MOVE_PER_M] per metre, and is impossible
 *    further than [MAX_SPEED_MPS] allows plus [NOISE_M]. The allowance is
 *    there because a far player's ground point moves a metre between frames
 *    when a foot lifts; a jump to another person is several metres;
 *  - a path starts free, and picking up again after an earlier piece costs
 *    [RESTART], so the path can be several stretches when the player was
 *    lost for longer than a skip allows.
 *
 * With one candidate per frame moving plausibly, every frame is chosen: the
 * per-frame pick's result. Returns, per input frame, the index of the chosen
 * candidate, or -1.
 */
fun chooseSidePath(frames: List<List<SideCandidate>>, fps: Double): IntArray {
    val chosen = IntArray(frames.size) { -1 }
    if (frames.isEmpty()) return chosen
    val maxSkipFrames = max(1, (MAX_SKIP_S * fps).roundToInt())
    val best = frames.map { DoubleArray(it.size) }
    // The node each node was reached from: an earlier continuation or, after
    // a restart, the cheapest path's last node. -1 for a path's first node.
    val fromFrame = frames.map { IntArray(it.size) { -1 } }
    val fromIndex = frames.map { IntArray(it.size) { -1 } }
    val seen = ArrayList<Int>()
    var bestCost = 0.0
    var bestFrame = -1
    var bestIndex = -1

    for (i in frames.indices) {
        val candidates = frames[i]
        if (candidates.isEmpty()) continue
        val leastOutside = candidates.minOf { it.outsideM }
        for ((j, c) in candidates.withIndex()) {
            val own = -REWARD - ln(max(c.confidence.toDouble(), 1e-3)) +
                OUTSIDE_PER_M * (c.outsideM - leastOutside)
            // A path's first node starts free; picking up again after an
            // earlier piece costs RESTART and keeps that piece.
            var choice = 0.0
            var pf = -1
            var pi = -1
            if (bestFrame >= 0 && bestCost + RESTART < choice) {
                choice = bestCost + RESTART
                pf = bestFrame
                pi = bestIndex
            }
            for (s in seen.indices.reversed()) {
                val k = seen[s]
                val gap = c.frame - frames[k][0].frame
                if (gap > maxSkipFrames) break
                for ((q, prev) in frames[k].withIndex()) {
                    val d = hypot(c.court.x - prev.court.x, c.court.y - prev.court.y)
                    if (d > MAX_SPEED_MPS * gap / fps + NOISE_M) continue
                    val cost = best[k][q] + SKIP_PER_FRAME * (gap - 1) + MOVE_PER_M * d
                    if (cost < choice) {
                        choice = cost
                        pf = k
                        pi = q
                    }
                }
            }
            best[i][j] = own + choice
            fromFrame[i][j] = pf
            fromIndex[i][j] = pi
        }
        seen.add(i)
        for (j in candidates.indices) {
            if (best[i][j] < bestCost) {
                bestCost = best[i][j]
                bestFrame = i
                bestIndex = j
            }
        }
    }

    var f = bestFrame
    var j = bestIndex
    while (f >= 0) {
        chosen[f] = j
        val pf = fromFrame[f][j]
        val pj = fromIndex[f][j]
        f = pf
        j = pj
    }
    return chosen
}

/**
 * Frames of [own] that are the other side's player, reaching across the net.
 *
 * The per-side path cannot see this on its own: when the far player is
 * hidden behind the near player at the net, the near player's ankles
 * crossing the net line make her the only person on the far side, and a few
 * frames of her are a cheaper path piece than none (IMG_5892 frames
 * 1376-1380, audited). What gives her away is where the piece starts or
 * ends: exactly where the other side's track just left her, or where it
 * picks her up again.
 *
 * So a piece of [own] (samples joined within [MAX_SKIP_S] and within reach
 * at [MAX_SPEED_MPS] plus [NOISE_M]) is the other player's when it lasts no
 * longer than [MAX_CROSSING_S], the [other] side has no sample during it,
 * and it starts within reach of [other]'s last sample before it or ends
 * within reach of [other]'s first sample after it. The length bound is what
 * keeps a genuine player re-found at the net beside the other one: a piece
 * of their own lasts longer than a walk across the net line.
 */
fun crossedFrom(own: List<PlayerSample>, other: List<PlayerSample>, fps: Double): Set<Int> {
    if (own.isEmpty() || other.isEmpty()) return emptySet()
    val maxSkipFrames = max(1, (MAX_SKIP_S * fps).roundToInt())
    fun reach(gap: Int) = MAX_SPEED_MPS * gap / fps + NOISE_M
    fun dist(a: PlayerSample, b: PlayerSample) =
        hypot(a.courtPosition.x - b.courtPosition.x, a.courtPosition.y - b.courtPosition.y)

    val sortedOther = other.sortedBy { it.frame }
    val pieces = ArrayList<MutableList<PlayerSample>>()
    for (sample in own.sortedBy { it.frame }) {
        val last = pieces.lastOrNull()?.last()
        val gap = if (last == null) Int.MAX_VALUE else sample.frame - last.frame
        if (last == null || gap > maxSkipFrames || dist(last, sample) > reach(gap)) {
            pieces.add(mutableListOf(sample))
        } else {
            pieces.last().add(sample)
        }
    }

    val crossed = HashSet<Int>()
    for (piece in pieces) {
        val first = piece.first()
        val last = piece.last()
        if ((last.frame - first.frame + 1) / fps > MAX_CROSSING_S) continue
        if (sortedOther.any { it.frame in first.frame..last.frame }) continue
        val before = sortedOther.lastOrNull { it.frame < first.frame && first.frame - it.frame <= maxSkipFrames }
        val after = sortedOther.firstOrNull { it.frame > last.frame && it.frame - last.frame <= maxSkipFrames }
        val fromOther = before != null && dist(before, first) <= reach(first.frame - before.frame)
        val toOther = after != null && dist(last, after) <= reach(after.frame - last.frame)
        if (fromOther || toOther) piece.forEach { crossed.add(it.frame) }
    }
    return crossed
}

const val REWARD = 5.0
const val OUTSIDE_PER_M = 3.0
const val SKIP_PER_FRAME = 0.5
const val MOVE_PER_M = 1.0
const val RESTART = 10.0
const val MAX_SKIP_S = 0.5
const val MAX_SPEED_MPS = 8.0
const val NOISE_M = 1.0
const val MAX_CROSSING_S = 1.0
