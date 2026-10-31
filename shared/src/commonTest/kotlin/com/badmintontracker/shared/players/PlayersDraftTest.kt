package com.badmintontracker.shared.players

import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class PlayersDraftTest {

    private val at = Instant.parse("2026-10-30T10:00:00Z")

    private fun player(id: String, name: String) = Player(id, "owner-1", name, RacketArm.RIGHT, at, at)

    private val marco = player("player-marco", "Marco")

    private fun log(
        homeId: String? = null,
        awayId: String? = null,
        home: List<String> = emptyList(),
        away: List<String> = emptyList(),
        format: MatchFormat? = null,
    ) = ScoreLog(
        id = "log-1", videoId = null, title = "Thu League",
        homePlayers = home, awayPlayers = away,
        homePlayerId = homeId, awayPlayerId = awayId, format = format,
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
        events = emptyList(), status = ScoreLogStatus.LIVE, createdAt = at, updatedAt = at,
    )

    @Test
    fun a_prefilled_player_starts_at_near_in_one_mode() {
        val draft = endsDraft(MatchPlayers.NONE, null, "player-marco")

        draft shouldBe EndsDraft(Tracked.ONE, "player-marco", null, "", playerAtNear = true)
        draft.showsDeviceLine() shouldBe false
        // Without a prefill the draft is empty, still one player at near.
        endsDraft(MatchPlayers.NONE, null, null) shouldBe EndsDraft(Tracked.ONE, null, null, "", playerAtNear = true)
        // A prefill never overrides a match that already says who is playing.
        endsDraft(MatchPlayers("player-ana", null, emptyList(), emptyList()), Side.HOME, "player-marco") shouldBe
            EndsDraft(Tracked.ONE, "player-ana", null, "", playerAtNear = true)
    }

    @Test
    fun swapping_moves_the_player_to_far() {
        val swapped = endsDraft(MatchPlayers.NONE, null, "player-marco").swapped()

        swapped shouldBe EndsDraft(Tracked.ONE, null, "player-marco", "", playerAtNear = false)
        swapped.showsDeviceLine() shouldBe true
        swapped.swapped() shouldBe EndsDraft(Tracked.ONE, "player-marco", null, "", playerAtNear = true)

        // With nobody chosen yet the swap still says where the player will stand.
        EndsDraft(Tracked.ONE, null, null, "Jo", playerAtNear = true).swapped() shouldBe
            EndsDraft(Tracked.ONE, null, null, "Jo", playerAtNear = false)

        val both = EndsDraft(Tracked.BOTH, "player-marco", "player-ana", "", playerAtNear = true)
        both.swapped() shouldBe EndsDraft(Tracked.BOTH, "player-ana", "player-marco", "", playerAtNear = true)
    }

    @Test
    fun one_mode_saves_the_player_and_the_opponent_by_end() {
        val atNear = EndsDraft(Tracked.ONE, "player-marco", null, "  Jo ", playerAtNear = true)
        atNear.toAssignment() shouldBe
            (MatchPlayers("player-marco", null, emptyList(), listOf("Jo")) to Side.HOME)

        // Far is the player's end: the opponent stands at near, and home is always near.
        val atFar = atNear.swapped()
        atFar.toAssignment() shouldBe
            (MatchPlayers(null, "player-marco", listOf("Jo"), emptyList()) to Side.HOME)

        // A blank opponent saves no name.
        EndsDraft(Tracked.ONE, "player-marco", null, "   ", playerAtNear = true).toAssignment() shouldBe
            (MatchPlayers("player-marco", null, emptyList(), emptyList()) to Side.HOME)

        // Only an opponent, no profile.
        EndsDraft(Tracked.ONE, null, null, "Jo", playerAtNear = true).toAssignment() shouldBe
            (MatchPlayers(null, null, emptyList(), listOf("Jo")) to Side.HOME)
    }

    @Test
    fun both_mode_saves_two_profiles_home_near() {
        val draft = EndsDraft(Tracked.BOTH, "player-marco", "player-ana", "left over", playerAtNear = true)

        draft.toAssignment() shouldBe
            (MatchPlayers("player-marco", "player-ana", emptyList(), emptyList()) to Side.HOME)
        draft.showsDeviceLine() shouldBe true
        draft.swapped().toAssignment() shouldBe
            (MatchPlayers("player-ana", "player-marco", emptyList(), emptyList()) to Side.HOME)
    }

    @Test
    fun an_existing_assignment_reads_back_as_both() {
        val both = MatchPlayers("player-marco", "player-ana", emptyList(), emptyList())

        endsDraft(both, Side.HOME, null) shouldBe
            EndsDraft(Tracked.BOTH, "player-marco", "player-ana", "", playerAtNear = true)
        // Away was nearest the camera: the ends read back the other way round.
        endsDraft(both, Side.AWAY, null) shouldBe
            EndsDraft(Tracked.BOTH, "player-ana", "player-marco", "", playerAtNear = true)
        // Not said: home is near, the convention a video-born match is saved with.
        endsDraft(both, null, null).nearPlayerId shouldBe "player-marco"

        // A profile and a typed opponent reads back as one player.
        val one = MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        endsDraft(one, Side.HOME, null) shouldBe
            EndsDraft(Tracked.ONE, "player-marco", null, "Jo", playerAtNear = true)
        endsDraft(one, Side.AWAY, null) shouldBe
            EndsDraft(Tracked.ONE, null, "player-marco", "Jo", playerAtNear = false)
        // Round trip.
        endsDraft(one, Side.HOME, null).toAssignment() shouldBe (one to Side.HOME)
        endsDraft(one, Side.AWAY, null).toAssignment() shouldBe
            (MatchPlayers(null, "player-marco", listOf("Jo"), emptyList()) to Side.HOME)
    }

    @Test
    fun a_far_profile_shows_the_device_line() {
        EndsDraft(Tracked.ONE, "player-marco", null, "", playerAtNear = true).showsDeviceLine() shouldBe false
        EndsDraft(Tracked.ONE, null, "player-marco", "", playerAtNear = false).showsDeviceLine() shouldBe true
        EndsDraft(Tracked.BOTH, "player-marco", null, "", playerAtNear = true).showsDeviceLine() shouldBe false
        EndsDraft(Tracked.BOTH, "player-marco", "player-ana", "", playerAtNear = true).showsDeviceLine() shouldBe true
    }

    @Test
    fun switching_between_one_and_both_keeps_the_right_player() {
        val both = EndsDraft(Tracked.BOTH, "player-marco", "player-ana", "", playerAtNear = true)
        both.withTracked(Tracked.BOTH) shouldBe both
        both.withTracked(Tracked.ONE) shouldBe EndsDraft(Tracked.ONE, "player-marco", null, "", playerAtNear = true)

        val one = EndsDraft(Tracked.ONE, "player-marco", null, "Jo", playerAtNear = true)
        one.withTracked(Tracked.BOTH) shouldBe EndsDraft(Tracked.BOTH, "player-marco", null, "Jo", playerAtNear = true)
        // The opponent name survives a round trip, but Both saves no names.
        one.withTracked(Tracked.BOTH).toAssignment().first.awayPlayers shouldBe emptyList()
        one.withTracked(Tracked.BOTH).withTracked(Tracked.ONE) shouldBe one

        // The player at far is not dropped by switching.
        val atFar = one.swapped()
        atFar.withTracked(Tracked.BOTH) shouldBe
            EndsDraft(Tracked.BOTH, null, "player-marco", "Jo", playerAtNear = false)
        atFar.withTracked(Tracked.BOTH).withTracked(Tracked.ONE) shouldBe atFar
    }

    @Test
    fun an_empty_draft_saves_nothing() {
        EndsDraft(Tracked.ONE, null, null, "  ", playerAtNear = true).toAssignment() shouldBe (MatchPlayers.NONE to null)
        EndsDraft(Tracked.BOTH, null, null, "", playerAtNear = true).toAssignment() shouldBe (MatchPlayers.NONE to null)
        endsDraft(MatchPlayers.NONE, null, null).toAssignment() shouldBe (MatchPlayers.NONE to null)
    }

    @Test
    fun a_side_with_a_profile_saves_no_names() {
        val draft = SidesDraft(
            homePlayerId = "player-marco", awayPlayerId = null,
            homeNames = listOf("Marco"), awayNames = listOf("Jo", "Kim"), nearSide = Side.HOME,
        )

        draft.toMatchPlayers(MatchFormat.SINGLES) shouldBe
            MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        // Doubles keep both typed names.
        draft.copy(homePlayerId = null).toMatchPlayers(MatchFormat.DOUBLES) shouldBe
            MatchPlayers(null, null, listOf("Marco"), listOf("Jo", "Kim"))
        // A blank is dropped.
        draft.copy(awayNames = listOf("  ")).toMatchPlayers(null).awayPlayers shouldBe emptyList()
    }

    @Test
    fun a_sides_draft_starts_from_the_log() {
        sidesDraft(log(homeId = "player-marco", away = listOf("Jo")), Side.AWAY) shouldBe
            SidesDraft("player-marco", null, emptyList(), listOf("Jo"), Side.AWAY)
        sidesDraft(log(), null) shouldBe SidesDraft(null, null, emptyList(), emptyList(), null)
    }

    @Test
    fun asks_for_the_home_end_only_with_a_profile() {
        asksForHomeEnd(log()) shouldBe false
        asksForHomeEnd(log(home = listOf("Marco"), away = listOf("Jo"))) shouldBe false
        asksForHomeEnd(log(homeId = "player-marco")) shouldBe true
        asksForHomeEnd(log(awayId = "player-marco")) shouldBe true
    }

    @Test
    fun the_home_end_title_names_the_home_side() {
        homeEndTitle(log(homeId = "player-marco"), listOf(marco)) shouldBe "Which end was Marco on?"
        homeEndTitle(log(awayId = "player-marco", home = listOf("Jo")), listOf(marco)) shouldBe "Which end was Jo on?"
        homeEndTitle(log(awayId = "player-marco"), listOf(marco)) shouldBe "Which end was Home on?"
        homeEndTitle(log(home = listOf("Ana", "Kim"), format = MatchFormat.DOUBLES), emptyList()) shouldBe
            "Which end was Ana / Kim on?"
        // A profile that is not loaded has no name to show.
        homeEndTitle(log(homeId = "player-gone"), emptyList()) shouldBe "Which end was Home on?"
    }
}
