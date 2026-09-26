package com.badmintontracker.android.match

import com.badmintontracker.android.localanalysis.ClipCutter
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClipLabelTest {
    private fun clip(index: Int, start: Double, end: Double) =
        ClipCutter.Clip(index = index, startSeconds = start, endSeconds = end, file = java.io.File("/tmp/$index.mp4"))

    @Test fun a_rally_is_named_with_its_bounds_and_length() {
        assertEquals("Rally 3  12.3s - 19.9s  (7.5s)", deviceClipLabel(clip(3, 12.34, 19.86)))
    }

    // Clips recovered by filename have no bounds; a fabricated "0.0s - 0.0s"
    // would read as a broken clip.
    @Test fun a_clip_with_no_bounds_is_named_without_fabricating_them() {
        assertEquals("Rally 7", deviceClipLabel(clip(7, 0.0, 0.0)))
    }
}
