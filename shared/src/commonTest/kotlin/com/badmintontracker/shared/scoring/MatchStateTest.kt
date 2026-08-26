package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MatchStateTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)

    private fun fold(events: List<ScoreEvent>, rules: ScoringRules = ScoringRules.BWF_21) =
        foldMatchState(rules, singles, events)

    /**
     * Points reaching [home]-[away], ordered so the sides stay as level as the
     * target allows. Order matters far more than it looks: scoring all of one
     * side's points first would win the game partway through and leave the rest
     * landing in the next one, so a naive "21 then 19" helper silently tests
     * 21-0 followed by 0-19.
     */
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
    fun an_empty_log_is_a_match_about_to_start() {
        val state = fold(emptyList())
        state.currentGame shouldBe SideScore.ZERO
        state.gameIndex shouldBe 0
        state.gamesWon shouldBe SideScore.ZERO
        state.completedGames.shouldBeEmpty()
        state.points.shouldBeEmpty()
        state.server shouldBe Side.HOME     // the coin toss, from MatchSetup
        state.winner shouldBe null
        state.isOver shouldBe false
    }

    @Test
    fun a_point_scores_and_the_winner_serves_next() {
        val state = fold(listOf(ScoreEvent.PointTo(Side.AWAY)))
        state.currentGame shouldBe SideScore(home = 0, away = 1)
        state.server shouldBe Side.AWAY
        state.pointCount shouldBe 1
    }

    @Test
    fun every_point_records_the_situation_it_was_played_in() {
        // This is the record L2 denormalises onto a rally clip, so it is asserted
        // here rather than trusted to a later layer.
        val state = fold(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.AWAY)))
        state.points shouldBe listOf(
            ScoredPoint(
                ordinal = 0, gameIndex = 0, wonBy = Side.HOME,
                scoreBefore = SideScore(0, 0), scoreAfter = SideScore(1, 0),
                servedBy = Side.HOME, tags = emptyList(), comment = null,
            ),
            ScoredPoint(
                ordinal = 1, gameIndex = 0, wonBy = Side.AWAY,
                scoreBefore = SideScore(1, 0), scoreAfter = SideScore(1, 1),
                // Home won the previous rally, so home served this one and lost it.
                servedBy = Side.HOME, tags = emptyList(), comment = null,
            ),
        )
    }

    @Test
    fun a_tag_lands_on_the_point_it_names() {
        val state = fold(
            listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.PointTo(Side.AWAY),
                ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), "cross court"),
            )
        )
        state.points[0].tags shouldBe listOf(PointTag("Good shot", "green"))
        state.points[0].comment shouldBe "cross court"
        state.points[1].tags.shouldBeEmpty()
        state.points[1].comment shouldBe null
    }

    @Test
    fun re_tagging_a_point_replaces_the_earlier_tag() {
        val state = fold(
            listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
                ScoreEvent.TagPoint(0, listOf(PointTag("Unforced error", "red")), "wrong call"),
            )
        )
        state.points[0].tags shouldBe listOf(PointTag("Unforced error", "red"))
        state.points[0].comment shouldBe "wrong call"
    }

    @Test
    fun a_tag_naming_no_point_is_ignored() {
        // Undo drops a point and leaves its tag behind. That log must still fold.
        val state = fold(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.TagPoint(7, emptyList(), "orphan")))
        state.pointCount shouldBe 1
        state.points[0].comment shouldBe null
    }

    @Test
    fun a_tag_recorded_before_its_point_still_lands_on_it() {
        // Order independence is what lets a sync layer deliver entries out of order
        // without the fold producing a different match.
        val state = fold(listOf(ScoreEvent.TagPoint(0, emptyList(), "early"), ScoreEvent.PointTo(Side.HOME)))
        state.points[0].comment shouldBe "early"
    }

    @Test
    fun twenty_one_to_nineteen_takes_a_game_and_the_winner_serves_the_next_one() {
        val state = fold(toScore(home = 21, away = 19))
        state.completedGames shouldBe listOf(SideScore(21, 19))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
        state.gameIndex shouldBe 1
        state.currentGame shouldBe SideScore.ZERO
        state.server shouldBe Side.HOME
        state.winner shouldBe null
    }

    @Test
    fun a_one_point_lead_at_the_target_does_not_take_the_game() {
        val state = fold(toScore(20, 20) + ScoreEvent.PointTo(Side.HOME))   // 21-20
        state.currentGame shouldBe SideScore(21, 20)
        state.completedGames.shouldBeEmpty()
        state.gamesWon shouldBe SideScore.ZERO
    }

    @Test
    fun a_two_point_lead_past_the_target_takes_the_game() {
        val state = fold(toScore(20, 20) + toScore(home = 2, away = 0))         // 22-20
        state.completedGames shouldBe listOf(SideScore(22, 20))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun the_cap_ends_setting() {
        val state = fold(toScore(29, 29) + ScoreEvent.PointTo(Side.AWAY))   // 29-30
        state.completedGames shouldBe listOf(SideScore(29, 30))
        state.gamesWon shouldBe SideScore(home = 0, away = 1)
    }

    @Test
    fun a_straight_race_needs_no_lead_at_all() {
        val state = fold(toScore(14, 14) + ScoreEvent.PointTo(Side.HOME), ScoringRules.STRAIGHT_15)
        state.completedGames shouldBe listOf(SideScore(15, 14))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun two_games_take_the_match_and_the_final_score_stays_on_screen() {
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.winner shouldBe Side.HOME
        state.isOver shouldBe true
        state.gamesWon shouldBe SideScore(home = 2, away = 0)
        state.completedGames shouldBe listOf(SideScore(21, 0), SideScore(21, 0))
        // The last game's score, not a reset board: the match page shows how it ended.
        state.currentGame shouldBe SideScore(21, 0)
        state.server shouldBe null
    }

    @Test
    fun points_scored_after_the_match_ended_are_ignored() {
        // The UI disables scoring once the match is over. This is the backstop, and
        // it matters because a log arriving from another device is not our UI.
        val state = fold(toScore(21, 0) + toScore(21, 0) + toScore(5, 5))
        state.pointCount shouldBe 42
        state.currentGame shouldBe SideScore(21, 0)
        state.winner shouldBe Side.HOME
    }

    @Test
    fun a_retirement_hands_the_match_to_the_other_side() {
        val state = fold(toScore(11, 5) + ScoreEvent.Retire(Side.HOME))
        state.winner shouldBe Side.AWAY
        state.isOver shouldBe true
        state.currentGame shouldBe SideScore(11, 5)      // the score it was abandoned at
        state.gamesWon shouldBe SideScore.ZERO           // nobody completed a game
        state.server shouldBe null
    }

    @Test
    fun undo_re_folds_rather_than_reversing_the_score() {
        // toScore(5, 3) ends on a home point, so undoing it gives 4-3 - and the
        // state has to be indistinguishable from having never scored that point,
        // serve and all, not merely to show the right number.
        val log = toScore(5, 3)
        val undone = foldMatchState(ScoringRules.BWF_21, singles, log.undoLast())
        undone.currentGame shouldBe SideScore(4, 3)
        undone shouldBe foldMatchState(ScoringRules.BWF_21, singles, toScore(4, 3))
    }

    @Test
    fun undo_crosses_a_game_boundary() {
        // The point that took the game is undone like any other, and the board goes
        // back to the game that was still being played. Nothing has to remember that
        // a game ended, because nothing recorded that it did.
        val state = fold(toScore(21, 19).undoLast())
        state.gameIndex shouldBe 0
        state.currentGame shouldBe SideScore(20, 19)
        state.completedGames.shouldBeEmpty()
        state.gamesWon shouldBe SideScore.ZERO
    }

    @Test
    fun a_game_won_by_a_single_point_is_a_playable_rule_set() {
        val sudden = ScoringRules(
            pointsToWin = 1, winBy = 1, cap = null, intervalAt = null, gamesToWin = 1, changeEndsAt = null,
        )
        val state = fold(listOf(ScoreEvent.PointTo(Side.AWAY)), sudden)
        state.winner shouldBe Side.AWAY
    }

    @Test
    fun the_winner_of_a_game_can_be_read_without_folding_a_log() {
        gameWinner(SideScore(21, 19), ScoringRules.BWF_21) shouldBe Side.HOME
        gameWinner(SideScore(21, 20), ScoringRules.BWF_21) shouldBe null
        gameWinner(SideScore(20, 20), ScoringRules.BWF_21) shouldBe null
        gameWinner(SideScore(30, 29), ScoringRules.BWF_21) shouldBe Side.HOME
        gameWinner(SideScore(0, 0), ScoringRules.BWF_21) shouldBe null
    }
}
