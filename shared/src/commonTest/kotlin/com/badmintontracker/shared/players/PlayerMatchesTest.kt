package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SEP_8 = "2026-09-08T10:00:00Z"
private const val OCT_28 = "2026-10-28T10:00:00Z"

private val MARCO = "p-marco"

/** "Now" in the year of [OCT_28], and early the next year. */
private val NOW_2026 = Instant.parse("2026-10-31T12:00:00Z").toEpochMilliseconds()
private val NOW_2027 = Instant.parse("2027-01-05T12:00:00Z").toEpochMilliseconds()

private fun log(id: String, players: MatchPlayers, at: String = SEP_8, videoId: String? = null) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME), events = emptyList(),
    status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(at), updatedAt = Instant.parse(at),
    homePlayerId = players.homePlayerId, awayPlayerId = players.awayPlayerId,
    homePlayers = players.homePlayers, awayPlayers = players.awayPlayers,
)

private fun entry(id: String, players: MatchPlayers, at: String = SEP_8) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(at).toEpochMilliseconds(),
    stage = AnalyzeStage.LOCAL,
    homePlayerId = players.homePlayerId, awayPlayerId = players.awayPlayerId,
    homePlayers = players.homePlayers, awayPlayers = players.awayPlayers,
)

private fun clip(videoId: String, index: Int, owner: String = ME, at: String = SEP_8) = RallyClip(
    id = "$videoId-$index", videoId = videoId, ownerId = owner, rallyIndex = index,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = null,
    title = null, annotationCount = 0, createdAt = Instant.parse(at),
)

private fun list(
    logs: List<ScoreLog> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    clips: List<RallyClip> = emptyList(),
    metadata: Map<String, MatchMetadata> = emptyMap(),
    sharers: Map<String, String> = emptyMap(),
    deviceClips: Map<String, Int> = emptyMap(),
): MatchList = buildMatchList(
    MatchListInput(
        clips = clips, currentUserId = ME, sharerByVideoId = sharers, metadataByVideoId = metadata,
        scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
        storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = deviceClips,
        localNoteCounts = emptyMap(), players = emptyList(),
    ),
    TimeZone.UTC,
)

private val home = MatchPlayers(MARCO, null, emptyList(), listOf("Li"))
private val away = MatchPlayers(null, MARCO, listOf("Coen"), emptyList())
private val someoneElse = MatchPlayers("p-ana", null, emptyList(), emptyList())

class PlayerMatchesTest {

    @Test
    fun for_player_keeps_matches_naming_the_player_on_either_side() {
        val matches = list(
            logs = listOf(log("home", home), log("other", someoneElse)),
            entries = listOf(entry("away", away), entry("nobody", MatchPlayers.NONE)),
            clips = listOf(clip("cloud", 1)),
            metadata = mapOf("cloud" to MatchMetadata("cloud", awayPlayerId = MARCO)),
        )

        matches.forPlayer(MARCO).map { it.key } shouldContainExactlyInAnyOrder
            listOf("score-home", "video-away", "video-cloud")
    }

    @Test
    fun for_player_skips_shared_matches() {
        val matches = list(
            clips = listOf(clip("theirs", 1, owner = OTHER)),
            metadata = mapOf("theirs" to MatchMetadata("theirs", homePlayerId = MARCO)),
            sharers = mapOf("theirs" to "coach@club.es"),
        )

        matches.shared.size shouldBe 1
        matches.forPlayer(MARCO).shouldBeEmpty()
    }

    @Test
    fun the_summary_counts_matches_rallies_and_the_last_date() {
        val matches = list(
            logs = listOf(log("scored", home, at = SEP_8, videoId = "v1"), log("other", someoneElse, at = "2026-10-30T10:00:00Z")),
            clips = listOf(clip("v1", 1), clip("v1", 2), clip("v1", 3)),
            entries = listOf(entry("filmed", away, at = OCT_28)),
            deviceClips = mapOf("filmed" to 2),
        )

        val summary = playerSummary(matches, MARCO)

        summary shouldBe PlayerSummary(
            matchCount = 2, rallyCount = 5, lastMatchAtEpochMs = Instant.parse(OCT_28).toEpochMilliseconds(),
        )
        playerRowDetail(summary, NOW_2026, TimeZone.UTC) shouldBe "2 matches · last Oct 28"
        lastMatchTile(summary, NOW_2026, TimeZone.UTC) shouldBe "Oct 28"
    }

    @Test
    fun a_date_from_an_earlier_year_says_the_year() {
        val summary = playerSummary(list(entries = listOf(entry("filmed", home, at = OCT_28))), MARCO)

        // The row has room for the whole date; the tile, a third of a phone
        // wide, keeps to one line with the year shortened.
        playerRowDetail(summary, NOW_2027, TimeZone.UTC) shouldBe "1 match · last Oct 28, 2026"
        lastMatchTile(summary, NOW_2027, TimeZone.UTC) shouldBe "Oct '26"
    }

    @Test
    fun the_year_is_the_coachs_own_not_utcs() {
        // 23:30 UTC on New Year's Eve is already the new year in Amsterdam,
        // and so is "now": the same year there, a year apart in UTC.
        val summary = playerSummary(list(entries = listOf(entry("filmed", home, at = "2026-12-31T23:30:00Z"))), MARCO)
        val now = Instant.parse("2027-01-01T10:00:00Z").toEpochMilliseconds()

        lastMatchTile(summary, now, TimeZone.of("Europe/Amsterdam")) shouldBe "Jan 1"
        lastMatchTile(summary, now, TimeZone.UTC) shouldBe "Dec '26"
        playerRowDetail(summary, now, TimeZone.UTC) shouldBe "1 match · last Dec 31, 2026"
    }

    @Test
    fun a_year_in_the_first_decade_keeps_two_digits() {
        val summary = playerSummary(list(entries = listOf(entry("filmed", home, at = "2005-03-02T10:00:00Z"))), MARCO)

        lastMatchTile(summary, NOW_2026, TimeZone.UTC) shouldBe "Mar '05"
        playerRowDetail(summary, NOW_2026, TimeZone.UTC) shouldBe "1 match · last Mar 2, 2005"
    }

    @Test
    fun a_player_with_no_matches_reads_no_matches_yet() {
        val summary = playerSummary(list(logs = listOf(log("other", someoneElse))), MARCO)

        summary shouldBe PlayerSummary(matchCount = 0, rallyCount = 0, lastMatchAtEpochMs = null)
        playerRowDetail(summary, NOW_2026, TimeZone.UTC) shouldBe "No matches yet"
        lastMatchTile(summary, NOW_2026, TimeZone.UTC) shouldBe "None yet"
    }

    @Test
    fun one_match_is_singular() {
        val summary = playerSummary(list(entries = listOf(entry("filmed", home, at = OCT_28))), MARCO)

        playerRowDetail(summary, NOW_2026, TimeZone.UTC) shouldBe "1 match · last Oct 28"
    }
}
