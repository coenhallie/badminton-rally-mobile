# Unified Match List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the drawer's "On this phone" and "My matches" sections with one list of matches, each row saying where its video is and what analysis it has, on Android and iOS, and put the Analytics picker on the same rows.

**Architecture:** A new pure Kotlin builder in `shared/` (`com.badmintontracker.shared.matchlist`) joins local entries, cloud clips, score logs and on-disk analysis results into one `MatchListItem` per match. Both platforms stop grouping matches themselves for the drawer and Analytics and render the builder's output. Platform code only supplies inputs (device-run state, which analysis files exist) and draws.

**Tech Stack:** Kotlin Multiplatform (commonMain, kotest assertions + kotlin.test), Jetpack Compose (Material 3, coil3), SwiftUI through SKIE, xcodegen.

**Spec:** `docs/plans/2026-09-25-unified-match-list-design.md`. Read it first. The mock the design refers to is published; the spec is authoritative where they differ, and this plan is authoritative where it differs from the spec (see "Changes from the spec").

## Changes from the spec

- **No outlined "in the cloud" glyph and no `has_pose` migration.** A cloud pose artifact is only ever downloaded as part of a cloud run started on this phone (`RallyAndroidApp.kt:89-98`, `CloudPoseCoordinator.install`). Nothing would download it for a match analysed elsewhere, so the glyph would promise something the app cannot do. Dropped from this change; listed as a follow-up.
- **"Clips on this phone (N)" and "Player heatmap" stay in the row menu.** The spec moved them to the match page, but a device run has no match page until `2026-09-11-on-device-rallies-as-matches` ships. Removing them would strand those results.
- **The description line leaves drawer rows.** Rows are title, detail, players (if named), status. The description stays on the match page.
- **"Where" is drawn as one or two bare glyphs** (phone, cloud), not a combined glyph. Measured width budget below.
- **US spelling**, as the rest of the app: "Not analyzed", "Analyzed".

## Global Constraints

- Implementers run **one at a time** on the single working tree `/Users/coenhallie/Desktop/projects/badminton-rally-mobile`, branch `feat/unified-match-list`. Never in parallel: Gradle's lock and the git index are shared.
- The repo is under iCloud. If Gradle stalls or fails on `* 2.*` files, clear generated output: `for d in shared/build analysis/build androidApp/build; do find "$d" -name "* [0-9].*" -delete; done`. A compile error in a file you did not touch ("Unresolved reference") may be an evicted source: find missing tracked files with `git ls-files | while read f; do [ -e "$f" ] || echo "$f"; done`.
- Android test commands: `./gradlew --offline :shared:jvmTest` and `./gradlew --offline :androidApp:testDebugUnitTest`. Judge a run by grepping Gradle's own output (`BUILD SUCCESSFUL` / `BUILD FAILED`, failing test names), never by a background task's exit code.
- iOS builds run from a copy outside iCloud (xcodebuild blocks on the `.xcodeproj` in place):
  ```bash
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  R=/Users/coenhallie/Desktop/projects/badminton-rally-mobile
  C=~/Library/Caches/rally-ios-src
  rsync -a --delete --exclude .git --exclude build --exclude .gradle --exclude .kotlin --exclude .claude --exclude .superpowers --exclude .idea --exclude tools --exclude docs --exclude supabase/.temp --exclude iosApp/iosApp.xcodeproj "$R/" "$C/"
  mkdir -p "$C/tools/models/onnx" && cp "$R/tools/models/manifest.json" "$C/tools/models/" && cp "$R"/tools/models/onnx/{tracknet,badminton,posen}.fp16.onnx "$C/tools/models/onnx/"
  cd "$C" && xcodegen generate --spec iosApp/project.yml --project iosApp && xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null
  xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath ~/Library/Caches/rally-ios-derived CODE_SIGNING_ALLOWED=NO 2>&1 | tee /tmp/ios-test.log | grep -E "Executed|error:|FAILED|TEST (SUCCEEDED|FAILED)"
  ```
  Report both suites' counts (`iosAppTests` and `iosAppUITests` report separately). When a Swift file is added or removed, also run `xcodegen generate --spec iosApp/project.yml --project iosApp` **in the real repo** (the `project.pbxproj` is tracked), and confirm the new file's `.o` exists under the derived data before believing a green build.
- Kotlin enums reach Swift through SKIE as native enums (`.allCases`, `switch`). Top-level functions are `FileNameKt.function(...)`. Suspend functions are `async throws`. Flows are `AsyncSequence`.
- Copy rules: no em dash in any string or comment you write. Sentence case. The strings live in `MatchListCopy` in shared, never re-typed per platform.
- Commits: conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), body explains why. **No `Co-Authored-By` trailer, no "Generated with" footer.** Do not hand-edit `CHANGELOG.md`.
- Comment density: this codebase explains *why* in comments generously, especially at decisions that look arbitrary. Match it. Do not add comments that restate code.
- Width budget (Android, measured from `DrawerList.kt`): drawer 330dp, side margins 24+24, row padding 16 start and 4 end (menu compensation), thumbnail 64 + gap 12, menu 48: **the text column is about 138dp**. The status line must fit 2 where-glyphs (14dp each, 2dp apart) + divider (1dp + 6dp each side) + 4 analysis chips (18dp, 4dp gaps) = 30 + 13 + 84 = 127dp. iOS: measure the drawer's text column in the simulator in Task 6 before styling; do not assume it matches.

## Review Focus

1. **Cold start with an empty score-log cache.** An entry whose `scoreLogId` is set but whose log is not (yet) in the list must not become a standalone row, or every attached video flashes into the list and out again on launch (`orphanedLocalVideoIds` documents this window). Test in Task 1.
2. **Every entry id and video id appears in at most one row across `mine` and `shared`.** One invariant test over a mixed input covers the whole class of the duplicate that motivated this change. Test in Task 1.
3. **An ANALYZED entry with no known clips** (clip refresh failed offline, or the cloud match was deleted elsewhere) shows "Analyzed", no Analyze button, and is excluded from "Not analyzed". Test in Task 1.
4. **"Delete match everywhere" on a row that is also on the phone** must discard the local entry and its annotations too, and is offered only when the entry is removable (`canRemoveLocalVideo` and no device run in flight). Today `deleteMatch(videoId)` deletes only the cloud side, and the surviving ANALYZED entry would bring the row back as phone-only. Builder rule tested in Task 1; the platform behaviour tested in Tasks 3 and 6.
5. **The filter resets to All when the chips hide** (fewer than six matches). Otherwise a chosen filter keeps filtering with no visible control. `effectiveFilter` tested in Task 1; used by both platforms.

---

## File structure

**Shared (new):** `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/`
- `MatchListItem.kt` - the output types and enums.
- `MatchListInput.kt` - the input type and `DeviceRun`.
- `MatchListBuilder.kt` - `buildMatchList` and its private helpers.
- `MatchListFilter.kt` - filters and the chip threshold.
- `MatchListCopy.kt` - every user-facing string the list and its sheets use.
- Tests: `shared/src/commonTest/kotlin/com/badmintontracker/shared/matchlist/MatchListBuilderTest.kt`, `MatchListFilterTest.kt`, `MatchListCopyTest.kt`.

**Shared (modified):** `shared/.../repo/MediaRepository.kt` (signed thumbnail URL cache).

**Android (new):** `androidApp/.../cliplist/MatchListRow.kt` (row + thumbnail), `androidApp/.../cliplist/MatchStatusLine.kt`, `androidApp/.../cliplist/MatchListSheets.kt` (legend + delete choice), `androidApp/.../cliplist/StoredResults.kt`.
**Android (modified):** `ShuttlIcons.kt`, `ClipListViewModel.kt`, `ClipListScreen.kt`, `HomeScreen.kt`, `AuthGate.kt`, `analytics/AnalyticsRows.kt`, `analytics/AnalyticsScreen.kt`, `localanalysis/LocalAnalysisRunner.kt` (a `toDeviceRun` mapper).
**Android (deleted when unused):** `cliplist/MatchRow.kt` and its test, `localvideo/LocalVideoSection.kt`'s `localVideoSection`/`LocalVideoRowItem` (keep `AnalyzeResultDialog`, move it if the file goes).

**iOS (new):** `iosApp/Sources/ClipList/MatchStatusLine.swift`, `iosApp/Sources/ClipList/MatchListRowView.swift`.
**iOS (modified):** `ClipListModel.swift`, `ClipListView.swift`, `MatchGrouping.swift` (remove what becomes unused), `Home/HomeView.swift` if wiring changes, `Analytics/AnalyticsRows.swift`, `Analytics/AnalyticsListView.swift`, tests.

---

### Task 1: The shared match list builder

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListItem.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListInput.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListBuilder.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListFilter.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListCopy.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/matchlist/MatchListBuilderTest.kt`, `MatchListFilterTest.kt`, `MatchListCopyTest.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt` (its private `matchTitle()` now delegates to the shared one, so the rule exists once in Kotlin)

**Interfaces:**
- Consumes: `LocalVideoEntry`, `AnalyzeStage`, `AnalyzeProgress`, `isAnalysisRunning`, `cloudAnalysisStatus`, `canRemoveLocalVideo`, `canEditLocalVideoDetails` (`shared/.../localvideo/LocalVideoEntry.kt`); `DevicePhase`, `deviceWorkLabel` (`localvideo/BackgroundWork.kt`); `ScoreLog`, `buildScoreMatchCard`, `scoreLogAttachStatus`, `AttachKind` (`scoring/`); `RallyClip`, `MatchMetadata`, `playersLine` (`model/`).
- Produces (later tasks rely on these exact names):
  - `enum class MatchWhere { PHONE, CLOUD, BOTH }`
  - `enum class MatchAnalysis { RALLIES, HEATMAP, SKELETON, SCORE }`
  - `enum class MatchAction { NONE, ANALYZE, MARK_COURT, RETRY, RUNNING, PAUSED }`
  - `enum class MatchDelete { NONE, VIDEO_MATCH, SCORE_MATCH, BOUND_MATCH, LEAVE_SHARE }`
  - `enum class DeviceRunKind { RUNNING, PAUSED, FAILED }`, `data class DeviceRun(kind, phase: DevicePhase = DevicePhase.ANALYSING, message: String? = null)`
  - `data class MatchListInput(...)`, `data class MatchListItem(...)`, `data class MatchList(mine, shared)` (fields below)
  - `fun buildMatchList(input: MatchListInput): MatchList`
  - `enum class MatchListFilter { ALL, ON_THIS_PHONE, NOT_ANALYZED }`, `fun MatchListItem.passes(filter: MatchListFilter): Boolean`, `fun showsFilters(mineCount: Int): Boolean`, `fun effectiveFilter(chosen: MatchListFilter, mineCount: Int): MatchListFilter`, `const val MATCH_LIST_FILTER_THRESHOLD = 6`
  - `object MatchListCopy { ... }` (strings below), `fun statusDescription(where: MatchWhere?, analyses: List<MatchAnalysis>, statusText: String?): String`
  - `fun List<RallyClip>.matchTitle(): String?` (public, in `MatchListBuilder.kt`)

- [ ] **Step 1: Write the types**

`MatchListItem.kt`:

```kotlin
package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.model.RallyClip

/** Where a match's video is. Null on an item means it has none: a match scored with no video. */
enum class MatchWhere { PHONE, CLOUD, BOTH }

/**
 * What analysis a match has, in the order the status line draws it.
 *
 * Only what exists is ever listed. An item with nothing says so in words
 * ("Not analyzed") rather than drawing four empty glyphs.
 */
enum class MatchAnalysis { RALLIES, HEATMAP, SKELETON, SCORE }

/**
 * The one thing a row asks of the coach, if anything. Any value but NONE makes
 * the row the drawer's raised card.
 */
enum class MatchAction { NONE, ANALYZE, MARK_COURT, RETRY, RUNNING, PAUSED }

/** What "Delete" does on this row, before the phone copy is considered. */
enum class MatchDelete { NONE, VIDEO_MATCH, SCORE_MATCH, BOUND_MATCH, LEAVE_SHARE }

/**
 * One match in the drawer and on the Analytics picker.
 *
 * Plain fields and enums rather than sealed types on purpose: Swift reads this
 * directly, and a nullable field is a plain optional there where a sealed type
 * would need SKIE's onEnum wrapper at every use.
 */
data class MatchListItem(
    /** Stable for the row's lifetime. "video-<id>" for anything filmed, "score-<id>" for a scored match. */
    val key: String,
    val sortAtEpochMs: Long,
    val title: String,
    /** "3-1 · 12 rallies · Sep 8, 2026". */
    val detail: String,
    val playersLine: String?,
    /** "Shared by coach@club.es", shared rows only. */
    val sharedBy: String?,
    val where: MatchWhere?,
    val analyses: List<MatchAnalysis>,
    /** "Not analyzed", "Analyzing 42%…", a failure. Null when the glyphs say everything. */
    val statusText: String?,
    val statusIsError: Boolean,
    val action: MatchAction,
    /** The local entry, when the file is on this phone. Equal to [videoId] once it has been uploaded. */
    val entryId: String?,
    /** The cloud video, when it has clips. */
    val videoId: String?,
    val scoreLogId: String?,
    /** Thumbnail sources, first non-null wins: the file on this phone, the cloud's cover clip, the score. */
    val localUri: String?,
    val coverClip: RallyClip?,
    /** The last game's score for a tile, e.g. "21-18". Null for a match nobody has scored. */
    val scoreText: String?,
    val isOwned: Boolean,
    val canShare: Boolean,
    val canEditDetails: Boolean,
    val canAnalyzeAgain: Boolean,
    val canRemoveFromPhone: Boolean,
    val delete: MatchDelete,
    /** A tap opens the local player rather than the match page: the file is here and nothing else is. */
    val opensLocalPlayer: Boolean,
    /** Rallies an on-device run cut, for the "Clips on this phone (N)" menu item. */
    val deviceClipCount: Int,
    /** The id a stored heatmap is keyed by, when there is one. */
    val heatmapId: String?,
    /** The whole status line, read as one sentence by a screen reader. */
    val accessibilityStatus: String,
) {
    val isRaised: Boolean get() = action != MatchAction.NONE
}

data class MatchList(
    val mine: List<MatchListItem>,
    val shared: List<MatchListItem>,
)
```

`MatchListInput.kt`:

```kotlin
package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.ScoreLog

enum class DeviceRunKind { RUNNING, PAUSED, FAILED }

/**
 * An on-device run, reduced to what a row needs. Absent from the map when the
 * run is idle or finished: a finished run speaks through the files it left.
 *
 * Each platform owns its own run-state type (Android's LocalAnalysisState, iOS's
 * own, which alone can be PAUSED), so they meet here.
 */
data class DeviceRun(
    val kind: DeviceRunKind,
    val phase: DevicePhase = DevicePhase.ANALYSING,
    /** The failure or pause reason. */
    val message: String? = null,
)

/**
 * Everything the list is built from. Every field is something Swift can hand
 * over as-is: collections of shared types, strings and ints.
 */
data class MatchListInput(
    val clips: List<RallyClip>,
    val currentUserId: String?,
    val sharerByVideoId: Map<String, String>,
    val metadataByVideoId: Map<String, MatchMetadata>,
    val scoreLogs: List<ScoreLog>,
    val localEntries: List<LocalVideoEntry>,
    val cloudProgress: Map<String, AnalyzeProgress>,
    val deviceRuns: Map<String, DeviceRun>,
    /** Ids (entry or video) with a stored player track: the heatmap. */
    val storedTrackIds: Set<String>,
    /** Ids with a stored skeleton. */
    val storedSkeletonIds: Set<String>,
    /** Rallies an on-device run cut, per entry id. Absent means none. */
    val deviceClipCounts: Map<String, Int>,
)
```

- [ ] **Step 2: Write the copy and the filter**

`MatchListCopy.kt`:

```kotlin
package com.badmintontracker.shared.matchlist

/**
 * Every string the match list and its two sheets show, in one place so the two
 * platforms cannot word the same row two ways.
 */
object MatchListCopy {
    const val NOT_ANALYZED = "Not analyzed"
    const val ANALYZED = "Analyzed"
    const val FILTER_ALL = "All"
    const val FILTER_ON_THIS_PHONE = "On this phone"
    const val FILTER_NOT_ANALYZED = "Not analyzed"
    const val EMPTY_ON_THIS_PHONE = "None of your matches have their video on this phone."
    const val EMPTY_NOT_ANALYZED = "Every match on this phone has been analyzed."
    const val SHARED_SECTION = "Shared with me"
    const val LEGEND_BUTTON = "What the icons mean"
    const val LEGEND_TITLE = "What the icons mean"

    const val WHERE_PHONE = "On this phone"
    const val WHERE_CLOUD = "In the cloud"
    const val WHERE_BOTH = "On this phone and in the cloud"
    const val WHERE_NONE = "No video"

    const val ANALYSIS_RALLIES = "Rallies"
    const val ANALYSIS_HEATMAP = "Heatmap"
    const val ANALYSIS_SKELETON = "Skeleton"
    const val ANALYSIS_SCORE = "Score"

    /** Legend rows: label, then the line under it. */
    val LEGEND: List<Pair<String, String>> = listOf(
        WHERE_PHONE to "The video file is here. The skeleton needs it.",
        WHERE_CLOUD to "Uploaded and cut into rallies. Opens on any phone.",
        ANALYSIS_RALLIES to "Cut into rally clips, on this phone or in the cloud.",
        ANALYSIS_HEATMAP to "Where the player moved, and their base position.",
        ANALYSIS_SKELETON to "Body positions over the video. Only while the video is on this phone.",
        ANALYSIS_SCORE to "Scored point by point courtside.",
    )

    const val MENU_SHARE = "Share"
    const val MENU_EDIT_DETAILS = "Edit details"
    const val MENU_ANALYZE_AGAIN = "Analyze again"
    const val MENU_HEATMAP = "Player heatmap"
    fun menuDeviceClips(count: Int) = "Clips on this phone ($count)"
    const val MENU_REMOVE_FROM_PHONE = "Remove video from this phone"
    const val MENU_DELETE = "Delete match"
    const val MENU_LEAVE_SHARE = "Remove from my list"

    const val SWIPE_DELETE = "Delete"
    const val SWIPE_REMOVE = "Remove"

    const val CHOICE_REMOVE = "Remove video from this phone"
    const val CHOICE_DELETE = "Delete match everywhere"
    const val CHOICE_CANCEL = "Cancel"

    /** Under "Remove video from this phone" in the choice sheet. */
    fun choiceRemoveDetail(delete: MatchDelete): String = when (delete) {
        MatchDelete.SCORE_MATCH -> "The match and every point you scored stay."
        else -> "The match, its rallies and its analysis stay in the cloud."
    }

    /** Under "Delete match everywhere": the same promise each single-choice dialog makes today. */
    fun choiceDeleteDetail(delete: MatchDelete): String = when (delete) {
        MatchDelete.SCORE_MATCH -> "Deletes this match and every point you scored. This can't be undone."
        MatchDelete.BOUND_MATCH -> "Deletes this match, every point you scored and all its rally clips. This can't be undone."
        else -> "Deletes this match and all its rally clips. This can't be undone."
    }
}

private fun analysisWord(a: MatchAnalysis): String = when (a) {
    MatchAnalysis.RALLIES -> "rallies"
    MatchAnalysis.HEATMAP -> "heatmap"
    MatchAnalysis.SKELETON -> "skeleton"
    MatchAnalysis.SCORE -> "score"
}

/**
 * The status line as one sentence: "On this phone and in the cloud. Rallies,
 * heatmap, skeleton." The glyphs are a scan aid; this is what a screen reader
 * hears instead of seven unlabeled shapes.
 */
fun statusDescription(where: MatchWhere?, analyses: List<MatchAnalysis>, statusText: String?): String {
    val whereText = when (where) {
        MatchWhere.PHONE -> MatchListCopy.WHERE_PHONE
        MatchWhere.CLOUD -> MatchListCopy.WHERE_CLOUD
        MatchWhere.BOTH -> MatchListCopy.WHERE_BOTH
        null -> MatchListCopy.WHERE_NONE
    }
    val parts = mutableListOf("$whereText.")
    if (analyses.isNotEmpty()) {
        parts += analyses.joinToString(", ") { analysisWord(it) }
            .replaceFirstChar { it.uppercase() } + "."
    }
    if (statusText != null) parts += if (statusText.endsWith(".") || statusText.endsWith("…")) statusText else "$statusText."
    return parts.joinToString(" ")
}
```

`MatchListFilter.kt`:

```kotlin
package com.badmintontracker.shared.matchlist

enum class MatchListFilter { ALL, ON_THIS_PHONE, NOT_ANALYZED }

/**
 * Below this many matches the chips are hidden: a list that fits on one screen
 * does not need narrowing, and the chips would only add chrome.
 */
const val MATCH_LIST_FILTER_THRESHOLD = 6

fun showsFilters(mineCount: Int): Boolean = mineCount >= MATCH_LIST_FILTER_THRESHOLD

/**
 * The filter actually applied. A chosen filter stops applying the moment its
 * chips are hidden: filtering on a control the coach can no longer see would
 * hide matches with no way to get them back.
 */
fun effectiveFilter(chosen: MatchListFilter, mineCount: Int): MatchListFilter =
    if (showsFilters(mineCount)) chosen else MatchListFilter.ALL

private val ANALYSED_BY_A_RUN = setOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP, MatchAnalysis.SKELETON)

fun MatchListItem.passes(filter: MatchListFilter): Boolean = when (filter) {
    MatchListFilter.ALL -> true
    MatchListFilter.ON_THIS_PHONE -> where == MatchWhere.PHONE || where == MatchWhere.BOTH
    // A video on this phone that no run has produced anything for. An ANALYZED
    // entry whose clips this phone has not seen yet is analyzed, not waiting
    // (it says "Analyzed" on its row), and a match with no video has nothing
    // to analyze.
    MatchListFilter.NOT_ANALYZED -> entryId != null &&
        analyses.none { it in ANALYSED_BY_A_RUN } &&
        statusText != MatchListCopy.ANALYZED
}
```

- [ ] **Step 3: Write the failing builder tests**

`MatchListBuilderTest.kt`. Fixtures follow `scoring/AttachStatusTest.kt`. Use the internal overload `buildMatchList(input, TimeZone.UTC)` so dates are deterministic.

```kotlin
package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreEvent
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SEP_8 = "2026-09-08T10:00:00Z"

private fun entry(
    id: String,
    stage: AnalyzeStage = AnalyzeStage.LOCAL,
    scoreLogId: String? = null,
    title: String? = null,
    addedAt: String = SEP_8,
    failureMessage: String? = null,
) = LocalVideoEntry(
    id = id, uri = "content://x/$id", displayName = "$id.mp4",
    durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = Instant.parse(addedAt).toEpochMilliseconds(),
    title = title, stage = stage,
    failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
    failureMessage = failureMessage, scoreLogId = scoreLogId,
)

private fun clip(videoId: String, index: Int, owner: String = ME, at: String = SEP_8, title: String? = null) = RallyClip(
    id = "$videoId-$index", videoId = videoId, ownerId = owner, rallyIndex = index,
    startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
    clipStoragePath = "clips/$videoId/$index.mp4", thumbnailStoragePath = "thumbs/$videoId/$index.jpg",
    title = title, annotationCount = 0, createdAt = Instant.parse(at),
)

private fun log(id: String, videoId: String? = null, points: Int = 0, at: String = SEP_8) = ScoreLog(
    id = id, videoId = videoId, title = "Thu League",
    rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
    events = List(points) { ScoreEvent.PointTo(Side.HOME) },
    status = if (videoId != null) ScoreLogStatus.BOUND else ScoreLogStatus.UNBOUND,
    createdAt = Instant.parse(at), updatedAt = Instant.parse(at),
)

private fun input(
    clips: List<RallyClip> = emptyList(),
    logs: List<ScoreLog> = emptyList(),
    entries: List<LocalVideoEntry> = emptyList(),
    deviceRuns: Map<String, DeviceRun> = emptyMap(),
    tracks: Set<String> = emptySet(),
    skeletons: Set<String> = emptySet(),
    deviceClips: Map<String, Int> = emptyMap(),
    metadata: Map<String, MatchMetadata> = emptyMap(),
    sharers: Map<String, String> = emptyMap(),
) = MatchListInput(
    clips = clips, currentUserId = ME, sharerByVideoId = sharers, metadataByVideoId = metadata,
    scoreLogs = logs, localEntries = entries, cloudProgress = emptyMap(), deviceRuns = deviceRuns,
    storedTrackIds = tracks, storedSkeletonIds = skeletons, deviceClipCounts = deviceClips,
)

private fun build(i: MatchListInput) = buildMatchList(i, TimeZone.UTC)

class MatchListBuilderTest {

    // --- the join: one row per match ---

    @Test
    fun a_video_analysed_in_the_cloud_is_one_row_on_the_phone_and_in_the_cloud() {
        // The duplicate this design exists to remove: the entry keeps its id as
        // the cloud video's id and stays ANALYZED after the run.
        val list = build(input(
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED)),
            clips = listOf(clip("v1", 1), clip("v1", 2)),
        ))
        list.mine.size shouldBe 1
        val item = list.mine.single()
        item.key shouldBe "video-v1"
        item.where shouldBe MatchWhere.BOTH
        item.entryId shouldBe "v1"
        item.videoId shouldBe "v1"
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES)
        item.opensLocalPlayer shouldBe false
    }

    @Test
    fun a_local_video_nobody_has_analysed_asks_to_be_analysed() {
        val item = build(input(entries = listOf(entry("v1")))).mine.single()
        item.where shouldBe MatchWhere.PHONE
        item.analyses.shouldBeEmpty()
        item.action shouldBe MatchAction.ANALYZE
        item.statusText shouldBe MatchListCopy.NOT_ANALYZED
        item.opensLocalPlayer shouldBe true
        item.detail shouldBe "0:48 · Sep 8, 2026"
    }

    @Test
    fun a_cloud_match_from_another_phone_is_in_the_cloud_only() {
        val item = build(input(clips = listOf(clip("v9", 1), clip("v9", 2), clip("v9", 3)))).mine.single()
        item.where shouldBe MatchWhere.CLOUD
        item.entryId.shouldBeNull()
        item.detail shouldBe "3 rallies · Sep 8, 2026"
        item.canRemoveFromPhone shouldBe false
        item.delete shouldBe MatchDelete.VIDEO_MATCH
    }

    @Test
    fun a_scored_match_with_no_video_has_no_where_and_shows_its_score() {
        val item = build(input(logs = listOf(log("l1", points = 3)))).mine.single()
        item.key shouldBe "score-l1"
        item.where.shouldBeNull()
        item.analyses shouldContainExactly listOf(MatchAnalysis.SCORE)
        item.scoreText shouldBe "3-0"
        item.delete shouldBe MatchDelete.SCORE_MATCH
        item.canShare shouldBe false
    }

    @Test
    fun a_scored_match_nobody_has_started_draws_no_score_glyph() {
        val item = build(input(logs = listOf(log("l1")))).mine.single()
        item.analyses.shouldBeEmpty()
        item.scoreText.shouldBeNull()
    }

    @Test
    fun a_scored_match_with_its_video_clipped_folds_the_video_in() {
        val item = build(input(
            logs = listOf(log("l1", videoId = "v1", points = 2)),
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED, scoreLogId = "l1")),
            clips = listOf(clip("v1", 1)),
        )).mine.single()
        item.key shouldBe "score-l1"
        item.where shouldBe MatchWhere.BOTH
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.SCORE)
        item.delete shouldBe MatchDelete.BOUND_MATCH
    }

    @Test
    fun an_attached_video_whose_match_is_not_loaded_yet_is_not_a_row_of_its_own() {
        // Review focus 1. The score-log cache starts empty on a cold start; an
        // entry pointing at a log not in the list is that window, not an orphan.
        build(input(entries = listOf(entry("v1", scoreLogId = "l-missing")))).mine.shouldBeEmpty()
    }

    @Test
    fun no_id_is_ever_listed_twice() {
        // Review focus 2, as an invariant over every kind of row at once.
        val list = build(input(
            entries = listOf(
                entry("a", AnalyzeStage.ANALYZED), entry("b"), entry("c", scoreLogId = "l1"),
                entry("d", AnalyzeStage.ANALYZED, scoreLogId = "l2"),
            ),
            logs = listOf(log("l1"), log("l2", videoId = "d"), log("l3")),
            clips = listOf(clip("a", 1), clip("d", 1), clip("e", 1), clip("s", 1, owner = OTHER)),
            sharers = mapOf("s" to "coach@club.es"),
        ))
        val all = list.mine + list.shared
        val ids = all.flatMap { listOfNotNull(it.entryId, it.videoId, it.scoreLogId).distinct() }
        ids.size shouldBe ids.toSet().size
        all.map { it.key }.toSet().size shouldBe all.size
        list.shared.map { it.videoId } shouldContainExactly listOf("s")
    }

    // --- what it has ---

    @Test
    fun a_device_run_shows_rallies_heatmap_and_skeleton_and_offers_to_analyze_again() {
        val item = build(input(
            entries = listOf(entry("v1")),
            tracks = setOf("v1"), skeletons = setOf("v1"), deviceClips = mapOf("v1" to 7),
        )).mine.single()
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP, MatchAnalysis.SKELETON)
        item.detail shouldBe "7 rallies · Sep 8, 2026"
        item.action shouldBe MatchAction.NONE
        item.statusText.shouldBeNull()
        item.canAnalyzeAgain shouldBe true
        item.deviceClipCount shouldBe 7
        item.heatmapId shouldBe "v1"
    }

    @Test
    fun the_skeleton_is_not_offered_once_the_video_has_left_the_phone() {
        // Same rule as AnalyticsRowState.READY_NO_VIDEO: it is drawn over playback.
        val item = build(input(
            clips = listOf(clip("v1", 1)), tracks = setOf("v1"), skeletons = setOf("v1"),
        )).mine.single()
        item.analyses shouldContainExactly listOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP)
    }

    @Test
    fun an_analyzed_video_with_no_clips_in_sight_says_analyzed_and_asks_nothing() {
        // Review focus 3: offline, or the cloud match was deleted elsewhere.
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)))).mine.single()
        item.statusText shouldBe MatchListCopy.ANALYZED
        item.action shouldBe MatchAction.NONE
        item.passes(MatchListFilter.NOT_ANALYZED) shouldBe false
    }

    // --- what it asks ---

    @Test
    fun a_device_run_in_flight_speaks_over_the_cloud_stage() {
        val item = build(input(
            entries = listOf(entry("v1")),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.RUNNING, DevicePhase.ANALYSING)),
        )).mine.single()
        item.action shouldBe MatchAction.RUNNING
        item.statusText shouldBe "Analyzing on device…"
        item.canRemoveFromPhone shouldBe false
    }

    @Test
    fun a_failed_device_run_asks_for_a_retry_in_red() {
        val item = build(input(
            entries = listOf(entry("v1")),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.FAILED, message = "Out of memory")),
        )).mine.single()
        item.action shouldBe MatchAction.RETRY
        item.statusText shouldBe "Analysis failed: Out of memory"
        item.statusIsError shouldBe true
    }

    @Test
    fun a_failed_cloud_run_shows_its_message_and_retries() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.FAILED, failureMessage = "Upload failed")))).mine.single()
        item.action shouldBe MatchAction.RETRY
        item.statusText shouldBe "Upload failed"
        item.statusIsError shouldBe true
    }

    @Test
    fun a_scored_match_with_an_unmarked_court_asks_for_the_court() {
        val item = build(input(
            logs = listOf(log("l1")), entries = listOf(entry("v1", scoreLogId = "l1")),
        )).mine.single()
        item.action shouldBe MatchAction.MARK_COURT
        item.where shouldBe MatchWhere.PHONE
    }

    // --- removal and delete (review focus 4) ---

    @Test
    fun a_merged_row_offers_both_remove_and_delete_when_nothing_is_running() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)))).mine.single()
        item.canRemoveFromPhone shouldBe true
        item.delete shouldBe MatchDelete.VIDEO_MATCH
    }

    @Test
    fun a_merged_row_offers_no_delete_while_a_device_run_holds_the_file() {
        val item = build(input(
            entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)),
            deviceRuns = mapOf("v1" to DeviceRun(DeviceRunKind.RUNNING)),
        )).mine.single()
        item.canRemoveFromPhone shouldBe false
        item.delete shouldBe MatchDelete.NONE
    }

    @Test
    fun a_video_only_on_this_phone_is_removed_not_deleted() {
        val item = build(input(entries = listOf(entry("v1")))).mine.single()
        item.canRemoveFromPhone shouldBe true
        item.delete shouldBe MatchDelete.NONE
    }

    // --- shared, titles, order ---

    @Test
    fun a_shared_match_is_listed_apart_with_who_shared_it() {
        val list = build(input(clips = listOf(clip("s", 1, owner = OTHER)), sharers = mapOf("s" to "coach@club.es")))
        list.mine.shouldBeEmpty()
        val item = list.shared.single()
        item.sharedBy shouldBe "Shared by coach@club.es"
        item.delete shouldBe MatchDelete.LEAVE_SHARE
        item.canShare shouldBe false
        item.isOwned shouldBe false
    }

    @Test
    fun the_name_comes_from_the_videos_row_before_the_clips_and_the_file() {
        val meta = MatchMetadata(videoId = "v1", title = "Club final")
        build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED, title = "old")), clips = listOf(clip("v1", 1, title = "clip name")), metadata = mapOf("v1" to meta)))
            .mine.single().title shouldBe "Club final"
        build(input(entries = listOf(entry("v2")))).mine.single().title shouldBe "v2.mp4"
    }

    @Test
    fun rows_sort_on_when_the_match_began_and_a_cloud_match_does_not_jump_when_it_is_recut() {
        val list = build(input(
            entries = listOf(entry("new", addedAt = "2026-09-20T10:00:00Z")),
            clips = listOf(clip("old", 1, at = "2026-09-01T10:00:00Z"), clip("old", 2, at = "2026-09-24T10:00:00Z")),
            logs = listOf(log("mid", at = "2026-09-10T10:00:00Z")),
        ))
        list.mine.map { it.key } shouldContainExactly listOf("video-new", "score-mid", "video-old")
    }

    @Test
    fun the_status_line_reads_as_one_sentence() {
        val item = build(input(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1", 1)), tracks = setOf("v1"))).mine.single()
        item.accessibilityStatus shouldBe "On this phone and in the cloud. Rallies, heatmap."
    }
}
```

Also write `MatchListFilterTest.kt` (the threshold, `effectiveFilter` returning ALL below six whatever was chosen, and `ON_THIS_PHONE` / `NOT_ANALYZED` over a PHONE, BOTH, CLOUD and no-video item built with `build(...)` as above) and `MatchListCopyTest.kt` (`statusDescription` for `null` where with a SCORE analysis gives `"No video. Score."`; a running status keeps its ellipsis: `statusDescription(MatchWhere.PHONE, emptyList(), "Analyzing on device…")` is `"On this phone. Analyzing on device…"`; and no string in `MatchListCopy` or produced by it contains "—").

- [ ] **Step 4: Run the tests to watch them fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*matchlist*"`
Expected: compilation fails on `buildMatchList` (unresolved).

- [ ] **Step 5: Write the builder**

`MatchListBuilder.kt`:

```kotlin
package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.canEditLocalVideoDetails
import com.badmintontracker.shared.localvideo.canRemoveLocalVideo
import com.badmintontracker.shared.localvideo.cloudAnalysisStatus
import com.badmintontracker.shared.localvideo.deviceWorkLabel
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.playersLine
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.scoreLogAttachStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The match name the web app stamps onto every clip of a video at cut time: the
 * most common non-null title, so retitling one clip does not relabel the match.
 */
fun List<RallyClip>.matchTitle(): String? =
    mapNotNull { it.title }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/** One row per match, from everything the phone knows about its matches. */
fun buildMatchList(input: MatchListInput): MatchList = buildMatchList(input, TimeZone.currentSystemDefault())

internal fun buildMatchList(input: MatchListInput, timeZone: TimeZone): MatchList {
    val clipsByVideo = input.clips.groupBy { it.videoId }
    fun ownedVideo(videoId: String?): List<RallyClip> =
        videoId?.let(clipsByVideo::get).orEmpty().takeIf { it.firstOrNull()?.isOwnedBy(input.currentUserId) == true }.orEmpty()

    val logIds = input.scoreLogs.map { it.id }.toSet()
    val claimed = mutableSetOf<String>()
    val mine = mutableListOf<MatchListItem>()

    // Scored matches first: a video filmed for a match belongs to that match's row.
    for (log in input.scoreLogs) {
        val entry = input.localEntries.firstOrNull { it.scoreLogId == log.id }
        val videoId = log.videoId ?: entry?.id
        val clips = ownedVideo(videoId)
        listOfNotNull(entry?.id, log.videoId).forEach { claimed += it }
        mine += scoreItem(log, entry, clips, input, timeZone)
    }

    // Videos filmed on their own. An entry naming a match is never one of these,
    // even when that match is missing from the list: the log cache starts empty on
    // a cold start, and falling back here would flash every attached video in and
    // out of the list (see orphanedLocalVideoIds for the real orphans).
    for (entry in input.localEntries) {
        if (entry.scoreLogId != null || entry.id in claimed) continue
        claimed += entry.id
        mine += videoItem(entry, ownedVideo(entry.id), input, timeZone)
    }

    val shared = mutableListOf<MatchListItem>()
    for ((videoId, clips) in clipsByVideo) {
        if (videoId in claimed) continue
        claimed += videoId
        if (clips.first().isOwnedBy(input.currentUserId)) mine += videoItem(null, clips, input, timeZone)
        else shared += videoItem(null, clips, input, timeZone, sharer = input.sharerByVideoId[videoId])
    }

    val order = compareByDescending<MatchListItem> { it.sortAtEpochMs }.thenBy { it.key }
    return MatchList(mine = mine.sortedWith(order), shared = shared.sortedWith(order))
}

private fun RallyClip.isOwnedBy(userId: String?) = userId != null && ownerId == userId

private data class Asks(val action: MatchAction, val text: String?, val isError: Boolean)

/** What the file's own pipelines are asking for, device run first: it is the one this row's button starts. */
private fun entryAsks(entry: LocalVideoEntry, input: MatchListInput, hasRunResults: Boolean): Asks {
    val run = input.deviceRuns[entry.id]
    return when {
        run?.kind == DeviceRunKind.FAILED -> Asks(MatchAction.RETRY, "Analysis failed: ${run.message ?: "Unknown error"}", true)
        run?.kind == DeviceRunKind.PAUSED -> Asks(MatchAction.PAUSED, run.message ?: "Paused", false)
        run?.kind == DeviceRunKind.RUNNING -> Asks(MatchAction.RUNNING, deviceWorkLabel(run.phase, null) + "…", false)
        isAnalysisRunning(entry.stage) ->
            Asks(MatchAction.RUNNING, cloudAnalysisStatus(entry.stage, input.cloudProgress[entry.id]) ?: "Analyzing…", false)
        entry.stage == AnalyzeStage.FAILED -> Asks(MatchAction.RETRY, entry.failureMessage ?: "Analysis failed", true)
        hasRunResults -> Asks(MatchAction.NONE, null, false)
        // Finished in the cloud, but this phone has not seen the clips: offline,
        // or deleted elsewhere. Asking to analyze it again would be a button that
        // does nothing (canAnalyze is false for ANALYZED).
        entry.stage == AnalyzeStage.ANALYZED -> Asks(MatchAction.NONE, MatchListCopy.ANALYZED, false)
        else -> Asks(MatchAction.ANALYZE, MatchListCopy.NOT_ANALYZED, false)
    }
}

private fun deviceRunHoldsFile(entry: LocalVideoEntry, input: MatchListInput): Boolean =
    input.deviceRuns[entry.id]?.kind.let { it == DeviceRunKind.RUNNING || it == DeviceRunKind.PAUSED }

private fun analysesFor(entry: LocalVideoEntry?, videoId: String?, cloudClips: Int, input: MatchListInput): Pair<List<MatchAnalysis>, String?> {
    val ids = listOfNotNull(entry?.id, videoId).distinct()
    val deviceClips = entry?.let { input.deviceClipCounts[it.id] } ?: 0
    val heatmapId = ids.firstOrNull { it in input.storedTrackIds }
    val list = buildList {
        if (cloudClips > 0 || deviceClips > 0) add(MatchAnalysis.RALLIES)
        if (heatmapId != null) add(MatchAnalysis.HEATMAP)
        // Drawn over playback, so only while the file is here.
        if (entry != null && ids.any { it in input.storedSkeletonIds }) add(MatchAnalysis.SKELETON)
    }
    return list to heatmapId
}

private fun whereOf(entry: LocalVideoEntry?, cloudClips: Int): MatchWhere? = when {
    entry != null && cloudClips > 0 -> MatchWhere.BOTH
    entry != null -> MatchWhere.PHONE
    cloudClips > 0 -> MatchWhere.CLOUD
    else -> null
}

private fun videoItem(
    entry: LocalVideoEntry?,
    clips: List<RallyClip>,
    input: MatchListInput,
    timeZone: TimeZone,
    sharer: String? = null,
): MatchListItem {
    val id = entry?.id ?: clips.first().videoId
    val videoId = id.takeIf { clips.isNotEmpty() }
    val meta = input.metadataByVideoId[id]
    val deviceClips = entry?.let { input.deviceClipCounts[it.id] } ?: 0
    val (analyses, heatmapId) = analysesFor(entry, videoId, clips.size, input)
    val asks = if (entry != null) entryAsks(entry, input, analyses.isNotEmpty()) else Asks(MatchAction.NONE, null, false)
    val sortAt = entry?.addedAtEpochMs ?: clips.minOf { it.createdAt }.toEpochMilliseconds()
    val rallies = if (clips.isNotEmpty()) clips.size else deviceClips
    val detail = listOfNotNull(
        ralliesText(rallies),
        entry?.takeIf { rallies == 0 }?.let { formatClock(it.durationMs) },
        formatMatchDate(sortAt, timeZone),
    ).joinToString(" · ")
    val where = whereOf(entry, clips.size)
    val owned = sharer == null && (entry != null || clips.first().isOwnedBy(input.currentUserId))
    val removable = entry != null && canRemoveLocalVideo(entry.stage) && !deviceRunHoldsFile(entry, input)
    return MatchListItem(
        key = "video-$id",
        sortAtEpochMs = sortAt,
        title = meta?.title ?: entry?.title ?: clips.matchTitle() ?: entry?.displayName
            ?: "Match · ${formatMatchDate(sortAt, timeZone)}",
        detail = detail,
        playersLine = meta?.playersLine(),
        sharedBy = sharer?.let { "Shared by $it" },
        where = where,
        analyses = analyses,
        statusText = asks.text,
        statusIsError = asks.isError,
        action = asks.action,
        entryId = entry?.id,
        videoId = videoId,
        scoreLogId = null,
        localUri = entry?.uri,
        coverClip = clips.minByOrNull { it.rallyIndex },
        scoreText = null,
        isOwned = owned,
        canShare = owned && videoId != null,
        canEditDetails = entry != null && canEditLocalVideoDetails(entry.stage),
        canAnalyzeAgain = entry != null && entry.stage == AnalyzeStage.LOCAL &&
            asks.action == MatchAction.NONE && analyses.isNotEmpty(),
        canRemoveFromPhone = removable,
        delete = when {
            !owned -> MatchDelete.LEAVE_SHARE
            videoId == null -> MatchDelete.NONE
            // Deleting the cloud match also discards the file, so it waits for
            // the same thing removing the file waits for.
            entry != null && !removable -> MatchDelete.NONE
            else -> MatchDelete.VIDEO_MATCH
        },
        opensLocalPlayer = entry != null && videoId == null,
        deviceClipCount = deviceClips,
        heatmapId = heatmapId,
        accessibilityStatus = statusDescription(where, analyses, asks.text),
    )
}

private fun scoreItem(
    log: ScoreLog,
    entry: LocalVideoEntry?,
    clips: List<RallyClip>,
    input: MatchListInput,
    timeZone: TimeZone,
): MatchListItem {
    val card = buildScoreMatchCard(log)
    val state = log.state()
    val started = state.points.isNotEmpty() || state.completedGames.isNotEmpty()
    val videoId = clips.firstOrNull()?.videoId
    val (runAnalyses, heatmapId) = analysesFor(entry, videoId, clips.size, input)
    val analyses = if (started) runAnalyses + MatchAnalysis.SCORE else runAnalyses
    val attach = scoreLogAttachStatus(log, input.localEntries, input.cloudProgress, clips.size)
    val asks = when (attach?.kind) {
        AttachKind.COURT_NOT_MARKED -> Asks(MatchAction.MARK_COURT, attach.text, false)
        AttachKind.FAILED -> Asks(MatchAction.RETRY, attach.text, true)
        AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP -> Asks(MatchAction.RUNNING, attach.text, false)
        null -> entry?.let { e ->
            // Only the device run can still be asking here: the cloud stages are
            // what the attach status above already speaks for.
            entryAsks(e, input, hasRunResults = true).takeIf { input.deviceRuns[e.id] != null }
        } ?: Asks(MatchAction.NONE, null, false)
    }
    val rallies = clips.size.takeIf { it > 0 } ?: (entry?.let { input.deviceClipCounts[it.id] } ?: 0)
    val where = whereOf(entry, clips.size)
    val removable = entry != null && canRemoveLocalVideo(entry.stage) && !deviceRunHoldsFile(entry, input)
    return MatchListItem(
        key = "score-${log.id}",
        sortAtEpochMs = card.createdAtEpochMs,
        title = card.title,
        detail = listOfNotNull(card.scoreLine, ralliesText(rallies), formatMatchDate(card.createdAtEpochMs, timeZone))
            .joinToString(" · "),
        playersLine = card.playersLine,
        sharedBy = null,
        where = where,
        analyses = analyses,
        statusText = asks.text,
        statusIsError = asks.isError,
        action = asks.action,
        entryId = entry?.id,
        videoId = videoId,
        scoreLogId = log.id,
        localUri = entry?.uri,
        coverClip = clips.minByOrNull { it.rallyIndex },
        // The last game, which is the one a coach remembers: "21-18, 5-3" does not fit a 64dp tile.
        scoreText = card.scoreLine.takeIf { started }?.substringAfterLast(", "),
        isOwned = true,
        canShare = videoId != null,
        canEditDetails = false,
        canAnalyzeAgain = false,
        canRemoveFromPhone = removable,
        delete = when {
            entry != null && !removable -> MatchDelete.NONE
            videoId != null -> MatchDelete.BOUND_MATCH
            else -> MatchDelete.SCORE_MATCH
        },
        opensLocalPlayer = false,
        deviceClipCount = entry?.let { input.deviceClipCounts[it.id] } ?: 0,
        heatmapId = heatmapId,
        accessibilityStatus = statusDescription(where, analyses, asks.text),
    )
}

private fun ralliesText(count: Int): String? =
    count.takeIf { it > 0 }?.let { "$it ${if (it == 1) "rally" else "rallies"}" }

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Sep 8, 2026": the date every match list in the app prints. */
internal fun formatMatchDate(epochMs: Long, timeZone: TimeZone): String {
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
}

/** "0:48". */
internal fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
```

Check while writing: `MatchState` exposes `points` and `completedGames` (`scoring/MatchState.kt`); `playersLine()` on `MatchMetadata` is in `model/MatchMetadata.kt`. If `buildScoreMatchCard` does not exist under that name, use the one `ScoreMatchCard.kt` defines. If a device PAUSED status is shown and `MatchAction.PAUSED` then disables the Analyze button, that is intended.

- [ ] **Step 6: Point Android's `matchTitle` at the shared one**

In `ClipListViewModel.kt`, delete the body of `internal fun List<RallyClip>.matchTitle()` and import `com.badmintontracker.shared.matchlist.matchTitle` at its call sites (the existing `ClipListViewModelTest` must still pass unchanged).

- [ ] **Step 7: Run the tests**

Run: `./gradlew --offline :shared:jvmTest --tests "*matchlist*"` then `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all matchlist tests pass, nothing else regresses.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist shared/src/commonTest/kotlin/com/badmintontracker/shared/matchlist androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt
git commit -m "feat: one shared list of matches, with where each lives and what it has"
```

---

### Task 2: A signed thumbnail URL survives the drawer reopening

**Why:** every drawer row now has a thumbnail, and a cloud match's comes from `MediaRepository.signedThumbnailUrl`, which signs a fresh URL per call. A new URL defeats the image caches, so every drawer open would re-sign and re-download every cover.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/MediaRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ThumbnailUrlCacheTest.kt`

**Interfaces:**
- Produces: `class ThumbnailUrlCache(ttl: Duration = 50.minutes, now: () -> Instant = { Clock.System.now() })` with `suspend fun get(path: String, sign: suspend (String) -> String?): String?`. `MediaRepositoryImpl` uses one instance. Public API of `MediaRepository` is unchanged.

- [ ] **Step 1: Failing test**

```kotlin
package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class ThumbnailUrlCacheTest {
    private var clock = Instant.parse("2026-09-25T10:00:00Z")
    private val cache = ThumbnailUrlCache(ttl = 50.minutes, now = { clock })
    private var signs = 0
    private val sign: suspend (String) -> String? = { path -> signs++; "https://x/$path?t=$signs" }

    @Test
    fun a_path_is_signed_once_while_its_url_is_fresh() = runTest {
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
        clock = clock.plus(49.minutes)
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
        signs shouldBe 1
    }

    @Test
    fun a_url_is_signed_again_before_the_one_hour_signature_lapses() = runTest {
        cache.get("a.jpg", sign)
        clock = clock.plus(51.minutes)
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=2"
    }

    @Test
    fun a_failed_signature_is_not_remembered() = runTest {
        cache.get("a.jpg") { null } shouldBe null
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
    }
}
```

Check `kotlinx-coroutines-test` is on `commonTest`'s classpath (`shared/build.gradle.kts`); existing tests using `runTest` confirm it.

- [ ] **Step 2: Run to fail** - `./gradlew --offline :shared:jvmTest --tests "*ThumbnailUrlCacheTest*"`: unresolved `ThumbnailUrlCache`.

- [ ] **Step 3: Implement**

```kotlin
/**
 * Signed thumbnail URLs, reused while their signature holds.
 *
 * A signed URL is new on every call, and a new URL is a cache miss for every
 * image loader downstream. The drawer draws a cover for every cloud match each
 * time it opens, so without this it re-signed and re-downloaded all of them.
 * 50 minutes against the one-hour signature leaves a margin for a slow load.
 */
class ThumbnailUrlCache(
    private val ttl: Duration = 50.minutes,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Pair<String, Instant>>()

    suspend fun get(path: String, sign: suspend (String) -> String?): String? {
        lock.withLock { entries[path]?.let { (url, at) -> if (now() - at < ttl) return url } }
        val url = sign(path) ?: return null
        lock.withLock { entries[path] = url to now() }
        return url
    }
}
```

and in `MediaRepositoryImpl`:

```kotlin
private val thumbnails = ThumbnailUrlCache()

override suspend fun signedThumbnailUrl(clip: RallyClip): String? {
    val path = clip.thumbnailStoragePath ?: return null
    return thumbnails.get(path) { p ->
        // Null (placeholder) on failure, as before: see the comment this replaces.
        runCatching { client.storage.from("thumbnails").createSignedUrl(p, 1.hours) }.getOrNull()
    }
}
```

Keep the existing comment about escaping exceptions on iOS.

- [ ] **Step 4: Run** - `./gradlew --offline :shared:jvmTest`: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** - `git commit -m "perf: a cover thumbnail is signed once an hour, not once per drawer open"`

---

### Task 3: Android drawer on the shared list

**Files:**
- Modify: `androidApp/.../ui/icons/ShuttlIcons.kt` (six glyphs)
- Create: `androidApp/.../cliplist/StoredResults.kt`, `MatchStatusLine.kt`, `MatchListRow.kt`, `MatchListSheets.kt`
- Modify: `androidApp/.../localanalysis/LocalAnalysisRunner.kt` (add `toDeviceRun`)
- Modify: `androidApp/.../cliplist/ClipListViewModel.kt`, `ClipListScreen.kt`, `home/HomeScreen.kt`, `AuthGate.kt` (Route.Home block)
- Delete once unused: `cliplist/MatchRow.kt`, `test/.../cliplist/MatchRowMergeTest.kt` (only if Task 4's Analytics rewrite no longer needs `MatchRow` - do the deletion in Task 4 if Analytics still compiles against it here), `localvideo/LocalVideoSection.kt`'s `localVideoSection` and `LocalVideoRowItem` (move `AnalyzeResultDialog` into `MatchListSheets.kt` if the file would otherwise be empty)
- Tests: `androidApp/src/test/.../cliplist/ClipListViewModelMatchListTest.kt`, `androidApp/src/test/.../localanalysis/DeviceRunMappingTest.kt`

**Interfaces:**
- Consumes: Task 1's `buildMatchList`, `MatchListItem`, `MatchList`, `MatchListInput`, `DeviceRun`, `DeviceRunKind`, `MatchListFilter`, `passes`, `showsFilters`, `effectiveFilter`, `MatchListCopy`, the enums. Task 2 transparently via `MediaRepository`.
- Produces for Task 4: `ClipListState.matchList: MatchList`, `ClipListState.storedResults: StoredResults`; `data class StoredResults(val trackIds: Set<String> = emptySet(), val skeletonIds: Set<String> = emptySet(), val clipCounts: Map<String, Int> = emptyMap())`; `fun interface StoredResultsReader { fun read(ids: Set<String>): StoredResults }`; `internal fun toDeviceRun(state: LocalAnalysisState): DeviceRun?`; `@Composable fun MatchStatusLine(item: MatchListItem, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Glyphs**

Add to `ShuttlIcons` six Lucide icons (Lucide 0.460.0, stroke 2, 24 viewport). Use Compose's SVG path parser rather than hand-translating arcs: `androidx.compose.ui.graphics.vector.addPathNodes(pathString)` inside `ImageVector.Builder.addPath(pathData = ..., fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)`. Add one private helper `buildLucideSvg(name: String, vararg d: String)`. Keep the header comment's promise honest: say these came from lucide-static 0.460.0, not the web app.

| Name | Lucide | Path strings (rects/lines/circles written as paths) |
| --- | --- | --- |
| `Smartphone` | smartphone | `M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z`, `M12 18h.01` |
| `Cloud` | cloud | `M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z` |
| `Film` | film | `M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z`, `M7 3v18`, `M3 7.5h4`, `M3 12h18`, `M3 16.5h4`, `M17 3v18`, `M17 7.5h4`, `M17 16.5h4` |
| `Flame` | flame | `M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z` |
| `PersonStanding` | person-standing | `M13 5a1 1 0 1 1-2 0a1 1 0 1 1 2 0z`, `m9 20 3-6 3 6`, `m6 8 6 2 6-2`, `M12 10v4` |
| `Hash` | hash | `M4 9h16`, `M4 15h16`, `M10 3 8 21`, `M16 3l-2 18` |

Glyph meaning: Smartphone = on this phone, Cloud = in the cloud, Film = rallies, Flame = heatmap, PersonStanding = skeleton, Hash = score.

- [ ] **Step 2: Device-run mapping, with a test**

In `LocalAnalysisRunner.kt`, beside `toDeviceWork`:

```kotlin
/** A run as the match list sees it; null when idle or done, which speak through their files. */
internal fun toDeviceRun(state: LocalAnalysisState): DeviceRun? = when (state) {
    is LocalAnalysisState.Preparing -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.PREPARING)
    is LocalAnalysisState.Analysing -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.ANALYSING)
    is LocalAnalysisState.Cutting -> DeviceRun(DeviceRunKind.RUNNING, DevicePhase.CUTTING)
    is LocalAnalysisState.Failed -> DeviceRun(DeviceRunKind.FAILED, message = state.message)
    is LocalAnalysisState.Idle, is LocalAnalysisState.Done -> null
}
```

Match the real `LocalAnalysisState` subtypes (read the sealed type; if there are more, map each deliberately). Test each case in `DeviceRunMappingTest`.

- [ ] **Step 3: Stored results and the view model**

`StoredResults.kt`:

```kotlin
package com.badmintontracker.android.cliplist

/**
 * What earlier runs left on disk, for the ids the list can show. Read once for
 * the whole list: the probes stat files and parse sidecars, and the list
 * recomposes several times a second while a run reports progress.
 */
data class StoredResults(
    val trackIds: Set<String> = emptySet(),
    val skeletonIds: Set<String> = emptySet(),
    val clipCounts: Map<String, Int> = emptyMap(),
)

fun interface StoredResultsReader {
    /** Called off the main thread. */
    fun read(ids: Set<String>): StoredResults
}
```

`ClipListViewModel` gains two constructor parameters, appended last: `deviceStates: StateFlow<Map<String, LocalAnalysisState>>` and `storedResults: StoredResultsReader`. Add to `ClipListState`: `val matchList: MatchList = MatchList(emptyList(), emptyList())` and `val storedResults: StoredResults = StoredResults()`.

Inside the view model:
- `candidateIds`: `localVideos.entries` ids plus every `clips.observeClips()` video id.
- `settledKey`: the set of device-state ids that are `Done` or `Failed`, plus each entry's `id to stage` (a cloud run that finishes installs its track as the stage turns ANALYZED).
- `storedFlow = combine(candidateIds, settledKey) { ids, _ -> ids }.distinctUntilChanged().mapLatest { ids -> withContext(Dispatchers.IO) { storedResults.read(ids) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoredResults())`. Inject the dispatcher (constructor default `Dispatchers.IO`) so the test can pass `UnconfinedTestDispatcher`, following how other view models here are tested.
- Build `matchList` by combining the existing state with `scoreLogs.logs`, `localVideos.entries`, `coordinator.progress`, `deviceStates`, `storedFlow` into `MatchListInput` and calling `buildMatchList`. Keep `ownedMatches`, `sharedMatches` and `clips` in the state: `MatchScreen` reads them.
- Remove `attachStatuses` and `ownedRows` once nothing reads them (Task 4 moves Analytics off `ownedRows`; if Analytics still reads it at the end of this task, leave both and delete them in Task 4).
- Delete behaviour change (review focus 4): `deleteMatch(videoId: String, entryId: String? = null)`. On success, when `entryId != null`, also `localVideos.remove(entryId)` and `localAnnotations.removeAllFor(entryId)`. Add `fun removeFromPhone(entryId: String)` that calls `localVideos.freeVideo(entryId)` and `localAnnotations.removeAllFor(entryId)` (the same thing `LocalVideoListViewModel.remove` does; call that instead if the screen already holds it, but do not keep two copies).

`ClipListViewModelMatchListTest`: using the existing fakes (`androidApp/src/test/.../testing/FakeVideosRepository.kt` and whatever `ClipListViewModelTest` uses), assert (a) an ANALYZED entry plus its cloud clips produce one `mine` item; (b) `deleteMatch("v1", entryId = "v1")` removes the entry from the fake local repository after a successful delete and leaves it when the delete fails; (c) the reader is called with entry ids and clip video ids together.

- [ ] **Step 4: The status line**

`MatchStatusLine.kt`:

```kotlin
/**
 * Where a match's video is, then what it has. Read as one sentence by
 * TalkBack ([MatchListItem.accessibilityStatus]); the glyphs are for scanning.
 *
 * Sized against the drawer's ~138dp text column: two bare 14dp where-glyphs,
 * a divider, and up to four 18dp analysis chips is 127dp.
 */
@Composable
fun MatchStatusLine(item: MatchListItem, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = item.accessibilityStatus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val whereIcons = when (item.where) {
            MatchWhere.PHONE -> listOf(ShuttlIcons.Smartphone)
            MatchWhere.CLOUD -> listOf(ShuttlIcons.Cloud)
            MatchWhere.BOTH -> listOf(ShuttlIcons.Smartphone, ShuttlIcons.Cloud)
            null -> emptyList()
        }
        whereIcons.forEachIndexed { i, icon ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (whereIcons.isNotEmpty() && (item.analyses.isNotEmpty() || item.statusText != null)) {
            Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 12.dp).background(ShuttlTheme.extended.borderSecondary))
        }
        item.analyses.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(a.icon(), null, Modifier.size(13.dp), tint = accent) }
        }
        if (item.action == MatchAction.RUNNING) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        // A failure has its own line under the row: two lines of reason do not fit here.
        if (item.statusText != null && !item.statusIsError) {
            Spacer(Modifier.width(6.dp))
            Text(item.statusText!!, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun MatchAnalysis.icon(): ImageVector = when (this) {
    MatchAnalysis.RALLIES -> ShuttlIcons.Film
    MatchAnalysis.HEATMAP -> ShuttlIcons.Flame
    MatchAnalysis.SKELETON -> ShuttlIcons.PersonStanding
    MatchAnalysis.SCORE -> ShuttlIcons.Hash
}
```

Use the real token names (check `ShuttlTheme.extended` for `borderSecondary`; the accent is `colorScheme.primary` in this app, confirm in `Theme.kt`). The light theme accent is `#16A34A`: check the 16% chip still separates from `bgTertiary` on a raised card in light mode (known collision class, see `ShuttlPalette.kt`); if it does not, raise the alpha for light only through a token, not a literal.

- [ ] **Step 5: The row**

`MatchListRow.kt`: `@Composable internal fun MatchListRow(item: MatchListItem, media: MediaRepository, onClick: () -> Unit, onAction: () -> Unit, menu: List<RowMenuItem>)` where `data class RowMenuItem(val label: String, val onClick: () -> Unit)`.

Layout, reusing `DrawerList` metrics and the padding compensation `LocalVideoRowItem` already explains (copy its comments about the IconButton's 12dp):
- `Row`: thumbnail 64x40 (`DrawerList.thumbWidth/thumbHeight`, `RoundedCornerShape(ShuttlRadius.small)`), gap, `Column(weight 1f)`: title (`titleMedium`, 1 line), detail (`bodySmall`, `onSurfaceVariant`, 1 line), players (if any, 1 line), sharedBy (if any), `MatchStatusLine` with 4dp top padding; trailing kebab `IconButton` + `DropdownMenu` when `menu` is not empty (menu shares a `Box` with its anchor).
- Under the row, when `item.statusIsError`: the failure text, `bodySmall`, `colorScheme.error`, 2 lines, start-aligned with the text column (start padding = thumb + gap).
- Under that, when the action is ANALYZE, RETRY or MARK_COURT: a full-width `ShuttlButton(variant = Primary, compact = true)` labelled "Analyze", "Retry" or "Mark court" (RETRY on an entry with `canResumeFailedAnalysis` is still "Retry"; the caller decides what it does), with the same end compensation `LocalVideoRowItem` uses.
- Thumbnail source: `item.localUri` → `AsyncImage(model = uri)`; else `item.coverClip` → `produceState` of `media.signedThumbnailUrl(clip)` into an `AsyncImage` whose `ImageRequest` sets `memoryCacheKey` and `diskCacheKey` to `clip.thumbnailStoragePath` (the signed URL changes hourly; the path does not); else `item.scoreText` → a tile (`bgTertiary`, or `background` when the row is raised so it still reads as a tile; 1dp `borderSecondary`; the score in `titleSmall`, tabular figures); else a tile with `ShuttlIcons.Hash`. The empty slot behind a loading image is `outlineVariant`, as today.
- Container: `DrawerRowContainer` with `RoundedCornerShape(ShuttlRadius.large)` + `bgTertiary` fill when `item.isRaised`, else `RoundedCornerShape(ShuttlRadius.medium)` + `colorScheme.background`. The swipe label and handler come from the caller.

- [ ] **Step 6: The sheets**

`MatchListSheets.kt`:
- `MatchLegendSheet(onDismiss)`: `ModalBottomSheet`, title `MatchListCopy.LEGEND_TITLE`, one row per `MatchListCopy.LEGEND` pair, each with its glyph drawn exactly as the status line draws it (where-glyphs bare, analysis glyphs in the tinted chip). Map pairs to icons by index in the order the list defines.
- `DeleteChoiceSheet(item, onRemoveFromPhone, onDeleteEverywhere, onDismiss)`: `ModalBottomSheet`, title = `item.title`, two outlined option cards: `CHOICE_REMOVE` over `choiceRemoveDetail(item.delete)`, then `CHOICE_DELETE` in `colorScheme.error` over `choiceDeleteDetail(item.delete)`, and a `CHOICE_CANCEL` text button. The option that cannot lose data is first.
- Move `AnalyzeResultDialog` here if `LocalVideoSection.kt` is deleted.

- [ ] **Step 7: The screen**

Rewrite the list part of `ClipListScreen`:
- Parameters: drop `onOpenHeatmap`/`hasHeatmap`/`onOpenLocalClips`/`localClipCount`/`localRows`-for-rendering; keep what the sheets and dialogs still need. New callbacks: `onOpenHeatmap: (String) -> Unit` (heatmap id), `onOpenLocalClips: (String) -> Unit` (entry id), `onAnalyze: (entryId: String) -> Unit`, `onMarkCourt: (scoreLogId: String) -> Unit`, `onRetryAttached: (scoreLogId: String) -> Unit`, `onLocalClick: (entryId: String) -> Unit`, plus the existing match/score click and removal callbacks. `HomeScreen` and `AuthGate` forward them, reusing the existing lambdas in `AuthGate`'s Route.Home block (court marking vs `canResumeFailedAnalysis` retry stays exactly as it is there).
- State: `var filter by rememberSaveable { mutableStateOf(MatchListFilter.ALL) }`; `val mine = state.matchList.mine`; `LaunchedEffect(showsFilters(mine.size)) { if (!showsFilters(mine.size)) filter = MatchListFilter.ALL }`; `val applied = effectiveFilter(filter, mine.size)`; `val visible = mine.filter { it.passes(applied) }`.
- `LazyColumn`: when `showsFilters(mine.size)`, a first item with three pill chips (`FILTER_ALL`, `FILTER_ON_THIS_PHONE`, `FILTER_NOT_ANALYZED`; selected = `onBackground` fill with `background` text; unselected = 1dp `borderSecondary` outline, `onSurfaceVariant` text; 12.5sp; `role = Role.RadioButton` semantics) and a trailing info `IconButton(ShuttlIcons.Info, contentDescription = MatchListCopy.LEGEND_BUTTON)` opening the legend. Keep the chip row on one line at 330dp (`horizontalScroll` if a larger font scale would wrap it). When the chips are hidden, keep the info button: put it at the end of a slim row with no chips.
- Then `visible` rows, keyed by `item.key`; when `visible` is empty but `mine` is not, one line of text: `EMPTY_ON_THIS_PHONE` or `EMPTY_NOT_ANALYZED`.
- Then, when `state.matchList.shared` is not empty, `DrawerSectionLabel(MatchListCopy.SHARED_SECTION)` and its rows (never filtered).
- Tap: `item.opensLocalPlayer` → `onLocalClick(item.entryId!!)`; else `item.scoreLogId` → the score route; else `item.videoId` → the match route. Build the `MatchSummary`/`ScoreMatchCard` the existing callbacks need from `state.ownedMatches`/the score logs, or better, change those callbacks to take ids (the routes only take ids: `Route.Match(videoId=...)`, `Route.Match(scoreLogId=...)`).
- Menu per row, in this order, only the ones that apply: Share (`canShare`), `menuDeviceClips(n)` (`deviceClipCount > 0 && entryId != null`), Player heatmap (`heatmapId != null`), Edit details (`canEditDetails`), Analyze again (`canAnalyzeAgain` → `onAnalyze(entryId)`), Remove video from this phone (`canRemoveFromPhone`), then Delete match / Remove from my list (`delete != NONE`).
- Swipe: `delete != NONE && canRemoveFromPhone` → label Delete, opens `DeleteChoiceSheet`; `delete != NONE` → label Delete (Remove for LEAVE_SHARE), opens today's single `ConfirmDialog` with today's copy per kind (`VIDEO_MATCH`, `SCORE_MATCH`, `BOUND_MATCH`, `LEAVE_SHARE`); only `canRemoveFromPhone` → label Remove, today's "Remove video?" dialog with `videoRemovalMessage(hasAnalysis = item.analyses.isNotEmpty())`; neither → no swipe.
- Delete paths: `VIDEO_MATCH` → `vm.deleteMatch(videoId, entryId = item.entryId)`; `SCORE_MATCH` → `vm.deleteScoreMatch(scoreLogId)`; `BOUND_MATCH` → `vm.deleteBoundMatch(videoId, scoreLogId)`; `LEAVE_SHARE` → `vm.leaveShare(videoId)`; remove → `vm.removeFromPhone(entryId)` (or the local view model's `remove`).
- The failed-run result dialog: keep its behaviour, sourcing the first FAILED, unseen, standalone entry from the entries rather than `LocalVideoRow`s.
- The empty state (no mine, no shared, not refreshing) is unchanged.
- `AuthGate` Route.Home: build `ClipListViewModel` with `localAnalysis.state` and a `StoredResultsReader { ids -> StoredResults(trackIds = ids.filterTo(mutableSetOf(), localAnalysis::hasStoredTrack), skeletonIds = ids.filterTo(mutableSetOf(), localAnalysis::hasStoredSkeleton), clipCounts = ids.associateWith { localAnalysis.storedClips(it).size }.filterValues { it > 0 }) }`. Delete `homeResults`/`LocalRunResults` (their job moved into the view model). Route.Analytics builds the same view model the same way.

- [ ] **Step 8: Build and test**

Run: `./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug` and grep for `BUILD SUCCESSFUL`. Fix lint the build reports. Do not install on any device in this task.

- [ ] **Step 9: Commit**

`git commit -m "feat(android): one match list in the drawer, with where each video is and what it has"`. The body names the delete behaviour change (a merged row's delete also discards the file) and the dropped description line.

---

### Task 4: Android Analytics picker on the same rows

**Files:**
- Modify: `androidApp/.../analytics/AnalyticsRows.kt`, `AnalyticsScreen.kt`, `AuthGate.kt` (Route.Analytics block)
- Test: `androidApp/src/test/.../analytics/AnalyticsRowsTest.kt` (retarget)
- Delete: `cliplist/MatchRow.kt` and `MatchRowMergeTest.kt`, `ClipListState.ownedRows`, the view model's `attachStatuses`, if not already gone

**Interfaces:**
- Consumes: `ClipListState.matchList`, `ClipListState.storedResults`, `MatchStatusLine`, `toDeviceRun`, existing `affordanceFor(entry, live, progress)`.
- Produces: `internal fun buildAnalyticsRows(matchList: MatchList, localEntries: List<LocalVideoEntry>, liveAnalysisStates: Map<String, LocalAnalysisState>, progressByEntryId: Map<String, AnalyzeProgress>, storedTrackIds: Set<String>): List<AnalyticsRow>`; `AnalyticsRow` gains `val item: MatchListItem`; `enum class AnalyticsGroup(val label: String?) { MINE(null), SHARED("Shared with me") }`.

- [ ] **Step 1: Retarget the tests first.** Rewrite `AnalyticsRowsTest` cases onto the new signature, building `matchList` with `buildMatchList(MatchListInput(...))`. Keep every existing rule the file tests (affordance precedence, legend selection, READY / READY_NO_VIDEO / ANALYSABLE / NOT_ON_DEVICE) and add: a local video analysed in the cloud is exactly one row (it used to rely on `distinctBy`); a shared match lands in `SHARED`. Run: fails to compile.
- [ ] **Step 2: Rewrite `buildAnalyticsRows`.** One row per item, `mine` then `shared`. `trackId = item.heatmapId`; `state = analyticsRowState(hasLocalEntry = item.entryId != null, hasStoredTrack = trackId != null)`; `entryId = item.entryId ?: trackId`; affordance via `affordanceFor` for ANALYSABLE rows exactly as today; `title = item.title`, `subtitle = item.detail`. Remove the `distinctBy` and its comment (the builder guarantees one row per match; say so in one line). Fix the two comments that say a finished cloud run retires its entry (`AnalyticsRows.kt` near `entryId = entry?.id ?: ...`, and `AuthGate.kt` near `trackCandidateIds`): it keeps the entry at ANALYZED since `f96d78c`.
- [ ] **Step 3: Screen.** Groups: MINE draws no section label; SHARED draws "Shared with me". Under each row's subtitle, `MatchStatusLine(row.item)` with 6dp top padding. Keep the READY dot, the legend and the Analyze pill as they are.
- [ ] **Step 4: AuthGate.** Route.Analytics reads `clipListState.matchList` and `clipListState.storedResults.trackIds`; delete its own `trackCandidateIds`/`storedTrackIds` computation.
- [ ] **Step 5: Run** `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`, grep `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** `git commit -m "feat(android): the Analytics picker lists the same matches the drawer does"`

---

### Task 5: iOS drawer on the shared list

**Files:**
- Create: `iosApp/Sources/ClipList/MatchStatusLine.swift`, `iosApp/Sources/ClipList/MatchListRowView.swift`
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift`, `ClipListView.swift`, `MatchGrouping.swift`, `iosApp/Sources/Home/HomeView.swift` (only if wiring changes), `iosApp/Sources/LocalAnalysis/LocalAnalysisRunner.swift` (skeleton id probe if missing)
- Delete from `LocalVideoSection.swift`: `LocalVideoRowView` once unused (keep `LocalPlayerRoute` and anything else still referenced)
- Tests: `iosApp/Tests/MatchListBridgingTests.swift` (new), `iosApp/Tests/MatchGroupingTests.swift` (trim to what remains)
- Run `xcodegen generate --spec iosApp/project.yml --project iosApp` in the real repo after adding/removing files.

**Interfaces:**
- Consumes: `MatchListBuilderKt.buildMatchList(input:)`, `MatchListInput`, `MatchListItem`, `MatchList`, `DeviceRun`, `DeviceRunKind`, `MatchListFilter`, `MatchListFilterKt.showsFilters(mineCount:)`, `MatchListFilterKt.effectiveFilter(chosen:mineCount:)`, `MatchListFilterKt.passes(_:filter:)` (confirm the exact SKIE spelling of the extension function in the generated header), `MatchListCopy.shared`, `statusDescription` not needed (the item carries `accessibilityStatus`).
- Produces for Task 6: `ClipListModel.matchList: MatchList`, `ClipListModel.storedTrackIds: Set<String>`; `struct MatchStatusLine: View { let item: MatchListItem }`.

- [ ] **Step 1: Measure.** In the simulator (the iPhone 17 Pro, which holds a signed-in session per the project notes), measure the drawer's row text column width with the current build (a temporary `GeometryReader` overlay printing its width, reverted afterwards, or the view debugger). Record the number in the commit message. If it is under 127pt, shrink the where-glyphs to 12pt and the chips to 16pt, and say so.
- [ ] **Step 2: Failing bridging test.** `MatchListBridgingTests`: construct a `MatchListInput` from Swift (an ANALYZED `LocalVideoEntry` and two `RallyClip`s with the same id, `currentUserId` set), call `MatchListBuilderKt.buildMatchList(input:)`, assert one `mine` item whose `where_` (or however SKIE names `where`, a Swift keyword) is `.both`. This pins the Swift-facing shape and fails until the model compiles against it.
- [ ] **Step 3: Model.** `ClipListModel` gains `private(set) var matchList = MatchList(mine: [], shared: [])`, `private var deviceRuns: [String: DeviceRun] = [:]`, `private(set) var storedTrackIds: Set<String> = []`, `private var storedSkeletonIds: Set<String> = []`, `private var deviceClipCounts: [String: KotlinInt] = [:]`, and two setters the view calls: `func updateDeviceRuns(_ runs: [String: DeviceRun])` and `func updateStoredResults(tracks:skeletons:clipCounts:)`, each ending in `regroup()`. `regroup()` builds `MatchListInput` from `clips`, `rally.auth.currentUserId()`, `sharerByVideoId`, `metadataByVideoId`, `scoreLogs`, `localEntries`, `progressByEntryId` and the three stored sets, then calls the builder. Keep `owned`/`shared` (`MatchView` and `EditMatchDetailsSheet` read `MatchSummary`); delete `ownedRows`, `attachMap()` and `mergeMatchRows` once Task 6 no longer needs them (if Analytics still reads `ownedRows` at the end of this task, leave them for Task 6 to delete).
  - Map iOS `LocalAnalysisState` to `DeviceRun` in one function next to the existing `LocalVideoStatus` logic: preparing/analysing/cutting → RUNNING with the phase, `.paused(reason)` → PAUSED with the reason text the row shows today ("Paused - keep Shuttl open" or whatever `LocalVideoStatus.rowStatus` produces), `.failed(message)` → FAILED, idle/done → absent. Unit-test it.
  - Stored results: extend `refreshStoredResults` (currently in `ClipListView.swift:669-691`) to probe **entry ids plus every clip video id**, and to read skeletons too (`localAnalysis.storedSkeletonIds(among:)`; add it beside `storedTrackIds(among:)` if it does not exist, using the iOS skeleton store's header check), then call `model.updateStoredResults`. Trigger it on entry-id changes, on clip video-id changes, on settled runs, and on entry stage changes.
  - `deleteMatch(videoId:entryId:)`: on success, when `entryId != nil`, also `rally.localVideos.remove(id:)` and `rally.localAnnotations.removeAllFor(videoId:)` (review focus 4). Add `removeFromPhone(entryId:)` mirroring Android's (free the video, drop annotations), reusing whatever the current swipe-remove path calls.
- [ ] **Step 4: Status line and row.** SF Symbols: phone `iphone`, cloud `cloud`, rallies `film`, heatmap `flame`, skeleton `figure.stand`, score `number`. Add each name to `SystemImageTests` (it validates symbol names). `MatchStatusLine`: bare where-symbols at 12pt in `Shuttl.textSecondary`, a 1x12 divider in the border token, analysis chips 18x18 with 6pt corner radius, `Shuttl.accent.opacity(0.16)` fill, 11pt symbol in `Shuttl.accent`; spinner for RUNNING; non-error status text in `.caption` secondary; `.accessibilityElement(children: .ignore).accessibilityLabel(item.accessibilityStatus)`. `MatchListRowView` mirrors Android's row: thumbnail (local via the existing `LocalThumbnails`, cloud via `AsyncImage` of `try? await rally.media.signedThumbnailUrl(clip:)`, score tile, placeholder tile), title, detail, players, sharedBy, status line, failure line, full-width action pill (reuse the existing pill button component the Analyze button uses), trailing `Menu` with the same items in the same order as Android.
- [ ] **Step 5: The list.** In `MatchesList.content`, replace the "On this phone" and "My matches" sections with: the filter chips + info button row (when `showsFilters`), `@State var filter: MatchListFilter = .all` reset to `.all` in `.onChange(of: showsFilters)`, the filtered `mine` rows, the empty-filter line, then "Shared with me". Raised rows use `Shuttl.bgTertiary` with the large radius, quiet rows keep today's style. Swipe actions follow the same three-way rule as Android (choice via `.confirmationDialog` with the two choices and `choiceDeleteDetail` as its message; single-kind deletes keep today's dialogs and copy). The legend is a `.sheet` listing `MatchListCopy.shared.LEGEND` with glyphs. Tap routing: `opensLocalPlayer` → `onLocalPlayer`, else `onMatchTap(MatchRoute(scoreLogId:videoId:))`.
- [ ] **Step 6: Trim `MatchGrouping.swift`.** Remove what nothing references (`mergeMatchRows`, `MatchRow`, `ScoreRowContent`, and their tests) only after Task 6 compiles without them; in this task, remove only what the drawer alone used.
- [ ] **Step 7: Build and test** with the Global Constraints recipe. Both suites green; report counts. Confirm the two new files' `.o` exist in the derived data.
- [ ] **Step 8: Commit** `git commit -m "feat(ios): one match list in the drawer, with where each video is and what it has"` (body: measured width, delete behaviour change, dropped description line).

---

### Task 6: iOS Analytics picker on the same rows

**Files:** `iosApp/Sources/Analytics/AnalyticsRows.swift`, `AnalyticsListView.swift`, `iosApp/Tests/AnalyticsRowsTests.swift`; then delete the grouping leftovers named in Task 5 step 6 and their tests.

- [ ] **Step 1:** Retarget `AnalyticsRowsTests` onto `buildAnalyticsRows(matchList:localEntries:progressByEntryId:storedTrackIds:deviceStates:)`, keeping every existing rule and adding the one-row-for-a-cloud-analysed-video and shared-section cases (mirror Task 4 step 1). Run: fails to compile.
- [ ] **Step 2:** Rewrite `buildAnalyticsRows` exactly as Task 4 step 2 describes (Swift port of the same rules; `.paused` affordance stays iOS-only). `AnalyticsGroup` becomes `.mine` (no label) and `.shared` ("Shared with me"). Drop the dedupe.
- [ ] **Step 3:** `AnalyticsListView`: add `MatchStatusLine(item: row.item)` under each subtitle; keep dot, legend, pill.
- [ ] **Step 4:** Delete now-unused grouping code and tests; `xcodegen generate` in the real repo; build and test per the recipe; both suites green.
- [ ] **Step 5: Commit** `git commit -m "feat(ios): the Analytics picker lists the same matches the drawer does"`

---

### Task 7: Verify on the emulator and the simulator, update the design, hand over the phone check

Done by the controller, not a subagent: it drives the emulator and simulator and writes to the published artifact.

- [ ] **Step 1: Android, reproduce the fix.** `assembleDebug`, then `ADB_MDNS=0 ADB_MDNS_OPENSCREEN=0 adb -s emulator-5554 install -r -d androidApp/build/outputs/apk/debug/androidApp-debug.apk` (this also replaces the build with the temporary id-logging line). Plant, via `run-as` into `shared_prefs/rally.xml` with the app force-stopped: the ANALYZED `cloudia` entry (id `3b824d2e-5b6d-4bc8-bf46-c825edac5ac9`) that reproduced the duplicate; an unanalysed local entry pointing at a real pushed video (`adb push` a short `ffmpeg -f lavfi -i testsrc` clip to `/sdcard/Movies`, scan it, use its `content://media/...` id); a FAILED entry with a failure message; and a device-analysed entry (copy an existing `player-tracks/*.track` onto its id if one exists, else skip and say so). Screenshot the drawer: `cloudia` appears once, marked phone + cloud with rallies. Save next to `docs/screenshots/2026-09-25-android-drawer-duplicate-before.png` as `...-after.png`.
- [ ] **Step 2: Android, every surface in both themes.** Light and dark (toggle from Home's overflow menu): the list, each filter (with at least six rows planted so the chips show), the legend sheet, the swipe choice sheet on the merged row, the single delete dialog on a cloud-only row, the Analytics picker. Be picky: alignment of the status line under the title, chip contrast on the raised card in light mode, truncation of long titles, the failure line. Fix what is wrong before moving on.
- [ ] **Step 3: Restore the emulator.** Restore `files/rally.xml.bak` over `shared_prefs/rally.xml` (app force-stopped), then **delete `files/rally.xml.bak`** (it holds a session token), delete planted tracks and pushed media. Confirm `local_videos` is back to `[]`.
- [ ] **Step 4: iOS.** Build to the simulator from the copy; if the session is live, screenshot the drawer and Analytics in light and dark (XCUITest + screenshot polling loop per the project notes); if not, say the iOS UI was verified by tests only and ask the user to sign in.
- [ ] **Step 5: Update the design doc and the published mock.** Edit `docs/plans/2026-09-25-unified-match-list-design.md` to match what shipped (no outlined glyph, menu items kept, description dropped, two bare where-glyphs, US spelling). Update the mock HTML at the scratchpad path it was published from and republish the same file path so the link the boss has stays current.
- [ ] **Step 6: Final whole-branch review and hand-over.** Full Android and iOS test runs, then hand the real-phone check to the user: the phone runs a release build, so a debug install would need an uninstall and lose its data; the check is `./gradlew :androidApp:assembleRelease` installed by the user, with a short list of what to look at.

---

## Follow-ups (not in this change)

- Showing a cloud analysis the phone has not downloaded (needs a download path for matches analysed on another phone, then a `has_pose` field on `list_match_metadata`).
- The on-device rallies match page (`2026-09-11` plan), after which the two row menu items can go.
- Storage size per row and bulk remove.
