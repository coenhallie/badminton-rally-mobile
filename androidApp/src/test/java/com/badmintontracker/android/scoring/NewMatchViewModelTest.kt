package com.badmintontracker.android.scoring

import com.badmintontracker.shared.scoring.PairPlayer
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class NewMatchViewModelTest {

    private fun vm() = NewMatchViewModel(
        ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
    )

    @Test
    fun an_empty_form_cannot_be_submitted_and_says_nothing_yet() {
        // No red text before the user has typed anything: the problem is reported,
        // but the button is what tells them they are not done.
        val state = vm().state.value
        state.canCreate shouldBe false
        state.problem shouldBe null
    }

    @Test
    fun a_complete_singles_form_can_be_submitted() {
        val vm = vm()
        vm.setTitle("Thu League")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.state.value.canCreate shouldBe true
        vm.state.value.problem shouldBe null
    }

    @Test
    fun switching_to_doubles_needs_the_second_pair_of_names() {
        val vm = vm()
        vm.setTitle("Club night")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.setDoubles(true)
        vm.state.value.canCreate shouldBe false
        vm.state.value.problem shouldBe "Doubles needs two players on each side."
        vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setPlayer(Side.AWAY, 1, "Li")
        vm.state.value.canCreate shouldBe true
    }

    @Test
    fun switching_back_to_singles_does_not_lose_the_typed_second_names() {
        // Toggling a segmented control must not destroy typing. The names are kept
        // and simply not submitted.
        val vm = vm()
        vm.setDoubles(true)
        vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setDoubles(false)
        vm.setDoubles(true)
        vm.state.value.homePlayers[1] shouldBe "Ana"
    }

    @Test
    fun the_default_rules_are_the_bwf_game() {
        vm().state.value.rules shouldBe ScoringRules.BWF_21
    }

    @Test
    fun creating_stores_a_live_match_and_hands_back_its_id() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Thu League")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.setRules(ScoringRules.CLUB_15)
        vm.setFirstServer(Side.AWAY)

        val id = vm.create()
        id.shouldNotBeNull()
        val stored = repo.get(id)
        stored.shouldNotBeNull()
        stored.title shouldBe "Thu League"
        stored.homePlayers shouldBe listOf("Coen")
        stored.rules shouldBe ScoringRules.CLUB_15
        stored.setup.firstServer shouldBe Side.AWAY
        stored.setup.doubles shouldBe false
        stored.status shouldBe ScoreLogStatus.LIVE
    }

    @Test
    fun a_doubles_match_stores_the_starting_arrangement() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Club night")
        vm.setDoubles(true)
        vm.setPlayer(Side.HOME, 0, "Coen"); vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setPlayer(Side.AWAY, 0, "Marco"); vm.setPlayer(Side.AWAY, 1, "Li")
        val stored = repo.get(vm.create()!!)!!
        stored.setup.doubles shouldBe true
        // Index 0 of each side starts in the right service court, which is what the
        // form's name order means and what L1b's serve display reads.
        stored.setup.homeStartsRight shouldBe PairPlayer.FIRST
        stored.setup.awayStartsRight shouldBe PairPlayer.FIRST
    }

    @Test
    fun an_incomplete_form_creates_nothing() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Thu League")
        vm.create() shouldBe null
        repo.logs.value shouldBe emptyList()
    }
}
