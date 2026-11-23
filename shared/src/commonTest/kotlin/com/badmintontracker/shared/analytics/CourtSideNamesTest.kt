package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

private const val ME = "user-me"
private const val SEP_8 = "2026-09-08T10:00:00Z"

private fun player(id: String, name: String, arm: RacketArm?) =
    Player(id, ME, name, arm, Instant.parse(SEP_8), Instant.parse(SEP_8))

private val marco = player("p-marco", "Marco", RacketArm.LEFT)
private val ana = player("p-ana", "Ana", null)

private fun log(id: String, videoId: String? = null, players: MatchPlayers = MatchPlayers.NONE) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = List(1) { ScoreEvent.PointTo(Side.HOME) },
    status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(SEP_8), updatedAt = Instant.parse(SEP_8),
    homePlayerId = players.homePlayerId, awayPlayerId = players.awayPlayerId,
    homePlayers = players.homePlayers, awayPlayers = players.awayPlayers,
)

private fun entry(id: String, scoreLogId: String? = null, players: MatchPlayers = MatchPlayers.NONE, nearSide: Side? = null) =
    LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "$id.mp4",
        durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(SEP_8).toEpochMilliseconds(),
        title = null, stage = AnalyzeStage.LOCAL, scoreLogId = scoreLogId,
        homePlayerId = players.homePlayerId, awayPlayerId = players.awayPlayerId,
        homePlayers = players.homePlayers, awayPlayers = players.awayPlayers, nearSide = nearSide,
    )

private fun clip(videoId: String) = RallyClip(
    id = "$videoId-1", videoId = videoId, ownerId = ME, rallyIndex = 1,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/1.mp4", thumbnailStoragePath = "thumbs/$videoId/1.jpg",
    title = null, annotationCount = 0, createdAt = Instant.parse(SEP_8),
)

private fun input(
    clips: List<RallyClip> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    metadata: Map<String, MatchMetadata> = emptyMap(),
    players: List<Player> = emptyList(),
) = MatchListInput(
    clips = clips, currentUserId = ME, sharerByVideoId = emptyMap(), metadataByVideoId = metadata,
    scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
    storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = emptyMap(),
    localNoteCounts = emptyMap(), players = players,
)

/** Any real item, with the four fields the toggle reads replaced by [with]. */
private fun item(with: (MatchListItem) -> MatchListItem): MatchListItem =
    with(buildMatchList(input(logs = listOf(log("l1"))), TimeZone.UTC).mine.single())

private fun item(
    homeNames: List<String>,
    awayNames: List<String>,
    nearSide: Side?,
    homePlayerId: String? = null,
    awayPlayerId: String? = null,
) = item {
    it.copy(
        homeNames = homeNames, awayNames = awayNames, nearSide = nearSide,
        homePlayerId = homePlayerId, awayPlayerId = awayPlayerId,
    )
}

class CourtSideNamesTest {

    @Test
    fun unknown_ends_keep_near_and_far() {
        val unknown = item(listOf("Marco"), listOf("Li"), nearSide = null, homePlayerId = "p-marco")

        courtSideNames(unknown, listOf(marco)).shouldBeNull()
        sideToggleLabel(CourtSide.NEAR, null) shouldBe "Near"
        sideToggleLabel(CourtSide.FAR, null) shouldBe "Far"
        profileArm(CourtSide.NEAR, null).shouldBeNull()
        profileArm(CourtSide.FAR, null).shouldBeNull()
    }

    @Test
    fun home_near_names_the_ends() {
        val names = courtSideNames(item(listOf("Marco"), listOf("Li", "Jo"), Side.HOME), emptyList())

        names shouldBe CourtSideNames("Marco", "Li / Jo", null, null)
        sideToggleLabel(CourtSide.NEAR, names) shouldBe "Marco"
        sideToggleLabel(CourtSide.FAR, names) shouldBe "Li / Jo"
    }

    @Test
    fun home_far_swaps_them() {
        val names = courtSideNames(item(listOf("Marco"), listOf("Li"), Side.AWAY), emptyList())

        names shouldBe CourtSideNames("Li", "Marco", null, null)
        sideToggleLabel(CourtSide.NEAR, names) shouldBe "Li"
        sideToggleLabel(CourtSide.FAR, names) shouldBe "Marco"
    }

    @Test
    fun an_empty_side_is_the_opponent() {
        val homeUnnamed = courtSideNames(item(emptyList(), listOf("Li"), Side.HOME), emptyList())
        homeUnnamed shouldBe CourtSideNames("Opponent", "Li", null, null)

        val awayUnnamed = courtSideNames(item(listOf("Marco"), emptyList(), Side.HOME), emptyList())
        awayUnnamed shouldBe CourtSideNames("Marco", "Opponent", null, null)

        // A blank typed name is no name.
        courtSideNames(item(listOf(" "), listOf("Li"), Side.HOME), emptyList()) shouldBe
            CourtSideNames("Opponent", "Li", null, null)
    }

    @Test
    fun no_name_on_either_side_keeps_near_and_far() {
        // Not "Opponent | Opponent": the ends are known but there is nobody to name.
        courtSideNames(item(emptyList(), emptyList(), Side.AWAY), emptyList()).shouldBeNull()
        courtSideNames(item(listOf(""), listOf(" ", ""), Side.HOME), emptyList()).shouldBeNull()
        // A profile this phone has not loaded (or that was deleted) resolves to no name.
        courtSideNames(
            item(emptyList(), emptyList(), Side.HOME, homePlayerId = "p-gone"),
            listOf(marco),
        ).shouldBeNull()
    }

    @Test
    fun a_profile_hand_is_the_sides_arm() {
        val names = courtSideNames(
            item(listOf("Marco"), listOf("Ana"), Side.AWAY, homePlayerId = "p-marco", awayPlayerId = "p-ana"),
            listOf(ana, marco),
        )

        // Home (Marco, left-handed) is far; Ana has no hand set. Both profile IDs are preserved.
        names shouldBe CourtSideNames("Ana", "Marco", null, RacketArm.LEFT, "p-ana", "p-marco")
        profileArm(CourtSide.FAR, names) shouldBe RacketArm.LEFT
        profileArm(CourtSide.NEAR, names).shouldBeNull()
        profilePlayerId(CourtSide.NEAR, names) shouldBe "p-ana"
        profilePlayerId(CourtSide.FAR, names) shouldBe "p-marco"
    }

    @Test
    fun typed_names_have_no_arm() {
        // A typed name has no id, and an id this phone does not know has no hand to read.
        val names = courtSideNames(
            item(listOf("Marco"), listOf("Li"), Side.HOME, homePlayerId = null, awayPlayerId = "p-gone"),
            listOf(marco),
        )

        names shouldBe CourtSideNames("Marco", "Li", null, null)
        profileArm(CourtSide.NEAR, names).shouldBeNull()
        profileArm(CourtSide.FAR, names).shouldBeNull()
        profilePlayerId(CourtSide.NEAR, names).shouldBeNull()
        profilePlayerId(CourtSide.FAR, names).shouldBeNull()
    }

    @Test
    fun a_scored_match_with_a_phone_video_takes_the_end_from_the_entry() {
        // The score log speaks for the players; the phone's entry for the end.
        val liHome = MatchPlayers(null, "p-marco", listOf("Li"), emptyList())
        val list = buildMatchList(
            input(
                logs = listOf(log("l1", videoId = "v1", players = liHome)),
                entries = listOf(entry("v1", scoreLogId = "l1", nearSide = Side.AWAY)),
                players = listOf(marco),
            ),
            TimeZone.UTC,
        )

        val names = courtSideNames(list.mine.single(), listOf(marco))

        // Away is Marco, and Away was at the near end.
        names shouldBe CourtSideNames("Marco", "Li", RacketArm.LEFT, null, "p-marco", null)
    }

    @Test
    fun a_cloud_video_takes_the_end_from_its_metadata() {
        val meta = MatchMetadata(
            videoId = "v2", homePlayerId = "p-marco", awayPlayers = listOf("Jip"), nearSide = Side.AWAY,
            homeNames = listOf("Marco"), awayNames = listOf("Jip"),
        )
        val list = buildMatchList(
            input(clips = listOf(clip("v2")), metadata = mapOf("v2" to meta), players = listOf(marco)),
            TimeZone.UTC,
        )

        val names = courtSideNames(list.mine.single(), listOf(marco))

        names shouldBe CourtSideNames("Jip", "Marco", null, RacketArm.LEFT, null, "p-marco")
    }
}
