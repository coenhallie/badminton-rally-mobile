package com.badmintontracker.android.match

import com.badmintontracker.android.localanalysis.ClipCutter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceClipLabelTest {
    private fun clip(index: Int, start: Double, end: Double) =
        ClipCutter.Clip(index = index, startSeconds = start, endSeconds = end, file = java.io.File("/tmp/$index.mp4"))

    @Test fun a_rally_is_named_as_the_cloud_rows_name_theirs() {
        assertEquals("Rally #3", deviceClipTitle(clip(3, 12.34, 19.86)))
    }

    @Test fun its_length_goes_through_the_shared_formatter() {
        assertEquals("7.5s", deviceClipLength(clip(3, 12.34, 19.86)))
    }

    @Test fun the_player_title_is_the_row_on_one_line() {
        assertEquals("Rally #3 · 7.5s", deviceClipLabel(clip(3, 12.34, 19.86)))
    }

    // Clips recovered by filename have no bounds; a fabricated "0.0s"
    // would read as a broken clip.
    @Test fun a_clip_with_no_bounds_is_named_without_fabricating_a_length() {
        assertNull(deviceClipLength(clip(7, 0.0, 0.0)))
        assertEquals("Rally #7", deviceClipLabel(clip(7, 0.0, 0.0)))
    }
}
