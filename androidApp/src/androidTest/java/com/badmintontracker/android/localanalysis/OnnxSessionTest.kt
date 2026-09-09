package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Proves ONNX Runtime loads and runs the bundled Phase 1 graphs on the actual
 * device, not on a laptop.
 *
 * The desktop export recorded each graph's shapes, so these assert the same
 * numbers: a device that disagrees about a tensor shape is a real finding, and
 * it is far cheaper to learn it here than from a wrong shuttle position.
 */
@RunWith(AndroidJUnit4::class)
class OnnxSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The TEST apk's own assets, which carry the one graph the app does not
     * ship. See `testOnlyModels` in androidApp/build.gradle.kts. Staged into
     * the app's files directory, which is the one this process can write to.
     */
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /**
     * Seeded noise, never zeros.
     *
     * Desktop measurement showed an all-zero image drives YOLO26's
     * score-indexed postprocessing out of range and kills the run with
     * "GatherElements op: Out of range value in index tensor". The 960 pose
     * graph happened to survive it, which is worse than failing, because it
     * reports a number for a path real frames never take.
     */
    private fun noise(n: Int): FloatArray {
        val r = Random(0)
        return FloatArray(n) { r.nextFloat() }
    }

    @Test
    fun the_detector_loads_and_runs_with_the_shape_the_export_recorded() {
        OnnxSession(ModelCatalog.path(context, Model.DETECTOR)).use { s ->
            assertEquals(listOf(1L, 3L, 640L, 640L), s.inputShape().toList())
            assertEquals(listOf(1L, 7L, 8400L), s.outputShape().toList())
            val out = s.run(noise(1 * 3 * 640 * 640), longArrayOf(1, 3, 640, 640))
            assertEquals(7 * 8400, out.size)
            assertTrue("output must not be all zeros", out.any { it != 0f })
        }
    }

    @Test
    fun tracknet_loads_and_runs_with_the_shape_the_export_recorded() {
        // 27 channels: 8 sequence frames plus one background frame, 3 channels
        // each, matching bg_mode = "concat".
        OnnxSession(ModelCatalog.path(context, Model.TRACKNET)).use { s ->
            assertEquals(listOf(1L, 27L, 288L, 512L), s.inputShape().toList())
            assertEquals(listOf(1L, 8L, 288L, 512L), s.outputShape().toList())
            val out = s.run(noise(1 * 27 * 288 * 512), longArrayOf(1, 27, 288, 512))
            assertEquals(8 * 288 * 512, out.size)
            // TrackNet ends in a sigmoid, so every value must be a probability.
            assertTrue("heatmap values must lie in [0,1]", out.all { it in 0f..1f })
        }
    }

    @Test
    fun inpaintnet_accepts_more_than_one_trajectory_length() {
        // The length axis is genuinely dynamic: production chunks at 256 with
        // stride 128 and pads to a multiple of 8, so one session sees several
        // lengths. Tracing at a single length would not prove this.
        OnnxSession(ModelCatalog.path(context, "models/inpaintnet.onnx", testAssets)).use { s ->
            assertEquals(-1L, s.inputShape()[2])
            for (length in listOf(16, 128, 256)) {
                val out = s.run(noise(1 * 3 * length), longArrayOf(1, 3, length.toLong()))
                assertEquals("length $length", 2 * length, out.size)
            }
        }
    }

    @Test
    fun the_model_version_is_derived_from_the_pinned_weights() {
        // Three eight-character SHA prefixes joined by dashes. Not a hand
        // typed string, so a weights change cannot go unrecorded.
        assertTrue(
            "unexpected model version ${ModelCatalog.VERSION}",
            Regex("^[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{8}$").matches(ModelCatalog.VERSION),
        )
    }
}
