package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class ScoreMatchCardTest {

    private fun log(
        events: List<ScoreEvent> = emptyList(),
        status: ScoreLogStatus = ScoreLogStatus.LIVE,
        home: List<String> = listOf("Coen"),
        away: List<String> = listOf("Marco"),
        videoId: String? = null,
    ) = ScoreLog(
        id = "log-1",
        videoId = videoId,
        title = "Thu League",
        homePlayers = home,
        awayPlayers = away,
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = home.size == 2, firstServer = Side.HOME),
        events = events,
        status = status,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
    )

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
    fun a_singles_side_reads_as_one_name() {
        sideLabel(listOf("Coen")) shouldBe "Coen"
    }

    @Test
    fun a_doubles_pair_reads_as_two() {
        sideLabel(listOf("Coen", "Ana")) shouldBe "Coen / Ana"
    }

    @Test
    fun an_unscored_match_says_so_rather_than_showing_nil_nil() {
        // "0-0" on a match nobody has started reads as a bug. This is the state a
        // match sits in between being created and the first rally.
        scoreLine(log().state()) shouldBe "Not started"
    }

    @Test
    fun a_match_in_progress_shows_the_game_being_played() {
        scoreLine(log(toScore(11, 9)).state()) shouldBe "11-9"
    }

    @Test
    fun a_match_in_progress_shows_the_games_already_won_first() {
        scoreLine(log(toScore(21, 18) + toScore(5, 3)).state()) shouldBe "21-18, 5-3"
    }

    @Test
    fun a_finished_match_shows_every_game_and_no_trailing_repeat() {
        // The fold leaves currentGame holding the final game's score, so appending
        // it as a game in progress would print the last game twice.
        scoreLine(log(toScore(21, 18) + toScore(21, 15)).state()) shouldBe "21-18, 21-15"
    }

    @Test
    fun a_card_carries_the_row_a_list_draws() {
        val card = buildScoreMatchCard(log(toScore(11, 9)))
        card.scoreLogId shouldBe "log-1"
        card.title shouldBe "Thu League"
        card.createdAtEpochMs shouldBe Instant.parse("2026-08-27T18:00:00Z").toEpochMilliseconds()
        card.playersLine shouldBe "Coen vs Marco"
        card.scoreLine shouldBe "11-9"
        card.statusLine shouldBe "Scoring"
        card.isLive shouldBe true
        card.hasVideo shouldBe false
    }

    @Test
    fun a_doubles_card_names_all_four_players() {
        val card = buildScoreMatchCard(log(home = listOf("Coen", "Ana"), away = listOf("Marco", "Li")))
        card.playersLine shouldBe "Coen / Ana vs Marco / Li"
    }

    @Test
    fun a_finished_match_says_who_won() {
        val card = buildScoreMatchCard(log(toScore(21, 18) + toScore(21, 15), status = ScoreLogStatus.UNBOUND))
        card.statusLine shouldBe "Coen won"
        card.isLive shouldBe false
    }

    @Test
    fun a_finished_doubles_match_names_the_winning_pair() {
        val card = buildScoreMatchCard(
            log(
                toScore(21, 18) + toScore(21, 15),
                status = ScoreLogStatus.UNBOUND,
                home = listOf("Coen", "Ana"),
                away = listOf("Marco", "Li"),
            )
        )
        card.statusLine shouldBe "Coen / Ana won"
    }

    @Test
    fun a_match_that_ended_with_no_video_is_not_apologised_for() {
        // §7.1: unbound is a terminal state, not a failure. Nothing in the row may
        // read as "missing a video".
        val card = buildScoreMatchCard(log(toScore(21, 0) + toScore(21, 0), status = ScoreLogStatus.UNBOUND))
        card.statusLine shouldBe "Coen won"
        card.hasVideo shouldBe false
    }

    @Test
    fun a_bound_match_knows_it_has_a_video() {
        val card = buildScoreMatchCard(log(status = ScoreLogStatus.BOUND, videoId = "vid-1"))
        card.hasVideo shouldBe true
    }
}
