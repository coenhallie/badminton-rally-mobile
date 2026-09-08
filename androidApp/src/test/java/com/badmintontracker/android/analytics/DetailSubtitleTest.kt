package com.badmintontracker.android.analytics

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import io.kotest.matchers.shouldBe
import org.junit.Test

class DetailSubtitleTest {

    @Test
    fun the_bar_says_how_long_the_video_is_and_the_day_it_was_added() {
        // Noon UTC, so the calendar day is the same in every zone the test
        // could run in.
        val entry = LocalVideoEntry(
            id = "e1",
            uri = "content://video/1",
            displayName = "28.mp4",
            durationMs = 65_000L,
            sizeBytes = 1L,
            addedAtEpochMs = 1_788_868_800_000L, // 2026-09-08T12:00:00Z
            stage = AnalyzeStage.LOCAL,
        )

        detailSubtitle(entry) shouldBe "1:05 · Sep 8, 2026"
    }
}
