package com.badmintontracker.android.localvideo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The label that lives beside the list's local video actions.
 *
 * The row model this view model used to build (LocalVideoRow, and the tests
 * that pinned its status text, busy state and remove gate) went when the local
 * video rows moved onto the shared match list; those rules are
 * MatchListBuilderTest's now. The details sheet's save, and the normalising
 * of the name and description it does, is SaveMatchPlayersTest's
 * (saveEntryMatchDetailsOrMessage).
 */
class LocalVideoListViewModelTest {

    @Test
    fun a_duration_reads_as_minutes_and_padded_seconds() {
        formatDuration(65_000) shouldBe "1:05"
        formatDuration(999) shouldBe "0:00"
        formatDuration(600_000) shouldBe "10:00"
    }
}
