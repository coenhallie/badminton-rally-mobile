package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The first shuttle track produced entirely on the device.
 *
 * Bounded to a prefix of the video: a full 5972-frame pass is a long
 * instrumented test, and what needs proving here is that the pieces compose
 * correctly, not how fast they are. Throughput is printed so the full-run cost
 * can be estimated from a short run.
 */
@RunWith(AndroidJUnit4::class)
class TrackNetRunnerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    @Test
    fun the_device_produces_a_shuttle_track() {
        val vid = video()
        assumeTrue("SKIPPED: push corpus-743d7fb1.mp4 to /data/local/tmp", vid != null)

        val frames = 256
        val source = VideoFrameSource(vid!!)
        val started = System.currentTimeMillis()
        val track = TrackNetRunner(context, source).track(
            sourceWidth = 1920, sourceHeight = 1080, maxFrames = frames,
        )
        val elapsed = System.currentTimeMillis() - started

        val visible = track.count { it.value.visible }
        val perFrame = elapsed.toDouble() / track.size
        println(
            "TRACKNET frames=${track.size} visible=$visible " +
                "(${"%.1f".format(100.0 * visible / track.size)}%) " +
                "elapsed=${elapsed}ms perFrame=${"%.1f".format(perFrame)}ms " +
                "projected5972=${"%.1f".format(perFrame * 5972 / 1000)}s"
        )

        // Every frame decoded gets an entry, visible or not: a track with gaps
        // in its keys would make the rally detectors' frame arithmetic wrong.
        assertEquals("one entry per decoded frame", frames, track.size)
        assertEquals("frames must be contiguous from 0", (0 until frames).toSet(), track.keys)

        // The cloud sees the shuttle in 44.2% of this whole video. A prefix
        // will not match that exactly, but a track that finds nothing means
        // the pipeline is broken, and one that finds everything means the
        // area filter is not running.
        assertTrue("no shuttle found at all in $frames frames", visible > 0)
        assertTrue("implausibly many detections: $visible of $frames", visible < frames)

        // Coordinates must be in SOURCE pixels, not model space. A track left
        // in 512x288 would be silently wrong by a factor of 3.75 and still
        // look like a plausible track.
        val xs = track.values.filter { it.visible }.map { it.x }
        assertTrue("x out of frame: ${xs.minOrNull()}..${xs.maxOrNull()}",
            xs.all { it in 0.0..1920.0 })
        assertTrue("some x must exceed the 512 model width if scaling ran",
            xs.any { it > 512.0 })
    }
}
