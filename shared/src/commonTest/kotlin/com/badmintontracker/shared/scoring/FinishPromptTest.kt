package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FinishPromptTest {

    @Test
    fun a_match_that_ends_during_the_visit_is_asked_about_once() {
        val prompt = FinishPrompt(playableOnEntry = true)
        prompt.shouldAsk(isPlayable = true) shouldBe false
        prompt.shouldAsk(isPlayable = false) shouldBe true
        prompt.shouldAsk(isPlayable = false) shouldBe false
    }

    @Test
    fun undoing_the_last_point_and_finishing_again_does_not_ask_twice() {
        val prompt = FinishPrompt(playableOnEntry = true)
        prompt.shouldAsk(isPlayable = false) shouldBe true
        prompt.shouldAsk(isPlayable = true) shouldBe false
        prompt.shouldAsk(isPlayable = false) shouldBe false
    }

    @Test
    fun a_board_opened_on_a_match_already_won_does_not_ask() {
        // A won match stays LIVE so that a mis-tapped last point can be undone,
        // and the match page offers "Resume scoring" for it. Asking on arrival
        // put the prompt over the board before anything could be tapped, and
        // every way out of the prompt leaves the board: undo was unreachable.
        val prompt = FinishPrompt(playableOnEntry = false)
        prompt.shouldAsk(isPlayable = false) shouldBe false
    }

    @Test
    fun a_match_reopened_by_undo_and_then_won_again_is_asked_about() {
        // The visit that undoes the mis-tap is a visit like any other once the
        // match is back in play.
        val prompt = FinishPrompt(playableOnEntry = false)
        prompt.shouldAsk(isPlayable = true) shouldBe false
        prompt.shouldAsk(isPlayable = false) shouldBe true
    }
}
