package com.badmintontracker.analysis

import com.badmintontracker.analysis.compare.compare
import com.badmintontracker.analysis.corpus.CorpusEntry
import com.badmintontracker.analysis.corpus.withCorpus
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.result.AnalysisResult
import com.badmintontracker.analysis.result.VideoMetadata
import com.badmintontracker.analysis.result.serialized
import com.badmintontracker.analysis.rally.padRallyWindows
import com.badmintontracker.analysis.rally.refineRallies
import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

class Phase1PipelineTest {

    /**
     * The one value the corpus cannot supply faithfully is the video duration.
     *
     * The worker pads with ffprobe's container duration
     * (`pad_rally_windows(..., video_duration=probe_video_duration(path))`),
     * and that number is persisted nowhere: `results.json`'s
     * `video_metadata.duration_seconds` and the `videos` row's
     * `results_meta.duration` are both `total_frames / fps`. Substituting
     * that is the closest available value and is what happens below.
     *
     * It only bites through `padRallyWindows`' final `min(clipEnd,
     * videoDuration)` clamp, so at most the LAST clip's end can disagree, and
     * only when the container duration and `total_frames / fps` differ - which
     * they do on variable-frame-rate sources and through metadata rounding.
     * If `clip_windows_match_the_rally_clips_rows_the_cloud_wrote` ever fails
     * on the last clip alone, check that before reading it as a porting bug.
     */
    /**
     * Feed the cloud's own two tracks, do not re-derive them.
     *
     * `results.json`'s `shuttle_positions` is the cloud's FILTERED track, not
     * its raw model output, and the raw TrackNet track is persisted nowhere.
     * Passing it to `runPhase1` filters it a second time: measured on this
     * capture, that drops a further 279 of 605 visible positions in the first
     * 68 seconds, and never keeps one the cloud dropped. The filter is
     * verified separately and directly against the worker's own Python by
     * ShuttleTrackParityTest, over 12,004 positions.
     *
     * So what this compares is everything downstream of filtering: shot
     * detection, both rally detectors, the union, refinement and padding.
     */
    private fun runOn(e: CorpusEntry): Phase1Output = runPhase1FromTracks(
        fusionTrack = e.fusionTrack,
        filteredTrack = e.shuttlePositions,
        fps = e.fps,
        totalFrames = e.totalFrames,
        videoDuration = e.totalFrames / e.fps,
    )

    // ---------------------------------------------------------------------
    // Always-on: properties that hold without any captured data.
    // ---------------------------------------------------------------------

    @Test
    fun an_empty_shuttle_track_produces_no_rallies_and_no_clips() {
        val out = runPhase1(Phase1Input(emptyMap(), 30.0, 1000, 1920, 1080, 33.3, null))
        out.storedRallies shouldBe emptyList()
        out.clipWindows shouldBe emptyList()
    }

    @Test
    fun an_unreadable_frame_rate_is_substituted_rather_than_zeroing_the_output() {
        // fps = 0 reaches every detector's `fps <= 0` guard and would yield no
        // rallies at all. normalizeFps is what stops a bad probe silently
        // costing the user their whole rally list.
        val out = runPhase1(
            Phase1Input(twoExchanges(), 0.0, 600, 1920, 1080, 20.0, squareCourt())
        )
        (out.storedRallies.isNotEmpty()) shouldBe true
    }

    @Test
    fun a_two_exchange_track_yields_two_rallies_with_padded_clips() {
        val out = runPhase1(
            Phase1Input(twoExchanges(), 30.0, 600, 1920, 1080, 20.0, squareCourt())
        )
        out.storedRallies.size shouldBe 2
        out.clipWindows.size shouldBe out.storedRallies.size
        // Padding may only widen: every clip contains its rally's own window.
        out.clipWindows.forEach { w ->
            (w.clipStart <= w.rally.startTimestamp) shouldBe true
            (w.clipEnd >= w.rally.endTimestamp) shouldBe true
        }
    }

    @Test
    fun clip_windows_can_overlap_when_refinement_overlaps() {
        // Worth pinning rather than assuming away. pad_rally_windows documents
        // that "no two clips duplicate rally footage", but that only holds for
        // non-overlapping input, and refine_rallies - its own upstream - can
        // produce overlapping rallies. So two clips CAN cover the same
        // footage. Anything that assumes disjoint clips is wrong on both this
        // port and the cloud.
        val refined = refineRallies(
            listOf(r(1, 10.0, 20.0), r(2, 23.5, 33.0)),
            listOf(r(1, 9.5, 25.0)),
            30.0,
        )
        val w = padRallyWindows(refined, 120.0)
        w.map { it.clipStart to it.clipEnd } shouldBe listOf(7.5 to 23.1, 20.4 to 34.5)
        (w[0].clipEnd > w[1].clipStart) shouldBe true
    }

    // ---------------------------------------------------------------------
    // Golden comparison against recorded cloud output.
    //
    // These skip loudly without a captured corpus, which is every checkout
    // until tools/corpus/fetch_corpus.py has been run. Set
    // ANALYSIS_REQUIRE_CORPUS=1 to make the skip a failure.
    // ---------------------------------------------------------------------

    @Test
    fun the_stored_rally_list_is_close_but_is_not_a_fidelity_check() = withCorpus("sample") { e ->
        // Informational, deliberately. `results.json`'s rally list CANNOT be
        // reproduced from a capture: it unions the gradient detector over the
        // filtered track with the shot-gap detector over PHASE 1's
        // skeleton_frames, and those are never persisted - Phase 2's full YOLO
        // loop overwrites them (modal_supabase_processor.py:4642), and a
        // phase1 capture has none. One input to the stored list is gone.
        //
        // Fidelity is checked by RallyStageParityTest, against the cloud's own
        // detectors on identical inputs. What this records is how close the
        // reconstruction lands anyway, and the shape of the difference: where
        // the two agree on a rally they agree on its bounds to the frame,
        // median start and end delta both 0.0s. The residual is the union
        // merging rallies the stored list kept separate, which is the cloud's
        // own union behaviour applied to a track it did not use.
        val report = compare(
            local = asResult(e, runOn(e).storedRallies),
            cloud = asResult(e, e.cloudRallies),
        )
        report.level2.matched shouldBe 19
        report.level2.medianStartDeltaSeconds shouldBe 0.0
        report.level2.medianEndDeltaSeconds shouldBe 0.0
    }

    @Test
    fun the_filtered_track_is_the_one_the_cloud_stored() = withCorpus("sample") { e ->
        // The comparison consumes the cloud's filtered track as given, so this
        // asserts the fixture is what it claims rather than re-deriving it.
        // Guards against a future change quietly reintroducing the double
        // filtering this test class exists to avoid.
        val out = runOn(e)
        out.filteredTrack.count { it.value.visible } shouldBe
            e.shuttlePositions.count { it.value.visible }
    }

    @Test
    fun both_tracks_are_present_and_differ() = withCorpus("sample") { e ->
        // The fusion track feeds the shot-gap detector and the filtered track
        // feeds the gradient detector. If a fixture ever carried one for both,
        // the comparison would look healthier than it is.
        e.shuttlePositions.count { it.value.visible } shouldBe 3015
        e.fusionTrack.size shouldBe 4355
    }

    // ---------------------------------------------------------------------

    private fun asResult(e: CorpusEntry, rallies: List<Rally>) = AnalysisResult(
        rallies = rallies.map { it.serialized() },
        shuttlePositions = emptyMap(),
        fps = e.fps,
        totalFrames = e.totalFrames,
        videoMetadata = VideoMetadata(e.totalFrames / e.fps, "sample.mp4"),
    )

    private fun r(id: Int, s: Double, end: Double) =
        Rally(id, (s * 30).toInt(), (end * 30).toInt(), s, end, end - s)

    private fun squareCourt() = CourtKeypoints.fromMap(
        mapOf(
            "top_left" to listOf(200.0, 200.0), "top_right" to listOf(1700.0, 200.0),
            "bottom_right" to listOf(1700.0, 900.0), "bottom_left" to listOf(200.0, 900.0),
            "net_left" to listOf(200.0, 550.0), "net_right" to listOf(1700.0, 550.0),
            "service_line_near_left" to listOf(200.0, 430.0),
            "service_line_near_right" to listOf(1700.0, 430.0),
            "service_line_far_left" to listOf(200.0, 670.0),
            "service_line_far_right" to listOf(1700.0, 670.0),
            "center_near" to listOf(950.0, 430.0), "center_far" to listOf(950.0, 670.0),
        )
    )

    /** Two oscillating exchanges separated by five seconds of dead air. */
    private fun twoExchanges(): Map<Int, ShuttleSample> {
        val visible = HashMap<Int, ShuttleSample>()
        fun exchange(start: Int, swings: Int) {
            var f = start
            for (s in 0 until swings) {
                val forward = s % 2 == 0
                for (i in 0 until 24) {
                    val x = if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0
                    visible[f] = ShuttleSample(x, 400.0, visible = true)
                    f++
                }
            }
        }
        exchange(0, 6)
        exchange(300, 6)
        // Built in frame order so the map reads the way a real track does.
        return (0 until 600).associateWith { visible[it] ?: ShuttleSample.INVISIBLE }
    }
}
