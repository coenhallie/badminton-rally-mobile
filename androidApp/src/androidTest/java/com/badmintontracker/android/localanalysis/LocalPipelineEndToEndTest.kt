package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.local.LocalAnalysisCoordinator
import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole local pipeline on the device: decode, TrackNet, filtering, both
 * rally detectors, union, refinement, clip padding.
 *
 * Everything downstream of the engine is the same `:analysis` code CI already
 * exercises against a fake engine and against the cloud's own detectors. What
 * this adds is that a real engine's output flows through it on a phone.
 *
 * Bounded to a prefix of the video, since a full pass is 23 minutes at the
 * measured 235ms per frame.
 */
@RunWith(AndroidJUnit4::class)
class LocalPipelineEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    /** The corpus video's own court markings, as the cloud used them. */
    private val keypoints = CourtKeypoints(
        topLeft = listOf(474f, 172f), topRight = listOf(1443f, 172f),
        bottomRight = listOf(1836f, 1010f), bottomLeft = listOf(83f, 1010f),
        netLeft = listOf(233f, 545f), netRight = listOf(1687f, 545f),
        serviceLineNearLeft = listOf(390f, 330f), serviceLineNearRight = listOf(1530f, 330f),
        serviceLineFarLeft = listOf(210f, 700f), serviceLineFarRight = listOf(1710f, 700f),
        centerNear = listOf(960f, 330f), centerFar = listOf(960f, 700f),
    )

    @Test
    fun a_video_on_this_phone_becomes_rallies_and_clip_windows() = runBlocking {
        val vid = video()
        assumeTrue("SKIPPED: push corpus-743d7fb1.mp4 to /data/local/tmp", vid != null)

        val engine = object : com.badmintontracker.shared.local.LocalInferenceEngine {
            override suspend fun run(videoPath: String, onProgress: (Float) -> Unit) =
                // A prefix, so the test finishes. The real engine over the
                // whole file is the same call without the cap.
                BoundedEngine(context, 320).run(videoPath, onProgress)
        }

        val progress = ArrayList<Float>()
        val outcome = LocalAnalysisCoordinator(engine)
            .analyze(vid!!.path, keypoints) { progress.add(it) }

        assertTrue("analysis failed: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        val result = outcome.getOrThrow()

        println(
            "LOCALPIPE frames=${result.result.totalFrames} " +
                "shuttleVisible=${result.result.shuttlePositions.count { it.value.visible }} " +
                "rallies=${result.result.rallies.size} clips=${result.clipWindows.size}"
        )

        // The pipeline ran end to end and produced the two things Stage 1 owes:
        // a results payload and clip windows.
        assertEquals("phase1", result.result.phase)
        assertTrue("no shuttle positions", result.result.shuttlePositions.isNotEmpty())
        assertTrue("progress must reach completion", progress.lastOrNull() == 1f)

        // Clip windows must never precede their rally or trail it.
        result.clipWindows.forEach { w ->
            assertTrue(w.clipStart <= w.rally.startTimestamp)
            assertTrue(w.clipEnd >= w.rally.endTimestamp)
        }

        // And the engine's own output must survive a round trip through the
        // format the two halves agree on.
        val raw = BoundedEngine(context, 32).run(vid.path) {}
        assertEquals(raw, RawInferenceCodec.decode(RawInferenceCodec.encode(raw)))
    }

    /** The real engine, capped so an instrumented run finishes. */
    private class BoundedEngine(
        private val context: android.content.Context,
        private val maxFrames: Int,
    ) : com.badmintontracker.shared.local.LocalInferenceEngine {
        override suspend fun run(videoPath: String, onProgress: (Float) -> Unit) =
            AndroidLocalInferenceEngine(context, maxFrames).run(videoPath, onProgress)
    }
}
