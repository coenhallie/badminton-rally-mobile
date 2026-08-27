package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ScoreLogTest {

    // encodeDefaults matches the Json that ScoreLogsRepository writes its cache
    // with, and it has to: this test pins the stored shape, and a config that
    // differs from the writer's would pin a shape nothing ever produces. It is
    // load bearing below, where awayStartsRight is left at its default and would
    // otherwise simply be absent from the encoded string.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun log(
        events: List<ScoreEvent> = emptyList(),
        status: ScoreLogStatus = ScoreLogStatus.LIVE,
        doubles: Boolean = false,
    ) = ScoreLog(
        id = "log-1",
        videoId = null,
        title = "Thu League vs Marco",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = doubles, firstServer = Side.HOME),
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
        // Pinned because it is a stored column. The doubles arrangement in
        // particular must not decode as FIRST when SECOND was written, or a coach
        // reopening a match is told the wrong player is serving.
        val setup = MatchSetup(
            doubles = true,
            firstServer = Side.AWAY,
            homeStartsRight = PairPlayer.SECOND,
            awayStartsRight = PairPlayer.FIRST,
        )
        val encoded = json.encodeToString(MatchSetup.serializer(), setup)
        encoded shouldBe
            """{"doubles":true,"first_server":"away","home_starts_right":"second","away_starts_right":"first"}"""
        json.decodeFromString(MatchSetup.serializer(), encoded) shouldBe setup
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
            doubles = true,
        )
        json.decodeFromString(ScoreLog.serializer(), json.encodeToString(ScoreLog.serializer(), original)) shouldBe
            original
    }

    @Test
    fun a_row_from_the_database_decodes_with_no_video() {
        val row = """
            {"id":"log-1","video_id":null,"title":"Thu League",
             "home_players":["Coen"],"away_players":["Marco"],
             "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
             "setup":{"doubles":false,"first_server":"home","home_starts_right":"first","away_starts_right":"first"},
             "events":[{"type":"point","side":"home"}],
             "status":"live",
             "created_at":"2026-08-27T18:00:00Z","updated_at":"2026-08-27T18:00:00Z"}
        """.trimIndent()
        val decoded = json.decodeFromString(ScoreLog.serializer(), row)
        decoded.videoId shouldBe null
        decoded.state().currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_playable_new_match_reports_no_problem() {
        createMatchProblem("Thu League", listOf("Coen"), listOf("Marco"), doubles = false) shouldBe null
        createMatchProblem("Club night", listOf("A", "B"), listOf("C", "D"), doubles = true) shouldBe null
    }

    @Test
    fun a_new_match_missing_something_reports_a_sentence_a_user_can_read() {
        createMatchProblem("  ", listOf("Coen"), listOf("Marco"), doubles = false) shouldBe
            "Give the match a name."
        createMatchProblem("x".repeat(81), listOf("Coen"), listOf("Marco"), doubles = false) shouldBe
            "The match name can be up to 80 characters."
        createMatchProblem("Thu", listOf(" "), listOf("Marco"), doubles = false) shouldBe
            "Name both players."
        createMatchProblem("Thu", listOf("A"), listOf("C", "D"), doubles = true) shouldBe
            "Doubles needs two players on each side."
        createMatchProblem("Thu", listOf("A", "B"), listOf("C"), doubles = true) shouldBe
            "Doubles needs two players on each side."
        createMatchProblem("Thu", listOf("A", "B"), listOf("C"), doubles = false) shouldBe
            "Singles has one player on each side."
        createMatchProblem("Thu", listOf("x".repeat(41)), listOf("Marco"), doubles = false) shouldBe
            "A player name can be up to 40 characters."
    }
}
