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
            rules = rules,
            setup = MatchSetup(firstServer = Side.HOME),
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
    fun the_first_serve_can_be_handed_over_before_a_point_is_scored() {
        // The form no longer asks who won the toss, so home serves by default and
        // the board is where that gets corrected.
        val (_, scorer) = scorer()
        scorer.canSwitchFirstServer() shouldBe true
        scorer.switchFirstServer()
        scorer.state()?.server shouldBe Side.AWAY
        scorer.switchFirstServer()
        scorer.state()?.server shouldBe Side.HOME
    }

    @Test
    fun the_first_serve_is_settled_once_a_point_is_scored_and_open_again_after_undo() {
        val (_, scorer) = scorer()
        scorer.score(Side.AWAY)
        scorer.canSwitchFirstServer() shouldBe false
        scorer.switchFirstServer()
        scorer.state()?.setup?.firstServer shouldBe Side.HOME
        scorer.undo()
        scorer.canSwitchFirstServer() shouldBe true
    }

    @Test
    fun a_match_finished_before_it_started_cannot_have_its_serve_switched() {
        val (_, scorer) = scorer()
        scorer.finish()
        scorer.canSwitchFirstServer() shouldBe false
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
    fun undo_takes_back_a_tagged_rally_in_one_tap() {
        // It used to take the tag first and the rally on a second tap, and the
        // first tap changed nothing on the board, so a mis-scored point looked
        // impossible to take back.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.tagPoint(1, listOf(PointTag("Forced error", "amber")), "net")
        scorer.undo()
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
        scorer.state()?.points?.single()?.tags?.shouldBeEmpty()
    }

    @Test
    fun undo_says_which_rally_it_took_back() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        val undone = scorer.undo()
        undone?.side shouldBe Side.AWAY
        undone?.scoreAfter shouldBe SideScore(home = 1, away = 1)
    }

    @Test
    fun there_is_nothing_to_undo_until_a_rally_is_scored() {
        val (_, scorer) = scorer()
        scorer.canUndo() shouldBe false
        scorer.undo().shouldBeNull()
        scorer.score(Side.HOME)
        scorer.canUndo() shouldBe true
    }

    @Test
    fun undo_takes_back_the_rally_the_board_shows_even_on_an_overlong_log() {
        // A log from another device can carry points past the end of the match,
        // which the fold ignores. Undo must take back the last rally the board
        // shows - the one its notice names - not an invisible one after it.
        val (repo, scorer) = scorer()
        val id = repo.logs.value.first().id
        repo.replaceEvents(id, List(43) { ScoreEvent.PointTo(Side.HOME) })
        scorer.state()!!.pointCount shouldBe 42
        val undone = scorer.undo()!!
        undone.scoreAfter shouldBe SideScore(home = 21, away = 0)
        scorer.state()!!.pointCount shouldBe 41
        scorer.state()!!.isOver shouldBe false
    }

    @Test
    fun redo_puts_back_the_rally_and_its_tags() {
        // Undo asks no question, so a mis-tap on it has to be recoverable, tags
        // and note included - re-scoring the rally would not bring those back.
        val (repo, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), "smash")
        val before = repo.logs.value.first().events
        val undone = scorer.undo()!!
        scorer.canRedo(undone) shouldBe true
        scorer.redo(undone)
        repo.logs.value.first().events shouldBe before
        scorer.canRedo(undone) shouldBe false
    }

    @Test
    fun redo_is_gone_once_the_match_has_moved_on() {
        // Putting the old log back after another rally would silently erase that
        // rally, so any change after the undo retires the redo.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        val undone = scorer.undo()!!
        scorer.score(Side.AWAY)
        scorer.canRedo(undone) shouldBe false
        scorer.redo(undone)
        scorer.state()!!.points.map { it.wonBy } shouldBe listOf(Side.AWAY)
    }

    @Test
    fun the_undo_notice_names_the_side_whose_rally_went() {
        val (repo, scorer) = scorer()
        scorer.score(Side.AWAY)
        val undone = scorer.undo()!!
        repo.logs.value.first().undoneNotice(undone) shouldBe "Point to Away undone"
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
