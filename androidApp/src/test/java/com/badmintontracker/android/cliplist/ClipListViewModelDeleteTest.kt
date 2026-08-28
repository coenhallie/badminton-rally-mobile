package com.badmintontracker.android.cliplist

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
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
 * `deleteMatch`'s unbind half: the database does this on its own (ON DELETE SET
 * NULL plus the unbind_score_log_on_video_delete trigger), but the phone would not
 * learn that until the next sync and would go on advertising clips for a video
 * that is gone. The view model must do the same thing locally.
 */
class ClipListViewModelDeleteTest {

    private val dispatcher = StandardTestDispatcher()

    /** A local-only store, same as ScoreLogsRepositoryTest's - no network reachable from here. */
    private fun scoreLogs() = ScoreLogsRepository(
        MapSettings(), now = { Instant.fromEpochMilliseconds(0) }, ownerId = { "user-self" },
    )

    private fun localVideos() = LocalVideoRepository(MapSettings())
    private fun coordinator(localVideos: LocalVideoRepository) = AnalyzeCoordinator(
        localVideos, FakeVideosRepository(), FakeClipsRepository(),
        CoroutineScope(dispatcher),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        localAnnotations = LocalAnnotationsRepository(MapSettings()),
    )

    private fun newViewModel(
        videos: FakeVideosRepository,
        scoreLogs: ScoreLogsRepository,
    ): ClipListViewModel {
        val lv = localVideos()
        return ClipListViewModel(
            FakeClipsRepository(), FakeAuthRepository(), FakeSharesRepository(),
            videos, scoreLogs, lv, coordinator(lv),
        )
    }

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    @Test
    fun deleteMatch_success_unbinds_a_score_log_pointing_at_the_deleted_video() = runTest {
        val scoreLogs = scoreLogs()
        val match = scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        scoreLogs.replaceEvents(match.id, listOf(ScoreEvent.PointTo(Side.HOME)))
        scoreLogs.attachVideo(match.id, "vid-1")

        val videos = FakeVideosRepository()
        val vm = newViewModel(videos, scoreLogs)

        vm.state.test {
            awaitItem()
            vm.deleteMatch("vid-1")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val log = scoreLogs.get(match.id)!!
        log.videoId.shouldBeNull()
        log.status shouldBe ScoreLogStatus.UNBOUND
        log.events shouldHaveSize 1
    }

    @Test
    fun deleteMatch_leaves_a_score_log_bound_to_a_different_video_untouched() = runTest {
        val scoreLogs = scoreLogs()
        val match = scoreLogs.create(
            title = "Fri League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        scoreLogs.attachVideo(match.id, "vid-other")

        val videos = FakeVideosRepository()
        val vm = newViewModel(videos, scoreLogs)

        vm.state.test {
            awaitItem()
            vm.deleteMatch("vid-1")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val log = scoreLogs.get(match.id)!!
        log.videoId shouldBe "vid-other"
    }
}
