package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.shuttle.ShuttleDetection
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Does the detector actually detect anything?
 *
 * Worth its own test because the end-to-end run cannot answer it. That test
 * reports the FILTERED track's visibility, which comes from raw TrackNet and
 * is unaffected by the detector, so a detector that silently returned nothing
 * would leave every number in it unchanged - and did, when the detector was
 * added: 94 visible and 1 rally both before and after.
 *
 * Checked against Ultralytics on the same 120 frames, which is the reference
 * implementation the cloud calls:
 *
 * | | detections | best confidence | position |
 * |---|---|---|---|
 * | Ultralytics | 4 | 0.450 | (949, 440) |
 * | this, via ONNX | 5 | 0.434 | (949, 439) |
 *
 * The same shuttle, one pixel apart, with the confidence gap explained by
 * fp16. The extra fifth detection is a borderline one that fp16 rounding
 * pushed over the 0.25 threshold. That agreement is what validates the
 * letterboxing, the NMS and the coordinate mapping together - any of the three
 * being wrong moves the position by far more than a pixel.
 *
 * Four detections in 120 frames is genuinely sparse, and that is the model on
 * this footage rather than a porting fault. It is also why the fusion prefers
 * TrackNet and consults this only as a fallback.
 */
@RunWith(AndroidJUnit4::class)
class DetectorRunnerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    @Test
    fun the_detector_finds_shuttles_in_real_frames() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        val found = ArrayList<ShuttleDetection>()
        var frames = 0
        DetectorRunner(context).use { detector ->
            VideoFrameSource(vid!!).forEachFrame(120) { _, _, image ->
                found += detector.detect(image)
                frames++
            }
        }

        val best = found.maxByOrNull { it.confidence }
        println(
            "DETECTOR frames=$frames detections=${found.size} " +
                "bestConfidence=${best?.confidence?.let { "%.3f".format(it) }} " +
                "x=${best?.x?.toInt()} y=${best?.y?.toInt()}"
        )

        assertTrue("the detector found no shuttle in $frames real frames", found.isNotEmpty())
        // Coordinates must be in SOURCE pixels. A detection left in the 640
        // letterboxed space would still look like a plausible position while
        // being wrong by a factor of three, and the fusion's court check would
        // then reject almost everything.
        assertTrue(
            "detections outside the frame: ${found.filter { it.x !in 0.0..1920.0 || it.y !in 0.0..1080.0 }.take(3)}",
            found.all { it.x in 0.0..1920.0 && it.y in 0.0..1080.0 },
        )
        assertTrue(
            "no detection beyond the 640 letterbox width, so scaling may not have run",
            found.any { it.x > 640.0 },
        )
    }
}
