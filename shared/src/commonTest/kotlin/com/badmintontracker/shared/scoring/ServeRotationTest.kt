package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ServeRotationTest {

    private val homeServes = MatchSetup(firstServer = Side.HOME)

    private fun fold(setup: MatchSetup, vararg winners: Side) =
        foldMatchState(ScoringRules.BWF_21, setup, winners.map { ScoreEvent.PointTo(it) })

    @Test
    fun the_first_serve_of_a_match_goes_from_the_right() {
        fold(homeServes).serviceCourt shouldBe ServiceCourt.RIGHT
    }

    @Test
    fun the_side_the_setup_names_serves_first() {
        fold(homeServes).server shouldBe Side.HOME
        fold(MatchSetup(firstServer = Side.AWAY)).server shouldBe Side.AWAY
    }

    @Test
    fun the_service_court_follows_the_servers_own_score() {
        // Home leads 1-0 and serves: odd, so from the left.
        fold(homeServes, Side.HOME).serviceCourt shouldBe ServiceCourt.LEFT
        // Home leads 2-0 and serves: even, so from the right.
        fold(homeServes, Side.HOME, Side.HOME).serviceCourt shouldBe ServiceCourt.RIGHT
        // Away takes one back and serves at 2-1: away's own score is odd, so left.
        fold(homeServes, Side.HOME, Side.HOME, Side.AWAY).serviceCourt shouldBe ServiceCourt.LEFT
    }

    @Test
    fun the_winner_of_a_game_serves_the_next_one_from_the_right() {
        val gameOne = List(21) { ScoreEvent.PointTo(Side.AWAY) }
        val state = foldMatchState(ScoringRules.BWF_21, homeServes, gameOne)
        state.gameIndex shouldBe 1
        state.server shouldBe Side.AWAY
        state.serviceCourt shouldBe ServiceCourt.RIGHT
    }

    @Test
    fun a_finished_match_has_nobody_serving() {
        val twoGames = List(42) { ScoreEvent.PointTo(Side.HOME) }
        val state = foldMatchState(ScoringRules.BWF_21, homeServes, twoGames)
        state.isOver shouldBe true
        state.server shouldBe null
        state.serviceCourt shouldBe null
    }
}
