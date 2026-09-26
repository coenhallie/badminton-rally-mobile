package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MatchDetailsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun log(
        home: List<String> = emptyList(),
        away: List<String> = emptyList(),
        format: MatchFormat? = null,
        events: List<ScoreEvent> = emptyList(),
    ) = ScoreLog(
        id = "log-1", videoId = null, title = "Thu League",
        homePlayers = home, awayPlayers = away, format = format,
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
        events = events, status = ScoreLogStatus.LIVE,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
    )

    @Test
    fun the_editor_offers_singles_doubles_and_mixed_doubles_in_that_order() {
        MatchFormat.entries.map { it.label } shouldBe listOf("Singles", "Doubles", "Mixed doubles")
    }

    @Test
    fun a_side_has_one_player_unless_the_format_is_a_doubles_one() {
        playersPerSide(null) shouldBe 1
        playersPerSide(MatchFormat.SINGLES) shouldBe 1
        playersPerSide(MatchFormat.DOUBLES) shouldBe 2
        playersPerSide(MatchFormat.MIXED_DOUBLES) shouldBe 2
    }

    @Test
    fun a_format_is_stored_as_the_database_spells_it() {
        json.encodeToString(MatchFormat.serializer(), MatchFormat.MIXED_DOUBLES) shouldBe "\"mixed_doubles\""
        json.decodeFromString(MatchFormat.serializer(), "\"singles\"") shouldBe MatchFormat.SINGLES
    }

    @Test
    fun only_the_name_is_required() {
        matchDetailsProblem("Thu League", emptyList(), emptyList()) shouldBe null
        matchDetailsProblem("Thu League", listOf("Coen", ""), listOf("", "")) shouldBe null
        matchDetailsProblem(" ", listOf("Coen"), listOf("Marco")) shouldBe "Give the match a name."
        matchDetailsProblem("x".repeat(81), emptyList(), emptyList()) shouldBe
            "The match name can be up to 80 characters."
    }

    @Test
    fun a_player_name_has_a_length_limit_measured_after_trimming() {
        matchDetailsProblem("Thu", listOf("x".repeat(40)), emptyList()) shouldBe null
        matchDetailsProblem("Thu", listOf("  " + "x".repeat(40) + "  "), emptyList()) shouldBe null
        matchDetailsProblem("Thu", emptyList(), listOf("Marco", "x".repeat(41))) shouldBe
            "A player name can be up to 40 characters."
    }

    @Test
    fun submitted_names_are_trimmed_and_cut_to_the_format() {
        submittedPlayers(listOf(" Coen ", "Ana"), null) shouldBe listOf("Coen")
        submittedPlayers(listOf(" Coen ", "Ana"), MatchFormat.SINGLES) shouldBe listOf("Coen")
        submittedPlayers(listOf(" Coen ", "Ana"), MatchFormat.DOUBLES) shouldBe listOf("Coen", "Ana")
        submittedPlayers(listOf("", "Ana"), MatchFormat.DOUBLES) shouldBe listOf("Ana")
        submittedPlayers(listOf(" ", ""), MatchFormat.MIXED_DOUBLES) shouldBe emptyList()
    }

    @Test
    fun an_unnamed_side_is_called_for_where_it_stands() {
        log().sideName(Side.HOME) shouldBe "Home"
        log().sideName(Side.AWAY) shouldBe "Away"
    }

    @Test
    fun a_named_side_is_called_by_its_players() {
        log(home = listOf("Coen")).sideName(Side.HOME) shouldBe "Coen"
        log(away = listOf("Marco", "Li")).sideName(Side.AWAY) shouldBe "Marco / Li"
    }

    @Test
    fun the_players_line_is_absent_until_somebody_is_named() {
        // "Home vs Away" is the same on every row, so it says nothing on a list.
        log().playersLine() shouldBe null
        log(home = listOf("Coen"), away = listOf("Marco")).playersLine() shouldBe "Coen vs Marco"
        log(home = listOf("Coen")).playersLine() shouldBe "Coen vs Away"
    }

    @Test
    fun a_video_born_match_reads_its_players_by_the_same_rule() {
        // The list shows a scored match and a filmed one side by side; the two
        // must not name a pair two ways.
        playersLine(emptyList(), emptyList()) shouldBe null
        playersLine(listOf("Coen", "Ana"), listOf("Marco", "Li")) shouldBe "Coen / Ana vs Marco / Li"
        playersLine(emptyList(), listOf("Marco")) shouldBe "Home vs Marco"
    }

    @Test
    fun the_name_cap_is_the_records_own() {
        // A score log allows 80; a video is capped where the web app caps it.
        matchDetailsProblem("x".repeat(61), emptyList(), emptyList()) shouldBe null
        matchDetailsProblem("x".repeat(60), emptyList(), emptyList(), VIDEO_MATCH_TITLE_CAP) shouldBe null
        matchDetailsProblem("x".repeat(61), emptyList(), emptyList(), VIDEO_MATCH_TITLE_CAP) shouldBe
            "The match name can be up to 60 characters."
        matchDetailsProblem("Club final", listOf("x".repeat(41)), emptyList(), VIDEO_MATCH_TITLE_CAP) shouldBe
            "A player name can be up to 40 characters."
    }

    @Test
    fun the_details_line_says_only_what_somebody_said() {
        matchDetailsLine(null, null) shouldBe null
        matchDetailsLine("Coen vs Marco", null) shouldBe "Coen vs Marco"
        matchDetailsLine(null, MatchFormat.DOUBLES) shouldBe "Doubles"
        matchDetailsLine("Coen / Ana vs Marco / Li", MatchFormat.MIXED_DOUBLES) shouldBe
            "Coen / Ana vs Marco / Li · Mixed doubles"
    }

    @Test
    fun a_row_written_before_the_details_existed_still_decodes() {
        // The cache of a phone that ran the build without these fields, and any
        // row a client that never sends them has written.
        val row = """
            {"id":"log-1","video_id":null,"title":"Thu League",
             "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
             "setup":{"first_server":"home"},"events":[],"status":"live",
             "created_at":"2026-08-27T18:00:00Z","updated_at":"2026-08-27T18:00:00Z"}
        """.trimIndent()
        val decoded = json.decodeFromString(ScoreLog.serializer(), row)
        decoded.homePlayers shouldBe emptyList()
        decoded.format shouldBe null
    }

    @Test
    fun a_row_with_a_null_format_decodes() {
        // What the database hands back for a match nobody has described.
        val row = """
            {"id":"log-1","video_id":null,"title":"Thu League",
             "home_players":[],"away_players":[],"format":null,
             "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
             "setup":{"first_server":"home"},"events":[],"status":"live",
             "created_at":"2026-08-27T18:00:00Z","updated_at":"2026-08-27T18:00:00Z"}
        """.trimIndent()
        json.decodeFromString(ScoreLog.serializer(), row).format shouldBe null
    }

    @Test
    fun details_survive_a_round_trip_under_the_column_names_the_database_uses() {
        val original = log(home = listOf("Coen", "Ana"), away = listOf("Marco", "Li"), format = MatchFormat.MIXED_DOUBLES)
        val encoded = json.encodeToString(ScoreLog.serializer(), original)
        (""""home_players":["Coen","Ana"]""" in encoded) shouldBe true
        (""""format":"mixed_doubles"""" in encoded) shouldBe true
        json.decodeFromString(ScoreLog.serializer(), encoded) shouldBe original
    }

    @Test
    fun an_undescribed_match_is_written_with_empty_lists_and_a_null_format() {
        // The columns are NOT NULL with a default; sending the empty list rather
        // than omitting the key keeps an upsert from depending on that default.
        val encoded = json.encodeToString(ScoreLog.serializer(), log())
        (""""home_players":[]""" in encoded) shouldBe true
        (""""format":null""" in encoded) shouldBe true
    }

    @Test
    fun a_won_match_is_credited_to_the_named_winner() {
        val won = log(home = listOf("Coen"), away = listOf("Marco"), events = List(42) { ScoreEvent.PointTo(Side.AWAY) })
        buildScoreMatchCard(won).statusLine shouldBe "Marco won"
        buildScoreMatchCard(won).playersLine shouldBe "Coen vs Marco"
    }

    @Test
    fun the_shared_text_names_the_players_when_there_are_any() {
        val named = log(home = listOf("Coen"), away = listOf("Marco"), events = listOf(ScoreEvent.PointTo(Side.AWAY)))
        exportMatchText(named) shouldBe """
            Thu League
            Coen vs Marco
            0-1

            1. 0-1 Marco
        """.trimIndent()
    }
}
