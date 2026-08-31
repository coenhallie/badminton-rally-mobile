package com.badmintontracker.analysis.rally

import io.kotest.matchers.shouldBe
import kotlin.test.Test

internal fun rally(id: Int, start: Double, end: Double, fps: Double = 30.0) = Rally(
    id = id,
    startFrame = (start * fps).toInt(),
    endFrame = (end * fps).toInt(),
    startTimestamp = start,
    endTimestamp = end,
    durationSeconds = end - start,
)

class RallyCombinationTest {

    @Test
    fun union_merges_two_detections_of_the_same_rally() {
        val a = listOf(rally(1, 10.0, 20.0))
        val b = listOf(rally(1, 10.5, 21.0))
        val u = unionRallies(a, b, 30.0)
        u.size shouldBe 1
        u[0].startTimestamp shouldBe 10.0
        u[0].endTimestamp shouldBe 21.0
    }

    @Test
    fun union_keeps_rallies_that_do_not_overlap_enough() {
        val u = unionRallies(listOf(rally(1, 10.0, 20.0)), listOf(rally(1, 40.0, 50.0)), 30.0)
        u.size shouldBe 2
        u.map { it.id } shouldBe listOf(1, 2)
    }

    @Test
    fun refine_widens_toward_the_raw_bounds_but_never_past_a_neighbour() {
        // The filtered track's splits are trustworthy but its tails are
        // over-trimmed; the raw track has better bounds but fabricates
        // rallies. Refinement takes the list from one and the edges from
        // the other, without letting a clip reach into its neighbour.
        //
        // Exact values from rally_detection_shot_gap.refine_rallies on this
        // input. Note what the second rally does: the same raw rally overlaps
        // it too, so it widens BACKWARDS to 20.4 and ends up overlapping the
        // first rally's refined end of 23.1. That is the source's behaviour,
        // not a porting slip - the neighbour clamp reads the ORIGINAL
        // filtered bounds, never the refined ones, so refinement can and does
        // produce overlapping windows. Anything consuming these downstream
        // has to expect that.
        val filtered = listOf(rally(1, 10.0, 20.0), rally(2, 23.5, 33.0))
        val raw = listOf(rally(1, 9.5, 25.0))
        val r = refineRallies(filtered, raw, 30.0)
        r[0].startTimestamp shouldBe 9.5
        r[0].endTimestamp shouldBe 23.1
        r[1].startTimestamp shouldBe 20.4
        r[1].endTimestamp shouldBe 33.0
    }

    @Test
    fun refine_drops_raw_rallies_that_overlap_nothing() {
        val filtered = listOf(rally(1, 10.0, 20.0))
        val raw = listOf(rally(1, 40.0, 50.0))
        val r = refineRallies(filtered, raw, 30.0)
        r.size shouldBe 1
        r[0].startTimestamp shouldBe 10.0
    }
}
