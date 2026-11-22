package com.badmintontracker.android.players

import com.badmintontracker.android.testing.FakePlayersRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayersViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val t0 = Instant.parse("2026-10-31T12:00:00Z")
    /** The fake stamps every player it creates with this owner; the forget is guarded on it. */
    private val owner = "owner"
    private val marco = Player("p-marco", owner, "Marco", null, t0, t0)

    private val localVideos = LocalVideoRepository(MapSettings())
    private val scoreLogs =
        ScoreLogsRepository(MapSettings(), now = { Instant.fromEpochMilliseconds(0) }, ownerId = { "user-self" })

    private fun newViewModel(players: FakePlayersRepository) =
        PlayersViewModel(players, scoreLogs, localVideos, currentOwnerId = { owner })

    /** A scored match with Marco at home, as this phone has it cached. */
    private fun marcosMatch(): String {
        val log = scoreLogs.create("Thu League", ScoringRules.BWF_21, MatchSetup(firstServer = Side.HOME))
        scoreLogs.assignPlayers(log.id, MatchPlayers("p-marco", null, emptyList(), listOf("Li")))
        return log.id
    }

    @Test
    fun refresh_failure_sets_load_failed() = runTest {
        val players = FakePlayersRepository()
        players.nextError = IllegalStateException("HTTP 503")
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.state.value.loadFailed shouldBe true
        // The shared sentence, never the raw error.
        vm.state.value.errorMessage shouldBe PlayersCopy.LOAD_FAILED
    }

    @Test
    fun a_refresh_that_works_clears_load_failed() = runTest {
        val players = FakePlayersRepository()
        players.nextError = IllegalStateException("HTTP 503")
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        vm.state.value.loadFailed shouldBe false
    }

    @Test
    fun publishes_the_repository_list() = runTest {
        val vm = newViewModel(FakePlayersRepository(listOf(marco)))
        advanceUntilIdle()

        vm.state.value.players shouldContainExactly listOf(marco)
    }

    @Test
    fun create_returns_the_player() = runTest {
        val players = FakePlayersRepository()
        val vm = newViewModel(players)
        advanceUntilIdle()

        val created = vm.create("  Marco Vidal ", RacketArm.LEFT).shouldBeInstanceOf<PlayerCreation.Created>()
        advanceUntilIdle()

        created.player.name shouldBe "Marco Vidal"
        created.player.racketArm shouldBe RacketArm.LEFT
        vm.state.value.players.map { it.name } shouldContainExactly listOf("Marco Vidal")
    }

    @Test
    fun a_failed_create_says_why_to_the_dialog_not_the_snackbar() = runTest {
        val players = FakePlayersRepository()
        val vm = newViewModel(players)
        advanceUntilIdle()
        players.nextError = IllegalStateException("HTTP 500: duplicate key")

        vm.create("Marco", null) shouldBe PlayerCreation.Failed(PlayersCopy.SAVE_FAILED)

        vm.state.value.errorMessage.shouldBeNull()
    }

    @Test
    fun nothing_is_empty_until_the_first_refresh_settles() = runTest {
        val players = FakePlayersRepository()
        val vm = newViewModel(players)

        vm.state.value.loaded shouldBe false
        vm.state.value.showsNoPlayers shouldBe false

        advanceUntilIdle()

        vm.state.value.loaded shouldBe true
        vm.state.value.showsNoPlayers shouldBe true
    }

    @Test
    fun a_failed_first_refresh_settles_too() = runTest {
        val players = FakePlayersRepository()
        players.nextError = IllegalStateException("HTTP 503")
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.state.value.loaded shouldBe true
        vm.state.value.showsNoPlayers shouldBe true
    }

    @Test
    fun cached_players_show_before_the_first_refresh() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        // A refresh that never comes back: the cache alone is on screen.
        players.duringRefresh = { throw CancellationException("never lands") }
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.state.value.loaded shouldBe false
        vm.state.value.players shouldContainExactly listOf(marco)
        vm.state.value.showsNoPlayers shouldBe false
    }

    @Test
    fun a_rename_reaches_the_list() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.rename(marco.id, " Marco Vidal ").shouldBeNull()
        advanceUntilIdle()

        vm.state.value.players.map { it.name } shouldContainExactly listOf("Marco Vidal")
    }

    @Test
    fun a_failed_rename_says_why_to_the_dialog_not_the_snackbar() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val vm = newViewModel(players)
        advanceUntilIdle()
        players.nextError = IllegalStateException("HTTP 500")

        // The rename dialog stays open with the name typed and says it there,
        // as the add dialog does.
        vm.rename(marco.id, "Marco Vidal") shouldBe PlayersCopy.SAVE_FAILED

        vm.state.value.errorMessage.shouldBeNull()
        vm.state.value.players.map { it.name } shouldContainExactly listOf("Marco")
    }

    @Test
    fun a_rename_to_a_blank_name_says_the_name_problem() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.rename(marco.id, "   ") shouldBe PlayersCopy.NAME_REQUIRED
    }

    @Test
    fun setting_the_hand_reaches_the_list() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.setRacketArm(marco.id, RacketArm.RIGHT)
        advanceUntilIdle()

        vm.state.value.players.single().racketArm shouldBe RacketArm.RIGHT
    }

    @Test
    fun delete_goes_through_the_shared_rewrite() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val id = marcosMatch()
        val vm = newViewModel(players)
        advanceUntilIdle()

        vm.delete(marco)
        advanceUntilIdle()

        players.deleted shouldContainExactly listOf(marco.id)
        // The cached match keeps the name it had, as a typed name, and loses the id,
        // so the next sync is not refused on a dead foreign key.
        scoreLogs.get(id).shouldNotBeNull().matchPlayers shouldBe
            MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        vm.state.value.players shouldContainExactly emptyList()
    }

    @Test
    fun a_failed_delete_rewrites_nothing_and_says_why() = runTest {
        val players = FakePlayersRepository(listOf(marco))
        val id = marcosMatch()
        val vm = newViewModel(players)
        advanceUntilIdle()
        players.nextError = IllegalStateException("HTTP 503")

        vm.delete(marco)
        advanceUntilIdle()

        scoreLogs.get(id).shouldNotBeNull().homePlayerId shouldBe marco.id
        vm.state.value.errorMessage shouldBe PlayersCopy.DELETE_FAILED
    }

    @Test
    fun a_refresh_forgets_a_player_deleted_on_another_device() = runTest {
        // Through the shared refresh-and-forget, not a bare repository refresh: a
        // bare one would drop the player from the list first, and the match list's
        // own refresh would then never see them go.
        val players = FakePlayersRepository(listOf(marco))
        val id = marcosMatch()
        val vm = newViewModel(players)
        advanceUntilIdle()
        players.serverPlayers = emptyList()

        vm.refresh()
        advanceUntilIdle()

        scoreLogs.get(id).shouldNotBeNull().matchPlayers shouldBe
            MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
    }

    @Test
    fun refresh_clears_isRefreshing_after_success_and_failure() = runTest {
        val players = FakePlayersRepository()
        val vm = newViewModel(players)
        advanceUntilIdle()
        vm.state.value.isRefreshing shouldBe false

        players.nextError = IllegalStateException("HTTP 503")
        vm.refresh()
        advanceUntilIdle()
        vm.state.value.isRefreshing shouldBe false
        vm.state.value.loadFailed shouldBe true
    }
}
