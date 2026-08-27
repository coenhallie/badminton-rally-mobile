package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchScorerTest {

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    private fun repo() =
        ScoreLogsRepository(client = null, settings = MapSettings(), now = { t0 }, ownerId = { "owner-1" })

    private fun scorer(
        repo: ScoreLogsRepository = repo(),
        rules: ScoringRules = ScoringRules.BWF_21,
    ): Pair<ScoreLogsRepository, MatchScorer> {
        val log = repo.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = rules,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        return repo to MatchScorer(repo, log.id)
    }

    @Test
    fun a_scorer_for_a_match_that_is_not_here_reports_no_state_rather_than_crashing() {
        // Reachable two ways: the match was deleted on another screen while this one
        // was open, and a cold start before the cache has loaded. Both platforms get
        // the same answer from here rather than inventing one each.
        val scorer = MatchScorer(repo(), "not-a-match")
        scorer.state().shouldBeNull()
        scorer.canScore() shouldBe false
        scorer.canUndo() shouldBe false
        // And every mutation is a no-op rather than a throw.
        scorer.score(Side.HOME)
        scorer.undo()
        scorer.finish()
        scorer.state().shouldBeNull()
    }

    @Test
    fun scoring_a_point_writes_it_straight_through_to_the_store() {
        // Not "eventually". The phone goes in a bag between games and the process
        // does not survive it, so the point has to be on disk before the next rally.
        val (repo, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
        repo.logs.value.first().events shouldBe listOf(ScoreEvent.PointTo(Side.HOME))
    }

    @Test
    fun undo_removes_the_last_thing_that_happened() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.undo()
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun undoing_a_tag_leaves_the_point_it_named() {
        // The log is append-only, so a tag is its own entry and undo takes it first.
        // Two taps to remove a tagged point is correct rather than surprising: the
        // coach watches the tag go, then the point.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Forced error", "amber")), null)
        scorer.undo()
        scorer.state()?.points?.first()?.tags?.shouldBeEmpty()
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_tag_can_land_on_a_point_that_is_no_longer_the_last_one() {
        // The interval case: three rallies have gone by and the coach finally has
        // time to write down what happened on the first one.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), "cross court winner")
        val points = scorer.state()!!.points
        points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
        points[0].comment shouldBe "cross court winner"
        points[2].tags.shouldBeEmpty()
    }

    @Test
    fun re_tagging_a_point_replaces_what_was_there() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), null)
        scorer.tagPoint(0, listOf(PointTag("Unforced error", "red")), null)
        scorer.state()!!.points[0].tags.map { it.labelName } shouldBe listOf("Unforced error")
    }

    @Test
    fun resetting_a_game_leaves_the_games_before_it_alone() {
        // The control the coach reaches for when he has been scoring the wrong side
        // for half a game. It must not cost him the game he already finished.
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }          // game one to home
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        val state = scorer.state()!!
        state.gameIndex shouldBe 1
        state.currentGame shouldBe SideScore.ZERO
        state.completedGames shouldBe listOf(SideScore(home = 21, away = 0))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun resetting_a_game_keeps_the_tags_on_the_games_before_it() {
        // The trap: ordinals are global across the match, so a cut that renumbered
        // them would slide every earlier tag onto a neighbouring rally.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), null)
        repeat(20) { scorer.score(Side.HOME) }          // 21-0, game one done
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        scorer.state()!!.points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
    }

    @Test
    fun resetting_the_first_game_empties_the_match() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        scorer.state()!!.points.shouldBeEmpty()
        scorer.state()!!.currentGame shouldBe SideScore.ZERO
    }

    @Test
    fun the_first_ordinal_of_the_current_game_is_where_a_reset_cuts() {
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }
        scorer.score(Side.AWAY)
        scorer.firstOrdinalOfCurrentGame(scorer.state()!!) shouldBe 21
    }

    @Test
    fun the_first_ordinal_of_an_unstarted_game_is_the_point_count() {
        // Just after a game ends there are no points in the new game yet, and a cut
        // there has to be a no-op rather than eating the game that just finished.
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }
        scorer.firstOrdinalOfCurrentGame(scorer.state()!!) shouldBe 21
        scorer.resetCurrentGame()
        scorer.state()!!.completedGames shouldBe listOf(SideScore(home = 21, away = 0))
    }

    @Test
    fun a_finished_match_cannot_be_scored_into() {
        val (_, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.state()!!.isOver shouldBe true
        scorer.canScore() shouldBe false
        scorer.score(Side.AWAY)
        // The fold ignores points after the end, but the log must not collect them
        // either: a stray tap is not a rally.
        scorer.state()!!.pointCount shouldBe 42
    }

    @Test
    fun a_finished_match_can_still_be_undone() {
        // The match ended because of a mis-tap. Undo is the way back.
        val (_, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.canUndo() shouldBe true
        scorer.undo()
        scorer.state()!!.isOver shouldBe false
    }

    @Test
    fun finishing_marks_the_match_unbound_rather_than_incomplete() {
        val (repo, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.finish()
        repo.logs.value.first().status shouldBe ScoreLogStatus.UNBOUND
    }
}
