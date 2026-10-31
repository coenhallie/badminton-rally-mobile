package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.testing.FakeVideosRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

class SaveMatchPlayersTest {

    private val t0 = Instant.parse("2026-10-31T12:00:00Z")
    private var clock = t0

    private val scoreLogs = ScoreLogsRepository(
        client = null, settings = MapSettings(), now = { clock }, ownerId = { "owner-1" },
    )
    private val localVideos = LocalVideoRepository(MapSettings())
    private val videos = FakeVideosRepository()

    private val marco = MatchPlayers("player-marco", null, emptyList(), emptyList())

    private fun newLog(): ScoreLog = scoreLogs.create(
        title = "Thu League", rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    )

    private fun entry(
        id: String,
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failedStep: AnalyzeStep? = null,
        scoreLogId: String? = null,
    ) = LocalVideoEntry(
        id = id, uri = "content://media/video/$id", displayName = "match.mp4",
        durationMs = 60_000L, sizeBytes = 1_000L, addedAtEpochMs = 0L,
        stage = stage, failedStep = failedStep, scoreLogId = scoreLogId,
    )

    private suspend fun save(
        target: PlayersTarget,
        players: MatchPlayers,
        nearSide: Side? = null,
        format: MatchFormat? = null,
        scoreLogId: String? = null,
        videoId: String? = null,
        entryId: String? = null,
    ) = saveMatchPlayersOrMessage(
        target, scoreLogs, videos, localVideos, scoreLogId, videoId, entryId, players, nearSide, format,
    )

    // playersTarget

    @Test
    fun players_target_prefers_the_score_log() {
        playersTarget("log-1", entry("e1"), "e1") shouldBe PlayersTarget.SCORE_LOG
        playersTarget("log-1", null, null) shouldBe PlayersTarget.SCORE_LOG
    }

    @Test
    fun players_target_is_the_entry_before_the_row_exists() {
        playersTarget(null, entry("e1"), null) shouldBe PlayersTarget.LOCAL_ENTRY
        // Even if a caller already knows the id the INSERT will use.
        playersTarget(null, entry("e1"), "e1") shouldBe PlayersTarget.LOCAL_ENTRY
    }

    @Test
    fun players_target_is_the_entry_after_a_failed_upload() {
        val upload = entry("e1", AnalyzeStage.FAILED, AnalyzeStep.UPLOAD)
        val createRow = entry("e1", AnalyzeStage.FAILED, AnalyzeStep.CREATE_ROW)
        playersTarget(null, upload, "e1") shouldBe PlayersTarget.LOCAL_ENTRY
        playersTarget(null, createRow, "e1") shouldBe PlayersTarget.LOCAL_ENTRY
        // Past the INSERT the row exists, so a later failure writes the row.
        playersTarget(null, entry("e1", AnalyzeStage.FAILED, AnalyzeStep.KEYPOINTS), "e1") shouldBe
            PlayersTarget.VIDEO_ROW
    }

    @Test
    fun players_target_waits_during_the_upload() {
        playersTarget(null, entry("e1", AnalyzeStage.UPLOADING), "e1") shouldBe PlayersTarget.UPLOADING
    }

    @Test
    fun players_target_is_the_row_once_uploaded() {
        playersTarget(null, entry("e1", AnalyzeStage.PROCESSING), "e1") shouldBe PlayersTarget.VIDEO_ROW
        playersTarget(null, null, "web-1") shouldBe PlayersTarget.VIDEO_ROW
        playersTarget(null, null, null).shouldBeNull()
        playersTarget(null, entry("e1", AnalyzeStage.PROCESSING), null).shouldBeNull()
    }

    // saveMatchPlayersOrMessage

    @Test
    fun a_problem_saves_nothing() = runTest {
        val log = newLog()
        localVideos.add(entry("e1"))
        val doubles = MatchPlayers("player-marco", null, emptyList(), emptyList())

        val message = save(
            PlayersTarget.SCORE_LOG, doubles, Side.HOME, MatchFormat.DOUBLES,
            scoreLogId = log.id, videoId = "e1", entryId = "e1",
        )

        message shouldBe PlayersCopy.DOUBLES_NOTE
        scoreLogs.get(log.id)?.matchPlayers shouldBe MatchPlayers.NONE
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers.NONE
        localVideos.get("e1")?.nearSide.shouldBeNull()
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun saving_to_a_score_log_dirties_it() = runTest {
        val log = newLog()
        clock = t0 + 1.hours

        val message = save(
            PlayersTarget.SCORE_LOG,
            MatchPlayers("player-marco", null, emptyList(), listOf(" Jo ")),
            format = MatchFormat.SINGLES, scoreLogId = log.id,
        )

        message.shouldBeNull()
        val saved = scoreLogs.get(log.id)!!
        saved.matchPlayers shouldBe MatchPlayers("player-marco", null, emptyList(), listOf("Jo"))
        // Restamped, which is what marks the row dirty for the next sync.
        saved.updatedAt shouldBe clock
        saved.title shouldBe "Thu League"
    }

    @Test
    fun saving_the_home_end_of_a_scored_match_writes_the_entry_before_upload() = runTest {
        val log = newLog()
        localVideos.add(entry("e1", scoreLogId = log.id))

        val message = save(
            PlayersTarget.SCORE_LOG, marco, Side.HOME, scoreLogId = log.id, videoId = null, entryId = "e1",
        )

        message.shouldBeNull()
        scoreLogs.get(log.id)?.homePlayerId shouldBe "player-marco"
        // The log speaks for the players; the entry carries only the end.
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers.NONE
        localVideos.get("e1")?.nearSide shouldBe Side.HOME
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun saving_the_home_end_of_a_scored_match_with_an_uploaded_video_writes_the_row() = runTest {
        val log = newLog()
        localVideos.add(entry("e1", AnalyzeStage.PROCESSING, scoreLogId = log.id))

        val message = save(
            PlayersTarget.SCORE_LOG, marco, Side.AWAY, scoreLogId = log.id, videoId = "e1", entryId = "e1",
        )

        message.shouldBeNull()
        scoreLogs.get(log.id)?.homePlayerId shouldBe "player-marco"
        videos.updatePlayersCalls.single() shouldBe (MatchPlayers.NONE to Side.AWAY)
        localVideos.get("e1")?.nearSide shouldBe Side.AWAY
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers.NONE
    }

    @Test
    fun a_scored_match_whose_video_is_uploading_saves_the_log_and_asks_to_wait() = runTest {
        val log = newLog()
        localVideos.add(entry("e1", AnalyzeStage.UPLOADING, scoreLogId = log.id))

        val message = save(
            PlayersTarget.SCORE_LOG, marco, Side.HOME, scoreLogId = log.id, videoId = null, entryId = "e1",
        )

        message shouldBe PlayersCopy.UPLOAD_IN_PROGRESS
        scoreLogs.get(log.id)?.homePlayerId shouldBe "player-marco"
        localVideos.get("e1")?.nearSide.shouldBeNull()
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun a_scored_match_with_no_video_saves_only_the_log() = runTest {
        val log = newLog()

        save(PlayersTarget.SCORE_LOG, marco, Side.HOME, scoreLogId = log.id).shouldBeNull()

        scoreLogs.get(log.id)?.homePlayerId shouldBe "player-marco"
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun saving_to_an_entry_before_upload_stores_the_normalised_assignment() = runTest {
        localVideos.add(entry("e1"))

        val message = save(
            PlayersTarget.LOCAL_ENTRY,
            MatchPlayers(null, null, listOf(" Coen ", "Ana"), listOf("Marco")),
            Side.HOME, MatchFormat.SINGLES, entryId = "e1",
        )

        message.shouldBeNull()
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))
        localVideos.get("e1")?.nearSide shouldBe Side.HOME
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun saving_to_an_upload_in_progress_asks_to_wait_and_writes_nothing() = runTest {
        localVideos.add(entry("e1", AnalyzeStage.UPLOADING))

        val message = save(PlayersTarget.UPLOADING, marco, Side.HOME, videoId = "e1", entryId = "e1")

        message shouldBe PlayersCopy.UPLOAD_IN_PROGRESS
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers.NONE
        videos.updatePlayersCalls.size shouldBe 0
    }

    @Test
    fun saving_to_a_row_mirrors_the_entry() = runTest {
        localVideos.add(entry("e1", AnalyzeStage.ANALYZED))

        val message = save(
            PlayersTarget.VIDEO_ROW, marco, Side.HOME, MatchFormat.SINGLES, videoId = "e1", entryId = "e1",
        )

        message.shouldBeNull()
        videos.updatePlayersCalls.single() shouldBe (marco to Side.HOME)
        videos.metadata.value.getValue("e1").homePlayerId shouldBe "player-marco"
        localVideos.get("e1")?.matchPlayers shouldBe marco
        localVideos.get("e1")?.nearSide shouldBe Side.HOME
    }

    @Test
    fun saving_to_a_row_this_phone_never_held_has_no_entry_to_mirror() = runTest {
        save(PlayersTarget.VIDEO_ROW, marco, Side.HOME, videoId = "web-1").shouldBeNull()

        videos.updatePlayersCalls.single() shouldBe (marco to Side.HOME)
        localVideos.entries.value.size shouldBe 0
    }

    @Test
    fun a_refused_row_save_leaves_the_entry_alone() = runTest {
        localVideos.add(entry("e1", AnalyzeStage.ANALYZED))
        videos.nextUpdatePlayersResult = Result.failure(IllegalStateException("HTTP 403"))

        val message = save(PlayersTarget.VIDEO_ROW, marco, Side.HOME, videoId = "e1", entryId = "e1")

        message shouldBe PlayersCopy.WHOS_PLAYING_SAVE_FAILED
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers.NONE
        localVideos.get("e1")?.nearSide.shouldBeNull()
        videos.metadata.value.size shouldBe 0
    }

    @Test
    fun a_refused_row_save_for_a_scored_match_reports_but_keeps_the_saved_log() = runTest {
        val log = newLog()
        localVideos.add(entry("e1", AnalyzeStage.ANALYZED, scoreLogId = log.id))
        videos.nextUpdatePlayersResult = Result.failure(IllegalStateException("HTTP 403"))

        val message = save(
            PlayersTarget.SCORE_LOG, marco, Side.HOME, scoreLogId = log.id, videoId = "e1", entryId = "e1",
        )

        message shouldBe PlayersCopy.WHOS_PLAYING_SAVE_FAILED
        scoreLogs.get(log.id)?.homePlayerId shouldBe "player-marco"
        localVideos.get("e1")?.nearSide.shouldBeNull()
    }
}
