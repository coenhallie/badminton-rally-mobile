package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.PLAYER_NAME_TOO_LONG
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchPlayersTest {

    private val at = Instant.parse("2026-10-30T10:00:00Z")

    private fun player(id: String, name: String) =
        Player(id, "owner-1", name, RacketArm.RIGHT, at, at)

    private val marco = player("player-marco", "Marco")
    private val ana = player("player-ana", "Ana")

    @Test
    fun forgetting_moves_the_name_onto_the_side_and_clears_the_id() {
        val players = MatchPlayers("player-marco", "player-ana", emptyList(), emptyList())

        players.forgetting("player-marco", "Marco") shouldBe
            MatchPlayers(null, "player-ana", listOf("Marco"), emptyList())
        players.forgetting("player-ana", "Ana") shouldBe
            MatchPlayers("player-marco", null, emptyList(), listOf("Ana"))
    }

    @Test
    fun forgetting_another_player_changes_nothing() {
        val players = MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))

        players.forgetting("player-ana", "Ana") shouldBe players
    }

    @Test
    fun names_resolve_a_profile_and_keep_typed_names() {
        val players = MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))

        players.names(Side.HOME, listOf(ana, marco)) shouldBe listOf("Marco")
        players.names(Side.AWAY, listOf(ana, marco)) shouldBe listOf("Jo")
    }

    @Test
    fun an_unknown_profile_has_no_names() {
        // The profile is gone or this phone has not loaded it yet. Not a stale typed
        // name, which the side no longer carries.
        val players = MatchPlayers("player-gone", null, emptyList(), emptyList())

        players.names(Side.HOME, listOf(ana, marco)) shouldBe emptyList()
        players.names(Side.HOME, emptyList()) shouldBe emptyList()
    }

    @Test
    fun none_is_nobody_on_either_side() {
        MatchPlayers.NONE shouldBe MatchPlayers(null, null, emptyList(), emptyList())
    }

    @Test
    fun doubles_with_a_profile_is_a_problem() {
        val profile = MatchPlayers("player-marco", null, emptyList(), emptyList())

        matchPlayersProblem(profile, MatchFormat.DOUBLES) shouldBe PlayersCopy.DOUBLES_NOTE
        matchPlayersProblem(profile, MatchFormat.MIXED_DOUBLES) shouldBe PlayersCopy.DOUBLES_NOTE
        matchPlayersProblem(profile.copy(homePlayerId = null, awayPlayerId = "player-ana"), MatchFormat.DOUBLES) shouldBe
            PlayersCopy.DOUBLES_NOTE
        // A profile is fine in singles and when nobody has said what the format is.
        matchPlayersProblem(profile, MatchFormat.SINGLES) shouldBe null
        matchPlayersProblem(profile, null) shouldBe null
        // Typed names are fine in doubles.
        matchPlayersProblem(MatchPlayers(null, null, listOf("A", "B"), listOf("C", "D")), MatchFormat.DOUBLES) shouldBe null
    }

    @Test
    fun names_and_a_profile_on_one_side_is_a_problem() {
        matchPlayersProblem(MatchPlayers("player-marco", null, listOf("Marco"), emptyList()), null) shouldBe
            PlayersCopy.PROFILE_AND_NAMES
        matchPlayersProblem(MatchPlayers(null, "player-ana", emptyList(), listOf("Ana")), MatchFormat.SINGLES) shouldBe
            PlayersCopy.PROFILE_AND_NAMES
        // A profile on one side and a typed opponent on the other is the intended shape.
        matchPlayersProblem(MatchPlayers("player-marco", null, emptyList(), listOf("Jo")), null) shouldBe null
        // The doubles sentence comes first, then this one, then the length.
        matchPlayersProblem(MatchPlayers("player-marco", null, listOf("Marco"), emptyList()), MatchFormat.DOUBLES) shouldBe
            PlayersCopy.DOUBLES_NOTE
        matchPlayersProblem(
            MatchPlayers("player-marco", null, listOf("x".repeat(MAX_PLAYER_NAME + 1)), emptyList()),
            null,
        ) shouldBe PlayersCopy.PROFILE_AND_NAMES
    }

    @Test
    fun a_typed_name_over_the_cap_is_a_problem() {
        val long = "x".repeat(MAX_PLAYER_NAME + 1)

        matchPlayersProblem(MatchPlayers(null, null, emptyList(), listOf(long)), null) shouldBe PLAYER_NAME_TOO_LONG
        matchPlayersProblem(MatchPlayers(null, null, emptyList(), listOf(" " + "x".repeat(MAX_PLAYER_NAME) + " ")), null) shouldBe
            null
        matchPlayersProblem(MatchPlayers.NONE, null) shouldBe null
    }

    @Test
    fun the_same_player_on_both_sides_is_a_problem() {
        val same = MatchPlayers("player-marco", "player-marco", emptyList(), emptyList())

        matchPlayersProblem(same, MatchFormat.SINGLES) shouldBe PlayersCopy.SAME_PLAYER_BOTH_SIDES
        matchPlayersProblem(same, null) shouldBe PlayersCopy.SAME_PLAYER_BOTH_SIDES
        // Two different players are fine, and so is no player on either side.
        matchPlayersProblem(same.copy(awayPlayerId = "player-ana"), null) shouldBe null
        matchPlayersProblem(MatchPlayers.NONE, null) shouldBe null
        // The doubles sentence still comes first.
        matchPlayersProblem(same, MatchFormat.DOUBLES) shouldBe PlayersCopy.DOUBLES_NOTE
    }
}
