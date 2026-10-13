package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class ScoreEditTest {

    private val t0 = Instant.parse("2026-09-29T18:00:00Z")

    private class Board(val repo: ScoreLogsRepository, val scorer: MatchScorer, val id: String) {
        val log: ScoreLog get() = repo.get(id)!!
        val state: MatchState get() = log.state()

        fun play(vararg sides: Side) = sides.forEach { scorer.score(it) }
        fun play(side: Side, times: Int) = repeat(times) { scorer.score(side) }
        fun tag(ordinal: Int, name: String, comment: String? = null) =
            scorer.tagPoint(ordinal, listOf(PointTag(name, "amber")), comment)
    }

    private fun board(rules: ScoringRules = ScoringRules.BWF_21): Board {
        val repo = ScoreLogsRepository(client = null, settings = MapSettings(), now = { t0 }, ownerId = { "owner-1" })
        val log = repo.create(title = "Thu League", rules = rules, setup = MatchSetup(firstServer = Side.HOME))
        return Board(repo, MatchScorer(repo, log.id), log.id)
    }

    private val H = Side.HOME
    private val A = Side.AWAY

    @Test
    fun raising_a_side_adds_rallies_won_by_that_side_and_it_serves_next() {
        // The usual reason to reach for the editor: rallies went by unscored. They
        // were real rallies, so they are added as rallies and the count still
        // lines up with the clips the video is cut into.
        val b = board()
        b.play(H, A, H, A, A)
        val plan = b.log.planScoreEdit(home = 4, away = 3, nextServer = null)
        plan.problem.shouldBeNull()
        plan.canSave shouldBe true
        plan.summary shouldBe "Adds 2 rallies for Home."
        plan.events.drop(5) shouldBe listOf(ScoreEvent.PointTo(H), ScoreEvent.PointTo(H))
        plan.server shouldBe H
    }

    @Test
    fun lowering_a_side_removes_its_latest_rallies_and_leaves_every_other_tag_on_its_own_rally() {
        val b = board()
        b.play(H, A, H, A)
        b.tag(0, "Smash")
        b.tag(1, "Net")
        b.tag(2, "Lift", comment = "too short")
        b.tag(3, "Drop")
        val plan = b.log.planScoreEdit(home = 1, away = 2, nextServer = null)
        plan.summary shouldBe "Removes 1 rally from Home, with its tags or note."
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.currentGame shouldBe SideScore(home = 1, away = 2)
        after.points.map { it.wonBy } shouldBe listOf(H, A, A)
        after.points.map { it.tags.single().labelName } shouldBe listOf("Smash", "Net", "Drop")
        after.points.map { it.comment } shouldBe listOf(null, null, null)
    }

    @Test
    fun a_retag_keeps_following_its_rally_when_an_earlier_rally_is_removed() {
        // The fold reads the last TagPoint per ordinal. A removal renumbers the
        // rallies after it, so the resolved tags must move with them rather than
        // the raw entries being kept at their old ordinals.
        val b = board()
        b.play(H, A, A)
        b.tag(2, "Clear")
        b.tag(2, "Drive", comment = "late")
        val plan = b.log.planScoreEdit(home = 0, away = 2, nextServer = null)
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.points.map { it.wonBy } shouldBe listOf(A, A)
        after.points[1].tags.single().labelName shouldBe "Drive"
        after.points[1].comment shouldBe "late"
        after.points[0].tags shouldBe emptyList()
    }

    @Test
    fun finished_games_and_their_tags_are_never_touched() {
        val b = board()
        b.play(H, 21)
        b.tag(20, "Winner")
        b.play(A, H, A)
        val plan = b.log.planScoreEdit(home = 0, away = 0, nextServer = null)
        plan.summary shouldBe "Removes 1 rally from Home and 2 from Away."
        plan.events shouldBe b.log.events.dropPointsFrom(21)
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.completedGames shouldBe listOf(SideScore(home = 21, away = 0))
        after.points[20].tags.single().labelName shouldBe "Winner"
    }

    @Test
    fun a_score_the_game_would_have_ended_before_is_refused_and_changes_nothing() {
        val b = board()
        val plan = b.log.planScoreEdit(home = 25, away = 10, nextServer = null)
        plan.problem shouldBe "The game would be over before 25-10."
        plan.canSave shouldBe false
        plan.events shouldBe b.log.events
    }

    @Test
    fun a_point_cannot_be_added_to_the_loser_of_a_game_that_is_already_won() {
        val b = board(ScoringRules.of(GamePoints.TWENTY_ONE, MatchSets.ONE))
        b.play(A, 19)
        b.play(H, 21)
        b.state.isOver shouldBe true
        b.log.planScoreEdit(home = 21, away = 20, nextServer = null).problem shouldBe
            "The game would be over before 21-20."
    }

    @Test
    fun negative_scores_are_refused() {
        val b = board()
        b.log.planScoreEdit(home = -1, away = 0, nextServer = null).problem shouldBe "A score can't be below 0."
    }

    @Test
    fun a_score_that_ends_the_game_is_accepted_and_says_so() {
        val b = board()
        repeat(19) { b.play(H, A) }
        val plan = b.log.planScoreEdit(home = 21, away = 19, nextServer = null)
        plan.problem.shouldBeNull()
        plan.summary shouldBe "Adds 2 rallies for Home. Home takes game 1."
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.completedGames shouldBe listOf(SideScore(home = 21, away = 19))
        after.gameIndex shouldBe 1
        // The game's winner opens the next one, so there is nothing to choose.
        plan.server shouldBe H
        plan.servers shouldBe SideFlags(home = true, away = false)
    }

    @Test
    fun a_score_that_ends_the_match_says_so_and_nobody_serves_next() {
        val b = board(ScoringRules.of(GamePoints.ELEVEN, MatchSets.ONE))
        b.play(A, 3)
        val plan = b.log.planScoreEdit(home = 11, away = 3, nextServer = null)
        plan.summary shouldBe "Adds 11 rallies for Home. Home wins the match."
        plan.server.shouldBeNull()
        plan.servers shouldBe SideFlags.NONE
    }

    @Test
    fun lowering_the_winner_of_a_finished_match_puts_it_back_in_play() {
        val b = board(ScoringRules.of(GamePoints.ELEVEN, MatchSets.ONE))
        b.play(H, 11)
        val plan = b.log.planScoreEdit(home = 10, away = 0, nextServer = null)
        plan.summary shouldBe "Removes 1 rally from Home. The match is back in play."
        foldMatchState(b.log.rules, b.log.setup, plan.events).isOver shouldBe false
    }

    @Test
    fun a_deuce_score_is_reached_without_passing_through_a_finished_game() {
        // Adding 22 and 21 naively, one side's run first, would end the game at
        // 21-0. The rallies are interleaved so every score on the way is live.
        val b = board()
        val plan = b.log.planScoreEdit(home = 22, away = 21, nextServer = null)
        plan.problem.shouldBeNull()
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.gameIndex shouldBe 0
        after.currentGame shouldBe SideScore(home = 22, away = 21)
        after.completedGames shouldBe emptyList()
    }

    @Test
    fun a_capped_game_is_reached_without_passing_through_a_finished_game() {
        // 15-point games cap at 21: 21-20 is a finished game, reachable only by
        // staying level almost all the way.
        val b = board(ScoringRules.of(GamePoints.FIFTEEN, MatchSets.THREE))
        val plan = b.log.planScoreEdit(home = 21, away = 20, nextServer = null)
        plan.problem.shouldBeNull()
        foldMatchState(b.log.rules, b.log.setup, plan.events).completedGames shouldBe
            listOf(SideScore(home = 21, away = 20))
    }

    @Test
    fun when_both_sides_are_raised_either_can_be_chosen_to_serve_next() {
        val b = board()
        b.play(A, H)
        b.state.server shouldBe H
        val kept = b.log.planScoreEdit(home = 3, away = 3, nextServer = null)
        kept.servers shouldBe SideFlags(home = true, away = true)
        // Left alone, the side serving now keeps the serve.
        kept.server shouldBe H
        kept.events.last() shouldBe ScoreEvent.PointTo(H)

        val handed = b.log.planScoreEdit(home = 3, away = 3, nextServer = A)
        handed.server shouldBe A
        handed.events.last() shouldBe ScoreEvent.PointTo(A)
        foldMatchState(b.log.rules, b.log.setup, handed.events).server shouldBe A
    }

    @Test
    fun only_the_raised_side_can_serve_next() {
        val b = board()
        b.play(A, H)
        val plan = b.log.planScoreEdit(home = 1, away = 3, nextServer = H)
        plan.servers shouldBe SideFlags(home = false, away = true)
        plan.server shouldBe A
    }

    @Test
    fun a_removal_alone_leaves_the_serve_with_whoever_won_the_last_rally_kept() {
        val b = board()
        b.play(H, A, A)
        val plan = b.log.planScoreEdit(home = 1, away = 1, nextServer = H)
        plan.servers shouldBe SideFlags(home = false, away = true)
        plan.server shouldBe A
    }

    @Test
    fun the_score_as_it_stands_is_no_change() {
        val b = board()
        b.play(H, A)
        val plan = b.log.planScoreEdit(home = 1, away = 1, nextServer = null)
        plan.problem.shouldBeNull()
        plan.summary shouldBe "No change."
        plan.canSave shouldBe false
    }

    @Test
    fun the_sides_are_called_by_their_players_once_named() {
        val b = board()
        b.scorer.updateDetails("Thu League", null, listOf("Coen"), listOf("Marco"))
        b.log.planScoreEdit(home = 0, away = 1, nextServer = null).summary shouldBe "Adds 1 rally for Marco."
    }

    @Test
    fun the_scorer_saves_an_edit_and_reports_whether_it_did() {
        val b = board()
        b.play(H)
        b.scorer.editScore(home = 3, away = 2, nextServer = A) shouldBe true
        b.state.currentGame shouldBe SideScore(home = 3, away = 2)
        b.state.server shouldBe A
        b.scorer.editScore(home = 40, away = 2, nextServer = null) shouldBe false
        b.state.currentGame shouldBe SideScore(home = 3, away = 2)
    }

    @Test
    fun a_match_ended_by_a_retirement_is_not_edited() {
        // The editor rebuilds the current game, and a retirement sits after it.
        // Dropping it would quietly un-retire a side, so the editor is not offered.
        val b = board()
        b.play(H)
        b.repo.replaceEvents(b.id, b.log.events + ScoreEvent.Retire(A))
        b.scorer.canEditScore() shouldBe false
        b.scorer.editScore(home = 2, away = 0, nextServer = null) shouldBe false
    }

    @Test
    fun the_editor_is_offered_on_a_match_on_this_phone_and_not_otherwise() {
        board().scorer.canEditScore() shouldBe true
        MatchScorer(board().repo, "not-a-match").canEditScore() shouldBe false
    }

    @Test
    fun the_editor_title_names_the_game_when_there_is_more_than_one() {
        val b = board()
        b.state.scoreEditTitle() shouldBe "Set the score · Game 1"
        board(ScoringRules.of(GamePoints.TWENTY_ONE, MatchSets.ONE)).state.scoreEditTitle() shouldBe "Set the score"
    }

    @Test
    fun a_removed_rally_does_not_slide_a_moment_onto_another() {
        // Home, Away, Home, then a moment, then Away: 2-2. Taking Home's latest
        // rally back (the third) must leave the moment just before Away's second.
        val b = board()
        b.play(H, A, H)
        b.scorer.logMoment(listOf(PointTag("Net", "slate")))
        b.play(A)
        val plan = b.log.planScoreEdit(home = 1, away = 2, nextServer = null)
        plan.canSave shouldBe true
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.points.map { it.wonBy } shouldBe listOf(H, A, A)
        after.moments.single().afterPoints shouldBe 2
    }

    @Test
    fun a_moment_in_a_later_game_is_re_anchored_from_that_game_s_start() {
        // Game 1 is H H H. Game 2 is H A H, a moment, then A: 2-2. Taking Home's
        // latest rally back leaves the moment after game 2's second rally, which
        // is rally 5 of the match, not rally 2.
        val b = board(ScoringRules(pointsToWin = 3, winBy = 1, cap = null, gamesToWin = 2, intervalAt = null, changeEndsAt = null))
        b.play(H, H, H)
        b.play(H, A, H)
        b.scorer.logMoment(listOf(PointTag("Net", "slate")))
        b.play(A)
        b.state.gameIndex shouldBe 1
        val plan = b.log.planScoreEdit(home = 1, away = 2, nextServer = null)
        plan.canSave shouldBe true
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.points.map { it.wonBy } shouldBe listOf(H, H, H, H, A, A)
        after.moments.single().afterPoints shouldBe 5
    }

    @Test
    fun added_rallies_go_after_a_moment_at_the_start_of_the_game() {
        val b = board()
        b.scorer.logMoment(listOf(PointTag("Net", "slate")))
        val plan = b.log.planScoreEdit(home = 2, away = 0, nextServer = null)
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.pointCount shouldBe 2
        after.moments.single().afterPoints shouldBe 0
    }
}
