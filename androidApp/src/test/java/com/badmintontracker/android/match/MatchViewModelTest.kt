package com.badmintontracker.android.match

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.scoring.MATCH_VIDEO_REMOVE_FAILED_MESSAGE
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.VIDEO_DETAILS_SAVE_FAILED_MESSAGE
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
 * each store's current snapshot (see the constructor), which is why the tests
 * below that mutate the stores *before* constructing the view model read correctly
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
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(firstServer = Side.HOME),
    )

    /**
     * Nothing under test here exercises the attach pipeline itself, so a real
     * LocalVideoRepository (empty, or carrying one hand-added entry) is enough.
     */
    private fun localVideos() = LocalVideoRepository(MapSettings())
    private fun clips() = FakeClipsRepository()

    private fun matchViewModel(
        store: ScoreLogsRepository,
        scoreLogId: String?,
        localVideos: LocalVideoRepository = localVideos(),
        videos: FakeVideosRepository = FakeVideosRepository(),
        localAnnotations: LocalAnnotationsRepository = LocalAnnotationsRepository(MapSettings()),
    ) = MatchViewModel(
        store, localVideos, clips(), videos, localAnnotations, scoreLogId,
    )

    private fun entry(scoreLogId: String, id: String = "e1", stage: AnalyzeStage = AnalyzeStage.LOCAL) =
        LocalVideoEntry(
            id = id, uri = "content://x/$id", displayName = "m.mp4",
            durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
            stage = stage, scoreLogId = scoreLogId,
        )

    @Test
    fun a_match_still_being_scored_cannot_take_a_video_yet() {
        // Its action is Score/Resume. Offering both would put "add the video" beside
        // a match that has not been played, and would need LIVE and BOUND to mean
        // something together.
        val store = store()
        val log = store.newMatch()
        val vm = matchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_match_the_rules_have_ended_can_take_a_video_even_while_still_marked_live() {
        val store = store()
        val log = store.newMatch()
        store.replaceEvents(log.id, List(42) { com.badmintontracker.shared.scoring.ScoreEvent.PointTo(Side.HOME) })
        val vm = matchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_ended_by_hand_can_take_a_video() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        matchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_that_already_has_a_video_is_not_offered_another_one() {
        // One video per match: replacing it is "Change video", which removes the
        // first and then reuses this same picker, rather than a second picker
        // standing open beside the video already attached.
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        matchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_match_whose_video_is_still_being_picked_up_is_not_offered_another_one() {
        // Wired through localVideos: an entry can exist for a while before the
        // pipeline gives the match a videoId (LOCAL, UPLOADING, PROCESSING).
        // Gating canAddVideo on videoId alone would let the picker reopen while
        // an attach for this same match is already in flight.
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        val videos = localVideos()
        videos.add(
            LocalVideoEntry(
                id = "e1", uri = "content://x/e1", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
                scoreLogId = log.id,
            )
        )
        matchViewModel(store, scoreLogId = log.id, localVideos = videos).state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_match_with_no_video_is_offered_neither_change_nor_remove() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        matchViewModel(store, scoreLogId = log.id).state.value.canRemoveVideo shouldBe false
    }

    @Test
    fun a_bound_match_can_change_or_remove_its_video() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        val vm = matchViewModel(store, scoreLogId = log.id)
        vm.state.value.canRemoveVideo shouldBe true
        vm.state.value.hasServerVideo shouldBe true
        // Mutually exclusive with canAddVideo, on every surface.
        vm.state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_failed_video_can_be_changed_or_removed() {
        // The reported dead end: analysis found no rallies, and Retry over the
        // same file is the only thing the page offered.
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        val videos = localVideos()
        videos.add(entry(log.id, id = "vid-1", stage = AnalyzeStage.FAILED))
        val vm = matchViewModel(store, scoreLogId = log.id, localVideos = videos)
        vm.state.value.canRemoveVideo shouldBe true
    }

    @Test
    fun a_video_still_uploading_can_be_neither_changed_nor_removed() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        val videos = localVideos()
        videos.add(entry(log.id, stage = AnalyzeStage.UPLOADING))
        matchViewModel(store, scoreLogId = log.id, localVideos = videos)
            .state.value.canRemoveVideo shouldBe false
    }

    @Test
    fun a_video_picked_but_not_yet_uploaded_says_there_are_no_rallies_to_lose() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        val videos = localVideos()
        videos.add(entry(log.id, stage = AnalyzeStage.LOCAL))
        val vm = matchViewModel(store, scoreLogId = log.id, localVideos = videos)
        vm.state.value.canRemoveVideo shouldBe true
        vm.state.value.hasServerVideo shouldBe false
    }

    @Test
    fun removing_a_video_leaves_the_match_and_reopens_add_video() = runTest {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        val entries = localVideos()
        entries.add(entry(log.id, id = "vid-1", stage = AnalyzeStage.FAILED))
        val videos = FakeVideosRepository()
        val vm = matchViewModel(store, scoreLogId = log.id, localVideos = entries, videos = videos)

        var removed = false
        vm.removeVideo(onRemoved = { removed = true })
        dispatcher.scheduler.advanceUntilIdle()

        removed shouldBe true
        videos.deleteMatchCalls shouldBe listOf("vid-1")
        vm.state.value.log?.videoId shouldBe null
        vm.state.value.canRemoveVideo shouldBe false
        // The whole point of "Change video": the attach path is open again with
        // no second implementation of it.
        vm.state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_failed_removal_surfaces_a_message_and_does_not_hand_over_to_the_picker() = runTest {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        val videos = FakeVideosRepository()
        videos.nextDeleteMatchResult = Result.failure(IllegalStateException("nope"))
        val vm = matchViewModel(store, scoreLogId = log.id, videos = videos)

        var removed = false
        vm.removeVideo(onRemoved = { removed = true })
        dispatcher.scheduler.advanceUntilIdle()

        removed shouldBe false
        vm.state.value.error shouldBe MATCH_VIDEO_REMOVE_FAILED_MESSAGE
        vm.state.value.log?.videoId shouldBe "vid-1"
    }

    @Test
    fun a_video_born_match_saves_its_details_onto_the_video_and_its_local_entry() = runTest {
        // No score log at all: cloudia, uploaded from this phone and never scored.
        val videos = FakeVideosRepository()
        val localVideos = localVideos().apply {
            add(LocalVideoEntry(
                id = "vid-1", uri = "content://x/1", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
                title = "cloudia", stage = AnalyzeStage.ANALYZED,
            ))
        }
        val vm = matchViewModel(store(), scoreLogId = null, localVideos = localVideos, videos = videos)

        vm.updateVideoDetails(
            "vid-1", title = "Club final", description = "", format = MatchFormat.DOUBLES,
            homePlayers = listOf("Coen", "Ana"), awayPlayers = listOf("Marco", "Li"),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val written = videos.updateDetailsCalls.single()
        written.title shouldBe "Club final"
        written.homePlayers shouldBe listOf("Coen", "Ana")
        written.format shouldBe MatchFormat.DOUBLES
        localVideos.get("vid-1")?.title shouldBe "Club final"
        vm.state.value.error shouldBe null
    }

    @Test
    fun a_refused_details_save_surfaces_a_message_and_leaves_the_local_entry_alone() = runTest {
        val videos = FakeVideosRepository()
        videos.nextUpdateDetailsResult = Result.failure(IllegalStateException("HTTP 403"))
        val localVideos = localVideos().apply {
            add(LocalVideoEntry(
                id = "vid-1", uri = "content://x/1", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
                title = "cloudia", stage = AnalyzeStage.ANALYZED,
            ))
        }
        val vm = matchViewModel(store(), scoreLogId = null, localVideos = localVideos, videos = videos)

        vm.updateVideoDetails("vid-1", "Club final", "", null, emptyList(), emptyList())
        dispatcher.scheduler.advanceUntilIdle()

        vm.state.value.error shouldBe VIDEO_DETAILS_SAVE_FAILED_MESSAGE
        localVideos.get("vid-1")?.title shouldBe "cloudia"
    }

    @Test
    fun a_deleted_match_leaves_the_page_inert_rather_than_crashing() = runTest {
        val store = store()
        val log = store.newMatch()
        val vm = matchViewModel(store, scoreLogId = log.id)
        store.removeLocally(log.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.state.value.log shouldBe null
        vm.state.value.canAddVideo shouldBe false
    }
}
