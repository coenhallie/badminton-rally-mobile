package com.badmintontracker.shared.invites

import com.badmintontracker.shared.analytics.courtSideNames
import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchDelete
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.matchpage.MatchPageSection
import com.badmintontracker.shared.matchpage.matchPageItem
import com.badmintontracker.shared.matchpage.matchPageSections
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.players.forPlayer
import com.badmintontracker.shared.players.playerSummary
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.repo.CloudPoseRepositoryImpl
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

class LinkedPlayerMatchesTest {

    private val coachUid = "coach-1"
    private val playerUid = "player-user-1"
    private val marcoProfileId = "marco-profile-1"
    private val t0 = Instant.parse("2026-11-20T10:00:00Z")

    private val marcoLinked = LinkedPlayerProfile(
        playerId = marcoProfileId,
        coachId = coachUid,
        coachEmail = "coach@club.nl",
        name = "Marco",
        racketArm = RacketArm.RIGHT,
        linkedAt = t0,
    )

    private fun clip(videoId: String, index: Int, owner: String = coachUid) = RallyClip(
        id = "$videoId-$index",
        videoId = videoId,
        ownerId = owner,
        rallyIndex = index,
        startTimestamp = 0f,
        endTimestamp = 5f,
        durationSeconds = 5f,
        clipStoragePath = "$owner/$videoId/$index.mp4",
        thumbnailStoragePath = "$owner/$videoId/$index.jpg",
        title = null,
        annotationCount = 2,
        createdAt = t0,
    )

    @Test
    fun cloudPoseRepository_uses_get_cloud_pose_artifact_rpc() = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            capturedBody = (req.body as? TextContent)?.text
            jsonResponse(
                """[{
                  "poses_artifact_path":"coach-1/v1/poses.raw",
                  "poses_frame_count":900,
                  "manual_court_keypoints":{
                    "top_left":[0,0],"top_right":[1,0],"bottom_right":[1,1],"bottom_left":[0,1],
                    "net_left":[0,0.5],"net_right":[1,0.5],
                    "service_line_near_left":[0,0.7],"service_line_near_right":[1,0.7],
                    "service_line_far_left":[0,0.3],"service_line_far_right":[1,0.3],
                    "center_near":[0.5,0.7],"center_far":[0.5,0.3]
                  }
                }]"""
            )
        }
        val repo = CloudPoseRepositoryImpl(client)

        val artifact = repo.artifact("v1").getOrThrow()!!

        capturedUrl!!.shouldContain("/rest/v1/rpc/get_cloud_pose_artifact")
        capturedBody!!.shouldContain("\"p_video_id\":\"v1\"")
        artifact.videoId shouldBe "v1"
        artifact.storagePath shouldBe "coach-1/v1/poses.raw"
        artifact.frameCount shouldBe 900
    }

    @Test
    fun scoreLogsRepository_refreshes_linked_logs_separately_and_never_pushes_them() = runTest {
        val postedUrls = mutableListOf<String>()
        val settings = MapSettings()
        val client = TestSupabase.client(settings) { req ->
            val path = req.url.encodedPath
            postedUrls += "${req.method.value} $path"
            when {
                path.contains("/token") -> jsonResponse(
                    """{"access_token":"jwt","token_type":"bearer","expires_in":3600,"refresh_token":"r",
                        "user":{"id":"$playerUid","aud":"authenticated","role":"authenticated",
                        "email":"marco@club.nl","app_metadata":{"account_role":"player"},
                        "created_at":"2026-11-20T10:00:00Z","updated_at":"2026-11-20T10:00:00Z"}}"""
                )
                path.endsWith("/rpc/list_linked_score_logs") -> jsonResponse(
                    """[{
                      "id":"sl-1","owner_id":"$coachUid","video_id":null,"title":"Club Night",
                      "home_players":[],"away_players":["Tom"],
                      "home_player_id":"$marcoProfileId","away_player_id":null,
                      "format":"singles",
                      "rules":{"points_to_win":21,"win_by":2,"cap":30,"games_to_win":2},
                      "setup":{"first_server":"home"},
                      "events":[{"type":"point","side":"home"}],
                      "status":"unbound",
                      "created_at":"2026-11-20T10:00:00Z","updated_at":"2026-11-20T10:00:00Z"
                    }]"""
                )
                else -> jsonResponse("[]")
            }
        }
        client.auth.signInWith(Email) { email = "marco@club.nl"; password = "pw" }
        val repo = ScoreLogsRepository(client, settings, { t0 })

        repo.refreshLinkedLogs().isSuccess shouldBe true
        repo.linkedLogs.value.size shouldBe 1
        repo.linkedLogs.value.single().awayPlayers shouldBe listOf("Tom")
        repo.linkedLogs.value.single().ownerId shouldBe coachUid
        repo.logs.value.shouldBeEmpty()

        // sync() must not upsert the linked log back to score_logs.
        repo.sync().isSuccess shouldBe true
        postedUrls.none { it.startsWith("POST /rest/v1/score_logs") } shouldBe true
    }

    @Test
    fun buildMatchList_places_coached_score_only_video_only_and_bound_matches_as_read_only() {
        val scoreOnly = ScoreLog(
            id = "sl-only",
            ownerId = coachUid,
            videoId = null,
            title = "Score Only",
            homePlayerId = marcoProfileId,
            awayPlayers = listOf("Tom"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(firstServer = Side.HOME),
            events = listOf(ScoreEvent.PointTo(Side.HOME)),
            status = ScoreLogStatus.UNBOUND,
            createdAt = t0,
            updatedAt = t0,
        )
        val bound = ScoreLog(
            id = "sl-bound",
            ownerId = coachUid,
            videoId = "v-bound",
            title = "Bound Match",
            homePlayerId = marcoProfileId,
            awayPlayers = listOf("Lin"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(firstServer = Side.HOME),
            events = listOf(ScoreEvent.PointTo(Side.HOME)),
            status = ScoreLogStatus.BOUND,
            createdAt = t0,
            updatedAt = t0,
        )
        val meta = mapOf(
            "v-bound" to MatchMetadata(
                videoId = "v-bound",
                homePlayerId = marcoProfileId,
                homeNames = listOf("Marco"),
                awayNames = listOf("Lin"),
                nearSide = Side.HOME,
                hasCloudPose = true,
            ),
            "v-video" to MatchMetadata(
                videoId = "v-video",
                title = "Drills Match",
                awayPlayerId = marcoProfileId,
                homeNames = listOf("Jip"),
                awayNames = listOf("Marco"),
                nearSide = Side.HOME,
                hasCloudPose = true,
            ),
            "v-shared" to MatchMetadata(
                videoId = "v-shared",
                title = "Ad-hoc Share",
                homeNames = listOf("Ana"),
                awayNames = listOf("Bea"),
                hasCloudPose = false,
            ),
        )
        val list = buildMatchList(
            MatchListInput(
                clips = listOf(clip("v-bound", 1), clip("v-video", 1), clip("v-video", 2), clip("v-shared", 1)),
                currentUserId = playerUid,
                sharerByVideoId = mapOf("v-shared" to "other@club.nl"),
                metadataByVideoId = meta,
                scoreLogs = emptyList(),
                localEntries = emptyList(),
                cloudProgress = emptyMap(),
                deviceRuns = emptyMap(),
                storedTrackIds = emptySet(),
                storedSkeletonIds = emptySet(),
                deviceClipCounts = emptyMap(),
                localNoteCounts = emptyMap(),
                players = emptyList(),
                linkedProfiles = listOf(marcoLinked),
                linkedScoreLogs = listOf(scoreOnly, bound),
            ),
            TimeZone.UTC,
        )

        list.mine.shouldBeEmpty()
        list.shared.map { it.videoId } shouldContainExactly listOf("v-shared")
        list.coached.size shouldBe 3

        val forMarco = list.forPlayer(marcoProfileId)
        forMarco.size shouldBe 3
        forMarco.all { !it.isOwned && !it.canShare && !it.canEditDetails && !it.canEditPlayers && it.delete == MatchDelete.NONE } shouldBe true

        val boundItem = list.matchPageItem(scoreLogId = "sl-bound", videoId = "v-bound", entryId = null)!!
        boundItem.playersLine shouldBe "Marco vs Lin"
        boundItem.rallyCount shouldBe 1
        boundItem.hasCloudPose shouldBe true
        boundItem.heatmapId shouldBe "v-bound"
        boundItem.analyses shouldContain MatchAnalysis.HEATMAP
        matchPageSections(boundItem) shouldContainExactly listOf(
            MatchPageSection.POINTS,
            MatchPageSection.RALLIES,
            MatchPageSection.ANALYSIS,
        )

        val videoItem = list.matchPageItem(scoreLogId = null, videoId = "v-video", entryId = null)!!
        videoItem.playersLine shouldBe "Jip vs Marco"
        videoItem.hasCloudPose shouldBe true
        val sideNames = courtSideNames(videoItem, listOf(marcoLinked.toPlayer()))!!
        sideNames.far shouldBe "Marco"
        sideNames.farArm shouldBe RacketArm.RIGHT
        sideNames.farPlayerId shouldBe marcoProfileId

        val summary = playerSummary(list, marcoProfileId)
        summary.matchCount shouldBe 3
        summary.rallyCount shouldBe 3
    }
}
