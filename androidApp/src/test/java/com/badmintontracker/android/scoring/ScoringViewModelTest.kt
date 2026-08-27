package com.badmintontracker.android.scoring

import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.SideScore
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The courtside surface's behaviour, minus the pixels.
 *
 * iOS mirrors this list name for name in `ScoringModelTests`. If the two lists
 * diverge, the two surfaces have diverged.
 */
class ScoringViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    private val goodShot = AnnotationLabel(
        id = "l1", name = "Good shot", colorKey = "green", createdAt = t0,
    )
    private val forcedError = AnnotationLabel(
        id = "l2", name = "Forced error", colorKey = "amber", createdAt = t0,
    )

    private fun repo() = ScoreLogsRepository(MapSettings(), now = { t0 }, ownerId = { "owner-1" })

    /** The view model plus the store and the match id behind it. */
    private fun fixture(
        repo: ScoreLogsRepository = repo(),
        labels: List<AnnotationLabel> = listOf(goodShot, forcedError),
    ): Triple<ScoreLogsRepository, String, ScoringViewModel> {
        val log = repo.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        return Triple(repo, log.id, ScoringViewModel(repo, FakeAnnotationLabelsRepository(labels), log.id))
    }

    @Test
    fun scoring_a_point_opens_the_tag_row_for_that_point() = runTest(dispatcher) {
        // The whole interaction. The row is already on screen when the coach looks
        // down, so the second tap costs him nothing.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 0
        vm.state.value.match?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun scoring_the_next_point_moves_the_tag_row_to_it() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.score(Side.AWAY)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 1
        // And the rally that was already tagged keeps its tag.
        vm.state.value.match!!.points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
    }

    @Test
    fun tagging_does_not_close_the_row() = runTest(dispatcher) {
        // A rally can be two things at once, and re-opening the row to say so would
        // cost the coach the next point.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, forcedError)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 0
        vm.toggleTag(0, goodShot)
        advanceUntilIdle()
        vm.state.value.match!!.points[0].tags.map { it.labelName } shouldBe
            listOf("Forced error", "Good shot")
    }

    @Test
    fun toggling_the_same_label_twice_removes_it() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.toggleTag(0, goodShot)
        advanceUntilIdle()
        vm.state.value.match!!.points[0].tags.shouldBeEmpty()
        // Undoing a mis-tap must not touch the score.
        vm.state.value.match!!.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_tag_snapshots_the_labels_name_and_colour() = runTest(dispatcher) {
        // Not the label id. Renaming "Forced error" next month must not rewrite
        // what the coach recorded tonight.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, forcedError)
        advanceUntilIdle()
        val tag = vm.state.value.match!!.points[0].tags.single()
        tag.labelName shouldBe "Forced error"
        tag.labelColor shouldBe "amber"
    }

    @Test
    fun the_palette_is_whatever_was_cached_offline() = runTest(dispatcher) {
        // A sports hall has no signal, and AnnotationLabelsRepository already caches
        // per owner. The surface reads that cache and never refreshes on entry.
        val labels = FakeAnnotationLabelsRepository(listOf(goodShot, forcedError))
        val repo = repo()
        val log = repo.create(
            "Thu League", listOf("Coen"), listOf("Marco"),
            ScoringRules.BWF_21, MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        val vm = ScoringViewModel(repo, labels, log.id)
        advanceUntilIdle()
        vm.state.value.labels.map { it.name } shouldBe listOf("Good shot", "Forced error")
        labels.refreshCount shouldBe 0
    }

    @Test
    fun undo_clears_a_tag_row_pointing_at_a_point_that_no_longer_exists() = runTest(dispatcher) {
        // Otherwise the next label lands on a rally that was taken back.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.undo()
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal.shouldBeNull()
        vm.state.value.match!!.points.shouldBeEmpty()
    }

    @Test
    fun finishing_ends_the_match_and_closes_the_row() = runTest(dispatcher) {
        val (repo, id, vm) = fixture()
        vm.score(Side.HOME)
        vm.finish()
        advanceUntilIdle()
        repo.get(id)!!.status shouldBe ScoreLogStatus.UNBOUND
        vm.state.value.pendingTagOrdinal.shouldBeNull()
    }

    @Test
    fun a_deleted_match_leaves_the_screen_inert_rather_than_crashing() = runTest(dispatcher) {
        // Reachable: the match was swiped away in the list on another screen while
        // this one was still open.
        val (repo, id, vm) = fixture()
        repo.removeLocally(id)
        advanceUntilIdle()
        vm.state.value.match.shouldBeNull()
        vm.state.value.canScore shouldBe false
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.finish()
        advanceUntilIdle()
        vm.state.value.match.shouldBeNull()
    }

    @Test
    fun resetting_the_current_game_leaves_finished_games_alone() = runTest(dispatcher) {
        // The coach mis-scored the third game, not the first two.
        val repo = repo()
        val log = repo.create(
            "Thu League", listOf("Coen"), listOf("Marco"),
            ScoringRules(pointsToWin = 2, winBy = 1, cap = null, gamesToWin = 2,
                intervalAt = null, changeEndsAt = null),
            MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        val vm = ScoringViewModel(repo, FakeAnnotationLabelsRepository(listOf(goodShot)), log.id)
        vm.score(Side.HOME)
        vm.score(Side.HOME)          // home takes game 1
        vm.score(Side.AWAY)          // one point into game 2
        vm.toggleTag(2, goodShot)
        advanceUntilIdle()
        vm.resetCurrentGame()
        advanceUntilIdle()
        val state = vm.state.value.match!!
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
        state.currentGame shouldBe SideScore.ZERO
        state.points.map { it.ordinal } shouldBe listOf(0, 1)
        vm.state.value.pendingTagOrdinal.shouldBeNull()
    }

    @Test
    fun a_finished_match_stops_taking_taps_but_can_still_be_undone() = runTest(dispatcher) {
        // A match can end on a mis-tap, and that is exactly when undo matters most.
        val repo = repo()
        val log = repo.create(
            "Thu League", listOf("Coen"), listOf("Marco"),
            ScoringRules(pointsToWin = 1, winBy = 1, cap = null, gamesToWin = 1,
                intervalAt = null, changeEndsAt = null),
            MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        val vm = ScoringViewModel(repo, FakeAnnotationLabelsRepository(listOf(goodShot)), log.id)
        vm.score(Side.HOME)
        advanceUntilIdle()
        vm.state.value.canScore shouldBe false
        vm.state.value.canUndo shouldBe true
        vm.score(Side.AWAY)
        advanceUntilIdle()
        vm.state.value.match!!.points.map { it.wonBy } shouldBe listOf(Side.HOME)
        vm.undo()
        advanceUntilIdle()
        vm.state.value.canScore shouldBe true
        vm.state.value.match!!.points.shouldBeEmpty()
    }
}
