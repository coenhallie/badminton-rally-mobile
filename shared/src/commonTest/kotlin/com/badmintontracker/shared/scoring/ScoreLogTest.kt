package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ScoreLogTest {

    // encodeDefaults matches the Json that ScoreLogsRepository writes its cache
    // with, and it has to: this test pins the stored shape, and a config that
    // differs from the writer's would pin a shape nothing ever produces.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun log(
        events: List<ScoreEvent> = emptyList(),
        status: ScoreLogStatus = ScoreLogStatus.LIVE,
    ) = ScoreLog(
        id = "log-1",
        videoId = null,
        title = "Thu League vs Marco",
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(firstServer = Side.HOME),
        events = events,
        status = status,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
    )

    @Test
    fun a_log_folds_to_its_own_score() {
        // The state is derived on demand, never stored, so a match read back from
        // the database cannot disagree with the same match still being scored.
        val state = log(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.HOME))).state()
        state.currentGame shouldBe SideScore(home = 2, away = 0)
        state.rules shouldBe ScoringRules.BWF_21
    }

    @Test
    fun a_match_setup_survives_a_round_trip() {
        // Pinned because it is a stored column.
        val setup = MatchSetup(firstServer = Side.AWAY)
        val encoded = json.encodeToString(MatchSetup.serializer(), setup)
        encoded shouldBe """{"first_server":"away"}"""
        json.decodeFromString(MatchSetup.serializer(), encoded) shouldBe setup
    }

    @Test
    fun a_setup_stored_before_doubles_was_removed_still_decodes() {
        // The setup column is jsonb and no migration rewrites it, so rows created
        // while the form still asked for a format carry keys nothing reads now.
        val stored = """{"doubles":true,"first_server":"away","home_starts_right":"second","away_starts_right":"first"}"""
        json.decodeFromString(MatchSetup.serializer(), stored) shouldBe MatchSetup(firstServer = Side.AWAY)
    }

    @Test
    fun a_status_is_stored_as_the_database_spells_it() {
        json.encodeToString(ScoreLogStatus.serializer(), ScoreLogStatus.UNBOUND) shouldBe "\"unbound\""
        json.decodeFromString(ScoreLogStatus.serializer(), "\"reconciled\"") shouldBe ScoreLogStatus.RECONCILED
    }

    @Test
    fun a_whole_log_survives_a_round_trip() {
        val original = log(
            events = listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.TagPoint(0, listOf(PointTag("Forced error", "amber")), "net"),
            ),
            status = ScoreLogStatus.UNBOUND,
        )
        json.decodeFromString(ScoreLog.serializer(), json.encodeToString(ScoreLog.serializer(), original)) shouldBe
            original
    }

    @Test
    fun a_row_from_the_database_decodes_with_no_video() {
        val row = """
            {"id":"log-1","video_id":null,"title":"Thu League",
             "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
             "setup":{"first_server":"home"},
             "events":[{"type":"point","side":"home"}],
             "status":"live",
             "created_at":"2026-08-27T18:00:00Z","updated_at":"2026-08-27T18:00:00Z"}
        """.trimIndent()
        val decoded = json.decodeFromString(ScoreLog.serializer(), row)
        decoded.videoId shouldBe null
        decoded.state().currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_named_new_match_reports_no_problem() {
        createMatchProblem("Thu League") shouldBe null
        createMatchProblem("x".repeat(80)) shouldBe null
    }

    @Test
    fun a_new_match_with_a_bad_name_reports_a_sentence_a_user_can_read() {
        createMatchProblem("  ") shouldBe "Give the match a name."
        createMatchProblem("x".repeat(81)) shouldBe "The match name can be up to 80 characters."
    }
}
