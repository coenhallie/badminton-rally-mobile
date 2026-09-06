package com.badmintontracker.android.cliplist

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
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
import io.kotest.matchers.nulls.shouldNotBeNull
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
        localVideos: LocalVideoRepository = localVideos(),
    ): ClipListViewModel {
        return ClipListViewModel(
            FakeClipsRepository(), FakeAuthRepository(), FakeSharesRepository(),
            videos, scoreLogs, localVideos, coordinator(localVideos),
            LocalAnnotationsRepository(MapSettings()),
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

    /**
     * Locks in `deleteBoundMatch`'s ordering. The local-only store this test uses
     * has no client, so `scoreLogs.delete`'s server call always fails here (its
     * local removal still happens unconditionally) - exactly the case where
     * going on to delete the video would be wrong: `deleteMatchVideo`'s success
     * path calls `refresh()`, which syncs score logs, and syncing while the
     * score log's own delete is still unacknowledged by the server would pull
     * that row straight back in, resurrecting the match within this same
     * action. A regression that reorders the two deletes, or fires them
     * concurrently instead of gating the second on the first's outcome, would
     * call `videos.deleteMatch` here regardless of the failure - this must not
     * happen.
     *
     * Also pins the wording fix (finding 6 of the 2026-08-28 review): the old
     * message only said the match was gone from this phone, which read as the
     * whole gesture - match, video and clips - having landed. It had not: the
     * video and clips above are completely untouched, not merely undeleted on
     * the server, and the message now says so.
     */
    @Test
    fun deleteBoundMatch_does_not_touch_the_video_when_the_score_log_delete_fails_on_the_server() = runTest {
        val scoreLogs = scoreLogs()
        val match = scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        scoreLogs.attachVideo(match.id, "vid-1")

        val videos = FakeVideosRepository()
        val vm = newViewModel(videos, scoreLogs)

        vm.state.test {
            awaitItem()
            vm.deleteBoundMatch("vid-1", match.id)
            advanceUntilIdle()

            videos.deleteMatchCalls shouldHaveSize 0
            scoreLogs.get(match.id).shouldBeNull()
            expectMostRecentItem().error shouldBe
                "Couldn't delete the match everywhere. The match is gone from this phone, " +
                "but its video and clips are still there."
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The terminal half of the orphaning bug: a bound-but-unclipped match has no
     * clips, so its delete routes through deleteScoreMatch (not deleteBoundMatch),
     * which used to remove only the log. AnalyzeCoordinator's zero-rally detection
     * produces exactly this - a FAILED entry still pointing at a scoreLogId - and
     * deleting the match is a coach's natural next move.
     */
    @Test
    fun deleteScoreMatch_removes_a_settled_entry_the_deleted_log_orphaned() = runTest {
        val scoreLogs = scoreLogs()
        val match = scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        scoreLogs.attachVideo(match.id, "vid-1")
        val lv = localVideos()
        lv.add(
            LocalVideoEntry(
                id = "vid-1", uri = "content://x/vid-1", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
                stage = AnalyzeStage.FAILED, failedStep = AnalyzeStep.PROCESSING,
                failureMessage = "Analysis finished but found no rallies in this video.",
                scoreLogId = match.id,
            )
        )
        val vm = newViewModel(FakeVideosRepository(), scoreLogs, localVideos = lv)

        vm.deleteScoreMatch(match.id)
        advanceUntilIdle()

        lv.get("vid-1").shouldBeNull()
    }

    /**
     * canRemoveLocalVideo already blocks removal while the pipeline is running,
     * for good reason: cancelling an in-flight upload or analysis is deliberately
     * out of scope. This half of the orphaning bug is left exactly as it was.
     */
    @Test
    fun deleteScoreMatch_leaves_a_mid_pipeline_entry_in_place() = runTest {
        val scoreLogs = scoreLogs()
        val match = scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        val lv = localVideos()
        lv.add(
            LocalVideoEntry(
                id = "vid-1", uri = "content://x/vid-1", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
                stage = AnalyzeStage.UPLOADING,
                scoreLogId = match.id,
            )
        )
        val vm = newViewModel(FakeVideosRepository(), scoreLogs, localVideos = lv)

        vm.deleteScoreMatch(match.id)
        advanceUntilIdle()

        lv.get("vid-1").shouldNotBeNull()
    }
}
