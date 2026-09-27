package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.MatchWhere
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.badmintontracker.shared.matchpage.MatchPageSection.ANALYSIS
import com.badmintontracker.shared.matchpage.MatchPageSection.MATCH
import com.badmintontracker.shared.matchpage.MatchPageSection.POINTS
import com.badmintontracker.shared.matchpage.MatchPageSection.RALLIES

private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SEP_8 = "2026-09-08T10:00:00Z"

private fun entry(id: String, stage: AnalyzeStage = AnalyzeStage.LOCAL, scoreLogId: String? = null) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(SEP_8).toEpochMilliseconds(),
    title = null, stage = stage,
    failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
    failureMessage = null, scoreLogId = scoreLogId,
)

private fun clip(videoId: String, index: Int, owner: String = ME) = RallyClip(
    id = "$videoId-$index", videoId = videoId, ownerId = owner, rallyIndex = index,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = "thumbs/$videoId/$index.jpg",
    title = null, annotationCount = 0, createdAt = Instant.parse(SEP_8),
)

private fun log(id: String, videoId: String? = null, points: Int = 0) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = List(points) { ScoreEvent.PointTo(Side.HOME) },
    status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(SEP_8), updatedAt = Instant.parse(SEP_8),
)

private fun list(
    clips: List<RallyClip> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    tracks: Set<String> = emptySet(),
    skeletons: Set<String> = emptySet(),
    deviceClips: Map<String, Int> = emptyMap(),
    sharers: Map<String, String> = emptyMap(),
): MatchList = buildMatchList(
    MatchListInput(
        clips = clips, currentUserId = ME, sharerByVideoId = sharers, metadataByVideoId = emptyMap(),
        scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = emptyMap(),
        storedTrackIds = tracks, storedSkeletonIds = skeletons, deviceClipCounts = deviceClips,
    ),
    TimeZone.UTC,
)

private fun only(l: MatchList): MatchListItem = (l.mine + l.shared).single()

/** One list per kind of match the app can hold, for the rule-wide checks below. */
private val EVERY_KIND: List<MatchList> = listOf(
    list(entries = listOf(entry("e1"))),                                                      // just imported
    list(entries = listOf(entry("e1")), deviceClips = mapOf("e1" to 3), tracks = setOf("e1"), skeletons = setOf("e1")), // analysed on the phone
    list(entries = listOf(entry("e1")), tracks = setOf("e1")),                                 // pose run, no rallies
    list(clips = listOf(clip("v1", 1), clip("v1", 2))),                                        // cloud only
    list(clips = listOf(clip("v1", 1)), entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), tracks = setOf("v1")), // cloud and phone
    list(clips = listOf(clip("v1", 1)), tracks = setOf("v1")),                                 // file removed, track kept
    list(logs = listOf(log("s1"))),                                                            // scored, never started
    list(logs = listOf(log("s1", points = 3))),                                                // scored
    list(logs = listOf(log("s1", videoId = "v1", points = 3)), clips = listOf(clip("v1", 1)),
        entries = listOf(entry("v1", AnalyzeStage.ANALYZED, scoreLogId = "s1")), tracks = setOf("v1")), // scored, filmed, analysed
    list(clips = listOf(clip("v2", 1, owner = OTHER)), sharers = mapOf("v2" to "coach@club.es")),   // shared
    list(clips = listOf(clip("v2", 1, owner = OTHER)), sharers = mapOf("v2" to "coach@club.es"), tracks = setOf("v2")), // shared, analysed
)

class MatchPageSectionTest {

    // --- the status banner: the row's own status, on a scored match's page ---

    @Test fun a_scored_match_with_an_unmarked_court_shows_the_rows_status_on_its_page() {
        val item = only(list(logs = listOf(log("s1", points = 3)), entries = listOf(entry("e1", scoreLogId = "s1"))))
        assertTrue(showsStatusBanner(item))
    }

    @Test fun a_scored_match_analyzed_on_the_phone_has_no_status_banner() {
        // The device run's results prove the court was marked: the row asks
        // nothing, so the page does not either.
        val item = only(list(
            logs = listOf(log("s1", points = 3)), entries = listOf(entry("e1", scoreLogId = "s1")),
            deviceClips = mapOf("e1" to 1), tracks = setOf("e1"),
        ))
        assertFalse(showsStatusBanner(item))
    }

    @Test fun a_video_only_match_asks_under_its_player_not_in_a_banner() {
        assertFalse(showsStatusBanner(only(EVERY_KIND[0])))
    }

    // --- which sections a match has ---

    @Test fun a_video_just_imported_has_only_the_match() {
        assertEquals(listOf(MATCH), matchPageSections(only(EVERY_KIND[0])))
    }

    @Test fun a_video_analysed_on_the_phone_has_match_rallies_and_analysis() {
        assertEquals(listOf(MATCH, RALLIES, ANALYSIS), matchPageSections(only(EVERY_KIND[1])))
    }

    @Test fun a_cloud_only_match_has_rallies_alone() {
        assertEquals(listOf(RALLIES), matchPageSections(only(EVERY_KIND[3])))
    }

    @Test fun a_match_whose_file_left_the_phone_keeps_its_analysis_but_not_the_match() {
        assertEquals(listOf(RALLIES, ANALYSIS), matchPageSections(only(EVERY_KIND[5])))
    }

    @Test fun a_scored_match_nobody_started_still_has_points_because_that_is_where_scoring_starts() {
        val item = only(EVERY_KIND[6])
        assertTrue(MatchAnalysis.SCORE !in item.analyses)
        assertEquals(listOf(POINTS), matchPageSections(item))
    }

    @Test fun a_scored_filmed_analysed_match_has_all_four_in_order() {
        assertEquals(listOf(POINTS, MATCH, RALLIES, ANALYSIS), matchPageSections(only(EVERY_KIND[8])))
    }

    @Test fun a_shared_match_has_no_match_section() {
        assertEquals(listOf(RALLIES), matchPageSections(only(EVERY_KIND[9])))
        assertEquals(listOf(RALLIES, ANALYSIS), matchPageSections(only(EVERY_KIND[10])))
    }

    // Spec 2.3: the chips on a row predict the page's sections, for every kind of match.
    @Test fun the_sections_agree_with_the_rows_chips_for_every_kind_of_match() {
        EVERY_KIND.flatMap { it.mine + it.shared }.forEach { item ->
            val s = matchPageSections(item)
            assertEquals(item.scoreLogId != null, POINTS in s, "points: ${item.key}")
            assertEquals(item.where == MatchWhere.PHONE || item.where == MatchWhere.BOTH, MATCH in s, "match: ${item.key}")
            assertEquals(MatchAnalysis.RALLIES in item.analyses, RALLIES in s, "rallies: ${item.key}")
            assertEquals(
                MatchAnalysis.HEATMAP in item.analyses || MatchAnalysis.SKELETON in item.analyses,
                ANALYSIS in s, "analysis: ${item.key}",
            )
            if (MatchAnalysis.SCORE in item.analyses) assertTrue(POINTS in s, "score chip without points: ${item.key}")
            assertTrue(s.isNotEmpty(), "a row that opens an empty page: ${item.key}")
        }
    }

    // --- which section opens ---

    @Test fun points_open_first_then_rallies_then_analysis_then_the_match() {
        assertEquals(POINTS, defaultSection(listOf(POINTS, MATCH, RALLIES, ANALYSIS)))
        assertEquals(RALLIES, defaultSection(listOf(MATCH, RALLIES, ANALYSIS)))
        assertEquals(ANALYSIS, defaultSection(listOf(MATCH, ANALYSIS)))
        assertEquals(MATCH, defaultSection(listOf(MATCH)))
        assertNull(defaultSection(emptyList()))
    }

    @Test fun the_coachs_choice_holds_while_the_section_exists() {
        assertEquals(ANALYSIS, shownSection(ANALYSIS, listOf(MATCH, RALLIES, ANALYSIS)))
    }

    // Review focus 1: the file removed while Match is showing.
    @Test fun a_chosen_section_that_goes_away_falls_back_to_the_default() {
        assertEquals(RALLIES, shownSection(MATCH, listOf(RALLIES, ANALYSIS)))
    }

    @Test fun no_choice_yet_opens_the_default() {
        assertEquals(RALLIES, shownSection(null, listOf(MATCH, RALLIES)))
    }

    @Test fun a_finished_run_opens_its_analysis_when_it_has_one_and_the_default_otherwise() {
        assertEquals(ANALYSIS, sectionAfterRun(only(EVERY_KIND[1])))
        assertEquals(RALLIES, sectionAfterRun(only(EVERY_KIND[3])))
    }

    @Test fun analysis_is_keyed_by_the_heatmap_id_and_falls_back_to_the_entry() {
        assertEquals("v1", analysisId(only(EVERY_KIND[5])))
        assertEquals("e1", analysisId(only(EVERY_KIND[1])))
    }

    // --- finding the page's item ---

    @Test fun a_page_finds_its_match_by_score_log_first() {
        val l = EVERY_KIND[8]
        assertSame(only(l), l.matchPageItem(scoreLogId = "s1", videoId = "nope", entryId = null))
    }

    @Test fun a_page_finds_a_cloud_match_by_its_video() {
        val l = EVERY_KIND[3]
        assertSame(only(l), l.matchPageItem(scoreLogId = null, videoId = "v1", entryId = null))
    }

    // A local entry's id becomes its videos.id on Analyze, so a page opened on
    // the entry still finds the row once the file has left the phone.
    @Test fun a_page_opened_on_an_entry_still_finds_it_as_a_cloud_video() {
        val l = EVERY_KIND[5]
        assertSame(only(l), l.matchPageItem(scoreLogId = null, videoId = null, entryId = "v1"))
    }

    @Test fun a_page_opened_on_a_phone_only_video_finds_it_by_entry() {
        val l = EVERY_KIND[0]
        assertSame(only(l), l.matchPageItem(scoreLogId = null, videoId = null, entryId = "e1"))
    }

    @Test fun a_match_that_is_gone_is_not_found() {
        assertNull(EVERY_KIND[0].matchPageItem(scoreLogId = null, videoId = null, entryId = "gone"))
    }
}
