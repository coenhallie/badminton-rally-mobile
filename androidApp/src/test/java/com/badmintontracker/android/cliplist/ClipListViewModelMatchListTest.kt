package com.badmintontracker.android.cliplist

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    private fun newViewModel(scoreLogs: ScoreLogsRepository = this.scoreLogs): ClipListViewModel {
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
     * A scored match whose video the list joined by the log's video id: the
     * entry is the row's, but its own scoreLogId is null, so it does not point
     * at the log and the log's removal does not orphan it.
     */
    private suspend fun boundRowWithAnUnpointedEntry(scoreLogs: ScoreLogsRepository): String {
        val log = scoreLogs.create(
            title = "Thu League",
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(firstServer = Side.HOME),
        )
        scoreLogs.attachVideo(log.id, "v1")
        localVideos.add(entry("v1", AnalyzeStage.ANALYZED)) // scoreLogId = null
        clips.clips.value = listOf(clip("c1", "v1"))
        return log.id
    }

    /**
     * The row hands over its own entry rather than having it looked up again by
     * scoreLogId, which would miss this one and leave the file behind as a
     * phone-only row once the match is gone.
     */
    @Test
    fun a_bound_delete_the_server_accepts_discards_the_rows_own_entry() = runTest {
        val served = ScoreLogsRepository(signedInClient(), MapSettings(), now = { Instant.fromEpochMilliseconds(0) })
        val logId = boundRowWithAnUnpointedEntry(served)
        val vm = newViewModel(served)
        subscribe(vm)
        val row = vm.state.value.matchList.mine.single()
        row.scoreLogId shouldBe logId
        row.entryId shouldBe "v1"

        vm.deleteBoundMatch("v1", logId, entryId = row.entryId)
        eventually { localVideos.get("v1") == null }

        videos.deleteMatchCalls shouldBe listOf("v1")
    }

    /**
     * The same row, with the server refusing the score log's delete. The match
     * survives in the cloud, and an entry the log did not orphan comes back as
     * an ordinary video on this phone: throwing its file away here would lose
     * it for a delete that did not happen.
     */
    @Test
    fun a_bound_delete_the_server_refuses_keeps_an_entry_the_log_did_not_orphan() = runTest {
        // The local-only store: its server delete always fails.
        val logId = boundRowWithAnUnpointedEntry(scoreLogs)
        val vm = newViewModel()
        subscribe(vm)
        val row = vm.state.value.matchList.mine.single()

        vm.deleteBoundMatch("v1", logId, entryId = row.entryId)
        advanceUntilIdle()

        localVideos.get("v1").shouldNotBeNull()
        videos.deleteMatchCalls shouldBe emptyList()
    }

    /**
     * A Supabase client over a mock engine that accepts every request, signed in
     * as the owner. Built here rather than through the app's factory: Auth's
     * Android setup registers lifecycle callbacks on the main Looper, which a
     * plain JVM test does not have, and its default stores want a Context.
     * minimalConfig turns both off and keeps the session in memory.
     */
    private suspend fun signedInClient(): SupabaseClient {
        val client = createSupabaseClient(supabaseUrl = "https://test.supabase.co", supabaseKey = "test-anon-key") {
            httpEngine = MockEngine {
                respond(
                    content = ByteReadChannel("[]"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            install(Auth) { minimalConfig() }
            install(Postgrest)
        }
        // Auth initialises on a coroutine of its own; importing before it has
        // finished is a race the import can lose.
        client.auth.awaitInitialization()
        client.auth.importSession(
            UserSession(
                accessToken = "test-token",
                refreshToken = "test-refresh",
                expiresIn = 3_600,
                tokenType = "Bearer",
                user = UserInfo(aud = "authenticated", id = "user-self"),
            ),
            autoRefresh = false,
        )
        return client
    }

    /**
     * The HTTP call resumes on the client's own threads, which the test
     * scheduler does not run, so this waits in real time (bounded) while
     * draining the scheduler between looks.
     */
    private suspend fun TestScope.eventually(condition: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        error("condition not met within 5s")
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
