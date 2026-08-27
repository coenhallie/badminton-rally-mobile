package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where each name is drawn on the board. The surface labels every player R or L,
 * on both sides of the net, and gets all four from these two answers.
 */
class CourtPositionTest {

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
    fun singles_has_nobody_to_place_within_a_pair() {
        val state = fold(singles, Side.HOME)
        state.rightCourtPlayer(Side.HOME) shouldBe null
        state.rightCourtPlayer(Side.AWAY) shouldBe null
    }

    @Test
    fun both_pairs_start_the_way_the_setup_arranged_them() {
        val state = fold(doubles)
        state.rightCourtPlayer(Side.HOME) shouldBe PairPlayer.FIRST
        state.rightCourtPlayer(Side.AWAY) shouldBe PairPlayer.FIRST
    }

    @Test
    fun the_serving_pair_swaps_and_the_receiving_pair_stays_put() {
        // Home wins its own serve, so home's pair changes courts and away's does not.
        val state = fold(doubles, Side.HOME)
        state.rightCourtPlayer(Side.HOME) shouldBe PairPlayer.SECOND
        state.rightCourtPlayer(Side.AWAY) shouldBe PairPlayer.FIRST
    }

    @Test
    fun winning_a_rally_against_the_serve_moves_nobody() {
        // Away takes the serve back without swapping: whoever stands in the court
        // away's new score calls for simply serves next.
        val state = fold(doubles, Side.AWAY)
        state.rightCourtPlayer(Side.HOME) shouldBe PairPlayer.FIRST
        state.rightCourtPlayer(Side.AWAY) shouldBe PairPlayer.FIRST
    }

    @Test
    fun both_pairs_are_placed_once_both_have_swapped() {
        // Home swapped on point one and away on point three, so neither pair is
        // where the setup put it any more.
        val state = fold(doubles, Side.HOME, Side.AWAY, Side.AWAY)
        state.serviceCourt shouldBe ServiceCourt.RIGHT
        state.rightCourtPlayer(Side.HOME) shouldBe PairPlayer.SECOND
        state.rightCourtPlayer(Side.AWAY) shouldBe PairPlayer.SECOND
    }

    @Test
    fun a_finished_match_places_nobody() {
        // There is no serve to derive from once the match is over.
        val over = foldMatchState(
            ScoringRules(pointsToWin = 1, winBy = 1, gamesToWin = 1),
            doubles,
            listOf(ScoreEvent.PointTo(Side.HOME)),
        )
        over.rightCourtPlayer(Side.HOME) shouldBe null
    }
}
