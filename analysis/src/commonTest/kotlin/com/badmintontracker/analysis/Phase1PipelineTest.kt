package com.badmintontracker.analysis

import com.badmintontracker.analysis.corpus.CorpusEntry
import com.badmintontracker.analysis.corpus.withCorpus
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.Rally
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
    private fun runOn(e: CorpusEntry): Phase1Output = runPhase1(
        Phase1Input(
            rawShuttle = e.shuttlePositions,
            fps = e.fps,
            totalFrames = e.totalFrames,
            videoWidth = e.videoWidth,
            videoHeight = e.videoHeight,
            videoDuration = e.totalFrames / e.fps,
            keypoints = e.keypoints,
        )
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
    fun the_stored_rally_count_matches_the_cloud() = withCorpus("sample") { e ->
        runOn(e).storedRallies.size shouldBe e.cloudRallies.size
    }

    @Test
    fun every_stored_rally_lines_up_with_a_cloud_rally() = withCorpus("sample") { e ->
        // Bounds are compared with a tolerance of one frame. Exact equality
        // would be the wrong assertion: the cloud's timestamps come from
        // container PTS and the fixture's from the same source, but the
        // gradient detector derives its own from frame/fps, so sub-frame
        // disagreement is expected and harmless.
        val tolerance = 1.0 / e.fps
        runOn(e).storedRallies.zip(e.cloudRallies.sortedBy { it.startTimestamp })
            .forEach { (mine, theirs) ->
                (abs(mine.startTimestamp - theirs.startTimestamp) <= tolerance) shouldBe true
                (abs(mine.endTimestamp - theirs.endTimestamp) <= tolerance) shouldBe true
            }
    }

    @Test
    fun clip_windows_match_the_rally_clips_rows_the_cloud_wrote() = withCorpus("sample") { e ->
        // rally_clips stores PADDED bounds, which describe the file the apps
        // play. This is the assertion that proves local clips would be cut at
        // the same offsets, which is what makes annotations portable.
        if (e.cloudClips.isEmpty()) return@withCorpus
        val out = runOn(e)
        out.clipWindows.size shouldBe e.cloudClips.size
        out.clipWindows.zip(e.cloudClips.sortedBy { it.rallyIndex }).forEach { (mine, theirs) ->
            (abs(mine.clipStart - theirs.startTimestamp) <= 0.1) shouldBe true
            (abs(mine.clipEnd - theirs.endTimestamp) <= 0.1) shouldBe true
        }
    }

    @Test
    fun the_filtered_track_matches_the_visibility_the_cloud_stored() = withCorpus("sample") { e ->
        // results.json stores the FILTERED track, so this compares like with
        // like. It is also the first thing to look at when a rally count
        // disagrees: a visibility mismatch here explains a boundary mismatch
        // downstream, and a match here rules the track out as the cause.
        val out = runOn(e)
        val mismatches = e.shuttlePositions.count { (frame, cloud) ->
            (out.filteredTrack[frame]?.visible ?: false) != cloud.visible
        }
        mismatches shouldBe 0
    }

    // ---------------------------------------------------------------------

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
