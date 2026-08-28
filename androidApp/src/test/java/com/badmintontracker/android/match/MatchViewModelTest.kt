package com.badmintontracker.android.match

import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Test

/**
 * [MatchViewModel.state] is eager: its initial value is built synchronously from
 * the store's current snapshot (see the constructor), which is why the four tests
 * below that mutate the store *before* constructing the view model read correctly
 * off `state.value` with no dispatcher help at all. Only a mutation made *after*
 * construction needs the eager sharing coroutine to actually run to reach
 * `state.value`, and that coroutine lives on `viewModelScope`
 * (`Dispatchers.Main.immediate`) - which nothing pumps in a plain JUnit test. Hence
 * the dispatcher scaffolding here, matching ScoringViewModelTest, and why only the
 * "deleted while open" test below needs `runTest` + `advanceUntilIdle`.
 */
class MatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val t0 = Instant.parse("2026-08-28T18:00:00Z")

    private fun store() = ScoreLogsRepository(MapSettings(), { t0 }, { "owner-1" })

    private fun ScoreLogsRepository.newMatch() = create(
        title = "Thu League",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = false, firstServer = Side.HOME),
    )

    @Test
    fun a_match_still_being_scored_cannot_take_a_video_yet() {
        // Its action is Score/Resume. Offering both would put "add the video" beside
        // a match that has not been played, and would need LIVE and BOUND to mean
        // something together.
        val store = store()
        val log = store.newMatch()
        val vm = MatchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_match_the_rules_have_ended_can_take_a_video_even_while_still_marked_live() {
        val store = store()
        val log = store.newMatch()
        store.replaceEvents(log.id, List(42) { com.badmintontracker.shared.scoring.ScoreEvent.PointTo(Side.HOME) })
        val vm = MatchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_ended_by_hand_can_take_a_video() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        MatchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_that_already_has_a_video_is_not_offered_another_one() {
        // Replacing means removing the first, which is the delete gesture, not a
        // second picker on the same page.
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        MatchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_deleted_match_leaves_the_page_inert_rather_than_crashing() = runTest {
        val store = store()
        val log = store.newMatch()
        val vm = MatchViewModel(store, scoreLogId = log.id)
        store.removeLocally(log.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.log shouldBe null
        vm.state.value.canAddVideo shouldBe false
    }
}
