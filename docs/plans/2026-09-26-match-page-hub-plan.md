# Match Page Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the match page the one place a match lives (Points | Match | Rallies | Analysis, Analysis with Heatmap | Base | Skeleton under it), open it from every row, and remove the Analytics tab and the bottom bar on Android and iOS.

**Architecture:** One shared rule (`matchPageSections` over the list's own `MatchListItem`) decides which sections a match has, so the row's chips and the page's toggles cannot disagree. The bodies of today's local player, analysis detail and device-clips screens move into section composables/views that the match page hosts; their routes are deleted. The match list becomes the root screen with a Settings gear; a finished run is announced by a snackbar (Android) or toast (iOS) with View.

**Tech Stack:** Kotlin Multiplatform (commonMain + kotlin.test/kotest), Jetpack Compose Material 3, SwiftUI via SKIE, xcodegen.

**Spec:** `docs/plans/2026-09-26-match-page-hub-design.md`. Read it first. Sections are cited below as "spec 2.x".

## Global Constraints

- **Branch** `feat/match-page-hub` (already created off `feat/simplify-new-match`, spec committed). Single working tree `/Users/coenhallie/Desktop/projects/badminton-rally-mobile`. Implementers run **one at a time**: Gradle's lock, the git index and the iOS derived data are shared.
- **The Desktop is no longer in iCloud.** Build in place. No rsync copy, no dataless-file recipes.
- **Java:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before any `./gradlew` and any iOS build (its "Compile Kotlin Framework" phase runs Gradle).
- **Android tests:** `./gradlew :shared:jvmTest` and `./gradlew :androidApp:testDebugUnitTest`. Judge by Gradle's own `BUILD SUCCESSFUL` / `BUILD FAILED` and `^e:` lines, never a background task's exit code; end background commands with `echo "gradle exit ${pipestatus[1]}"`.
- **Android devices:** `adb devices` often lists Coen's phone. Always `ANDROID_SERIAL=emulator-5554`; never install a verification build on the phone. Emulator: `~/Library/Android/sdk/emulator/emulator -avd Pixel_9a -dns-server 8.8.8.8,1.1.1.1`. Install with `assembleDebug` then `adb -s emulator-5554 install -r <apk>` (keeps the session).
- **iOS test command** (in the repo):
  ```bash
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  xcodegen generate --spec iosApp/project.yml --project iosApp
  xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null
  xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO 2>&1 | tee /tmp/ios-test.log | grep -E "Executed|error:|FAILED|TEST (SUCCEEDED|FAILED)"
  ```
  `iosAppTests` and `iosAppUITests` report separately; add both counts. `** TEST SUCCEEDED **` also prints for skipped UI tests: read the `Test Case '...' (passed|skipped)` lines in `/tmp/ios-test.log`. After adding or deleting a Swift file, commit the regenerated `iosApp/iosApp.xcodeproj/project.pbxproj` (tracked and generated: never hand-edit), and confirm a new file's `.o` exists under `iosApp/build/DerivedData` before trusting a green build.
- **SKIE:** Kotlin enums are Swift enums (`MatchPageSection.analysis`); a nullable Kotlin enum is a Swift optional (`MatchWhere?` already works this way); top-level functions are `FileNameKt.fn(...)` (`MatchPageSectionKt.matchPageSections(item:)`); a Kotlin extension function is a method on its receiver (`list.matchPageItem(scoreLogId:videoId:entryId:)`); objects are `Thing.shared`; Kotlin default arguments do not reach Swift.
- **iOS lint tests** walk `Sources`: `TypographyAdoptionTests` fails any `.font(` outside `Theme/` (use `.shuttlType(ShuttlType.x)`); `SystemImageTests` checks every `Image(systemName: "literal")` resolves.
- **iOS deployment target is 17.**
- **Copy:** no em dash in any string, comment or commit message. Sentence case. US spelling ("analyze"). Every new user-facing string lives in shared `MatchPageCopy` (Task 1), never re-typed per platform. Section labels are exactly `Points`, `Match`, `Rallies`, `Analysis`; panel labels stay `Heatmap`, `Base`, `Skeleton`.
- **Colour:** tokens only (`ShuttlTheme.extended.*` / `MaterialTheme.colorScheme.*` on Android, `Shuttl.*` on iOS). Material components get every colour and shape set explicitly. Light theme: `bgInput == bgTertiary`, so never stack one on the other.
- **Commits:** conventional prefix, body says why. **No `Co-Authored-By` trailer, no "Generated with" footer.** Do not hand-edit `CHANGELOG.md`.
- **Comments:** this codebase explains *why*, generously, at decisions that look arbitrary. Match it. When you move or delete code, rewrite every comment that describes a tab, the Analytics list, the local player screen, the heatmap route or "Clips on this phone"; do not leave one describing code that is gone.

## Review Focus

1. **A section disappearing under the coach** (the file removed from the phone while Match is showing; a re-analysis clearing the clips while Rallies is showing) falls back to the default section instead of drawing an empty page or crashing. `shownSection` is tested in Task 1; the page must read `shownSection`, never `chosenSection` directly (Tasks 3, 7).
2. **Full-screen video on Android keeps its position**: rotating to landscape on Match goes full screen and back without the player being rebuilt, and Back leaves full screen before it leaves the page. The player lives in `MatchVideoSection`, which stays at one call site in `MatchScreen` in both layouts (Task 3). Checked on the emulator in Task 5.
3. **iOS match page stays live while pushed**: a run that finishes while the page is open makes Analysis appear without leaving the page. On iOS the root list's `.task` (which runs `ClipListModel.start()`) stops when a page is pushed, so the match page runs `start()` itself (Task 7), checked with a temporary log line.
4. **A finished run never navigates on its own** and is announced once: only when the list is the front screen with nothing above it, and not again when the coach later returns (the seen-set rule, Task 1 tests). The View action opens the right match on Analysis (Tasks 4, 8).
5. **The four-pill row at large font scale on a 360dp screen** does not clip or overflow, and the second row (Heatmap | Base | Skeleton) reads as belonging to Analysis. Screenshots in Tasks 5 and 9; if the two rows read as peers, stop and raise it with Coen rather than restyling on your own.
6. **The scored match's Analyze path is not doubled**: a scored match with a bound video shows its Mark court / Retry in `AttachStatusBanner` only, never a second button under the player (Tasks 3, 7 compute `analyzeLabel` as null when `item.scoreLogId != null`).

---

## File structure

**Shared (new):** `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageSection.kt` (enum, `matchPageSections`, `defaultSection`, `shownSection`, `sectionAfterRun`, `analysisId`, `MatchList.matchPageItem`), `.../matchpage/FinishedRunMessage.kt`, `.../matchpage/MatchPageCopy.kt`. Tests: `shared/src/commonTest/kotlin/com/badmintontracker/shared/matchpage/MatchPageSectionTest.kt`, `FinishedRunMessageTest.kt`, `MatchPageCopyTest.kt`.
**Shared (modified):** `localvideo/RunAnnouncement.kt` (+ test). **Deleted in Task 10:** `home/HomeTab.kt` (+ test), `analytics/AnalyticsRowState.kt` (+ test), `HomeCopy.TAB_*`/`ANALYTICS_EMPTY_*`, `MatchListItem.opensLocalPlayer`, `MatchListCopy.MENU_HEATMAP`/`menuDeviceClips`, `RunAnnouncement.openAnalytics`.

**Android (new):** `androidApp/src/main/java/com/badmintontracker/android/match/MatchVideoSection.kt`, `match/AnalysisSection.kt`, `match/DeviceRalliesSection.kt`, `settings/SettingsScreen.kt` (from `home/SettingsTab.kt`), `home/HomeTopBar.kt` (from `home/TabTopBar.kt`). Test: `androidApp/src/test/java/com/badmintontracker/android/match/DeviceClipLabelTest.kt`.
**Android (modified):** `match/MatchScreen.kt`, `nav/Route.kt`, `AuthGate.kt`, `cliplist/ClipListScreen.kt`, `home/HomeScreen.kt`.
**Android (deleted):** `localvideo/LocalPlayerScreen.kt` (Task 3), `home/HomeNavigationBar.kt`, `home/MatchesTab.kt`, `home/SettingsTab.kt`, `home/TabTopBar.kt`, `analytics/AnalyticsScreen.kt`, `analytics/AnalyticsRows.kt`, `analytics/AnalyticsDetailScreen.kt`, tests `analytics/AnalyticsRowsTest.kt`, `analytics/DetailSubtitleTest.kt` (Task 4). `analytics/CloudAnalysisPanelsTest.kt` moves to `match/`.

**iOS (new):** `iosApp/Sources/Match/MatchVideoSection.swift`, `Match/AnalysisSection.swift`, `Match/DeviceRalliesSection.swift`, `Components/ShuttlToast.swift`, `Home/MatchesScreen.swift` (from `Home/MatchesTabView.swift`), `Home/HomeHeader.swift` (from `Home/TabHeader.swift`).
**iOS (modified):** `Match/MatchView.swift`, `Home/HomeView.swift`, `ClipList/ClipListView.swift`, `Home/SettingsView.swift`, the four `iosApp/UITests/*.swift`.
**iOS (deleted):** `LocalVideo/LocalPlayerView.swift`, `LocalVideo/LocalPlayerRoute.swift` (its `LocalVideoEntry: Identifiable` extension moves), `LocalAnalysis/LocalClipsView.swift` (Task 7); `Analytics/AnalyticsListView.swift`, `Analytics/AnalyticsRows.swift`, `Analytics/AnalyticsDetailView.swift` (helpers move), `Tests/AnalyticsRowsTests.swift` (Task 8).

---

### Task 1: Shared section rule, item lookup, announcement and copy

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageSection.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/FinishedRunMessage.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageCopy.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/RunAnnouncement.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/matchpage/MatchPageSectionTest.kt`, `FinishedRunMessageTest.kt`, `MatchPageCopyTest.kt`; modify `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/RunAnnouncementTest.kt`

**Interfaces:**
- Produces: `enum class MatchPageSection(val label: String) { POINTS, MATCH, RALLIES, ANALYSIS }`; `fun matchPageSections(item: MatchListItem): List<MatchPageSection>`; `fun defaultSection(sections: List<MatchPageSection>): MatchPageSection?`; `fun shownSection(chosen: MatchPageSection?, sections: List<MatchPageSection>): MatchPageSection?`; `fun sectionAfterRun(item: MatchListItem): MatchPageSection?`; `fun analysisId(item: MatchListItem): String?`; `fun MatchList.matchPageItem(scoreLogId: String?, videoId: String?, entryId: String?): MatchListItem?`; `data class FinishedRunMessage(val text: String, val item: MatchListItem?)` + `fun finishedRunMessage(ids: Set<String>, list: MatchList): FinishedRunMessage?`; `object MatchPageCopy`; `RunAnnouncement.announce: Set<String>` (with `openAnalytics` kept as a computed property until Task 10).

- [ ] **Step 1: Write the failing tests**

`MatchPageSectionTest.kt` (fixtures are copied from `MatchListBuilderTest.kt`, whose helpers are file-private; keep them in step with it):

```kotlin
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
```

`FinishedRunMessageTest.kt`:

```kotlin
package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinishedRunMessageTest {
    private fun item(key: String, title: String, entryId: String? = null, videoId: String? = null) =
        MatchListItem(
            key = key, sortAtEpochMs = 0, title = title, detail = "", playersLine = null, sharedBy = null,
            where = null, analyses = emptyList(), statusText = null, statusIsError = false,
            action = com.badmintontracker.shared.matchlist.MatchAction.NONE,
            entryId = entryId, videoId = videoId, scoreLogId = null, localUri = null, coverClip = null,
            scoreText = null, isOwned = true, canShare = false, canEditDetails = false, canAnalyzeAgain = false,
            canRemoveFromPhone = false, delete = com.badmintontracker.shared.matchlist.MatchDelete.NONE,
            opensLocalPlayer = false, deviceClipCount = 0, heatmapId = null, accessibilityStatus = "",
        )

    private val thu = item("video-e1", "Thu League", entryId = "e1")
    private val sat = item("video-v2", "Sat Cup", videoId = "v2")
    private val list = MatchList(mine = listOf(thu, sat), shared = emptyList())

    @Test fun one_run_names_its_match_and_offers_it() {
        assertEquals(FinishedRunMessage("Thu League is analyzed", thu), finishedRunMessage(setOf("e1"), list))
    }

    @Test fun a_cloud_run_is_found_by_its_video() {
        assertEquals(sat, finishedRunMessage(setOf("v2"), list)?.item)
    }

    @Test fun several_runs_are_counted_and_offer_nothing_to_open() {
        assertEquals(FinishedRunMessage("2 matches are analyzed", null), finishedRunMessage(setOf("e1", "v2"), list))
    }

    @Test fun a_run_whose_match_is_gone_says_nothing() {
        assertNull(finishedRunMessage(setOf("gone"), list))
    }
}
```

(When Task 10 removes `opensLocalPlayer` from `MatchListItem`, delete that argument here.)

`MatchPageCopyTest.kt`:

```kotlin
package com.badmintontracker.shared.matchpage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MatchPageCopyTest {
    @Test fun the_sections_are_named_as_the_spec_names_them() {
        assertEquals(listOf("Points", "Match", "Rallies", "Analysis"), MatchPageSection.entries.map { it.label })
    }

    @Test fun no_em_dash_anywhere() {
        listOf(
            MatchPageCopy.SECTIONS, MatchPageCopy.VIEW, MatchPageCopy.SORT, MatchPageCopy.SORT_RALLY_ORDER,
            MatchPageCopy.SORT_MOST_NOTES, MatchPageCopy.SETTINGS, MatchPageCopy.DEVICE_CLIPS_EMPTY,
            MatchPageCopy.MISSING, MatchPageCopy.ADD_NOTE, MatchPageCopy.analyzed("A"), MatchPageCopy.analyzedCount(2),
        ).forEach { assertFalse('\u2014' in it, it) }
    }
}
```

Add to `RunAnnouncementTest.kt` (keep every existing test; they still read `openAnalytics`, which stays until Task 10):

```kotlin
    @Test
    fun only_the_runs_that_just_landed_are_announced() {
        val first = announceFinishedRuns(setOf("v1"), emptySet(), onHome = true)
        val both = announceFinishedRuns(setOf("v1", "v2"), first.seen, onHome = true)

        both.announce shouldBe setOf("v2")
    }

    @Test
    fun nothing_is_announced_while_something_is_above_the_list() {
        announceFinishedRuns(setOf("v1"), emptySet(), onHome = false).announce shouldBe emptySet()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :shared:jvmTest --tests '*MatchPage*' --tests '*FinishedRunMessage*' --tests '*RunAnnouncement*'`
Expected: FAIL to compile (`Unresolved reference: matchPageSections`, `announce`).

- [ ] **Step 3: Implement**

`MatchPageSection.kt`:

```kotlin
package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem

/**
 * The parts of one match page, in the order the toggle row draws them.
 *
 * "Match", not "Video": every rally is a video too, and Match against Rallies
 * reads as the whole recording against its parts. See
 * docs/plans/2026-09-26-match-page-hub-design.md.
 */
enum class MatchPageSection(val label: String) {
    POINTS("Points"),
    MATCH("Match"),
    RALLIES("Rallies"),
    ANALYSIS("Analysis"),
}

/**
 * Which sections [item]'s page has.
 *
 * Read from the same fields the row's status line draws its chips from, so
 * the chips on a row are the sections of its page: # is Points, the phone is
 * Match, the film is Rallies, the flame or the figure is Analysis. The two
 * tabs this replaced drew the same row and disagreed about what it had; one
 * rule over one item cannot.
 *
 * One exception, on purpose: a score log nobody has started draws no # chip,
 * but its page still has Points, because Points is where scoring starts.
 */
fun matchPageSections(item: MatchListItem): List<MatchPageSection> = buildList {
    if (item.scoreLogId != null) add(MatchPageSection.POINTS)
    if (item.entryId != null) add(MatchPageSection.MATCH)
    if (MatchAnalysis.RALLIES in item.analyses) add(MatchPageSection.RALLIES)
    if (MatchAnalysis.HEATMAP in item.analyses || MatchAnalysis.SKELETON in item.analyses) {
        add(MatchPageSection.ANALYSIS)
    }
}

/**
 * Points before rallies before analysis before the raw recording: a scored
 * match opens where it always has, a filmed one on its rallies rather than the
 * whole video, and a video just imported on the Match section, which is where
 * its Analyze button is. The draw order ([MatchPageSection.entries]) is not the
 * opening order, which is why this list exists.
 */
private val OPENING_ORDER = listOf(
    MatchPageSection.POINTS, MatchPageSection.RALLIES, MatchPageSection.ANALYSIS, MatchPageSection.MATCH,
)

/** The section a page opens on, or null for a page with none (a match that is gone). */
fun defaultSection(sections: List<MatchPageSection>): MatchPageSection? =
    OPENING_ORDER.firstOrNull { it in sections }

/**
 * What the page draws: the coach's [chosen] section while it still exists,
 * else the default. A section can go while it is on screen (the file removed,
 * a re-analysis clearing the clips); the choice is kept rather than cleared,
 * so the page returns to it if it comes back.
 */
fun shownSection(chosen: MatchPageSection?, sections: List<MatchPageSection>): MatchPageSection? =
    chosen?.takeIf { it in sections } ?: defaultSection(sections)

/**
 * Where a finished run's View takes the coach: the analysis it produced, or
 * the page's default when the run cut rallies but kept no track.
 */
fun sectionAfterRun(item: MatchListItem): MatchPageSection? {
    val sections = matchPageSections(item)
    return if (MatchPageSection.ANALYSIS in sections) MatchPageSection.ANALYSIS else defaultSection(sections)
}

/**
 * The id the Analysis section reads its track, skeleton and clips by: the
 * heatmap's own id (the entry's, or the cloud video's once the file has gone),
 * else the entry, for a match with a skeleton and no track.
 */
fun analysisId(item: MatchListItem): String? = item.heatmapId ?: item.entryId

/**
 * The row a match page is showing, as the list draws it now, or null once it
 * is gone.
 *
 * By score log first, because a scored match's log is its identity whatever
 * video it gains. The two video ids are tried both ways round: a local entry's
 * id becomes its `videos.id` on Analyze, so a page opened on the entry keeps
 * finding its row after the file has left the phone, and a page opened on a
 * cloud video finds a row that still has its file here.
 */
fun MatchList.matchPageItem(scoreLogId: String?, videoId: String?, entryId: String?): MatchListItem? {
    val all = mine + shared
    if (scoreLogId != null) all.firstOrNull { it.scoreLogId == scoreLogId }?.let { return it }
    for (id in listOfNotNull(videoId, entryId)) {
        all.firstOrNull { it.videoId == id || it.entryId == id }?.let { return it }
    }
    return null
}
```

`FinishedRunMessage.kt`:

```kotlin
package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem

/**
 * What the list says when runs land: [text], and the one match its View opens,
 * or null when several landed at once (the rows already show which).
 */
data class FinishedRunMessage(val text: String, val item: MatchListItem?)

/**
 * The announcement for the run ids `announceFinishedRuns` just returned.
 *
 * Ids are entry ids for a device run and entry-or-video ids for a cloud one,
 * so a row is matched on either. Null when none of them is a row any more:
 * a match deleted while its run was finishing has nothing to announce.
 */
fun finishedRunMessage(ids: Set<String>, list: MatchList): FinishedRunMessage? {
    fun hit(id: String?) = id != null && id in ids
    val items = (list.mine + list.shared).filter { hit(it.entryId) || hit(it.videoId) }.distinctBy { it.key }
    return when (items.size) {
        0 -> null
        1 -> FinishedRunMessage(MatchPageCopy.analyzed(items.single().title), items.single())
        else -> FinishedRunMessage(MatchPageCopy.analyzedCount(items.size), null)
    }
}
```

`MatchPageCopy.kt`:

```kotlin
package com.badmintontracker.shared.matchpage

/** Every string the match page, its sections and the run announcement draw. */
object MatchPageCopy {
    /** The section toggle row, to a screen reader. */
    const val SECTIONS = "Match sections"
    const val VIEW = "View"
    fun analyzed(title: String) = "$title is analyzed"
    fun analyzedCount(count: Int) = "$count matches are analyzed"

    const val SORT = "Sort rallies"
    const val SORT_RALLY_ORDER = "Rally order"
    const val SORT_MOST_NOTES = "Most notes"

    /** The list's gear, and the page it opens. */
    const val SETTINGS = "Settings"

    const val ADD_NOTE = "Add note"
    const val DEVICE_CLIPS_EMPTY = "No clips are stored for this video."
    const val MISSING = "This match is no longer on this phone."
}
```

`RunAnnouncement.kt`: replace the data class and the function body (keep and update the KDoc: it now reads "whether a finished run should be announced on the match list", `onHome` is "the list is the front screen with nothing above it", and the return carries the ids to announce):

```kotlin
data class RunAnnouncement(
    /** The runs to announce now: newly finished, and only while the list is in front. */
    val announce: Set<String>,
    /** Pass back into the next [announceFinishedRuns] call. */
    val seen: Set<String>,
) {
    /** Transitional, for the tab-switch callers until Task 10 of the 2026-09-26 plan removes them. */
    val openAnalytics: Boolean get() = announce.isNotEmpty()
}

fun announceFinishedRuns(
    finished: Set<String>,
    seen: Set<String>,
    onHome: Boolean,
): RunAnnouncement = RunAnnouncement(
    announce = if (onHome) finished - seen else emptySet(),
    seen = finished,
)
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :shared:jvmTest`
Expected: `BUILD SUCCESSFUL`, the new tests among those run. If the agreement test fails, the builder and the rule disagree about a kind of match: read which field, and fix the rule to follow the builder, not the other way round.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage shared/src/commonTest/kotlin/com/badmintontracker/shared/matchpage shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/RunAnnouncement.kt shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/RunAnnouncementTest.kt
git commit -m "feat(shared): one rule for a match page's sections, and the finished-run message"
```

---

### Task 2: Android section bodies, moved out of their screens

A behaviour-preserving move: the three screens keep working, now as thin wrappers over the new section composables. Task 3 deletes the wrappers.

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/MatchVideoSection.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/AnalysisSection.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/DeviceRalliesSection.kt`
- Modify: `localvideo/LocalPlayerScreen.kt`, `analytics/AnalyticsDetailScreen.kt`, `AuthGate.kt` (the `Route.LocalClips` composable, lines 619-663)
- Test: `androidApp/src/test/java/com/badmintontracker/android/match/DeviceClipLabelTest.kt`

**Interfaces:**
- Consumes: `MatchPageCopy.DEVICE_CLIPS_EMPTY`, `MatchPageCopy.ADD_NOTE` (Task 1).
- Produces:
  - `@Composable fun MatchVideoSection(vm: LocalPlayerViewModel, entry: LocalVideoEntry, playbackPrefs: PlaybackPreferenceRepository, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit, snackbar: SnackbarHostState, analyzeLabel: String?, onAnalyze: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun AnalysisSection(analysisId: String, localAnalysis: LocalAnalysisRunner, localVideos: LocalVideoRepository, playbackPrefs: PlaybackPreferenceRepository, racketArmPrefs: RacketArmPreferenceRepository, cloudClips: List<RallyClip>, modifier: Modifier = Modifier)`
  - `@Composable fun DeviceRalliesSection(entryId: String, localAnalysis: LocalAnalysisRunner, modifier: Modifier = Modifier)` and `internal fun deviceClipLabel(clip: ClipCutter.Clip): String`

- [ ] **Step 1: Write the failing test**

`DeviceClipLabelTest.kt` (the same three cases `iosApp/Tests/LocalClipsTests.swift` pins for iOS's `localClipLabel`):

```kotlin
package com.badmintontracker.android.match

import com.badmintontracker.android.localanalysis.ClipCutter
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClipLabelTest {
    private fun clip(index: Int, start: Double, end: Double) =
        ClipCutter.Clip(index = index, startSeconds = start, endSeconds = end, file = java.io.File("/tmp/$index.mp4"))

    @Test fun a_rally_is_named_with_its_bounds_and_length() {
        assertEquals("Rally 3  12.3s - 19.9s  (7.5s)", deviceClipLabel(clip(3, 12.34, 19.86)))
    }

    // Clips recovered by filename have no bounds; a fabricated "0.0s - 0.0s"
    // would read as a broken clip.
    @Test fun a_clip_with_no_bounds_is_named_without_fabricating_them() {
        assertEquals("Rally 7", deviceClipLabel(clip(7, 0.0, 0.0)))
    }
}
```

Before writing it, open `localanalysis/ClipCutter.kt` and match `ClipCutter.Clip`'s real constructor (field names and the file/uri field); adjust `clip(...)` to it, keeping the three values. Run `./gradlew :androidApp:testDebugUnitTest --tests '*DeviceClipLabelTest*'`: expect a compile failure on `deviceClipLabel`.

- [ ] **Step 2: Create `DeviceRalliesSection.kt`**

The list from the `Route.LocalClips` composable, drawn as the rows `LocalClipsView.swift` already draws on iOS: one card per rally on `surfaceVariant`, a play glyph trailing.

```kotlin
package com.badmintontracker.android.match

/**
 * The rallies an on-device run cut, as the Rallies section shows them until
 * the 2026-09-11 design turns them into real rally rows.
 *
 * Only for a match with no cloud clips: when both exist the cloud clips are
 * the rallies, the rule the row's own rally count follows. Plain rows, with
 * no thumbnail or note count, because device clips have neither yet.
 */
@Composable
fun DeviceRalliesSection(entryId: String, localAnalysis: LocalAnalysisRunner, modifier: Modifier = Modifier) {
    // Read once per entry: a sidecar parse and one stat per clip, and the set
    // cannot grow while the section is up without a run this page would show.
    val clips = remember(entryId) { localAnalysis.storedClips(entryId) }
    var playing by remember { mutableStateOf<ClipCutter.Clip?>(null) }
    playing?.let { LocalClipPlayerDialog(clip = it, onDismiss = { playing = null }) }
    val shape = RoundedCornerShape(ShuttlRadius.large)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (clips.isEmpty()) item(key = "empty") { PanelMessage(MatchPageCopy.DEVICE_CLIPS_EMPTY) }
        items(clips, key = { it.index }) { clip ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                    .clickable(role = Role.Button) { playing = clip }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    deviceClipLabel(clip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(ShuttlIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * "Rally 3  12.3s - 19.9s  (7.5s)", or the rally number alone for a clip
 * recovered by filename, whose bounds are unknown: "0.0s - 0.0s" would read as
 * a broken clip rather than as missing bookkeeping. iOS's `localClipLabel`.
 */
internal fun deviceClipLabel(clip: ClipCutter.Clip): String =
    if (clip.endSeconds > clip.startSeconds) {
        "Rally ${clip.index}  ${"%.1f".format(clip.startSeconds)}s - ${"%.1f".format(clip.endSeconds)}s  " +
            "(${"%.1f".format(clip.endSeconds - clip.startSeconds)}s)"
    } else {
        "Rally ${clip.index}"
    }
```

`PanelMessage` is `internal` in `localanalysis/SkeletonPanel.kt`; same module, so import it. Replace the body of the `Route.LocalClips` composable's `Scaffold` content with `DeviceRalliesSection(entryId = args.entryId, localAnalysis = localAnalysis, modifier = Modifier.padding(padding))` and delete its old `clips`, `playing` and `Column`.

- [ ] **Step 3: Create `AnalysisSection.kt`**

Move everything inside `AnalyticsDetailScreen` from `val hasSkeleton = ...` through the `when (panel) { ... }` into:

```kotlin
package com.badmintontracker.android.match

/**
 * The Analysis section: a second toggle row between the heatmap, the per-rally
 * base position and the skeleton, drawn only when more than one has content,
 * over the panel chosen. Moved out of AnalyticsDetailScreen unchanged, rules
 * and all: which panels exist is still `availablePanels`, and the rally windows
 * still come from whichever run the heatmap draws.
 *
 * [analysisId] is `analysisId(item)`: the heatmap's own id, else the entry.
 */
@Composable
fun AnalysisSection(
    analysisId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    racketArmPrefs: RacketArmPreferenceRepository,
    cloudClips: List<RallyClip>,
    modifier: Modifier = Modifier,
) {
    // ... the moved lines, with every `entryId` renamed `analysisId` ...
    Column(modifier.fillMaxSize()) {
        // ... the moved `if (panels.size > 1) ShuttlPillTabs(...) else Text("HEATMAP", ...)` and `when (panel)` ...
    }
}
```

Keep the moved comments (rewrite the one that says "a coach arrives here in one tap from that list" to say the label sits under the page's own section row). `AnalyticsDetailScreen`'s `Scaffold` content becomes `AnalysisSection(analysisId = entryId, ..., modifier = Modifier.padding(padding))`, and its `entry`/`VideoTitle` lines stay for its top bar.

- [ ] **Step 4: Create `MatchVideoSection.kt`**

Move `LocalPlayerScreen`'s body into it, with these changes (everything else moves as is, comments included):

```kotlin
package com.badmintontracker.android.match

/**
 * The Match section: the full recording on this phone, with its timestamped
 * notes and the label chips, as LocalPlayerScreen showed it.
 *
 * Fullscreen is hoisted because, in fullscreen, the page drops its own bar,
 * status banner and section row and this section fills the screen: only the
 * page can do that. The player stays remembered here in both layouts, and the
 * page calls this from one place in both, so going fullscreen and back keeps
 * the position. Leaving the section disposes it, which releases the player.
 *
 * [analyzeLabel] is "Analyze" or "Retry" under the player while the match asks
 * for one (the row's own action), else null.
 */
@Composable
fun MatchVideoSection(
    vm: LocalPlayerViewModel,
    entry: LocalVideoEntry,
    playbackPrefs: PlaybackPreferenceRepository,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    analyzeLabel: String?,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

- `var isFullscreen by remember { ... }` goes. Every read of it reads `fullscreen`; every write calls `onFullscreenChange(...)`: `BackHandler(enabled = fullscreen) { onFullscreenChange(false) }`, `FullscreenEffect(fullscreen)`, `LaunchedEffect(orientation) { onFullscreenChange(orientation == Configuration.ORIENTATION_LANDSCAPE) }`, and `onFullscreenToggle = { onFullscreenChange(!fullscreen) }`.
- `val snackbar = remember { SnackbarHostState() }` goes; the parameter replaces it. The `LaunchedEffect(labelErrorMessage, isFullscreen)` keeps its logic on `fullscreen`.
- The `Scaffold`, `TopAppBar` and its Analyze button go. The non-fullscreen `Column` becomes `Column(modifier.fillMaxSize())`, with, right after the `TransportBar`:

```kotlin
                analyzeLabel?.let { label ->
                    ShuttlButton(
                        text = label,
                        onClick = onAnalyze,
                        variant = ShuttlButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                }
```

- The fullscreen `Box` becomes `Box(modifier.fillMaxSize().background(Color.Black))`, and the two layouts are an `if (fullscreen) { Box ... } else { Column ... }`.
- `ANNOTATION_STORAGE_NOTE` moves with it (private).

`LocalPlayerScreen` becomes a wrapper that keeps its behaviour until Task 3 deletes it: `var isFullscreen by remember { mutableStateOf(false) }`; `Scaffold(topBar = { if (!isFullscreen) TopAppBar(...as today, Analyze button included...) }, snackbarHost = { SnackbarHost(snackbar) })`; content `MatchVideoSection(vm, entry, playbackPrefs, fullscreen = isFullscreen, onFullscreenChange = { isFullscreen = it }, snackbar = snackbar, analyzeLabel = null, onAnalyze = onAnalyze, modifier = Modifier.padding(if (isFullscreen) PaddingValues() else padding))`.

- [ ] **Step 5: Build and run the tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, `DeviceClipLabelTest` passing.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/match androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt androidApp/src/test/java/com/badmintontracker/android/match
git commit -m "refactor(android): the player, analysis and device-clip bodies become match page sections"
```

---

### Task 3: Android match page with sections, opened from every row

**Files:**
- Modify: `nav/Route.kt`, `AuthGate.kt`, `match/MatchScreen.kt`, `cliplist/ClipListScreen.kt`, `home/HomeScreen.kt`
- Delete: `localvideo/LocalPlayerScreen.kt`

**Interfaces:**
- Consumes: Task 1's `matchPageSections`, `shownSection`, `analysisId`, `MatchList.matchPageItem`, `MatchPageSection`, `MatchPageCopy`; Task 2's three sections.
- Produces: `Route.Match(scoreLogId: String? = null, videoId: String? = null, entryId: String? = null, attach: String? = null, section: String? = null)`; `ClipListScreen(onOpenMatch: (MatchListItem) -> Unit, ...)`; AuthGate-level `analyzeEntry: (entryId: String) -> Unit` and `openMatch: (MatchListItem, MatchPageSection?) -> Unit` (Task 4 uses both).

- [ ] **Step 1: Route**

In `Route.Match`, add after `videoId`: `val entryId: String? = null,` and after `attach`: `val section: String? = null,`. Rewrite its KDoc: at least one of the three ids is non-null; `entryId` is how a video only on this phone is opened; `section` is a `MatchPageSection` name, read once when the page opens (the run announcement's View). Delete `Route.LocalPlayer`, `Route.LocalClips` and `Route.Heatmap` (and `Heatmap`'s KDoc; rewrite `AnalyticsDetail`'s KDoc to say only the Analytics tab opens it now, until Task 4 deletes it).

- [ ] **Step 2: AuthGate: one list view model, one analyze rule, one way into a match**

Above the `NavHost`, next to `storedResults`:

```kotlin
            // One factory for the one list view model. The match page reads the
            // Route.Home entry's instance rather than building its own: it finds
            // its match in that list (matchPageItem), and a second instance
            // would be a second, later copy of the same list.
            val clipListVmFactory = remember(storedResults) {
                viewModelFactory {
                    initializer {
                        ClipListViewModel(
                            rally.clips, rally.auth, rally.shares, rally.videos, rally.scoreLogs,
                            localVideos, coordinator, localAnnotations,
                            deviceStates = localAnalysis.state,
                            storedResults = storedResults,
                        )
                    }
                }
            }

            // Resume a failed cloud run whose court points are saved, else mark
            // the court. The one decision behind every Analyze and Retry: the
            // list's rows, the match page's button under the player, and its
            // "Analyze again". (Move the long comment from inside Route.Home's
            // `fun analyze` here, unchanged.)
            val analyzeEntry: (String) -> Unit = remember(nav) {
                { entryId ->
                    localVideos.get(entryId)?.let { entry ->
                        if (canResumeFailedAnalysis(entry)) coordinator.retry(entry.id)
                        else nav.navigate(Route.CourtMarking(entry.id))
                    }
                }
            }

            // Every way into a match: a row's tap, and (Task 4) a finished
            // run's View. All three ids ride along; the page finds its row by
            // whichever the list still has.
            val openMatch: (MatchListItem, MatchPageSection?) -> Unit = remember(nav) {
                { item, section ->
                    nav.navigate(
                        Route.Match(
                            scoreLogId = item.scoreLogId, videoId = item.videoId,
                            entryId = item.entryId, section = section?.name,
                        )
                    )
                }
            }
```

In `composable<Route.Home>`: `val clipListVm: ClipListViewModel = viewModel(factory = clipListVmFactory)`; delete the local `fun analyze` (its comment moved above) and pass `analyzeEntry` where `::analyze` was (`onAnalyze = analyzeEntry`, and the Analytics tab's `onAnalyse = { row -> row.entryId?.let(analyzeEntry) }`). Replace `onMatchClick`, `onScoreMatchClick`, `onLocalClick`, `onOpenHeatmap`, `onOpenLocalClips` with `onOpenMatch = { openMatch(it, null) }`.

Delete the `composable<Route.LocalPlayer>`, `composable<Route.LocalClips>` and `composable<Route.Heatmap>` blocks, and rewrite the `CourtMarking` pop comment that lists "LocalPlayer's own Analyze/Re-analyze" to name the match page's Analyze instead.

Replace the start of `composable<Route.Match>` through the `MatchScreen(` call with:

```kotlin
                composable<Route.Match> { entry ->
                    val args = entry.toRoute<Route.Match>()
                    // Route.Home is always under a match page (the start
                    // destination, and every push is over it), so its entry is
                    // there to scope the shared list view model to.
                    val homeEntry = remember(entry) { nav.getBackStackEntry<Route.Home>() }
                    val clipListVm: ClipListViewModel = viewModel(homeEntry, factory = clipListVmFactory)
                    val listState by clipListVm.state.collectAsStateWithLifecycle()
                    val logs by rally.scoreLogs.logs.collectAsStateWithLifecycle()
                    val logVideoId = logs.firstOrNull { it.id == args.scoreLogId }?.videoId
                    val item = listState.matchList.matchPageItem(args.scoreLogId, args.videoId ?: logVideoId, args.entryId)
                    // The row's video first: a video only on this phone gains
                    // one when a cloud run creates it, and the page must start
                    // showing its rallies without a re-entry. Then the route's,
                    // then the score log's, for a match bound after it opened.
                    val effectiveVideoId = item?.videoId ?: args.videoId ?: logVideoId
```

keep the `summaryVm` and `matchVm` blocks as they are, then call `MatchScreen` with (only the changed and new arguments shown; the rest stay):

```kotlin
                    val cloudClips by rally.clips.observeClips().collectAsStateWithLifecycle(emptyList())
                    MatchScreen(
                        vm = clipListVm,
                        // ... matchVm, summaryVm, media, shares as today; themePrefs removed ...
                        scoreLogId = args.scoreLogId,
                        videoId = effectiveVideoId,
                        entryId = args.entryId,
                        initialSection = MatchPageSection.entries.firstOrNull { it.name == args.section },
                        attach = args.attach,
                        // ... onBack, onClipClick, onScore, onAddVideo, onMarkCourt, onRetry as today ...
                        onAnalyzeEntry = analyzeEntry,
                        matchSection = { e, fullscreen, onFullscreenChange, snackbar, analyzeLabel ->
                            val playerVm: LocalPlayerViewModel = viewModel(
                                key = "player-${e.id}",
                                factory = viewModelFactory {
                                    initializer { LocalPlayerViewModel(e.id, localAnnotations, rally.labels) }
                                },
                            )
                            MatchVideoSection(
                                vm = playerVm, entry = e, playbackPrefs = rally.playbackPrefs,
                                fullscreen = fullscreen, onFullscreenChange = onFullscreenChange,
                                snackbar = snackbar, analyzeLabel = analyzeLabel,
                                onAnalyze = { analyzeEntry(e.id) },
                            )
                        },
                        analysisSection = { id ->
                            AnalysisSection(
                                analysisId = id, localAnalysis = localAnalysis, localVideos = localVideos,
                                playbackPrefs = rally.playbackPrefs, racketArmPrefs = rally.racketArmPrefs,
                                cloudClips = cloudClips,
                            )
                        },
                        deviceRalliesSection = { id -> DeviceRalliesSection(entryId = id, localAnalysis = localAnalysis) },
                    )
```

(`item` is only used here to pick `effectiveVideoId`; `MatchScreen` looks it up again from `vm.state` itself, from the same list.)

- [ ] **Step 3: MatchScreen**

Signature: remove `themePrefs` (and the `ThemeToggleButton` in the actions and its `themeMode` line). Add:

```kotlin
    /** A video only on this phone has no cloud id and no score log: this is how the page finds it. */
    entryId: String?,
    /** The section to open on, from a finished run's View; null opens the default. */
    initialSection: MatchPageSection?,
    onAnalyzeEntry: (entryId: String) -> Unit,
    /**
     * The three section bodies, built by AuthGate, which holds the runner and
     * the repositories they read. Slots rather than parameters so this screen
     * does not take five more dependencies to pass straight through.
     */
    matchSection: @Composable (entry: LocalVideoEntry, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit, snackbar: SnackbarHostState, analyzeLabel: String?) -> Unit,
    analysisSection: @Composable (analysisId: String) -> Unit,
    deviceRalliesSection: @Composable (entryId: String) -> Unit,
```

Delete `private enum class Facet` and the whole `hasPoints` / `hasRallies` / `chosenFacet` / `facet` block. In its place, after `val log = matchState.log`:

```kotlin
    // The row this page is for, from the same list the Matches screen draws,
    // and the sections it has by the one shared rule: the chips on that row
    // are the toggles here. See matchPageSections.
    val matchItem = state.matchList.matchPageItem(scoreLogId, videoId, entryId)
    val sections = matchItem?.let(::matchPageSections).orEmpty()
    // Saveable, held for the life of this page and never cleared: a section
    // can go while on screen (the file removed, clips emptying mid-refresh),
    // and shownSection falls back without forgetting what the coach picked.
    var chosenSection by rememberSaveable { mutableStateOf(initialSection) }
    val section = shownSection(chosenSection, sections)
    var videoFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = videoFullscreen && section == MatchPageSection.MATCH
    val entry = matchItem?.entryId?.let { id -> state.localEntries.firstOrNull { it.id == id } }
    // Under the player only for a video with no score log. A scored match's
    // video asks through AttachStatusBanner already; two buttons for one
    // question is the kind of drift the shared rule exists to stop.
    val analyzeLabel = matchItem?.takeIf { it.scoreLogId == null }?.action
        ?.takeIf { it == MatchAction.ANALYZE || it == MatchAction.RETRY }
        ?.let(MatchListCopy::actionLabel)
```

Title: make the first branch of the `title = { when { ... } }` read `matchItem != null -> Text(matchItem.title, maxLines = 1, overflow = TextOverflow.Ellipsis)`, keeping the existing branches after it for the moment before the item arrives.

`topBar = { if (!fullscreen) TopAppBar(...) }`. In the actions: delete the sort `IconButton` and its `DropdownMenu`; change the overflow's condition to `log != null || match?.isOwned == true || matchItem?.canAnalyzeAgain == true`, show "Edit details" only under `log != null || match?.isOwned == true`, and add after "Export as text":

```kotlin
                            matchItem?.takeIf { it.canAnalyzeAgain }?.entryId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(MatchListCopy.MENU_ANALYZE_AGAIN) },
                                    onClick = { overflowOpen = false; onAnalyzeEntry(id) },
                                )
                            }
```

Replace `pageBody` and the `if (videoId != null) PullToRefreshBox ... else ...` after it with:

```kotlin
        if (matchItem == null) {
            // Gone (deleted elsewhere, or the file removed from a match that
            // was only on this phone), or not listed yet in the first frame
            // after a push. Only the first is worth words.
            val gone = (scoreLogId != null && log == null && match == null) ||
                (scoreLogId == null && videoId == null && entryId != null && state.localEntries.none { it.id == entryId })
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (gone) Text(MatchPageCopy.MISSING)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(if (fullscreen) PaddingValues() else padding)) {
            if (!fullscreen) {
                // Same status, same actions as the list's row: see AttachStatusBanner.
                matchState.attach?.let { attach ->
                    AttachStatusBanner(attach = attach, onMarkCourt = onMarkCourt, onRetry = onRetry)
                }
                // A row over one section is a control that does nothing.
                if (sections.size > 1 && section != null) {
                    ShuttlPillTabs(
                        labels = sections.map { it.label },
                        selectedIndex = sections.indexOf(section),
                        onSelect = { chosenSection = sections[it] },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            // One call site for every section, in both layouts: the Match
            // section's player lives inside it, and moving the call would
            // rebuild the player on every trip into fullscreen and back.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SectionBody(
                    section = section,
                    refreshable = videoId != null,
                    isRefreshing = state.isRefreshing,
                    onRefresh = { vm.refresh(); summaryVm?.refresh() },
                ) {
                    when (section) {
                        MatchPageSection.POINTS -> LazyColumn(Modifier.fillMaxSize()) {
                            if (log != null) pointsFacet(
                                log = log, card = matchState.card, tally = matchState.tally,
                                points = matchState.match?.points.orEmpty(), onScore = onScore,
                            )
                        }
                        MatchPageSection.MATCH -> entry?.let {
                            matchSection(it, fullscreen, { on -> videoFullscreen = on }, snackbarHostState, analyzeLabel)
                        }
                        MatchPageSection.RALLIES -> {
                            val deviceOnly = clipsForMatch.isEmpty() && matchItem.deviceClipCount > 0 && matchItem.entryId != null
                            if (deviceOnly) {
                                deviceRalliesSection(matchItem.entryId!!)
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    if (clipsForMatch.size > 1) item(key = "sort") { RallySortRow(sort) { sort = it } }
                                    ralliesFacet(
                                        // ... the same arguments as today's call ...
                                    )
                                }
                            }
                        }
                        MatchPageSection.ANALYSIS -> analysisId(matchItem)?.let { analysisSection(it) }
                        null -> Unit
                    }
                }
            }
        }
```

`clipsForMatch` stays as it is (`state.clips.filter { it.videoId == videoId }`), since `videoId` is now the effective one. Add, at the bottom of the file:

```kotlin
/**
 * Pull to refresh around the sections that show cloud data (Points for a
 * filmed match, Rallies), and nothing around the others: pulling on the Match
 * section's notes or on a heatmap refreshing the clip cache would be a
 * gesture that does nothing visible. A score-only match has no video to
 * refresh, as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionBody(
    section: MatchPageSection?,
    refreshable: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (refreshable && (section == MatchPageSection.POINTS || section == MatchPageSection.RALLIES)) {
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { content() }
    } else {
        content()
    }
}

/**
 * The rally sort, at the top of the Rallies section: it only reorders rallies,
 * so it lives with them rather than in a top bar every section shares.
 */
@Composable
private fun RallySortRow(sort: ClipSort, onSort: (ClipSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        Box {
            TextButton(
                onClick = { open = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.semantics { contentDescription = MatchPageCopy.SORT },
            ) {
                Icon(ShuttlIcons.ListOrdered, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (sort == ClipSort.RallyOrder) MatchPageCopy.SORT_RALLY_ORDER else MatchPageCopy.SORT_MOST_NOTES,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf(ClipSort.RallyOrder to MatchPageCopy.SORT_RALLY_ORDER, ClipSort.MostNotes to MatchPageCopy.SORT_MOST_NOTES)
                    .forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { if (sort == value) Icon(Icons.Default.Check, contentDescription = null) },
                            onClick = { onSort(value); open = false },
                        )
                    }
            }
        }
    }
}
```

Style that `DropdownMenu` with the same container colour and shape the list row's `RowMenu` in `cliplist/MatchListRow.kt` passes (read it and copy those arguments), so no Material default colour appears.

Fix every remaining reference to `facet` (the `LaunchedEffect`s and sheets only read `clipsForMatch`/`summary`; the sort state stays).

- [ ] **Step 4: The list opens every row the same way**

In `ClipListScreen`: replace the parameters `onMatchClick`, `onScoreMatchClick`, `onLocalClick`, `onOpenHeatmap`, `onOpenLocalClips` with

```kotlin
    /** Every row opens its match page, whatever the match has: see matchPageSections. */
    onOpenMatch: (MatchListItem) -> Unit,
```

`fun open(item)` goes; the row's `onClick = { onOpenMatch(item) }`. In `menuFor`, delete the `deviceClipCount` item and the `heatmapId` item. `HomeScreen` takes `onOpenMatch: (MatchListItem) -> Unit` in place of the same five and passes it through.

- [ ] **Step 5: Delete the local player screen**

`git rm androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt`. `grep -rn "LocalPlayerScreen\|Route.LocalPlayer\|Route.LocalClips\|Route.Heatmap\|onOpenHeatmap\|onOpenLocalClips\|onLocalClick" androidApp/src` must print nothing but comments you then rewrite.

- [ ] **Step 6: Build and test**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `MatchViewModelTest`, `ClipListViewModel*Test`, `RowGesturesTest` and `AuthNavigationTest` unchanged and green.

- [ ] **Step 7: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): the match page shows Points, Match, Rallies and Analysis, and every row opens it"
```

---

### Task 4: Android without the bar: the list as root, Settings as a page, the run as a snackbar

**Files:**
- Create: `settings/SettingsScreen.kt` (from `home/SettingsTab.kt`), `home/HomeTopBar.kt` (from `home/TabTopBar.kt`)
- Modify: `home/HomeScreen.kt`, `AuthGate.kt`, `nav/Route.kt`
- Delete: `home/HomeNavigationBar.kt`, `home/MatchesTab.kt`, `home/SettingsTab.kt`, `home/TabTopBar.kt`, `analytics/AnalyticsScreen.kt`, `analytics/AnalyticsRows.kt`, `analytics/AnalyticsDetailScreen.kt`, `androidApp/src/test/.../analytics/AnalyticsRowsTest.kt`, `androidApp/src/test/.../analytics/DetailSubtitleTest.kt`
- Move: `androidApp/src/test/.../analytics/CloudAnalysisPanelsTest.kt` to `androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt`

**Interfaces:**
- Consumes: `announceFinishedRuns(...).announce`, `finishedRunMessage`, `sectionAfterRun`, `MatchPageCopy.VIEW`/`SETTINGS` (Task 1); `openMatch`, `analyzeEntry` (Task 3).
- Produces: `Route.Settings`; `HomeScreen(..., onSettings: () -> Unit, announced: Set<String>, onAnnounced: () -> Unit, onOpenMatch: (MatchListItem, MatchPageSection?) -> Unit)`.

- [ ] **Step 1: Settings as a pushed page**

Add `@Serializable data object Settings : Route` to `Route.kt`. `git mv home/SettingsTab.kt settings/SettingsScreen.kt`, change its package to `com.badmintontracker.android.settings`, rename the function `SettingsScreen`, drop `contentPadding`, and wrap it in a `Scaffold` with

```kotlin
        topBar = {
            TopAppBar(
                title = { Text(MatchPageCopy.SETTINGS) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
```

in place of its `TabTopBar`, adding an `onBack: () -> Unit` parameter; the body `Column` takes the Scaffold's padding. Rewrite its KDoc: "Everything the drawer's footer and Home's overflow once held, now behind the list's gear." Its comment about "the tab title's own inset and the Analytics tab's gutter" becomes "the page gutter". In `AuthGate`:

```kotlin
                composable<Route.Settings> { entry ->
                    // Sign out goes through the list's view model, as it did from
                    // the Settings tab (vm::signOut): the Home entry's instance,
                    // fetched the way the match page fetches it.
                    val homeEntry = remember(entry) { nav.getBackStackEntry<Route.Home>() }
                    val homeVm: ClipListViewModel = viewModel(homeEntry, factory = clipListVmFactory)
                    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
                    SettingsScreen(
                        themeMode = themeMode,
                        onToggleTheme = themePrefs::toggle,
                        email = rally.auth.currentUserEmail(),
                        onLabels = { nav.navigate(Route.Labels) },
                        onSignOut = homeVm::signOut,
                        onBack = { nav.popBackStack() },
                    )
                }
```

- [ ] **Step 2: The top bar**

`git mv home/TabTopBar.kt home/HomeTopBar.kt`, rename `TabTopBar` to `HomeTopBar`, and rewrite its KDoc: the root screen's own title bar (headline "Matches"), drawn under the status bar itself because it is not a `TopAppBar`: a root screen has no navigation icon, and a 28sp headline does not fit that slot.

- [ ] **Step 3: HomeScreen without tabs**

Rewrite `HomeScreen` (fold `MatchesTab`'s `Column` into it, then `git rm home/MatchesTab.kt home/HomeNavigationBar.kt`):

```kotlin
/**
 * The root screen: the match list under its title bar, the Add button, and
 * the one snackbar host. Every other route pushes over the whole of it.
 *
 * Mirrors iosApp's MatchesScreen.swift.
 */
@Composable
fun HomeScreen(
    /** Any sheet or dialog over the list: the add sheet here, or one the list owns. */
    onOverlayChange: (Boolean) -> Unit,
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    onOpenMatch: (MatchListItem, MatchPageSection?) -> Unit,
    onSettings: () -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onLocalResultSeen: (entryId: String) -> Unit,
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit,
    autoDetailsEntryId: String?,
    onAutoDetailsShown: () -> Unit,
    intakeError: String?,
    onIntakeErrorShown: () -> Unit,
    /** Runs just announced by AuthGate's watcher, to say on the snackbar; empty when none. */
    announced: Set<String>,
    onAnnounced: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var addSheetOpen by remember { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted: the Add button reads firstVisibleItemIndex to decide whether
    // to show its label, and it is not a descendant of the list.
    val listState = rememberLazyListState()

    LaunchedEffect(addSheetOpen, listOverlayOpen) { onOverlayChange(addSheetOpen || listOverlayOpen) }

    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    // A run that landed while the list was in front. The app no longer moves
    // on its own (a tab switch changed a list; opening a page would change the
    // screen): it says so, and View opens that match on what the run made.
    LaunchedEffect(announced) {
        if (announced.isEmpty()) return@LaunchedEffect
        val message = finishedRunMessage(announced, state.matchList)
        onAnnounced()
        message ?: return@LaunchedEffect
        val item = message.item
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = item?.let { MatchPageCopy.VIEW },
            duration = if (item != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && item != null) onOpenMatch(item, sectionAfterRun(item))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset only: HomeTopBar draws under the status bar itself,
        // and with no bottom bar the Scaffold must lift the Add button and the
        // snackbar clear of the gesture bar. The side insets are taken back
        // below, as before.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        floatingActionButton = {
            val expanded by remember { derivedStateOf { addButtonExpanded(listState.firstVisibleItemIndex) } }
            AddMatchButton(
                expanded = expanded,
                onClick = { addSheetOpen = true },
                modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE)
        Column(Modifier.fillMaxSize().windowInsetsPadding(HorizontalSafeDrawing)) {
            HomeTopBar(HomeCopy.TAB_MATCHES) {
                BackgroundWorkAction()
                IconButton(onClick = onSettings) {
                    Icon(ShuttlIcons.Settings, contentDescription = MatchPageCopy.SETTINGS, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ClipListScreen(
                    vm = vm, shares = shares, media = media,
                    onOpenMatch = { onOpenMatch(it, null) },
                    onAnalyze = onAnalyze,
                    onMarkCourt = onMarkCourt,
                    onRemoveFromPhone = onRemoveFromPhone,
                    onLocalResultSeen = onLocalResultSeen,
                    onLocalDetailsSaved = onLocalDetailsSaved,
                    autoDetailsEntryId = autoDetailsEntryId,
                    onAutoDetailsShown = onAutoDetailsShown,
                    listState = listState,
                    snackbarHostState = snackbarHostState,
                    onOverlayChange = { listOverlayOpen = it },
                    heroPaused = addSheetOpen,
                    contentPadding = bottom,
                )
            }
        }
    }

    if (addSheetOpen) {
        AddMatchSheet(
            onNewMatch = { addSheetOpen = false; onNewMatch() },
            onRecord = { addSheetOpen = false; onRecord() },
            onImport = { addSheetOpen = false; onImport() },
            onDismiss = { addSheetOpen = false },
        )
    }
}
```

Keep `HorizontalSafeDrawing` and `ADD_BUTTON_CLEARANCE` (rewrite their comments: no bar any more; the clearance is the button's footprint above the gesture inset). `ShuttlIcons.Settings` is the gear the video card already uses (its KDoc says so; update the `SlidersHorizontal` comment, which calls it "the Settings tab", since that icon is now unused: delete `SlidersHorizontal` and `Rows` if `grep` shows no other use). `HomeCopy.TAB_MATCHES` stays as the title until Task 10 renames it.

- [ ] **Step 4: AuthGate without tabs**

- Delete `homeTab` and every write to it (the `TO_HOME` branch, `onAdded`, `SignIn`'s `onSignedIn`, `LocalBackgroundWorkClick`), and the comments that explain them. The `onAdded` comment about the naming sheet being drawn "by the Matches tab's list" becomes: drawn by the list, which is the root, so there is nothing to select.
- Add `var announced by remember { mutableStateOf(emptySet<String>()) }` beside `homeOverlayOpen`.
- In the watcher: `onHome = nothingAbove` (the `isHomeFront` import goes), and `if (announcement.announce.isNotEmpty()) announced = announcement.announce` in place of the tab switch. Rewrite the watcher's comments: it announces rather than switching; every "Matches tab" / "Analytics tab" becomes "the list".
- `composable<Route.Home>`: drop the `localVm` factory only if nothing else uses it (it still serves `onRemoveFromPhone`, `onLocalResultSeen`, `onLocalDetailsSaved`: keep it), delete the `analyticsContent` slot, `selectedTab`, `onSelectTab`, `themePrefs`, `email`, `onLabels`, and pass `onOpenMatch = openMatch`, `onSettings = { nav.navigate(Route.Settings) }`, `announced = announced`, `onAnnounced = { announced = emptySet() }`.
- Delete `composable<Route.AnalyticsDetail>` and `Route.AnalyticsDetail` itself.

- [ ] **Step 5: Delete the Analytics tab**

```bash
git rm androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsScreen.kt \
       androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsRows.kt \
       androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt \
       androidApp/src/test/java/com/badmintontracker/android/analytics/AnalyticsRowsTest.kt \
       androidApp/src/test/java/com/badmintontracker/android/analytics/DetailSubtitleTest.kt
git mv androidApp/src/test/java/com/badmintontracker/android/analytics/CloudAnalysisPanelsTest.kt \
       androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt
```

Change the moved test's package to `com.badmintontracker.android.match` and its KDoc's "What AnalyticsDetailScreen composes" to "What AnalysisSection composes". Then `grep -rn "AnalyticsTab\|AnalyticsScreen\|AnalyticsDetail\|HomeTab\|isHomeFront\|TabTopBar\|MatchesTab\|SettingsTab\|Analytics tab" androidApp/src` and rewrite or remove every hit (comments in `BackgroundWorkAction.kt`, `ClipListScreen.kt`, `ClipListViewModel.kt`'s `matchList` KDoc, `MatchStatusLine.kt` and `MatchListRow.kt` name the Analytics tab).

- [ ] **Step 6: Build and test**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `AuthNavigationTest` unchanged and green; `AddMatchButtonTest` green.

- [ ] **Step 7: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): drop the bottom bar and the Analytics tab; Settings behind a gear, finished runs as a snackbar"
```

---

### Task 5: Android on the emulator

- [ ] **Step 1: Install and check the session**

Start `Pixel_9a` with the DNS flags, `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:assembleDebug`, `adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Confirm the app opens signed in on the list. If it opens on sign-in, stop and ask Coen to sign in by hand; do not fetch credentials.

- [ ] **Step 2: Capture, light and dark, each checked by eye before the next tap**

Capture each to a scratch path, view it, then copy the good ones into `docs/screenshots/2026-09-26-android-<name>-<light|dark>.png`:
1. `list`: title, gear and background-work indicator aligned on one baseline; the Add button clear of the gesture bar; no bottom bar.
2. `settings`: back arrow, title, the two cards, the version line.
3. `match-video-only`: a video only on this phone, not analysed: no toggle row, the player, Analyze under the transport bar.
4. `match-analysed`: a phone-analysed match: Match | Rallies | Analysis, then Analysis selected with Heatmap | Base | Skeleton. (A track can be planted with the `run-as` recipe in the emulator memory note if none exists; delete it afterwards.)
5. `match-scored`: a scored, filmed match with all four pills, at the largest system font scale (`adb shell settings put system font_scale 1.3`, then reset to 1.0). No pill label clipped.
6. `match-cloud`: a cloud-only match (Rallies, maybe Analysis), with the sort row above the rallies.
7. `match-device-rallies`: Rallies for a device-only match, and the clip dialog playing.
8. `match-landscape`: Match in landscape (fullscreen, no bar, no toggles), then back to portrait at the same position.
9. `run-snackbar`: start a device run on a short video, return to the list, wait for "... is analyzed" with View; tap View and confirm the page opens on Analysis.

Compare every frame with its iOS counterpart from `docs/screenshots/2026-09-25-*` where one exists. Fix anything misaligned, clipped, or in a Material default colour before moving on, and commit fixes with the screenshots.

- [ ] **Step 3: Commit the screenshots**

```bash
git add docs/screenshots/2026-09-26-android-*
git commit -m "docs: emulator screenshots of the match page sections and the barless list"
```

---

### Task 6: iOS section bodies, moved out of their screens

Behaviour-preserving, like Task 2.

**Files:**
- Create: `iosApp/Sources/Match/MatchVideoSection.swift`, `Match/AnalysisSection.swift`, `Match/DeviceRalliesSection.swift`
- Modify: `LocalVideo/LocalPlayerView.swift`, `Analytics/AnalyticsDetailView.swift`, `LocalAnalysis/LocalClipsView.swift`

**Interfaces:**
- Consumes: `MatchPageCopy.shared.ADD_NOTE`, `MatchPageCopy.shared.DEVICE_CLIPS_EMPTY`.
- Produces: `struct MatchVideoSection: View { let rally: RallyApp; let entryId: String; let analyzeLabel: String?; let onAnalyze: () -> Void }`; `struct AnalysisSection: View { let rally: RallyApp; let localAnalysis: LocalAnalysisRunner?; let analysisId: String }`; `struct DeviceRalliesSection: View { let localAnalysis: LocalAnalysisRunner?; let entryId: String }`.

- [ ] **Step 1: `DeviceRalliesSection.swift`**

Move from `LocalClipsView.swift` into it: `localClipLabel`, the `PlayerTrackStore.Clip: Identifiable` extension, `LocalClipPlayerSheet` and `ClipLooper` (unchanged). Then:

```swift
import AVFoundation
import AVKit
import Shared
import SwiftUI

/// The rallies an on-device run cut, as the Rallies section shows them until
/// the 2026-09-11 design turns them into real rally rows. Only for a match
/// with no cloud clips: when both exist the cloud clips are the rallies.
/// Port of androidApp's `DeviceRalliesSection`.
struct DeviceRalliesSection: View {
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    @State private var clips: [PlayerTrackStore.Clip] = []
    @State private var playing: PlayerTrackStore.Clip? = nil

    var body: some View {
        ScrollView {
            // ... the `VStack` from LocalClipsView's body, unchanged, with its
            // empty message reading `MatchPageCopy.shared.DEVICE_CLIPS_EMPTY` ...
        }
        .background(Shuttl.bg)
        // ... LocalClipsView's `.task` (off the main actor) and `.sheet(item: $playing)`, unchanged ...
    }
}
```

`LocalClipsView` keeps only `LocalClipsRoute` and becomes `DeviceRalliesSection(localAnalysis:entryId:)` plus its `.navigationTitle("Clips on this phone")`, `.navigationBarTitleDisplayMode(.inline)` and `.shuttlNavigationBarBackground()`. `LocalClipsTests` still passes (it calls `localClipLabel`, now in the new file).

- [ ] **Step 2: `AnalysisSection.swift`**

Move from `AnalyticsDetailView` into `struct AnalysisSection`: the `@State`s, `panels`, `panel`, `runSettled`, `Stored`, `reload()`, `heatmap`, and the body's `ScrollView` with its three modifiers `.background`, the two `.task`s and `.onChange(of: runSettled)`. Rename `entryId` to `analysisId`. `hasVideo: rally.localVideos.get(id: analysisId)?.uri != nil` as today. Keep the free functions (`heatmapSource`, `rallyWindows`, `HeatmapSource`, `SideToggle`, `detailSubtitle`) where they are for now; Task 8 moves the ones that survive. `AnalyticsDetailView`'s body becomes `AnalysisSection(rally: rally, localAnalysis: localAnalysis, analysisId: entryId)` with its existing toolbar and navigation modifiers. Rewrite the moved "HEATMAP" comment as in Task 2 Step 3.

- [ ] **Step 3: `MatchVideoSection.swift`**

Move `LocalPlayerView`'s model creation, `liveEntry`, `addSheet`, `deleteTarget`, `content(_:)`, `annotationRow`, `AddSheetItem` and the sheet/alert into:

```swift
/// The Match section: the full recording on this phone, with its notes and
/// label chips, as LocalPlayerView showed it. `ShuttlPlayer` owns fullscreen
/// itself (a full-screen cover), so nothing is hoisted here, unlike Android.
///
/// `analyzeLabel` is "Analyze" or "Retry" under the player while the match
/// asks for one (the row's own action), else nil.
struct MatchVideoSection: View {
    let rally: RallyApp
    let entryId: String
    let analyzeLabel: String?
    let onAnalyze: () -> Void
    // ... the moved @State properties ...

    var body: some View {
        Group {
            if let model { content(model) } else { SplashView() }
        }
        // ... LocalPlayerView's two `.task`s, except that a vanished entry is
        // not dismissed here: the page that hosts this section falls back to
        // another section when the file goes (shownSection). Just return. ...
    }
}
```

In `content(_:)`: drop `.navigationTitle` and the whole `.toolbar`; after the `ShuttlPlayer` add

```swift
            if let analyzeLabel {
                Button(analyzeLabel, action: onAnalyze)
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.bottom, 8)
            }
```

and replace the trailing storage-note `Text` in the `List` with a header row above the `List` (Android's `NotesHeader`: the storage note doubles as the notes' heading):

```swift
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text("Notes are saved on this phone and are removed if you remove the video from the app.")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                Spacer(minLength: 0)
                Button {
                    addSheet = AddSheetItem(timestamp: model.currentTimestampSeconds())
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(MatchPageCopy.shared.ADD_NOTE)
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 8)
```

`LocalPlayerView` becomes: `MatchVideoSection(rally:, entryId:, analyzeLabel: nil, onAnalyze: {})`, its `.navigationTitle` from the live entry, its toolbar with `BackgroundWorkAction` and the Analyze button only (the "+" is now inside the section), its `courtTarget` destination, and the dismiss-on-vanish `.task` it had.

- [ ] **Step 4: Build and test**

Run the iOS test command. Expected: `** TEST SUCCEEDED **`, both suites' counts as before (plus none lost), `LocalClipsTests`, `CloudAnalysisPanelsTests`, `LocalPlayerModelTests` green, `ReplayPlayerUITests` passed (not skipped): it drives the player through `LocalPlayerView`, which now draws `MatchVideoSection`. Confirm the three new `.o` files exist.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "refactor(ios): the player, analysis and device-clip bodies become match page sections"
```

---

### Task 7: iOS match page with sections, opened from every row

**Files:**
- Modify: `Match/MatchView.swift`, `Home/HomeView.swift`, `Home/MatchesTabView.swift`, `ClipList/ClipListView.swift`
- Delete: `LocalVideo/LocalPlayerView.swift`, `LocalVideo/LocalPlayerRoute.swift`, `LocalAnalysis/LocalClipsView.swift`

**Interfaces:**
- Consumes: Task 1 (via SKIE: `MatchPageSectionKt.matchPageSections(item:)`, `MatchPageSectionKt.shownSection(chosen:sections:)`, `MatchPageSectionKt.analysisId(item:)`, `matchList.matchPageItem(scoreLogId:videoId:entryId:)`), Task 6's sections.
- Produces: `struct MatchRoute: Hashable { let scoreLogId: String?; let videoId: String?; var entryId: String? = nil; var attach: AttachIntent? = nil; var section: MatchPageSection? = nil }`; `MatchView(rally:analyze:localAnalysis:listModel:route:)`; `MatchesList(... onMatchTap: (MatchRoute) -> Void ...)` without `onLocalPlayer`, `onLocalClips`, `onAnalyticsDetail`.

- [ ] **Step 1: Route and list**

`MatchRoute` gains `var entryId: String? = nil` (after `videoId`) and `var section: MatchPageSection? = nil` (after `attach`), each with a doc line matching Android's. Existing `MatchRoute(scoreLogId:videoId:attach:)` call sites keep compiling (memberwise defaults).

In `MatchesList`: delete `onLocalPlayer`, `onLocalClips`, `onAnalyticsDetail` and their docs; `open(_:)` becomes

```swift
    /// Every row opens its match page, whatever the match has: see
    /// `matchPageSections`. Mirrors androidApp's `onOpenMatch`.
    private func open(_ item: MatchListItem) {
        onMatchTap(MatchRoute(scoreLogId: item.scoreLogId, videoId: item.videoId, entryId: item.entryId))
    }
```

and `menu(for:)` loses the device-clips and heatmap items. `MatchesTabView` drops the same three closures. In `HomeView`, delete `localPlayerRoute` and `localClipsRoute` and their destinations and `goToMatches`/`nothingAbove` entries; keep `analyticsDetailRoute` for the Analytics tab until Task 8. Pass `listModel` to both `MatchView(...)` constructions.

- [ ] **Step 2: MatchView sections**

Add `let listModel: ClipListModel` (doc: the app's one list model; the page finds its row in it). Replace `Facet`, `chosenFacet`, `hasPoints`, `hasRallies` and `facet` with:

```swift
    /// The row this page is for, from the list the Matches screen draws, and
    /// its sections by the one shared rule: the chips on that row are the
    /// toggles here.
    private var item: MatchListItem? {
        listModel.matchList.matchPageItem(
            scoreLogId: route.scoreLogId,
            videoId: route.videoId ?? matchModel.log?.videoId,
            entryId: route.entryId
        )
    }
    private var sections: [MatchPageSection] {
        item.map { MatchPageSectionKt.matchPageSections(item: $0) } ?? []
    }
    /// Held for the life of this page and never cleared: a section can go
    /// while on screen, and `shownSection` falls back without forgetting it.
    @State private var chosenSection: MatchPageSection?
    private var section: MatchPageSection? {
        MatchPageSectionKt.shownSection(chosen: chosenSection, sections: sections)
    }
    /// Under the player only for a video with no score log: a scored match's
    /// video asks through `AttachStatusBanner` already.
    private var analyzeLabel: String? {
        guard let item, item.scoreLogId == nil, item.action == .analyze || item.action == .retry else { return nil }
        return MatchListCopy.shared.actionLabel(action: item.action)
    }
```

In `init`, `_chosenSection = State(initialValue: route.section)` replaces the facet line. `effectiveVideoId` becomes `item?.videoId ?? route.videoId ?? matchModel.log?.videoId`. `title` takes `item?.title` first (keep the rest as the fallback before the list has it). `isMissing` becomes `item == nil && ((route.scoreLogId != nil && matchModel.log == nil && effectiveVideoId == nil) || (route.scoreLogId == nil && route.videoId == nil && route.entryId.map { id in rally.localVideos.get(id: id) == nil } == true))`, and its message reads `MatchPageCopy.shared.MISSING`.

In `body`, replace the `if hasPoints && hasRallies { ShuttlPillTabs ... }` and `listContent` with:

```swift
                        if sections.count > 1, let section {
                            ShuttlPillTabs(
                                labels: sections.map(\.label),
                                selectedIndex: sections.firstIndex(of: section) ?? 0,
                                onSelect: { chosenSection = sections[$0] },
                                accessibilityLabel: MatchPageCopy.shared.SECTIONS
                            )
                            .padding(.horizontal, ShuttlGutter.page)
                            .padding(.vertical, 8)
                        }
                        sectionContent
```

and add:

```swift
    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .points:
            listContent
        case .match:
            if let entryId = item?.entryId {
                MatchVideoSection(rally: rally, entryId: entryId, analyzeLabel: analyzeLabel) {
                    analyzeEntry(entryId)
                }
            }
        case .rallies:
            if clipsForMatch.isEmpty, let item, item.deviceClipCount > 0, let entryId = item.entryId {
                DeviceRalliesSection(localAnalysis: localAnalysis, entryId: entryId)
            } else {
                listContent
            }
        case .analysis:
            if let item, let id = MatchPageSectionKt.analysisId(item: item) {
                AnalysisSection(rally: rally, localAnalysis: localAnalysis, analysisId: id)
            }
        default:
            EmptyView()
        }
    }

    /// The same decision the list's Analyze makes (`analyseAction(for:)`):
    /// resume a failed cloud run with saved court points, else mark the court.
    private func analyzeEntry(_ entryId: String) {
        guard let entry = rally.localVideos.get(id: entryId) else { return }
        switch analyseAction(for: entry) {
        case .resume(let id): analyze.retry(entryId: id)
        case .markCourt(let id): navigationTarget = CourtMarkingRoute(entryId: id)
        }
    }
```

`listBody` switches on `section == .points` instead of `facet == .points`. `analyseAction(for:)` and `AnalyseAction` are internal free declarations in `Analytics/AnalyticsRows.swift` today; Task 8 moves them before deleting that file.

- [ ] **Step 3: The toolbar and the sort**

In `toolbarContent`: delete the sort `ToolbarItem`; change the overflow's condition to `matchModel.log != nil || isOwned || item?.canAnalyzeAgain == true`, put "Edit details" under `matchModel.log != nil || isOwned`, and add after "Export as text":

```swift
                    if let item, item.canAnalyzeAgain, let entryId = item.entryId {
                        Button(MatchListCopy.shared.MENU_ANALYZE_AGAIN) { analyzeEntry(entryId) }
                    }
```

In `listBody`, before `RalliesFacet` (only in the rallies branch, only when `clipsForMatch.count > 1`):

```swift
                HStack {
                    Spacer()
                    Menu {
                        Picker(MatchPageCopy.shared.SORT, selection: $sort) {
                            Text(MatchPageCopy.shared.SORT_RALLY_ORDER).tag(ClipSort.rallyOrder)
                            Text(MatchPageCopy.shared.SORT_MOST_NOTES).tag(ClipSort.mostNotes)
                        }
                    } label: {
                        Label(
                            sort == .rallyOrder ? MatchPageCopy.shared.SORT_RALLY_ORDER : MatchPageCopy.shared.SORT_MOST_NOTES,
                            systemImage: "arrow.up.arrow.down"
                        )
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                    }
                    .accessibilityLabel(MatchPageCopy.shared.SORT)
                }
                .listRowSeparator(.hidden)
```

- [ ] **Step 4: Keep the list model live under the page (Review focus 3)**

First find out whether pushing a page cancels the root list's `.task { await model.start() }` (`ClipListView.swift:94`): add a temporary `print("start begin")` at the top of `ClipListModel.start()` and `defer { print("start end") }`, run the app on the simulator from Xcode or with `xcrun simctl launch --console`, open a match, and read the console. Remove both prints.
- If "start end" prints on push (cancelled), add to `MatchView`: `.task { await listModel.start() }` with a comment saying the root's loops stop while a page is pushed, so the page runs its own set, and only one set runs at a time because each is cancelled when its view is covered.
- If it does not print, the loops stay live under the page; add nothing, and write that finding as a comment at `MatchView`'s `listModel` property instead.

- [ ] **Step 5: Delete the old screens**

Move `extension LocalVideoEntry: Identifiable {}` from `LocalPlayerRoute.swift` to the top of `LocalVideo/LocalVideoLogic.swift`, then:

```bash
git rm iosApp/Sources/LocalVideo/LocalPlayerView.swift iosApp/Sources/LocalVideo/LocalPlayerRoute.swift iosApp/Sources/LocalAnalysis/LocalClipsView.swift
```

(`LocalClipsView.swift` holds only the route and the wrapper after Task 6.) `grep -rn "LocalPlayerView\|LocalPlayerRoute\|LocalClipsRoute\|LocalClipsView\|onLocalPlayer\|onLocalClips" iosApp` must print nothing but comments you then rewrite.

- [ ] **Step 6: Build and test**

Run the iOS test command. Expected: `** TEST SUCCEEDED **`; `ReplayPlayerUITests` and `VideoMatchDetailsUITests` passed, not skipped (the first now reaches the player through the match page's Match section).

- [ ] **Step 7: Commit**

```bash
git add -A iosApp
git commit -m "feat(ios): the match page shows Points, Match, Rallies and Analysis, and every row opens it"
```

---

### Task 8: iOS without the tab bar: the list as root, Settings as a page, the run as a toast

**Files:**
- Create: `iosApp/Sources/Components/ShuttlToast.swift`
- Rename: `Home/MatchesTabView.swift` to `Home/MatchesScreen.swift`, `Home/TabHeader.swift` to `Home/HomeHeader.swift`
- Modify: `Home/HomeView.swift`, `Home/SettingsView.swift`, `ClipList/MatchListFeed.swift` and `ClipList/ClipListView.swift` (comments), the four UI tests
- Delete: `Analytics/AnalyticsListView.swift`, `Analytics/AnalyticsRows.swift`, `Tests/AnalyticsRowsTests.swift`; `AnalyticsDetailView` (the struct)

**Interfaces:**
- Consumes: `finishedRunMessage`, `sectionAfterRun`, `RunAnnouncement.announce`, `MatchPageCopy` (Task 1).
- Produces: `struct ShuttlToast` and `.shuttlToast(_ toast: Binding<ToastMessage?>)`; `struct ToastMessage: Equatable { let text: String; let actionLabel: String?; let action: (() -> Void)? }`.

- [ ] **Step 1: The toast**

```swift
import SwiftUI

/// A short message over the bottom of a screen, gone after four seconds: what
/// a finished run says on the match list. iOS has no system snackbar, and the
/// inline `ErrorBanner` is for failures that stay until dealt with.
///
/// Posts a VoiceOver announcement when it appears, since a message that
/// leaves on its own is otherwise missed by anyone not looking at it.
struct ToastMessage: Equatable {
    let id = UUID()
    let text: String
    let actionLabel: String?
    let action: (() -> Void)?

    static func == (a: ToastMessage, b: ToastMessage) -> Bool { a.id == b.id }
}

private struct ShuttlToast: View {
    let message: ToastMessage
    let onDone: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(message.text)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.bg)
                .lineLimit(2)
            Spacer(minLength: 0)
            if let label = message.actionLabel, let action = message.action {
                Button(label) { action(); onDone() }
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.accent)
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Shuttl.textHeading, in: RoundedRectangle(cornerRadius: ShuttlRadius.medium))
        .padding(.horizontal, ShuttlGutter.page)
        .task(id: message.id) {
            AccessibilityNotification.Announcement(message.text).post()
            try? await Task.sleep(for: .seconds(4))
            onDone()
        }
    }
}

extension View {
    /// Shows `toast` at the bottom, `bottomInset` above the edge, until it times out or its action runs.
    func shuttlToast(_ toast: Binding<ToastMessage?>, bottomInset: CGFloat) -> some View {
        overlay(alignment: .bottom) {
            if let message = toast.wrappedValue {
                ShuttlToast(message: message) { toast.wrappedValue = nil }
                    .padding(.bottom, bottomInset)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.default, value: toast.wrappedValue)
    }
}
```

Check the three tokens against `Theme/ShuttlPalette.swift` (`Shuttl.textHeading` as the inverse surface, `Shuttl.bg` on it, `Shuttl.accent` for the action) in both themes: the action must clear 3:1 on the toast. If `ShuttlRadius.medium` or `ShuttlType.bodyMedium` is named differently, use the existing names.

- [ ] **Step 2: The root screen**

`git mv iosApp/Sources/Home/MatchesTabView.swift iosApp/Sources/Home/MatchesScreen.swift` and `git mv iosApp/Sources/Home/TabHeader.swift iosApp/Sources/Home/HomeHeader.swift`; rename the types (`MatchesScreen`, `HomeHeader`) and rewrite both doc comments (no tabs: the root screen's title, with the indicator and the gear trailing). `MatchesScreen` gains `let onSettings: () -> Void` and `@Binding var toast: ToastMessage?`; its header becomes

```swift
            HomeHeader(HomeCopy.shared.TAB_MATCHES) {
                BackgroundWorkAction()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                .accessibilityLabel(MatchPageCopy.shared.SETTINGS)
            }
```

and after its `.overlay` for the Add button: `.shuttlToast($toast, bottomInset: 88)` (the 56pt button, its 16pt margin, 16pt of air).

- [ ] **Step 3: Settings as a page**

`SettingsView`: remove its `HomeHeader`/`TabHeader`, the `onLabels` parameter, and add `@State private var showLabels = false`, `.navigationDestination(isPresented: $showLabels) { LabelsView(rally: rally) }`, `.navigationTitle(MatchPageCopy.shared.SETTINGS)`, `.navigationBarTitleDisplayMode(.inline)`, `.shuttlNavigationBarBackground()`, and a toolbar `ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }`. The Labels row's button sets `showLabels = true`. Registering Labels here rather than on `HomeView` keeps a push-from-a-push on the view that is actually on screen.

- [ ] **Step 4: HomeView without the TabView**

- Replace `TabView(selection:) { ... }.tint(...)` with `MatchesScreen(rally:analyze:intake:model:heroPaused:onMatchTap:onCourtMarking:onOverlayChange:onAdd:onSettings:toast:)`, keeping `.toolbar(.hidden, for: .navigationBar)` on it. `heroPaused: !nothingAbove`.
- Delete `selectedTab`, `tabLabel`, `showLabels` and its destination, `analyticsDetailRoute` and its destination; add `@State private var showSettings = false` with `.navigationDestination(isPresented: $showSettings) { SettingsView(rally: rally) }`, and `@State private var toast: ToastMessage? = nil`.
- `nothingAbove`: drop `showLabels`, `localPlayerRoute`, `localClipsRoute`, `analyticsDetailRoute`; add `showSettings`. `goToMatches`: same, and no tab.
- `.sheet(item: $detailsTarget, onDismiss:)` no longer selects a tab: delete that `onDismiss` and its comment.
- `announce(cloudFinished:)`:

```swift
        let announcement = RunAnnouncementKt.announceFinishedRuns(
            finished: Set(onDevice).union(cloudFinished),
            seen: announcedRuns,
            onHome: nothingAbove
        )
        announcedRuns = announcement.seen
        guard !announcement.announce.isEmpty,
              let message = FinishedRunMessageKt.finishedRunMessage(ids: announcement.announce, list: listModel.matchList)
        else { return }
        let item = message.item
        toast = ToastMessage(
            text: message.text,
            actionLabel: item == nil ? nil : MatchPageCopy.shared.VIEW,
            action: item.map { item in {
                matchRoute = MatchRoute(
                    scoreLogId: item.scoreLogId, videoId: item.videoId, entryId: item.entryId,
                    section: MatchPageSectionKt.sectionAfterRun(item: item)
                )
            } }
        )
```

Rewrite every comment in `HomeView.swift` that describes the tab host, the `TabView` cancelling `.task`s, or switching to Analytics (the type's doc comment, `listModel`'s, `announcedRuns`'s, the `.onChange(of: localAnalysis?.settledRuns)` block's, `announce`'s, `goToMatches`'s). `listModel`'s doc: `start()` runs from the list's `.task` (and the match page's, per Task 7 Step 4), one set at a time.

- [ ] **Step 5: Delete the Analytics tab**

Two things in the files about to go are still used and move first:
- `AnalyseAction` and `analyseAction(for:)` (with their doc comments) from `Analytics/AnalyticsRows.swift` to the end of `LocalVideo/LocalVideoLogic.swift`: the list's Analyze and the match page's both call them. Move their three tests (`testAFailedRunWithItsCourtAlreadySavedResumesFromTheFailedStep`, `testAFailedRunWithNoCourtMustBeMarkedAgain`, `testAFreshVideoStartsAtCourtMarking`) and the `entry(...)`/`court()` helpers they need from `AnalyticsRowsTests.swift` into `Tests/LocalVideoLogicTests.swift`.
- `heatmapSource`, `HeatmapSource`, `rallyWindows`, `SideToggle` from `AnalyticsDetailView.swift` into `Match/AnalysisSection.swift` (below the struct). Delete `detailSubtitle` if nothing else uses it (`grep -rn detailSubtitle iosApp`).

Then:

```bash
git rm iosApp/Sources/Analytics/AnalyticsListView.swift iosApp/Sources/Analytics/AnalyticsRows.swift \
       iosApp/Sources/Analytics/AnalyticsDetailView.swift iosApp/Tests/AnalyticsRowsTests.swift
```

Update `CloudAnalysisPanelsTests.swift`'s doc ("What `AnalyticsDetailView` composes" becomes "What `AnalysisSection` composes"). `grep -rn "Analytics tab\|AnalyticsListView\|AnalyticsDetailView\|AnalyticsDetailRoute\|HomeTab\|isHomeFront\|TabView\|MatchesTabView\|TabHeader" iosApp` and rewrite every hit (`MatchListFeed.swift:11`, `ClipListView.swift:20` and the `model` doc there among them). `MetricsStrip.swift`'s own paging `TabView` is unrelated; leave it.

- [ ] **Step 6: UI tests**

In all four UI tests, replace the sign-in gate `app.tabBars.buttons["Settings"].firstMatch` with `app.buttons["Settings"].firstMatch` (the list's gear), and rewrite the comments that say "the tab bar only exists behind sign-in" to say the gear does. In `LabelSwatchGridUITests.openLabelsScreen`, tapping the gear opens Settings, then `app.buttons["Labels"]` as before. Run the iOS test command and read `/tmp/ios-test.log`: all four `Test Case '-[iosAppUITests....]' passed`, none skipped.

- [ ] **Step 7: Commit**

```bash
git add -A iosApp
git commit -m "feat(ios): drop the tab bar and the Analytics tab; Settings behind a gear, finished runs as a toast"
```

---

### Task 9: iOS on the simulator

- [ ] **Step 1: Capture, light and dark**

The simulator (iPhone 17 Pro) is signed in; if not, stop and ask Coen. Drive screens with a throwaway XCUITest plus a `xcrun simctl io <udid> screenshot` polling loop, as the emulator memory note describes (never `cliclick`). `simctl` refuses to overwrite: write each capture to a fresh path. Capture the same nine frames as Task 5 into `docs/screenshots/2026-09-26-ios-<name>-<light|dark>.png`, the four-pill frame at the largest Dynamic Type size (`xcrun simctl ui <udid> content_size extra-extra-extra-large`, then reset), and the toast after a run. The app's theme is its own setting (the Settings switch), not the simulator's appearance.

- [ ] **Step 2: Compare across platforms**

Put each iOS frame beside its Android frame from Task 5. Spacing, gutters, pill heights, the toggle rows' gap, the Analyze button's width and the header's alignment should match to the point. Fix differences, rerun the affected captures, then delete the throwaway UI test and regenerate the project.

- [ ] **Step 3: Commit**

```bash
git add docs/screenshots/2026-09-26-ios-* iosApp
git commit -m "docs: simulator screenshots of the match page sections and the barless list"
```

---

### Task 10: Shared cleanup, docs, final review and the device handover

**Files:**
- Delete: `shared/.../home/HomeTab.kt`, `shared/src/commonTest/.../home/HomeTabTest.kt`, `shared/.../analytics/AnalyticsRowState.kt`, `shared/src/commonTest/.../analytics/AnalyticsRowStateTest.kt`
- Modify: `shared/.../home/HomeCopy.kt` (+ `HomeCopyTest.kt`), `shared/.../matchlist/MatchListItem.kt`, `MatchListBuilder.kt`, `MatchListCopy.kt`, `localvideo/RunAnnouncement.kt` (+ tests), both `AnalysisFiles` (comments), `docs/plans/2026-09-26-match-page-hub-design.md`, `docs/plans/2026-09-25-bottom-navigation-design.md`

- [ ] **Step 1: Remove what nothing uses any more**

For each, `grep -rn` across `shared androidApp iosApp` first; delete only what has no caller outside comments and its own test:
- `HomeTab`, `isHomeFront` and `HomeTabTest`.
- `AnalyticsRowState`, `analyticsRowState`, `opensAnalytics` and `AnalyticsRowStateTest`; rewrite the two `AnalysisFiles` comments that cite `READY_NO_VIDEO` to describe the case in words (a track kept after its file is gone).
- `HomeCopy.TAB_ANALYTICS`, `TAB_SETTINGS`, `ANALYTICS_EMPTY_TITLE`, `ANALYTICS_EMPTY_BODY`; rename `TAB_MATCHES` to `MATCHES_TITLE` (both platforms' call sites), and update `HomeCopyTest`'s list.
- `MatchListItem.opensLocalPlayer` (and its two builder lines, any builder-test assertion on it, and the argument in `FinishedRunMessageTest`); rewrite `deviceClipCount`'s doc ("for the Rallies section of a match with no cloud clips") and the item's class doc ("one match on the list").
- `MatchListCopy.MENU_HEATMAP`, `menuDeviceClips`.
- `RunAnnouncement.openAnalytics`: switch the six existing `RunAnnouncementTest` assertions to `announce` (`openAnalytics shouldBe true` becomes `announce shouldBe setOf("v1")` or the matching set; `false` becomes `announce shouldBe emptySet()`), rename them from "opens analytics" to "announces", and delete the property.

Run `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` and the iOS test command. Expected: both green, UI tests passed not skipped. Commit: `refactor: remove the tab model, the Analytics row state and the menu items the match page replaced`.

- [ ] **Step 2: Docs**

- The design doc: set **Status** to implemented on `feat/match-page-hub` with the date, and add a "What shipped differently" section listing every place the code differs from the spec (at least: the iOS toast's exact look; whether `MatchView` runs its own `start()`, per Task 7 Step 4; the sort control's shape; anything Task 5 or 9 changed).
- `2026-09-25-bottom-navigation-design.md`: under its status line add "Superseded in part by `2026-09-26-match-page-hub-design.md`: the tab bar and the Analytics tab are gone."

Commit: `docs: mark the match page hub shipped, and the tab bar superseded`.

- [ ] **Step 3: Final review**

Dispatch a fresh reviewer (superpowers:requesting-code-review) over `git diff feat/simplify-new-match...HEAD` with the spec and this plan's Review Focus. Fix what it confirms; list what it raises that is out of scope.

- [ ] **Step 4: Hand the device checks to Coen**

The real-phone checks cannot be done here. Report them as open, explicitly: on Coen's Android phone and iPhone, a real finished run's snackbar/toast and its View; rotating on Match (position kept) and on Skeleton; the gesture bar and the Add button's clearance; switching sections releasing the player (no audio from a section left behind). Also report the PR: this branch is stacked on PR #7 and needs its own PR (ask before opening it), and it must not be released separately from #7.
