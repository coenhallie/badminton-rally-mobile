package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawShuttle
import com.badmintontracker.shared.model.CourtKeypoints
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class LocalAnalysisCoordinatorTest {

    private val keypoints = CourtKeypoints(
        topLeft = listOf(200f, 200f), topRight = listOf(1700f, 200f),
        bottomRight = listOf(1700f, 900f), bottomLeft = listOf(200f, 900f),
        netLeft = listOf(200f, 550f), netRight = listOf(1700f, 550f),
        serviceLineNearLeft = listOf(200f, 430f), serviceLineNearRight = listOf(1700f, 430f),
        serviceLineFarLeft = listOf(200f, 670f), serviceLineFarRight = listOf(1700f, 670f),
        centerNear = listOf(950f, 430f), centerFar = listOf(950f, 670f),
    )

    /** Two oscillating exchanges: the same shape Phase1PipelineTest uses. */
    private fun twoExchanges(fps: Double = 30.0): RawInference {
        val visible = HashMap<Int, Pair<Double, Double>>()
        fun exchange(start: Int, swings: Int) {
            var f = start
            for (s in 0 until swings) {
                val forward = s % 2 == 0
                for (i in 0 until 24) {
                    visible[f] = (if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0) to 400.0
                    f++
                }
            }
        }
        exchange(0, 6)
        exchange(300, 6)
        return RawInference(
            header = RawHeader(1, fps, 600, 1920, 1080, "test@1"),
            frames = (0 until 600).map { f ->
                val p = visible[f]
                RawFrame(
                    frame = f,
                    timestamp = f / fps,
                    shuttle = p?.let { RawShuttle(it.first.toFloat(), it.second.toFloat(), 0.9f, true) },
                    boxes = emptyList(),
                    persons = emptyList(),
                )
            },
        )
    }

    private class FakeEngine(
        private val inference: RawInference? = null,
        private val failure: Throwable? = null,
        private val progress: List<Float> = listOf(0.25f, 0.5f, 0.75f),
    ) : LocalInferenceEngine {
        var ran = 0
        override suspend fun run(videoPath: String, onProgress: (Float) -> Unit): RawInference {
            ran++
            progress.forEach(onProgress)
            failure?.let { throw it }
            return inference!!
        }
    }

    private fun coordinator(engine: LocalInferenceEngine) = LocalAnalysisCoordinator(engine)

    @Test
    fun a_scripted_track_produces_the_rallies_analysis_produces_for_it() = runTest {
        val outcome = coordinator(FakeEngine(twoExchanges()))
            .analyze("/tmp/match.mp4", keypoints).getOrThrow()
        // Exact bounds, not a count, and taken from runPhase1 on this same
        // track rather than assumed: the coordinator's only job is to not
        // distort what the pipeline returns, so the pipeline is the oracle.
        //
        // Note the first rally starts at 24, not at 27 where the gradient
        // detector alone puts it. results.json carries the UNION of the
        // gradient and raw shot-gap detections, and the shot-gap side reaches
        // further back. A coordinator that fed only the filtered track, or
        // rescaled the input, would move these.
        outcome.result.rallies.map { Triple(it.id, it.startFrame, it.endFrame) } shouldBe
            listOf(Triple(1, 24, 132), Triple(2, 300, 432))
        outcome.clipWindows.size shouldBe outcome.result.rallies.size
    }

    @Test
    fun shuttle_coordinates_reach_the_pipeline_in_source_pixels() = runTest {
        // RawInference carries source-video pixels already scaled back from
        // model space. A rescale slipping in here would be invisible until it
        // moved every rally boundary.
        val outcome = coordinator(FakeEngine(twoExchanges()))
            .analyze("/tmp/match.mp4", keypoints).getOrThrow()
        outcome.result.shuttlePositions["0"]!!.x shouldBe 300.0
        outcome.result.shuttlePositions["0"]!!.y shouldBe 400.0
    }

    @Test
    fun the_filename_comes_from_the_path_not_the_whole_path() = runTest {
        val outcome = coordinator(FakeEngine(twoExchanges()))
            .analyze("/var/mobile/Containers/Data/match one.mp4", keypoints).getOrThrow()
        outcome.result.videoMetadata.filename shouldBe "match one.mp4"
    }

    @Test
    fun progress_is_monotonic_and_completes_exactly_once() = runTest {
        val seen = ArrayList<Float>()
        coordinator(FakeEngine(twoExchanges())).analyze("/tmp/m.mp4", keypoints) { seen.add(it) }
        seen shouldBe seen.sorted()
        seen.count { it >= 1f } shouldBe 1
        seen.last() shouldBe 1f
    }

    @Test
    fun an_engine_failure_is_an_outcome_not_a_crash() = runTest {
        val result = coordinator(FakeEngine(failure = IllegalStateException("model missing")))
            .analyze("/tmp/m.mp4", keypoints)
        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldBe "model missing"
    }

    @Test
    fun an_unreadable_frame_rate_still_produces_rallies() = runTest {
        // fps = 0 reaches every detector's `fps <= 0` guard and would yield no
        // rallies at all. normalizeFps on this path is what stops a bad probe
        // silently costing the user their whole rally list, which is the guard
        // the cloud lacked.
        val outcome = coordinator(FakeEngine(twoExchanges(fps = 0.0)))
            .analyze("/tmp/m.mp4", keypoints).getOrThrow()
        (outcome.result.rallies.isNotEmpty()) shouldBe true
    }

    @Test
    fun the_engine_runs_once_per_analysis() = runTest {
        val engine = FakeEngine(twoExchanges())
        coordinator(engine).analyze("/tmp/m.mp4", keypoints)
        engine.ran shouldBe 1
    }
}
