package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MatchMilestonesTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)

    private fun fold(events: List<ScoreEvent>, rules: ScoringRules = ScoringRules.BWF_21) =
        foldMatchState(rules, singles, events)

    /** Same helper as MatchStateTest: points ordered so a game is never won early. */
    private fun toScore(home: Int, away: Int): List<ScoreEvent> {
        val events = ArrayList<ScoreEvent>(home + away)
        var h = 0
        var a = 0
        while (h < home || a < away) {
            if (h < home && (h <= a || a >= away)) {
                events += ScoreEvent.PointTo(Side.HOME); h += 1
            } else {
                events += ScoreEvent.PointTo(Side.AWAY); a += 1
            }
        }
        return events
    }

    @Test
    fun reaching_the_interval_score_announces_the_interval_once() {
        fold(toScore(10, 0)).isIntervalPoint shouldBe false
        fold(toScore(11, 0)).isIntervalPoint shouldBe true
        // The next rally clears it, and the interval does not come round again when
        // the other side also reaches 11.
        fold(toScore(12, 0)).isIntervalPoint shouldBe false
        fold(toScore(11, 11)).isIntervalPoint shouldBe false
    }

    @Test
    fun undoing_the_interval_point_un_announces_the_interval() {
        // The edge is re-derived from the log, so it cannot get stuck on.
        fold(toScore(11, 0).undoLast()).isIntervalPoint shouldBe false
    }

    @Test
    fun a_new_game_brings_the_interval_back() {
        fold(toScore(21, 0) + toScore(11, 0)).isIntervalPoint shouldBe true
    }

    @Test
    fun rules_without_an_interval_never_announce_one() {
        val noInterval = ScoringRules(
            pointsToWin = 21, winBy = 2, cap = 30, intervalAt = null, gamesToWin = 2, changeEndsAt = null,
        )
        fold(toScore(11, 0), noInterval).isIntervalPoint shouldBe false
    }

    @Test
    fun ends_change_after_every_game() {
        fold(toScore(5, 5)).endsSwapCount shouldBe 0
        val afterGameOne = fold(toScore(21, 0))
        afterGameOne.endsSwapCount shouldBe 1
        afterGameOne.isChangeEndsPoint shouldBe true
        // And the announcement clears on the next rally of the new game.
        fold(toScore(21, 0) + toScore(1, 0)).isChangeEndsPoint shouldBe false
    }

    @Test
    fun ends_change_again_at_eleven_in_the_deciding_game() {
        val decider = toScore(21, 0) + toScore(0, 21)          // one game each
        fold(decider).endsSwapCount shouldBe 2
        val atEleven = fold(decider + toScore(11, 0))
        atEleven.endsSwapCount shouldBe 3
        atEleven.isChangeEndsPoint shouldBe true
        // Only once per game, whoever gets there second.
        fold(decider + toScore(11, 11)).endsSwapCount shouldBe 3
    }

    @Test
    fun eleven_in_a_non_deciding_game_does_not_change_ends() {
        fold(toScore(11, 0)).endsSwapCount shouldBe 0
        fold(toScore(11, 0)).isChangeEndsPoint shouldBe false
    }

    @Test
    fun the_match_ending_is_not_a_change_of_ends() {
        // Nobody walks to the other end of an empty court.
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.isOver shouldBe true
        state.endsSwapCount shouldBe 1               // only the between-games change
        state.isChangeEndsPoint shouldBe false
    }

    @Test
    fun a_side_one_point_from_the_game_is_at_game_point() {
        val state = fold(toScore(20, 19))
        state.gamePoint shouldBe SideFlags(home = true, away = false)
        state.gamePoint.of(Side.HOME) shouldBe true
        state.gamePoint.any shouldBe true
    }

    @Test
    fun level_at_the_target_is_game_point_for_nobody() {
        fold(toScore(20, 20)).gamePoint shouldBe SideFlags.NONE      // 20-20
    }

    @Test
    fun at_the_point_before_the_cap_both_sides_are_at_game_point() {
        // 29-29: whoever wins the rally reaches the cap and takes the game, so a
        // board that could only say "game point" could not say whose.
        fold(toScore(29, 29)).gamePoint shouldBe SideFlags(home = true, away = true)
    }

    @Test
    fun game_point_is_only_match_point_on_the_last_game_a_side_needs() {
        // First game: home is one point from the game, two games from the match.
        fold(toScore(20, 0)).matchPoint shouldBe SideFlags.NONE
        // Second game, home already one game up: now it is both.
        val second = fold(toScore(21, 0) + toScore(20, 0))
        second.gamePoint shouldBe SideFlags(home = true, away = false)
        second.matchPoint shouldBe SideFlags(home = true, away = false)
    }

    @Test
    fun a_finished_match_is_at_no_kind_of_point() {
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.gamePoint shouldBe SideFlags.NONE
        state.matchPoint shouldBe SideFlags.NONE
        state.isIntervalPoint shouldBe false
    }

    @Test
    fun a_retirement_clears_the_milestones_too() {
        val state = fold(toScore(20, 19) + ScoreEvent.Retire(Side.AWAY))
        state.winner shouldBe Side.HOME
        state.gamePoint shouldBe SideFlags.NONE
        state.matchPoint shouldBe SideFlags.NONE
    }
}
