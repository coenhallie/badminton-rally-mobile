package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ScoreEventTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val logSerializer = ListSerializer(ScoreEvent.serializer())

    private fun point(side: Side) = ScoreEvent.PointTo(side)

    @Test
    fun a_side_knows_its_opponent() {
        Side.HOME.other shouldBe Side.AWAY
        Side.AWAY.other shouldBe Side.HOME
    }

    @Test
    fun the_stored_shape_of_a_log_is_pinned() {
        // The discriminators are the wire format of every stored match. They are
        // short SerialNames rather than class names precisely so renaming a Kotlin
        // class cannot silently invalidate every log already on a coach's phone.
        val log = listOf(
            point(Side.HOME),
            ScoreEvent.TagPoint(pointOrdinal = 0, tags = listOf(PointTag("Forced error", "amber")), comment = "net"),
            ScoreEvent.Retire(Side.AWAY),
        )
        json.encodeToString(logSerializer, log) shouldBe
            """[{"type":"point","side":"home"},""" +
            """{"type":"tag","point_ordinal":0,"tags":[{"label_name":"Forced error","label_color":"amber"}],"comment":"net"},""" +
            """{"type":"retire","side":"away"}]"""
    }

    @Test
    fun a_log_survives_a_round_trip() {
        val log = listOf(
            point(Side.HOME),
            point(Side.AWAY),
            ScoreEvent.TagPoint(1, listOf(PointTag("Good shot", "green")), null),
        )
        json.decodeFromString(logSerializer, json.encodeToString(logSerializer, log)) shouldBe log
    }

    @Test
    fun undo_takes_back_the_last_rally_with_its_tags_and_note() {
        val log = listOf(
            point(Side.HOME),
            point(Side.AWAY),
            ScoreEvent.TagPoint(1, listOf(PointTag("Forced error", "amber")), null),
            ScoreEvent.TagPoint(1, listOf(PointTag("Forced error", "amber")), "sloppy"),
        )
        // One undo, however many times the rally was tagged: the coach is taking
        // back a score, and the tags described a rally that is no longer there.
        log.undoLastPoint() shouldBe listOf(point(Side.HOME))
        log.undoLastPoint().undoLastPoint().shouldBeEmpty()
    }

    @Test
    fun undo_keeps_a_note_on_an_earlier_rally_written_after_the_last_one() {
        // The interval case: the note on point 0 was the last thing written, but
        // it is not about the rally being taken back, so it must survive it.
        val note = ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), "cross court winner")
        val log = listOf(point(Side.HOME), point(Side.AWAY), note)
        log.undoLastPoint() shouldBe listOf(point(Side.HOME), note)
    }

    @Test
    fun a_rally_scored_again_after_undo_does_not_inherit_the_old_tags() {
        // The fold resolves tags by ordinal, so a tag left behind for the undone
        // ordinal would attach itself to whichever rally is scored there next.
        val log = listOf(point(Side.HOME), ScoreEvent.TagPoint(0, listOf(PointTag("Unforced error", "red")), null))
        val rescored = log.undoLastPoint() + point(Side.AWAY)
        foldMatchState(ScoringRules.BWF_21, MatchSetup(firstServer = Side.HOME), rescored)
            .points.single().tags.shouldBeEmpty()
    }

    @Test
    fun undoing_a_log_without_a_rally_is_a_no_op_rather_than_a_crash() {
        // The Undo control is disabled until a rally is scored. If that ever slips,
        // a courtside crash is a far worse answer than nothing happening.
        emptyList<ScoreEvent>().undoLastPoint().shouldBeEmpty()
    }

    @Test
    fun tagging_appends_rather_than_rewriting_the_point() {
        val log = listOf(point(Side.HOME), point(Side.AWAY))
        log.tagPoint(0, listOf(PointTag("Unforced error", "red")), null) shouldBe
            log + ScoreEvent.TagPoint(0, listOf(PointTag("Unforced error", "red")), null)
    }

    @Test
    fun cutting_at_an_ordinal_takes_that_point_and_everything_after_it() {
        val log = listOf(
            point(Side.HOME),                                   // ordinal 0
            ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
            point(Side.AWAY),                                   // ordinal 1
            point(Side.HOME),                                   // ordinal 2
            ScoreEvent.TagPoint(2, emptyList(), "long rally"),
        )
        // Reset-to-here: point 1 onward goes, and so do the tags naming them. The
        // tag on point 0 stays, and its ordinal still names the right point
        // because nothing below the cut moved.
        log.dropPointsFrom(1) shouldBe listOf(
            point(Side.HOME),
            ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
        )
    }

    @Test
    fun cutting_removes_a_retirement_as_well() {
        // A cut re-opens the match, so a retirement recorded after the cut point
        // cannot survive it - it would leave the log saying the match is over at a
        // score that never happened.
        val log = listOf(point(Side.HOME), ScoreEvent.Retire(Side.AWAY))
        log.dropPointsFrom(1) shouldBe listOf(point(Side.HOME))
    }

    @Test
    fun cutting_at_zero_empties_the_log() {
        listOf(point(Side.HOME), point(Side.AWAY)).dropPointsFrom(0).shouldBeEmpty()
    }

    @Test
    fun cutting_beyond_the_last_point_changes_nothing() {
        val log = listOf(point(Side.HOME), ScoreEvent.TagPoint(0, emptyList(), "x"))
        log.dropPointsFrom(9) shouldBe log
    }
}
