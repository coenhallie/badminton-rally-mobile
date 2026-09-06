package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ServeRotationTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)
    private val doubles = MatchSetup(
        doubles = true,
        firstServer = Side.HOME,
        homeStartsRight = PairPlayer.FIRST,
        awayStartsRight = PairPlayer.FIRST,
    )

    private fun fold(setup: MatchSetup, vararg winners: Side) =
        foldMatchState(ScoringRules.BWF_21, setup, winners.map { ScoreEvent.PointTo(it) })

    @Test
    fun the_first_serve_of_a_match_goes_from_the_right() {
        fold(singles).serviceCourt shouldBe ServiceCourt.RIGHT
        fold(doubles).serviceCourt shouldBe ServiceCourt.RIGHT
    }

    @Test
    fun the_service_court_follows_the_servers_own_score() {
        // Home leads 1-0 and serves: odd, so from the left.
        fold(singles, Side.HOME).serviceCourt shouldBe ServiceCourt.LEFT
        // Home leads 2-0 and serves: even, so from the right.
        fold(singles, Side.HOME, Side.HOME).serviceCourt shouldBe ServiceCourt.RIGHT
        // Away takes one back and serves at 2-1: away's own score is odd, so left.
        fold(singles, Side.HOME, Side.HOME, Side.AWAY).serviceCourt shouldBe ServiceCourt.LEFT
    }

    @Test
    fun singles_has_no_player_within_a_pair() {
        val state = fold(singles, Side.HOME)
        state.servingPlayer shouldBe null
        state.receivingPlayer shouldBe null
    }

    @Test
    fun the_opening_doubles_serve_is_first_to_first() {
        val state = fold(doubles)
        state.server shouldBe Side.HOME
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun the_serving_pair_swaps_courts_on_winning_so_the_same_player_serves_again() {
        // Home wins its own serve. Home's pair swaps courts, home's score is now
        // odd so the serve comes from the left - and the player standing there is
        // the one who just served.
        val state = fold(doubles, Side.HOME)
        state.serviceCourt shouldBe ServiceCourt.LEFT
        state.servingPlayer shouldBe PairPlayer.FIRST
        // The other opponent now receives, because the receiving pair did not move.
        state.receivingPlayer shouldBe PairPlayer.SECOND
    }

    @Test
    fun two_won_serves_return_the_pair_to_where_it_started() {
        val state = fold(doubles, Side.HOME, Side.HOME)
        state.serviceCourt shouldBe ServiceCourt.RIGHT
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun the_receiving_pair_does_not_swap_when_it_wins_the_serve_back() {
        // Home wins twice, away then wins the rally at 2-0. Away's pair has not
        // moved all game, and away's score is 1, so the serve comes from away's
        // left court - where the second player is standing.
        val state = fold(doubles, Side.HOME, Side.HOME, Side.AWAY)
        state.server shouldBe Side.AWAY
        state.serviceCourt shouldBe ServiceCourt.LEFT
        state.servingPlayer shouldBe PairPlayer.SECOND
        state.receivingPlayer shouldBe PairPlayer.SECOND
    }

    @Test
    fun a_pair_may_start_the_match_the_other_way_round() {
        val flipped = doubles.copy(homeStartsRight = PairPlayer.SECOND)
        val state = foldMatchState(ScoringRules.BWF_21, flipped, emptyList())
        state.servingPlayer shouldBe PairPlayer.SECOND
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun a_new_game_restores_the_starting_arrangement() {
        // 21-0 to home: home's pair swapped courts on all 21 of its own serves, an
        // odd number, so without a reset it would start game two the wrong way up.
        val gameOne = List(21) { ScoreEvent.PointTo(Side.HOME) }
        val state = foldMatchState(ScoringRules.BWF_21, doubles, gameOne)
        state.gameIndex shouldBe 1
        state.server shouldBe Side.HOME
        state.serviceCourt shouldBe ServiceCourt.RIGHT
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun a_finished_match_has_nobody_serving() {
        val twoGames = List(42) { ScoreEvent.PointTo(Side.HOME) }
        val state = foldMatchState(ScoringRules.BWF_21, doubles, twoGames)
        state.isOver shouldBe true
        state.server shouldBe null
        state.serviceCourt shouldBe null
        state.servingPlayer shouldBe null
        state.receivingPlayer shouldBe null
    }
}
