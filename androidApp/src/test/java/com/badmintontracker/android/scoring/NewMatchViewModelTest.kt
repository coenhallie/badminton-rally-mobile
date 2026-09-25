package com.badmintontracker.android.scoring

import com.badmintontracker.shared.scoring.GamePoints
import com.badmintontracker.shared.scoring.MatchSets
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

@OptIn(ExperimentalCoroutinesApi::class)
class NewMatchViewModelTest {

    @BeforeTest fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val repo =
        ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })

    private fun vm(places: PlaceNames = PlaceNames { null }) = NewMatchViewModel(
        scoreLogs = repo,
        places = places,
        now = { Instant.parse("2026-09-20T12:30:00Z") },
        zone = TimeZone.of("Europe/Amsterdam"),
    )

    @Test
    fun the_form_opens_named_for_the_time_and_ready_to_submit() {
        // The whole point of the simplification: a match can be started courtside
        // without typing anything.
        val state = vm().state.value
        state.title shouldBe "Sep 20, 14:30"
        state.canCreate shouldBe true
        state.problem shouldBe null
    }

    @Test
    fun the_defaults_are_twenty_one_points_over_three_sets() {
        val state = vm().state.value
        state.points shouldBe GamePoints.TWENTY_ONE
        state.sets shouldBe MatchSets.THREE
    }

    @Test
    fun a_place_the_phone_can_name_joins_the_default_title() {
        val vm = vm(places = { "Amsterdam" })
        vm.addPlaceToTitle()
        vm.state.value.title shouldBe "Sep 20, 14:30 - Amsterdam"
    }

    @Test
    fun no_place_leaves_the_time_alone() {
        // Permission refused, location off, no geocoder: all the same null.
        val vm = vm(places = { null })
        vm.addPlaceToTitle()
        vm.state.value.title shouldBe "Sep 20, 14:30"
    }

    @Test
    fun a_place_arriving_after_the_user_typed_a_name_does_not_overwrite_it() {
        val place = CompletableDeferred<String?>()
        val vm = vm(places = { place.await() })
        vm.addPlaceToTitle()
        vm.setTitle("Club final")
        place.complete("Amsterdam")
        vm.state.value.title shouldBe "Club final"
    }

    @Test
    fun the_place_is_looked_up_once_however_often_it_is_asked_for() {
        var lookups = 0
        val vm = vm(places = { lookups += 1; "Amsterdam" })
        vm.addPlaceToTitle()
        vm.addPlaceToTitle()
        lookups shouldBe 1
    }

    @Test
    fun clearing_the_name_blocks_creation_and_says_why() {
        val vm = vm()
        vm.setTitle(" ")
        vm.state.value.canCreate shouldBe false
        vm.state.value.problem shouldBe "Give the match a name."
        vm.create() shouldBe null
        repo.logs.value shouldBe emptyList()
    }

    @Test
    fun creating_stores_the_chosen_points_and_sets() {
        val vm = vm()
        vm.setTitle("Thu League")
        vm.setPoints(GamePoints.ELEVEN)
        vm.setSets(MatchSets.ONE)

        val log = repo.get(vm.create().shouldNotBeNull()).shouldNotBeNull()
        log.title shouldBe "Thu League"
        log.rules shouldBe ScoringRules.of(GamePoints.ELEVEN, MatchSets.ONE)
        log.rules.pointsToWin shouldBe 11
        log.rules.gamesToWin shouldBe 1
        log.status shouldBe ScoreLogStatus.LIVE
    }

    @Test
    fun a_new_match_opens_with_home_serving() {
        // The form does not ask; the board is where it is switched.
        val log = repo.get(vm().create().shouldNotBeNull()).shouldNotBeNull()
        log.setup.firstServer shouldBe Side.HOME
    }

    @Test
    fun an_untouched_form_creates_a_bwf_match_under_its_default_name() {
        val log = repo.get(vm().create().shouldNotBeNull()).shouldNotBeNull()
        log.title shouldBe "Sep 20, 14:30"
        log.rules shouldBe ScoringRules.BWF_21
    }
}
