package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ScoringRulesTest {

    @Test
    fun bwf_preset_is_the_21_point_game() {
        val rules = ScoringRules.BWF_21
        rules.pointsToWin shouldBe 21
        rules.winBy shouldBe 2
        rules.cap shouldBe 30
        rules.intervalAt shouldBe 11
        rules.gamesToWin shouldBe 2
        rules.changeEndsAt shouldBe 11
    }

    @Test
    fun both_fifteen_point_presets_are_playable() {
        // The two variants clubs actually mean by "to 15". Neither is a guess the
        // engine has to make: they are both here, and L1 picks.
        ScoringRules.CLUB_15.winBy shouldBe 2
        ScoringRules.CLUB_15.cap shouldBe 21
        ScoringRules.STRAIGHT_15.winBy shouldBe 1
        ScoringRules.STRAIGHT_15.cap shouldBe null
        ScoringRules.PRESETS shouldBe listOf(
            ScoringRules.BWF_21, ScoringRules.CLUB_15, ScoringRules.STRAIGHT_15,
        )
    }

    @Test
    fun a_playable_rule_set_reports_no_problem() {
        scoringRulesProblem(21, 2, 30, 11, 2, 11) shouldBe null
        // No cap, no interval and no change of ends are all legitimate.
        scoringRulesProblem(15, 1, null, null, 1, null) shouldBe null
    }

    @Test
    fun an_unplayable_rule_set_reports_a_sentence_a_user_can_read() {
        scoringRulesProblem(0, 2, null, null, 2, null) shouldBe
            "A game needs at least one point."
        scoringRulesProblem(21, 0, null, null, 2, null) shouldBe
            "A game has to be won by at least one point."
        scoringRulesProblem(21, 2, null, null, 0, null) shouldBe
            "A match needs at least one game."
        scoringRulesProblem(21, 2, 20, null, 2, null) shouldBe
            "The cap cannot be below the target score."
        scoringRulesProblem(21, 2, 30, 21, 2, null) shouldBe
            "The interval has to fall inside the game."
        scoringRulesProblem(21, 2, 30, 11, 2, 0) shouldBe
            "The change of ends has to fall inside the game."
    }

    @Test
    fun constructing_an_unplayable_rule_set_fails_loudly() {
        // The UI calls scoringRulesProblem first, so this is unreachable from the
        // app. It stays an exception rather than a silent clamp because a rule set
        // nobody validated must not quietly score a match wrong.
        val failure = assertFailsWith<IllegalArgumentException> {
            ScoringRules(pointsToWin = 21, winBy = 2, cap = 5, intervalAt = null, gamesToWin = 2, changeEndsAt = null)
        }
        failure.message shouldBe "The cap cannot be below the target score."
    }

    @Test
    fun rules_survive_a_json_round_trip() {
        // Rules travel with the match: the same log under different rules is a
        // different score, so they are stored beside it rather than re-derived.
        val json = Json.encodeToString(ScoringRules.serializer(), ScoringRules.BWF_21)
        json shouldBe """{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11}"""
        Json.decodeFromString(ScoringRules.serializer(), json) shouldBe ScoringRules.BWF_21
    }

    @Test
    fun decoding_an_unplayable_stored_rule_set_throws_rather_than_scoring_wrong() {
        // kotlinx-serialization 1.10.0 calls the constructor from the generated
        // deserializer and does not wrap what it throws, so the init check's own
        // sentence arrives intact. Verified against this version rather than
        // assumed: a version that wrapped it would surface a SerializationException
        // here instead, and this test is where that would be caught.
        val stored = """{"points_to_win":21,"win_by":2,"cap":5,"games_to_win":2}"""
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(ScoringRules.serializer(), stored)
        }.message shouldBe "The cap cannot be below the target score."
    }
}
