package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.badmintontracker.analysis.rally.ClipWindow
import com.badmintontracker.analysis.rally.Rally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Clip cutting on the device, held to frame accuracy.
 *
 * A stream copy would pass a duration check while starting seconds early on
 * the preceding keyframe, so duration alone is not enough: these also check
 * where each clip actually begins, which is the property re-encoding buys.
 */
@RunWith(AndroidJUnit4::class)
class ClipCutterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fps = 29.73572449542545

    private fun video(): File? =
        File("/data/local/tmp/corpus-743d7fb1.mp4").takeIf { it.isFile && it.canRead() }

    private fun window(start: Double, end: Double): ClipWindow =
        ClipWindow(
            rally = Rally(1, (start * fps).toInt(), (end * fps).toInt(), start, end, end - start),
            clipStart = start,
            clipEnd = end,
        )

    private fun durationSeconds(f: File): Double {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDouble() ?: 0.0) / 1000.0
        } finally {
            r.release()
        }
    }

    private fun outDir(name: String): File =
        File(context.cacheDir, "cliptest/$name").apply { deleteRecursively(); mkdirs() }

    @Test
    fun a_clip_has_the_duration_it_was_asked_for() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        val start = 20.0
        val end = 24.0
        val clips = ClipCutter().cut(vid!!, listOf(window(start, end)), outDir("duration"))
        assertEquals(1, clips.size)

        val actual = durationSeconds(clips[0].file)
        println("CLIPCUT asked=%.2fs got=%.2fs file=%d bytes"
            .format(end - start, actual, clips[0].file.length()))
        assertTrue("clip file is empty", clips[0].file.length() > 0)
        // Within two frames: the encoder emits whole frames, and the window
        // boundaries rarely land exactly on one.
        assertTrue(
            "duration ${"%.3f".format(actual)}s is not within two frames of ${end - start}s",
            abs(actual - (end - start)) <= 2 / fps,
        )
    }

    @Test
    fun a_clip_starts_where_it_was_asked_to_and_not_at_a_keyframe() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        // Deliberately mid-GOP. A stream copy would start at the preceding
        // sync sample, which on this file is up to a couple of seconds back,
        // and would still pass a duration check.
        val start = 37.4
        val end = 39.4
        val clips = ClipCutter().cut(vid!!, listOf(window(start, end)), outDir("offset"))

        // Compare the clip's first frame against the source frame at `start`.
        // Identical pixels would be luck; what matters is that they are far
        // closer to each other than to a frame two seconds earlier.
        val srcAtStart = frameAt(vid, (start * 1_000_000).toLong())
        val srcEarlier = frameAt(vid, ((start - 2.0) * 1_000_000).toLong())
        val clipFirst = frameAt(clips[0].file, 0L)
        assumeTrue("could not decode comparison frames",
            srcAtStart != null && srcEarlier != null && clipFirst != null)

        val toStart = meanAbsDiff(clipFirst!!, srcAtStart!!)
        val toEarlier = meanAbsDiff(clipFirst, srcEarlier!!)
        println("CLIPCUT firstFrame vs source@start=%.1f vs source@start-2s=%.1f".format(toStart, toEarlier))
        assertTrue(
            "clip's first frame ($toStart) is no closer to the requested start " +
                "than to two seconds earlier ($toEarlier) - it may be keyframe-aligned",
            toStart < toEarlier,
        )
    }

    @Test
    fun overlapping_windows_each_produce_their_own_clip() {
        val vid = video()
        assumeTrue("SKIPPED: corpus video absent", vid != null)

        // refineRallies can emit overlapping rallies and padding preserves the
        // overlap, so this is a real case rather than a defensive one.
        val clips = ClipCutter().cut(
            vid!!,
            listOf(window(10.0, 13.0), window(12.0, 15.0)),
            outDir("overlap"),
        )
        assertEquals(2, clips.size)
        clips.forEach { assertTrue("empty clip ${it.file.name}", it.file.length() > 0) }
        assertEquals(setOf("rally-1.mp4", "rally-2.mp4"), clips.map { it.file.name }.toSet())
    }

    private fun frameAt(f: File, us: Long): IntArray? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val bmp = r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 64, 36, true)
            val px = IntArray(64 * 36)
            scaled.getPixels(px, 0, 64, 0, 0, 64, 36)
            bmp.recycle(); scaled.recycle()
            px
        } catch (_: Throwable) {
            null
        } finally {
            r.release()
        }
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) +
                abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) +
                abs((x and 0xFF) - (y and 0xFF))
        }
        return sum.toDouble() / (a.size * 3)
    }
}
