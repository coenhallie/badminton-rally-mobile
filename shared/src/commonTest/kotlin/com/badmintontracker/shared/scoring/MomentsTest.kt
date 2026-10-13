package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MomentsTest {

    private val t0 = Instant.parse("2026-10-13T18:00:00Z")
    private val rules = ScoringRules.BWF_21
    private val setup = MatchSetup(firstServer = Side.HOME)
    private val good = PointTag("Good shot", "green")
    private val net = PointTag("Net", "slate")

    private fun moment(id: String, after: Int, at: Instant = t0, tags: List<PointTag> = listOf(good), comment: String? = null) =
        ScoreEvent.Moment(id = id, afterPoints = after, loggedAt = at, tags = tags, comment = comment)

    private fun fold(vararg events: ScoreEvent) = foldMatchState(rules, setup, events.toList())

    @Test
    fun a_moment_folds_without_touching_the_score() {
        val state = fold(moment("m1", 0))
        state.points.shouldBeEmpty()
        state.currentGame shouldBe SideScore.ZERO
        state.server shouldBe Side.HOME
        state.moments.map { it.id } shouldBe listOf("m1")
        state.moments.single().tags shouldBe listOf(good)
    }

    @Test
    fun the_last_version_of_a_moment_wins() {
        val state = fold(moment("m1", 0), moment("m1", 0, tags = listOf(net), comment = "short"))
        state.moments.single().tags shouldBe listOf(net)
        state.moments.single().comment shouldBe "short"
    }

    @Test
    fun a_version_with_nothing_on_it_removes_the_moment() {
        fold(moment("m1", 0), moment("m1", 0, tags = emptyList(), comment = null)).moments.shouldBeEmpty()
    }

    @Test
    fun a_blank_comment_alone_is_nothing_on_it() {
        fold(moment("m1", 0, tags = emptyList(), comment = "   ")).moments.shouldBeEmpty()
    }

    @Test
    fun an_anchor_past_the_last_rally_clamps_to_it() {
        // Logged after rally 2, and undo then took rally 2 back.
        val state = fold(ScoreEvent.PointTo(Side.HOME), moment("m1", 2))
        state.moments.single().afterPoints shouldBe 1
    }

    @Test
    fun moments_are_ordered_by_place_then_time() {
        val state = fold(
            ScoreEvent.PointTo(Side.HOME),
            moment("late", 0, at = Instant.parse("2026-10-13T18:05:00Z")),
            moment("early", 0, at = Instant.parse("2026-10-13T18:01:00Z")),
            moment("after", 1, at = Instant.parse("2026-10-13T18:00:30Z")),
        )
        state.moments.map { it.id } shouldBe listOf("early", "late", "after")
    }

    @Test
    fun a_moment_after_match_point_still_folds() {
        val oneGame = ScoringRules(pointsToWin = 1, winBy = 1, cap = null, gamesToWin = 1, intervalAt = null, changeEndsAt = null)
        val state = foldMatchState(oneGame, setup, listOf(ScoreEvent.PointTo(Side.AWAY), moment("m1", 1)))
        state.winner shouldBe Side.AWAY
        state.moments.map { it.id } shouldBe listOf("m1")
    }

    @Test
    fun undo_and_reset_keep_moments() {
        val events = listOf(ScoreEvent.PointTo(Side.HOME), moment("m1", 1), ScoreEvent.PointTo(Side.AWAY))
        events.undoLastPoint() shouldBe listOf(ScoreEvent.PointTo(Side.HOME), moment("m1", 1))
        events.dropPointsFrom(0) shouldBe listOf(moment("m1", 1))
    }

    @Test
    fun a_moment_is_stored_under_its_wire_names() {
        val json = Json.encodeToString(ScoreEvent.serializer(), moment("m1", 3, comment = "short"))
        json shouldBe """{"type":"moment","id":"m1","after_points":3,"logged_at":"2026-10-13T18:00:00Z",""" +
            """"tags":[{"label_name":"Good shot","label_color":"green"}],"comment":"short"}"""
        Json.decodeFromString(ScoreEvent.serializer(), json) shouldBe moment("m1", 3, comment = "short")
    }

    @Test
    fun clock_time_is_the_24_hour_wall_clock() {
        clockTime(Instant.parse("2026-10-13T07:05:00Z"), TimeZone.UTC) shouldBe "07:05"
        clockTime(Instant.parse("2026-10-13T19:42:00Z"), TimeZone.of("Europe/Amsterdam")) shouldBe "21:42"
    }

    @Test
    fun the_timeline_puts_each_moment_before_the_rally_that_followed_it() {
        val state = fold(
            moment("before", 0),
            ScoreEvent.PointTo(Side.HOME),
            ScoreEvent.PointTo(Side.AWAY),
            moment("between", 1),
            moment("end", 2),
        )
        matchTimeline(state).map {
            when (it) {
                is TimelineEntry.Rally -> "r${it.point.ordinal}"
                is TimelineEntry.Moment -> it.moment.id
            }
        } shouldBe listOf("before", "r0", "between", "r1", "end")
    }
}
