package com.badmintontracker.analysis

import com.badmintontracker.analysis.corpus.CorpusEntry
import com.badmintontracker.analysis.corpus.withCorpus
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.rally.detectRalliesFromShots
import com.badmintontracker.analysis.rally.detectRalliesGradient
import com.badmintontracker.analysis.rally.refineRallies
import com.badmintontracker.analysis.rally.unionRallies
import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The port against badminton-tracker's own detectors, on real captured data,
 * stage by stage.
 *
 * Why stages and not `results.json`'s rally list: that list cannot be
 * reproduced from a capture. It is the union of the gradient detector over the
 * filtered track with the shot-gap detector over PHASE 1's `skeleton_frames`,
 * and Phase 1's skeleton frames are never persisted - a `completed` capture
 * carries Phase 2's, written by the full YOLO loop
 * (modal_supabase_processor.py:4642), and a `phase1` capture carries none. One
 * input to the stored list is simply gone by the time anyone reads it.
 *
 * That is why an earlier comparison against `results.json` looked like a
 * welding bug: on the `sample` capture the stored list is 26 rallies while the
 * union of the reconstructed tracks is 20. Running the cloud's own Python on
 * those same reconstructed tracks also gives 20. The union is not wrong; the
 * comparison was.
 *
 * Checking every stage against the cloud's own code on identical inputs is
 * both determinate and stronger than one end number: it localises any future
 * divergence to a single detector instead of leaving it to be bisected.
 *
 * Goldens are regenerated with tools/corpus/make_stage_goldens.py.
 */
class RallyStageParityTest {

    private fun stages(e: CorpusEntry): Map<String, List<Pair<Int, Int>>> {
        val fps = e.fps
        val total = e.totalFrames

        // Built exactly as Phase1Pipeline builds them, and identically on the
        // Python side, so this tests the port rather than the timestamp source.
        fun frames(track: Map<Int, ShuttleSample>) = (0 until total).map { f ->
            FrameSample(f, f / fps, track[f]?.takeIf { it.visible })
        }

        val gradient = detectRalliesGradient(e.shuttlePositions, fps, total)
        val raw = detectRalliesFromShots(frames(e.fusionTrack), fps)
        val filtered = detectRalliesFromShots(frames(e.shuttlePositions), fps)
        val union = unionRallies(gradient, raw, fps)
        val refined = refineRallies(filtered, raw, fps).ifEmpty { union }

        fun List<Rally>.bounds() = map { it.startFrame to it.endFrame }
        return mapOf(
            "gradient" to gradient.bounds(),
            "raw_shot_gap" to raw.bounds(),
            "filtered_shot_gap" to filtered.bounds(),
            "union" to union.bounds(),
            "refined" to refined.bounds(),
        )
    }

    private fun check(name: String) = withCorpus(name) { e ->
        val expected = e.stages
        // All five stages, not "some". A goldens file regenerated against a
        // partial run would otherwise narrow what this checks without failing.
        expected.keys shouldBe setOf(
            "gradient", "raw_shot_gap", "filtered_shot_gap", "union", "refined",
        )
        // And it must say which badminton-tracker produced it. An
        // unattributed golden cannot be told apart from one re-baselined
        // against a modified checkout, which is the difference between a
        // golden and a copy of the current output.
        (e.stagesTrackerCommit?.length == 40) shouldBe true
        val actual = stages(e)
        // Every stage, compared as full bound lists rather than counts: two
        // detectors can agree on how many rallies there are and disagree about
        // where every one of them starts.
        expected.keys.forEach { stage ->
            actual[stage] shouldBe expected[stage]
        }
    }

    @Test
    fun every_stage_matches_the_python_at_25fps() = check("sample")

    @Test
    fun every_stage_matches_the_python_at_50fps() = check("0a654e34")

    @Test
    fun every_stage_matches_the_python_at_a_fractional_frame_rate() = check("743d7fb1")
}
