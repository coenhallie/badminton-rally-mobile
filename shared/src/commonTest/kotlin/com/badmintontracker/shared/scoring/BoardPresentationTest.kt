package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class BoardPresentationTest {

    private val t0 = Instant.parse("2026-10-13T18:00:00Z")
    private val good = PointTag("Good shot", "green")
    private val net = PointTag("Net", "slate")

    private fun board(rules: ScoringRules = ScoringRules.BWF_21): Pair<ScoreLogsRepository, MatchScorer> {
        val repo = ScoreLogsRepository(MapSettings(), now = { t0 }, ownerId = { "owner-1" })
        val log = repo.create(title = "Club night", rules = rules, setup = MatchSetup(firstServer = Side.HOME))
        return repo to MatchScorer(repo, log.id)
    }

    @Test
    fun the_row_targets_a_rally_when_there_is_one_and_moments_otherwise() {
        val (_, s) = board()
        boardTarget(s.state()!!, null) shouldBe BoardTarget.Moments
        s.score(Side.HOME)
        boardTarget(s.state()!!, 0) shouldBe BoardTarget.Rally(0)
        boardTarget(s.state()!!, null) shouldBe BoardTarget.Moments
        boardTarget(s.state()!!, 5) shouldBe BoardTarget.Moments   // a rally undo took back
    }

    @Test
    fun captions() {
        val (_, s) = board()
        val m = { s.state()!! }
        boardCaption(m(), BoardTarget.Moments, hasBoardLabels = true, draft = null) shouldBe
            "Tap a side to score, or a label to log a moment"
        boardCaption(m(), BoardTarget.Moments, hasBoardLabels = false, draft = null) shouldBe
            "Tap a side to score, or SERVE to switch server"
        s.logMoment(listOf(good))
        boardCaption(m(), BoardTarget.Moments, true, null) shouldBe "1 moment logged"
        s.logMoment(listOf(net))
        boardCaption(m(), BoardTarget.Moments, true, null) shouldBe "2 moments logged"
        val draft = s.openMomentDraft()!!
        boardCaption(m(), BoardTarget.Moments, true, draft) shouldBe "New moment"
        boardCaption(m(), BoardTarget.Moments, true, draft.toggle(good).toggle(net)) shouldBe "New moment: Good shot, Net"
        s.score(Side.AWAY)
        boardCaption(m(), BoardTarget.Rally(0), true, null) shouldBe "Point 1: 0-1"
    }

    @Test
    fun captions_once_the_match_is_over() {
        val (_, s) = board(ScoringRules(pointsToWin = 1, winBy = 1, cap = null, gamesToWin = 1, intervalAt = null, changeEndsAt = null))
        s.score(Side.HOME)
        boardCaption(s.state()!!, BoardTarget.Rally(0), true, null) shouldBe "Match over"
        boardCaption(s.state()!!, BoardTarget.Moments, true, null) shouldBe "Match over"
        s.logMoment(listOf(good))
        boardCaption(s.state()!!, BoardTarget.Moments, true, null) shouldBe "Match over · 1 moment logged"
    }

    @Test
    fun notices_say_what_happened_and_how_to_reverse_it() {
        val (repo, s) = board()
        val log = { repo.logs.value.single() }
        val m = s.logMoment(listOf(good, net))!!
        val added = BoardNotice.MomentAdded(m)
        log().noticeMessage(added) shouldBe "Moment added: Good shot, Net"
        added.actionLabel shouldBe "Undo"
        s.isLive(added) shouldBe true

        s.reverse(added)
        s.state()!!.moments shouldBe emptyList()
        s.isLive(added) shouldBe false

        val noteOnly = s.commitMomentDraft(s.openMomentDraft()!!.withText("short"))!!
        log().noticeMessage(BoardNotice.MomentAdded(noteOnly)) shouldBe "Moment added"

        val removed = BoardNotice.MomentRemoved(s.removeMoment(noteOnly.id)!!)
        log().noticeMessage(removed) shouldBe "Moment removed"
        s.isLive(removed) shouldBe true
        s.reverse(removed)
        s.state()!!.moments shouldBe listOf(noteOnly)

        s.score(Side.HOME)
        val undone = BoardNotice.RallyUndone(s.undo()!!)
        log().noticeMessage(undone) shouldBe "Point to Home undone"
        undone.actionLabel shouldBe "Redo"
        s.reverse(undone)
        s.state()!!.pointCount shouldBe 1
    }
}
