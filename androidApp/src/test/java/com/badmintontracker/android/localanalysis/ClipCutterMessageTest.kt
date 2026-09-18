package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ClipCutterMessageTest {
    @Test
    fun an_empty_window_names_the_file_it_was_cut_from() {
        // The message is shown to the coach verbatim, and used to end in the
        // literal text "${source.name}": an escaped dollar sign left over from a
        // template.
        emptyWindowMessage(12.5, 20.0, "match.mp4") shouldBe
            "no frames fell inside 12.50..20.00s of match.mp4"
    }
}
