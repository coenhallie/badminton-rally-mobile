package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.russhwolf.settings.MapSettings
import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ReceivedShare
import io.kotest.matchers.collections.shouldHaveSize
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

class ClipListViewModelTest {

    /** No matches scored on this phone; the list under test is the video half. */
    private fun emptyScoreLogs() = ScoreLogsRepository(
        MapSettings(), now = { Instant.fromEpochMilliseconds(0) }, ownerId = { "user-self" },
    )
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private fun clip(id: String) = RallyClip(
        id = id, videoId = "v", ownerId = "user-self", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    private fun ownedClip(id: String, videoId: String) =
        clip(id).copy(videoId = videoId, ownerId = "user-self")
    private fun sharedClip(id: String, videoId: String) =
        clip(id).copy(videoId = videoId, ownerId = "user-other")

    @Test
    fun init_triggers_refresh() = runTest {
        val clips = FakeClipsRepository()
        ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        advanceUntilIdle()
        clips.refreshCalls shouldHaveSize 1
    }

    @Test
    fun state_reflects_observed_clips() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(clip("a"), clip("b")) }
        val vm = ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.clips.isEmpty()) s = awaitItem()
            s.clips.map { it.id } shouldBe listOf("a", "b")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun state_partitions_owned_and_shared_matches() = runTest {
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(
                ownedClip("a", "v-own"),
                sharedClip("b", "v-shared"),
                sharedClip("c", "v-shared"),
            )
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty() && s.sharedMatches.isEmpty()) s = awaitItem()
            s.ownedMatches.map { it.videoId } shouldBe listOf("v-own")
            s.sharedMatches.map { it.videoId } shouldBe listOf("v-shared")
            s.ownedMatches.first().isOwned shouldBe true
            s.sharedMatches.first().isOwned shouldBe false
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_failure_surfaces_in_error() = runTest {
        val clips = FakeClipsRepository().apply { refreshError = IllegalStateException("net down") }
        val vm = ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.error == null) s = awaitItem()
            s.error shouldBe "Couldn't refresh matches. Pull to try again."
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun shared_matches_carry_sharer_email() = runTest {
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(
                ownedClip("a", "v-own"),
                sharedClip("b", "v-shared"),
            )
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository().apply {
            receivedShares = listOf(
                ReceivedShare(
                    videoId = "v-shared",
                    sharerEmail = "alice@example.com",
                    sharedAt = Instant.parse("2026-05-07T10:00:00Z"),
                ),
            )
        }
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty() || s.ownedMatches.isEmpty() || s.sharedMatches.first().sharerEmail == null) {
                s = awaitItem()
            }
            s.sharedMatches.first().sharerEmail shouldBe "alice@example.com"
            s.ownedMatches.first().sharerEmail shouldBe null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun listReceived_failure_does_not_surface_error_or_block_rows() = runTest {
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(sharedClip("b", "v-shared"))
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository().apply {
            listReceivedError = IllegalStateException("shares fetch failed")
        }
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty()) s = awaitItem()
            s.sharedMatches.first().sharerEmail shouldBe null
            s.error shouldBe null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteMatch_success_prunes_row_and_refreshes() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(ownedClip("a", "v-own")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val videos = FakeVideosRepository()
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), videos, emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            vm.deleteMatch("v-own")
            while (s.ownedMatches.isNotEmpty()) s = awaitItem()
            videos.deleteMatchCalls shouldBe listOf("v-own")
            s.error shouldBe null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteMatch_failure_surfaces_error_and_keeps_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(ownedClip("a", "v-own")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val videos = FakeVideosRepository().apply {
            nextDeleteMatchResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), videos, emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            vm.deleteMatch("v-own")
            while (s.error == null) s = awaitItem()
            s.error shouldBe "Couldn't delete the match. Please try again."
            s.ownedMatches shouldHaveSize 1
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leaveShare_success_prunes_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(sharedClip("b", "v-shared")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository()
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty()) s = awaitItem()
            vm.leaveShare("v-shared")
            while (s.sharedMatches.isNotEmpty()) s = awaitItem()
            shares.leaveShareCalls shouldBe listOf("v-shared")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leaveShare_failure_surfaces_error_and_keeps_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(sharedClip("b", "v-shared")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository().apply {
            nextLeaveShareResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty()) s = awaitItem()
            vm.leaveShare("v-shared")
            while (s.error == null) s = awaitItem()
            s.error shouldBe "Couldn't remove the shared match. Please try again."
            s.sharedMatches shouldHaveSize 1
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun match_title_comes_from_the_clips_match_name() = runTest {
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(
                ownedClip("a", "v-own").copy(title = "Thu League vs Marco"),
                ownedClip("b", "v-own").copy(title = "Thu League vs Marco"),
            )
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            s.ownedMatches.first().title shouldBe "Thu League vs Marco"
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun match_title_is_null_when_no_clip_carries_one() = runTest {
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(ownedClip("a", "v-own"), ownedClip("b", "v-own"))
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            s.ownedMatches.first().title shouldBe null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun match_title_survives_a_single_clip_being_renamed() = runTest {
        // The app lets a user retitle one clip; that must not relabel the match.
        val clips = FakeClipsRepository().apply {
            this.clips.value = listOf(
                ownedClip("a", "v-own").copy(rallyIndex = 0, title = "Great smash"),
                ownedClip("b", "v-own").copy(rallyIndex = 1, title = "Thu League vs Marco"),
                ownedClip("c", "v-own").copy(rallyIndex = 2, title = "Thu League vs Marco"),
            )
        }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), FakeVideosRepository(), emptyScoreLogs())
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            s.ownedMatches.first().title shouldBe "Thu League vs Marco"
            cancelAndIgnoreRemainingEvents()
        }
    }
}
