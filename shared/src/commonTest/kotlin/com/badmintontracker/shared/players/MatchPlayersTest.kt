package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
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
}
