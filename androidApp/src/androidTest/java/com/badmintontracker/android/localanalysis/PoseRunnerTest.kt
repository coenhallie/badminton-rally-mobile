package com.badmintontracker.android.localanalysis

import android.util.Log
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.CourtOccupancy
import com.badmintontracker.analysis.player.NearPlayerSelector
import com.badmintontracker.analysis.player.PlayerSample
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.assertTrue

/**
 * The pose half of Phase 2, end to end on a real video.
 *
 * The unit tests cover selection and occupancy against hand-built frames; what
 * they cannot cover is whether the graph's output means what the decoder thinks
 * it means. That layout was read off a real export and confirmed against
 * Ultralytics, and this is what keeps it confirmed on the device.
 *
 * Side-loaded, as the throughput tests are, so an unproven path does not move
 * APK size:
 *
 *   adb push tools/models/onnx/posen.960.fp16.onnx /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class PoseRunnerTest {

    private val NL = System.lineSeparator()

    // The marked court for the corpus video this test runs on.
    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5065789473684, 484.8241206030151),
        topRight = Point(1277.3026315789473, 481.2060301507538),
        bottomRight = Point(1579.4407894736842, 1004.0201005025126),
        bottomLeft = Point(360.0328947368421, 1004.0201005025126),
        netLeft = Point(550.0, 663.9195979899498),
        netRight = Point(1382.2368421052631, 665.7286432160804),
        serviceLineNearLeft = Point(430.0, 880.0),
        serviceLineNearRight = Point(1500.0, 880.0),
        serviceLineFarLeft = Point(690.0, 520.0),
        serviceLineFarRight = Point(1240.0, 518.0),
        centerNear = Point(966.1184210526316, 593.3668341708543),
        centerFar = Point(966.1184210526316, 736.2814070351759),
    )

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    private fun model(): File? =
        File("/data/local/tmp/posen.960.fp16.onnx").takeIf { it.isFile && it.canRead() }

    @Test
    fun real_frames_become_a_near_player_track_on_the_court() {
        val vid = video()
        val mdl = model()
        assumeTrue("SKIPPED: corpus video absent", vid != null)
        assumeTrue("SKIPPED: posen.960.fp16.onnx absent from /data/local/tmp", mdl != null)

        val metadata = VideoFrameSource(vid!!).metadata()
        val selector = NearPlayerSelector(keypoints, metadata.width.toDouble(), metadata.height.toDouble())
        assertTrue("the marked court must give a homography and a net line", selector.usable)

        val samples = ArrayList<PlayerSample>()
        var frames = 0
        var peopleSeen = 0
        PoseRunner(mdl!!.path).use { runner ->
            VideoFrameSource(vid).forEachFrame(FRAMES) { index, _, image ->
                val pose = runner.detect(index, image)
                peopleSeen += pose.people.size
                selector.select(pose).sample?.let { samples.add(it) }
                frames++
            }
        }

        val onAnkles = samples.count { it.onAnkles }
        val occupancy = CourtOccupancy()
        occupancy.addAll(samples, metadata.fps)

        Log.i(
            "PoseRunner",
            buildString {
                append("frames $frames, people $peopleSeen, near-player samples ${samples.size}").append(NL)
                append("  coverage      ${100 * samples.size / frames}%").append(NL)
                append("  on ankles     ${if (samples.isEmpty()) 0 else 100 * onAnkles / samples.size}%").append(NL)
                append("  occupancy     ${occupancy.totalSeconds} s over ${occupancy.grid().flatten().count { it > 0 }} cells")
            },
        )

        // Detection at all. Anything close to zero means the decode is wrong,
        // which is the failure this test exists to catch.
        assertTrue("expected at least one person per frame, got $peopleSeen over $frames", peopleSeen > frames)

        // Coverage on the near player was 93% on the host over 120 frames. A
        // floor well under that still fails hard if the layout is misread.
        assertTrue(
            "expected the near player in most frames, got ${samples.size} of $frames",
            samples.size > frames / 2,
        )

        // Every accepted sample is on the court by construction; this checks the
        // projection actually ran rather than the gate being vacuous.
        assertTrue(
            "a sample projected off the court",
            samples.all { it.courtPosition.y > -NearPlayerSelector.OUT_OF_COURT_MARGIN_M },
        )

        // The near player is on the near half, which is the larger y under this
        // camera. If the net-line side test were inverted this is what would
        // catch it.
        val nearHalf = samples.count { it.courtPosition.y > HALF_COURT_M }
        assertTrue(
            "expected the track on the near half, got $nearHalf of ${samples.size}",
            nearHalf > samples.size / 2,
        )

        assertTrue("occupancy accumulated no time", occupancy.totalSeconds > 0.0)
    }

    private companion object {
        const val FRAMES = 60
        const val HALF_COURT_M = 6.7
    }
}
