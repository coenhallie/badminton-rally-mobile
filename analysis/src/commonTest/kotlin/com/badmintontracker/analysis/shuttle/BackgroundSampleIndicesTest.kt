package com.badmintontracker.analysis.shuttle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the background sampling picks the frames production picks.
 *
 * These used to be instrumented tests on Android, sitting on a
 * `VideoFrameSource` built over a file called "unused" - they needed a device
 * only because the arithmetic happened to live in a class that decodes. Moved
 * here with the function, so both platforms are held to them and CI runs them.
 */
class BackgroundSampleIndicesTest {

    @Test
    fun indices_truncate_the_way_numpy_linspace_does() {
        // np.linspace(0, 5971, 300, dtype=int) truncates rather than rounds.
        // Checked against the same arithmetic production performs, not against
        // this implementation: step is 5971/299, so index 1 is 19.97 -> 19,
        // and rounding would make it 20 and sample a different frame.
        val idx = backgroundSampleIndices(totalFrames = 5972, maxSamples = 300)
        assertEquals(300, idx.size)
        assertEquals(0, idx.first())
        assertEquals(19, idx[1])
        assertEquals(5971, idx.last())
        assertTrue(idx.zipWithNext().all { it.first < it.second }, "indices must be strictly increasing")
    }

    @Test
    fun a_short_video_samples_every_frame_without_duplicates() {
        assertEquals((0 until 10).toList(), backgroundSampleIndices(10, 300))
    }

    @Test
    fun a_single_frame_video_samples_that_frame() {
        // The count == 1 branch exists because the general step would divide by
        // zero. Guarded rather than left to produce NaN and an empty list.
        assertEquals(listOf(0), backgroundSampleIndices(1, 300))
    }

    @Test
    fun an_empty_video_samples_nothing() {
        assertEquals(emptyList(), backgroundSampleIndices(0, 300))
    }
}
