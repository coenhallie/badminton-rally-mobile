package com.badmintontracker.shared.players

import com.badmintontracker.analysis.player.CourtSide
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

    private fun ends(
        tracked: Tracked = Tracked.ONE,
        near: String? = null,
        far: String? = null,
        nearName: String = "",
        farName: String = "",
        playerAtNear: Boolean = true,
        nearSide: Side? = null,
    ) = EndsDraft(tracked, near, far, nearName, farName, playerAtNear, nearSide)

    @Test
    fun a_prefilled_player_starts_at_near_in_one_mode() {
        val draft = endsDraft(MatchPlayers.NONE, null, "player-marco")

        draft shouldBe ends(near = "player-marco")
        draft.showsDeviceLine() shouldBe false
        // Without a prefill the draft is empty, still one player at near.
        endsDraft(MatchPlayers.NONE, null, null) shouldBe ends()
        // A prefill never overrides a match that already says who is playing.
        endsDraft(MatchPlayers("player-ana", null, emptyList(), emptyList()), Side.HOME, "player-marco") shouldBe
            ends(near = "player-ana", nearSide = Side.HOME)
        endsDraft(MatchPlayers(null, null, listOf("Jo"), emptyList()), null, "player-marco") shouldBe
            ends(nearName = "Jo")
    }

    @Test
    fun swapping_moves_the_player_to_far() {
        val swapped = endsDraft(MatchPlayers.NONE, null, "player-marco").swapped()

        swapped shouldBe ends(far = "player-marco", playerAtNear = false)
        swapped.showsDeviceLine() shouldBe true
        swapped.swapped() shouldBe ends(near = "player-marco")

        // With nobody chosen yet the swap still says where the player will stand, and moves the names.
        ends(farName = "Jo").swapped() shouldBe ends(nearName = "Jo", playerAtNear = false)

        ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana").swapped() shouldBe
            ends(tracked = Tracked.BOTH, near = "player-ana", far = "player-marco")
    }

    @Test
    fun one_mode_saves_the_player_and_the_opponent_by_end() {
        val atNear = ends(near = "player-marco", farName = "  Jo ")
        atNear.toAssignment() shouldBe
            (MatchPlayers("player-marco", null, emptyList(), listOf("Jo")) to Side.HOME)

        // Far is the player's end: the opponent stands at near, and home is the near end.
        atNear.swapped().toAssignment() shouldBe
            (MatchPlayers(null, "player-marco", listOf("Jo"), emptyList()) to Side.HOME)

        // A blank opponent saves no name.
        ends(near = "player-marco", farName = "   ").toAssignment() shouldBe
            (MatchPlayers("player-marco", null, emptyList(), emptyList()) to Side.HOME)

        // Only an opponent, no profile, and the ends were never said: no near side is invented.
        ends(farName = "Jo").toAssignment() shouldBe (MatchPlayers(null, null, emptyList(), listOf("Jo")) to null)
    }

    @Test
    fun a_profile_end_saves_no_typed_name_but_keeps_it_in_the_draft() {
        val draft = ends(nearName = "Marco", farName = "Jo").withPlayer(CourtSide.NEAR, "player-marco")

        draft.toAssignment() shouldBe (MatchPlayers("player-marco", null, emptyList(), listOf("Jo")) to Side.HOME)
        // Taking the profile off brings the typed name back.
        draft.withPlayer(CourtSide.NEAR, null).nearName shouldBe "Marco"
    }

    @Test
    fun both_mode_saves_two_profiles_home_near() {
        val draft = ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana", farName = "left over")

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
            ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana", nearSide = Side.HOME)
        // Away was nearest the camera: the ends read back the other way round.
        endsDraft(both, Side.AWAY, null) shouldBe
            ends(tracked = Tracked.BOTH, near = "player-ana", far = "player-marco", nearSide = Side.AWAY)
        // Not said: home is laid out as near, and the draft still says not said.
        endsDraft(both, null, null).nearPlayerId shouldBe "player-marco"
        endsDraft(both, null, null).nearSide shouldBe null

        // A profile and a typed opponent reads back as one player.
        val one = MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        endsDraft(one, Side.HOME, null) shouldBe ends(near = "player-marco", farName = "Jo", nearSide = Side.HOME)
        endsDraft(one, Side.AWAY, null) shouldBe
            ends(far = "player-marco", nearName = "Jo", playerAtNear = false, nearSide = Side.AWAY)
    }

    @Test
    fun an_existing_assignment_saves_back_unchanged() {
        val cases = listOf(
            // typed names on both ends, the shape of every match that exists today
            MatchPlayers(null, null, listOf("Marco"), listOf("Jo")),
            // a name on one side only
            MatchPlayers(null, null, listOf("Marco"), emptyList()),
            MatchPlayers(null, null, emptyList(), listOf("Jo")),
            // a profile and a typed name
            MatchPlayers("player-marco", null, emptyList(), listOf("Jo")),
            MatchPlayers(null, "player-marco", listOf("Jo"), emptyList()),
            // two profiles
            MatchPlayers("player-marco", "player-ana", emptyList(), emptyList()),
        )
        for (players in cases) {
            for (stored in listOf(Side.HOME, Side.AWAY)) {
                endsDraft(players, stored, null).toAssignment() shouldBe (players to stored)
            }
        }
        // Ends never said and nothing but names: they are not invented on the way back.
        for (players in cases.filter { it.homePlayerId == null && it.awayPlayerId == null }) {
            endsDraft(players, null, null).toAssignment() shouldBe (players to null)
        }
    }

    @Test
    fun an_away_near_match_swapped_saves_the_exchange() {
        // Marco (home) was at the far end, Jo (away) at the near end.
        val players = MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        val swapped = endsDraft(players, Side.AWAY, null).swapped()

        swapped shouldBe ends(near = "player-marco", farName = "Jo", nearSide = Side.AWAY)
        // The near side stays away, so the near player is saved as away.
        swapped.toAssignment() shouldBe (MatchPlayers(null, "player-marco", listOf("Jo"), emptyList()) to Side.AWAY)
    }

    @Test
    fun with_player_and_with_name_place_values_at_the_right_end() {
        // One mode: choosing moves the single player to that end.
        val one = ends(near = "player-marco", farName = "Jo")
        one.withPlayer(CourtSide.FAR, "player-ana") shouldBe
            ends(far = "player-ana", farName = "Jo", playerAtNear = false)
        one.withPlayer(CourtSide.FAR, "player-ana").withPlayer(CourtSide.NEAR, "player-marco") shouldBe one
        // The saved shape after a swap and a pick puts profile and opponent on opposite sides.
        one.swapped().withPlayer(CourtSide.FAR, "player-ana").toAssignment() shouldBe
            (MatchPlayers(null, "player-ana", listOf("Jo"), emptyList()) to Side.HOME)
        one.withPlayer(CourtSide.NEAR, null) shouldBe ends(farName = "Jo")

        // Both mode: each end is its own.
        val both = ends(tracked = Tracked.BOTH)
        both.withPlayer(CourtSide.NEAR, "player-marco").withPlayer(CourtSide.FAR, "player-ana") shouldBe
            ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana")
        both.withPlayer(CourtSide.FAR, "player-ana") shouldBe ends(tracked = Tracked.BOTH, far = "player-ana")

        // Typing at an end chooses "Someone else" there.
        ends().withName(CourtSide.NEAR, "Marco").withName(CourtSide.FAR, "Jo") shouldBe
            ends(nearName = "Marco", farName = "Jo").copy(nearSomeoneElse = true, farSomeoneElse = true)
    }

    @Test
    fun a_far_profile_shows_the_device_line() {
        ends(near = "player-marco").showsDeviceLine() shouldBe false
        ends(far = "player-marco", playerAtNear = false).showsDeviceLine() shouldBe true
        ends(tracked = Tracked.BOTH, near = "player-marco").showsDeviceLine() shouldBe false
        ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana").showsDeviceLine() shouldBe true
    }

    @Test
    fun switching_between_one_and_both_keeps_the_right_player() {
        val both = ends(tracked = Tracked.BOTH, near = "player-marco", far = "player-ana")
        both.withTracked(Tracked.BOTH) shouldBe both
        both.withTracked(Tracked.ONE) shouldBe ends(near = "player-marco")

        val one = ends(near = "player-marco", farName = "Jo")
        one.withTracked(Tracked.BOTH) shouldBe ends(tracked = Tracked.BOTH, near = "player-marco", farName = "Jo")
        // The opponent's name is now the far picker's "Someone else", shown and saved
        // (Ruling 17: no end drops a typed name out of sight), and survives the round trip.
        one.withTracked(Tracked.BOTH).field(CourtSide.FAR) shouldBe EndField.SOMEONE_ELSE
        one.withTracked(Tracked.BOTH).toAssignment().first.awayPlayers shouldBe listOf("Jo")
        one.withTracked(Tracked.BOTH).withTracked(Tracked.ONE) shouldBe one

        // The player at far is not dropped by switching.
        val atFar = one.swapped()
        atFar.withTracked(Tracked.BOTH) shouldBe
            ends(tracked = Tracked.BOTH, far = "player-marco", nearName = "Jo", playerAtNear = false)
        atFar.withTracked(Tracked.BOTH).withTracked(Tracked.ONE) shouldBe atFar
    }

    @Test
    fun a_typed_name_at_the_player_end_is_shown_and_saved() {
        // "Coen vs Marco", no profile: the player's end reads Someone else with Coen,
        // never a bare "Choose a player" hiding the name.
        val typed = endsDraft(MatchPlayers(null, null, listOf("Coen"), listOf("Marco")), null, null)
        typed.field(CourtSide.NEAR) shouldBe EndField.SOMEONE_ELSE
        typed.field(CourtSide.FAR) shouldBe EndField.OPPONENT
        typed.toAssignment() shouldBe (MatchPlayers(null, null, listOf("Coen"), listOf("Marco")) to null)

        // In Both mode both names are on screen and both are saved.
        val both = typed.withTracked(Tracked.BOTH)
        both.field(CourtSide.NEAR) shouldBe EndField.SOMEONE_ELSE
        both.field(CourtSide.FAR) shouldBe EndField.SOMEONE_ELSE
        both.toAssignment() shouldBe (MatchPlayers(null, null, listOf("Coen"), listOf("Marco")) to null)
    }

    @Test
    fun someone_else_takes_the_profile_off_and_keeps_its_field_open() {
        val picked = ends(near = "player-marco", nearName = "Coen")
        picked.field(CourtSide.NEAR) shouldBe EndField.PLAYER

        val someone = picked.withSomeoneElse(CourtSide.NEAR)
        someone shouldBe ends(nearName = "Coen").copy(nearSomeoneElse = true)
        someone.field(CourtSide.NEAR) shouldBe EndField.SOMEONE_ELSE
        // Cleared to retype: still Someone else, saving nothing until a name is typed.
        val cleared = someone.withName(CourtSide.NEAR, "")
        cleared.field(CourtSide.NEAR) shouldBe EndField.SOMEONE_ELSE
        cleared.toAssignment() shouldBe (MatchPlayers.NONE to null)

        // Picking a player ends Someone else; the swap carries the choice with its end.
        someone.withPlayer(CourtSide.NEAR, "player-ana").nearSomeoneElse shouldBe false
        someone.swapped().farSomeoneElse shouldBe true
        // A bare end with no name and no choice is the bare picker.
        ends().field(CourtSide.NEAR) shouldBe EndField.PLAYER
    }

    @Test
    fun a_side_reads_someone_else_while_it_has_a_name_and_no_profile() {
        val draft = SidesDraft(null, null, listOf("Coen"), emptyList(), null)
        draft.showsName(Side.HOME) shouldBe true
        draft.showsName(Side.AWAY) shouldBe false

        val picked = draft.withPlayer(Side.HOME, "player-marco")
        picked.showsName(Side.HOME) shouldBe false
        // The typed name stays, unsaved, and comes back with Someone else.
        picked.toMatchPlayers(null) shouldBe MatchPlayers("player-marco", null, emptyList(), emptyList())
        picked.withSomeoneElse(Side.HOME).toMatchPlayers(null) shouldBe MatchPlayers(null, null, listOf("Coen"), emptyList())

        // Chosen and cleared to retype: the field stays.
        val away = draft.withSomeoneElse(Side.AWAY).withName(Side.AWAY, 0, "Jo").withName(Side.AWAY, 0, "")
        away.showsName(Side.AWAY) shouldBe true
        // A partner lands at its own index.
        draft.withName(Side.HOME, 1, "Ana").homeNames shouldBe listOf("Coen", "Ana")
        SidesDraft(null, null, emptyList(), emptyList(), null).withName(Side.AWAY, 1, "Kim").awayNames shouldBe listOf("", "Kim")
    }

    @Test
    fun an_empty_draft_saves_nothing() {
        ends(nearName = "  ", farName = "").toAssignment() shouldBe (MatchPlayers.NONE to null)
        ends(tracked = Tracked.BOTH).toAssignment() shouldBe (MatchPlayers.NONE to null)
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
