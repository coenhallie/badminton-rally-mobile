package com.badmintontracker.android.cliplist

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.matchlist.MatchWhere
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The drawer's one list, as the view model folds it together and keeps it current. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipListViewModelMatchListTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val localVideos = LocalVideoRepository(MapSettings())
    private val clips = FakeClipsRepository()
    private val videos = FakeVideosRepository()
    private val deviceStates = MutableStateFlow<Map<String, LocalAnalysisState>>(emptyMap())
    private val reads = mutableListOf<Set<String>>()
    private val reader = StoredResultsReader { ids -> reads += ids; StoredResults() }
    private val scoreLogs =
        ScoreLogsRepository(MapSettings(), now = { Instant.fromEpochMilliseconds(0) }, ownerId = { "user-self" })

    private fun newViewModel(): ClipListViewModel {
        val annotations = LocalAnnotationsRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(dispatcher),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
            localAnnotations = annotations,
        )
        return ClipListViewModel(
            clips, FakeAuthRepository(), FakeSharesRepository(), videos,
            scoreLogs,
            localVideos, coordinator, annotations,
            deviceStates = deviceStates,
            storedResults = reader,
            ioDispatcher = dispatcher,
        )
    }

    /** The state is shared WhileSubscribed, as the screen collects it. */
    private fun TestScope.subscribe(vm: ClipListViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    private fun entry(id: String, stage: AnalyzeStage = AnalyzeStage.LOCAL) = LocalVideoEntry(
        id = id, uri = "content://$id", displayName = "$id.mp4", durationMs = 60_000,
        sizeBytes = 1, addedAtEpochMs = 1_000, stage = stage,
    )

    private fun clip(id: String, videoId: String) = RallyClip(
        id = id, videoId = videoId, ownerId = "user-self", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    private fun done() = LocalAnalysisState.Done(
        rallies = 3, shuttleVisible = 100, totalFrames = 200, clips = emptyList(), elapsedSeconds = 12.0,
        playerTrack = PlayerTrack(samples = emptyList(), framesWithPose = 0, rejections = emptyMap()),
        fps = 30.0,
    )

    @Test
    fun an_analyzed_entry_and_its_cloud_clips_are_one_match() = runTest {
        localVideos.add(entry("v1", AnalyzeStage.ANALYZED))
        clips.clips.value = listOf(clip("c1", "v1"), clip("c2", "v1"))
        val vm = newViewModel()
        subscribe(vm)

        val mine = vm.state.value.matchList.mine
        mine shouldHaveSize 1
        mine.single().where shouldBe MatchWhere.BOTH
        mine.single().entryId shouldBe "v1"
        mine.single().videoId shouldBe "v1"
    }

    @Test
    fun deleting_a_match_that_is_also_on_the_phone_discards_the_entry() = runTest {
        localVideos.add(entry("v1", AnalyzeStage.ANALYZED))
        clips.clips.value = listOf(clip("c1", "v1"))
        val vm = newViewModel()
        subscribe(vm)

        vm.deleteMatch("v1", entryId = "v1")
        advanceUntilIdle()

        videos.deleteMatchCalls shouldBe listOf("v1")
        // Without this the surviving ANALYZED entry brings the row straight
        // back as a phone-only match the coach just deleted.
        localVideos.get("v1").shouldBeNull()
        vm.state.value.matchList.mine shouldHaveSize 0
    }

    @Test
    fun a_failed_delete_leaves_the_entry_on_the_phone() = runTest {
        localVideos.add(entry("v1", AnalyzeStage.ANALYZED))
        clips.clips.value = listOf(clip("c1", "v1"))
        videos.nextDeleteMatchResult = Result.failure(IllegalStateException("offline"))
        val vm = newViewModel()
        subscribe(vm)

        vm.deleteMatch("v1", entryId = "v1")
        advanceUntilIdle()

        localVideos.get("v1").shouldNotBeNull()
    }

    /**
     * The builder joins an entry to a scored match by the log's video id as well
     * as by the entry's own scoreLogId. A delete that looked the entry up again
     * by scoreLogId would miss this one and leave the file behind as a
     * phone-only row; the row hands over its own entry instead.
     */
    @Test
    fun a_bound_delete_discards_the_rows_own_entry_even_without_a_score_log_id() = runTest {
        val log = scoreLogs.create(
            title = "Thu League",
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(firstServer = Side.HOME),
        )
        scoreLogs.attachVideo(log.id, "v1")
        localVideos.add(entry("v1", AnalyzeStage.ANALYZED)) // scoreLogId = null
        clips.clips.value = listOf(clip("c1", "v1"))
        val vm = newViewModel()
        subscribe(vm)
        val row = vm.state.value.matchList.mine.single()
        row.scoreLogId shouldBe log.id
        row.entryId shouldBe "v1"

        vm.deleteBoundMatch("v1", log.id, entryId = row.entryId)
        advanceUntilIdle()

        localVideos.get("v1").shouldBeNull()
    }

    @Test
    fun the_reader_is_asked_about_entries_and_cloud_videos_together() = runTest {
        localVideos.add(entry("e1"))
        clips.clips.value = listOf(clip("c1", "v-cloud"))
        val vm = newViewModel()
        subscribe(vm)

        reads.last() shouldBe setOf("e1", "v-cloud")
    }

    @Test
    fun a_run_that_settles_is_read_again_for_the_same_ids() = runTest {
        localVideos.add(entry("e1"))
        deviceStates.value = mapOf("e1" to LocalAnalysisState.Analysing(0.1f))
        val vm = newViewModel()
        subscribe(vm)
        val before = reads.size

        // Progress ticks change nothing on disk: no read for them.
        deviceStates.value = mapOf("e1" to LocalAnalysisState.Analysing(0.2f))
        advanceUntilIdle()
        reads.size shouldBe before

        // The ids are unchanged, but the run just wrote its track and clips.
        deviceStates.value = mapOf("e1" to done())
        advanceUntilIdle()
        reads.size shouldBe before + 1
        reads.last() shouldBe setOf("e1")
    }
}
