package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LabelUsageTest {

    @Test
    fun both_is_offered_on_both_surfaces() {
        LabelUsage.BOTH.onScoreboard shouldBe true
        LabelUsage.BOTH.onClips shouldBe true
    }

    @Test
    fun scoreboard_is_offered_on_the_board_only() {
        LabelUsage.SCOREBOARD.onScoreboard shouldBe true
        LabelUsage.SCOREBOARD.onClips shouldBe false
    }

    @Test
    fun clips_is_offered_on_the_pickers_only() {
        LabelUsage.CLIPS.onScoreboard shouldBe false
        LabelUsage.CLIPS.onClips shouldBe true
    }

    @Test
    fun resolves_a_stored_key() {
        LabelUsage.from("scoreboard") shouldBe LabelUsage.SCOREBOARD
        LabelUsage.from("clips") shouldBe LabelUsage.CLIPS
        LabelUsage.from("both") shouldBe LabelUsage.BOTH
    }

    @Test
    fun returns_null_for_a_key_this_build_does_not_know() {
        LabelUsage.from("courtside").shouldBeNull()
        LabelUsage.from(null).shouldBeNull()
    }
}
