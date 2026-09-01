package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.testing.FakeClipsRepository
import com.badmintontracker.shared.testing.FakeVideosRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * The gate, the copy and the ordering. The ordering is here rather than ported
 * into each platform's view model because its interesting case is a failure -
 * the server refusing the delete has to abort the local half - and iOS has no
 * VideosRepository double that can fail. See the 2026-08-29 design, section 3.
 */
class MatchVideoRemovalTest {

    private val t0 = Instant.parse("2026-08-29T18:00:00Z")

    private fun entry(
        id: String = "entry-1",
        stage: AnalyzeStage = AnalyzeStage.FAILED,
        scoreLogId: String? = "log-1",
    ) = LocalVideoEntry(
        id = id,
        uri = "content://media/video/$id",
        displayName = "match.mp4",
        durationMs = 60_000L,
        sizeBytes = 1_000L,
        addedAtEpochMs = 0L,
        stage = stage,
        scoreLogId = scoreLogId,
    )

    // ---- the gate ----

    @Test
    fun a_match_with_no_video_at_all_has_nothing_to_change_or_remove() {
        canRemoveMatchVideo(hasVideo = false, entry = null) shouldBe false
    }

    @Test
    fun a_bound_match_whose_entry_is_gone_can_still_have_its_video_removed() {
        // The "Finishing up…" dead end: bound, no clips, no entry, and today a
        // bare spinner with no action on it at all.
        canRemoveMatchVideo(hasVideo = true, entry = null) shouldBe true
    }

    @Test
    fun a_failed_entry_can_be_changed_or_removed() {
        canRemoveMatchVideo(hasVideo = true, entry = entry(stage = AnalyzeStage.FAILED)) shouldBe true
    }

    @Test
    fun a_video_picked_but_not_yet_uploaded_can_be_taken_back() {
        // No videos row yet, so hasVideo is false - the entry alone is enough.
        canRemoveMatchVideo(hasVideo = false, entry = entry(stage = AnalyzeStage.LOCAL)) shouldBe true
    }

    @Test
    fun a_running_pipeline_blocks_both_gestures() {
        canRemoveMatchVideo(hasVideo = false, entry = entry(stage = AnalyzeStage.UPLOADING)) shouldBe false
        canRemoveMatchVideo(hasVideo = true, entry = entry(stage = AnalyzeStage.PROCESSING)) shouldBe false
    }

    @Test
    fun an_analyzed_entry_kept_for_its_notes_can_still_be_removed() {
        canRemoveMatchVideo(hasVideo = true, entry = entry(stage = AnalyzeStage.ANALYZED)) shouldBe true
    }

    @Test
    fun the_gate_reads_only_the_entry_belonging_to_this_match() {
        val log = store().let { it.newMatch().also { m -> it.finish(m.id) } }
        val others = listOf(entry(id = "other", scoreLogId = "some-other-log"))
        scoreLogCanRemoveVideo(log, others) shouldBe false
    }

    // ---- the copy ----

    @Test
    fun the_confirm_says_the_points_survive_in_every_case() {
        listOf(MatchVideoAction.REMOVE, MatchVideoAction.CHANGE).forEach { action ->
            listOf(true, false).forEach { hasServerVideo ->
                matchVideoPrompt(action, hasServerVideo).body shouldContain
                    "The match, its points and its tags stay."
            }
        }
    }

    @Test
    fun a_video_with_no_server_row_is_not_described_as_losing_rallies() {
        val prompt = matchVideoPrompt(MatchVideoAction.REMOVE, hasServerVideo = false)
        prompt.title shouldBe "Remove this video?"
        prompt.body shouldContain "taken off this match"
    }

    @Test
    fun changing_says_a_new_video_will_be_analysed_and_removing_does_not() {
        matchVideoPrompt(MatchVideoAction.CHANGE, hasServerVideo = true).body shouldContain
            "pick a new video"
        matchVideoPrompt(MatchVideoAction.REMOVE, hasServerVideo = true).body shouldContain
            "rallies and any notes"
    }

    // ---- the ordering ----

    private fun store(settings: MapSettings = MapSettings()) =
        ScoreLogsRepository(client = null, settings = settings, now = { t0 }, ownerId = { "owner-1" })

    private fun ScoreLogsRepository.newMatch() = create(
        title = "Thu League",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = false, firstServer = Side.HOME),
    )

    private class Fixture {
        val scoreLogs = ScoreLogsRepository(
            client = null,
            settings = MapSettings(),
            now = { Instant.parse("2026-08-29T18:00:00Z") },
            ownerId = { "owner-1" },
        )
        val videos = FakeVideosRepository()
        val clips = FakeClipsRepository()
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
    }

    private suspend fun Fixture.remove(scoreLogId: String) = removeMatchVideoOrMessage(
        scoreLogId = scoreLogId,
        scoreLogs = scoreLogs,
        videos = videos,
        clips = clips,
        localVideos = localVideos,
        localAnnotations = localAnnotations,
    )

    private fun Fixture.boundMatch(stage: AnalyzeStage? = AnalyzeStage.FAILED): String {
        val log = scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        scoreLogs.finish(log.id)
        scoreLogs.attachVideo(log.id, "vid-1")
        clips.clips.value = listOf(clip("clip-1", "vid-1"), clip("clip-2", "vid-other"))
        if (stage != null) {
            localVideos.add(
                LocalVideoEntry(
                    id = "vid-1",
                    uri = "content://media/video/vid-1",
                    displayName = "match.mp4",
                    durationMs = 60_000L,
                    sizeBytes = 1_000L,
                    addedAtEpochMs = 0L,
                    stage = stage,
                    scoreLogId = log.id,
                ),
            )
        }
        return log.id
    }

    @Test
    fun removing_a_bound_match_deletes_the_video_unbinds_the_match_and_keeps_its_points() = runTest {
        val f = Fixture()
        val logId = f.boundMatch()
        f.localAnnotations.add("vid-1", 1f, "watch the footwork", null)

        f.remove(logId).shouldBeNull()

        f.videos.deleteMatchCalls shouldBe listOf("vid-1")
        // The clip of another video is untouched.
        f.clips.clips.value.map { it.videoId } shouldBe listOf("vid-other")
        val log = f.scoreLogs.get(logId).shouldNotBeNull()
        log.videoId.shouldBeNull()
        log.status shouldBe ScoreLogStatus.UNBOUND
        log.title shouldBe "Thu League"
        f.localVideos.get("vid-1").shouldBeNull()
        f.localAnnotations.annotationsFor("vid-1").shouldBeEmpty()
    }

    @Test
    fun a_server_refusal_leaves_the_binding_the_clips_and_the_entry_alone() = runTest {
        val f = Fixture()
        val logId = f.boundMatch()
        f.videos.nextDeleteMatchResult = Result.failure(IllegalStateException("nope"))

        f.remove(logId) shouldBe MATCH_VIDEO_REMOVE_FAILED_MESSAGE

        f.scoreLogs.get(logId)?.videoId shouldBe "vid-1"
        f.clips.clips.value shouldHaveSize 2
        f.localVideos.get("vid-1").shouldNotBeNull()
    }

    @Test
    fun an_entry_that_never_reached_the_server_is_removed_without_touching_it() = runTest {
        val f = Fixture()
        val log = f.scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        f.scoreLogs.finish(log.id)
        f.localVideos.add(
            LocalVideoEntry(
                id = "entry-1",
                uri = "file://entry-1",
                displayName = "match.mp4",
                durationMs = 1L,
                sizeBytes = 1L,
                addedAtEpochMs = 0L,
                stage = AnalyzeStage.FAILED,
                scoreLogId = log.id,
            ),
        )

        f.remove(log.id).shouldBeNull()

        f.videos.deleteMatchCalls.shouldBeEmpty()
        f.localVideos.get("entry-1").shouldBeNull()
        f.scoreLogs.get(log.id)?.status shouldBe ScoreLogStatus.UNBOUND
    }

    @Test
    fun a_running_pipeline_is_refused_and_nothing_is_touched() = runTest {
        val f = Fixture()
        val logId = f.boundMatch(stage = AnalyzeStage.UPLOADING)

        f.remove(logId) shouldBe MATCH_VIDEO_BUSY_MESSAGE

        f.videos.deleteMatchCalls.shouldBeEmpty()
        f.localVideos.get("vid-1").shouldNotBeNull()
        f.scoreLogs.get(logId)?.videoId shouldBe "vid-1"
    }

    @Test
    fun a_match_that_has_no_video_is_a_no_op_rather_than_an_error() = runTest {
        val f = Fixture()
        val log = f.scoreLogs.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )

        f.remove(log.id).shouldBeNull()
        f.videos.deleteMatchCalls.shouldBeEmpty()
    }
}

private fun clip(id: String, videoId: String) = RallyClip(
    id = id,
    videoId = videoId,
    ownerId = "owner-1",
    rallyIndex = 1,
    startTimestamp = 0f,
    endTimestamp = 1f,
    durationSeconds = 1f,
    clipStoragePath = "clips/$id.mp4",
    annotationCount = 0,
    createdAt = Instant.parse("2026-08-29T18:00:00Z"),
)
