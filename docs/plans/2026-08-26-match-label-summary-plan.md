# Match Label Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the match rallies page a summary of how the match was tagged: how many labelled notes it carries, which labels dominate, and which rally drew the most labels.

**Architecture:** One pure aggregation in `shared/commonMain` turns a video's clips plus its annotations into a `MatchLabelSummary`, so Android and iOS cannot disagree about counting rules. One new repository read fetches annotations for many clips at once. Each platform renders a strip at the top of the rally list and a sheet behind it.

**Tech Stack:** Kotlin Multiplatform (kotlinx-serialization, kotlinx-coroutines, supabase-kt 3.5.0), Jetpack Compose / Material 3 on Android, SwiftUI on iOS. Tests: kotlin.test plus kotest matchers on the Kotlin side, XCTest on iOS.

**Spec:** `docs/plans/2026-08-26-match-label-summary-design.md`

## Global Constraints

- No em dash in any prose, comment, commit message or user-facing string. Use a plain dash.
- No agent attribution in commit messages, and no "Generated with" footer.
- A labelled note is an annotation whose `labelName` is non-blank. Body-only notes never count.
- Labels are grouped on `name.trim().lowercase()`. Display name and colour come from the group's most recent annotation by `(createdAt, id)`.
- User-facing copy says "labelled notes", never "notes", so the strip's total is never read as the rally rows' `N NOTES`.
- Singular and plural are both handled: "1 labelled note", "3 labelled notes", "1 label", "6 labels".
- No database migration. This feature is a read over existing tables.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`; it is xcodegen output. Run `cd iosApp && xcodegen generate && cd ..` after adding a Swift file.
- Test commands: `./gradlew :shared:jvmTest`, `./gradlew :androidApp:testDebugUnitTest`, `./gradlew :androidApp:assembleDebug`, and for iOS:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

## File Structure

**Shared (KMP)**
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt` - `LabelCount`, `TopRally`, `MatchLabelSummary`, `buildMatchLabelSummary`, `stripLabelName`. The whole counting contract lives here and nowhere else.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchLabelSummaryTest.kt`.
- Modify `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt` - add `listForClips`.
- Modify `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt`.
- Modify `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt` - add `listForClipsOrNull`.

**Android**
- Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryViewModel.kt` - the view model plus `topRallyName`.
- Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryUi.kt` - `MatchLabelStrip`, `MatchSummarySheet` and their private pieces. Composables only, so the view model file stays unit-testable on the JVM without Compose.
- Create `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchSummaryViewModelTest.kt`.
- Modify `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`.
- Modify `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt` and `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`.

**iOS**
- Create `iosApp/Sources/ClipList/MatchSummaryView.swift` - `MatchLabelStripView`, `MatchSummarySheet` and their private pieces.
- Modify `iosApp/Sources/ClipList/MatchGrouping.swift` - add `topRallyName`.
- Modify `iosApp/Tests/MatchGroupingTests.swift`.
- Modify `iosApp/Sources/ClipList/MatchClipsView.swift`.

**Docs**
- Modify `CHANGELOG.md`.

---

### Task 1: The shared aggregation

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchLabelSummaryTest.kt`

**Interfaces:**
- Consumes: `RallyClip` and `RallyAnnotation` from `com.badmintontracker.shared.model`.
- Produces:
  - `data class LabelCount(val name: String, val colorKey: String?, val count: Int, val sharePercent: Int)`
  - `data class TopRally(val clipId: String, val rallyIndex: Int, val labelCount: Int)`
  - `data class MatchLabelSummary(val labelledNoteCount: Int, val labels: List<LabelCount>, val topRally: TopRally?)` with `val isEmpty: Boolean` and `companion object { val EMPTY }`
  - `fun buildMatchLabelSummary(clips: List<RallyClip>, annotations: List<RallyAnnotation>): MatchLabelSummary`
  - `fun stripLabelName(name: String): String`

This is the only place the counting rules exist. Both platforms call it, so a rule changed here changes both at once, and a rule tested here is tested for both.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchLabelSummaryTest.kt`:

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchLabelSummaryTest {

    private fun clip(id: String, rallyIndex: Int) = RallyClip(
        id = id, videoId = "v1", ownerId = "u", rallyIndex = rallyIndex,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-08-26T12:00:00Z"),
    )

    private fun note(
        id: String,
        clipId: String,
        label: String?,
        color: String? = "green",
        body: String = "",
        createdAt: String = "2026-08-26T12:00:00Z",
    ) = RallyAnnotation(
        id = id, clipId = clipId, timestampSeconds = 1f, body = body,
        labelName = label, labelColor = if (label == null) null else color,
        createdAt = Instant.parse(createdAt),
    )

    private val twoClips = listOf(clip("c1", 1), clip("c2", 2))

    @Test
    fun body_only_notes_are_not_counted() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", label = null, body = "wrong footwork"),
                note("a2", "c1", label = "   ", body = "blank label"),
                note("a3", "c1", label = "Good shot"),
            ),
        )

        summary.labelledNoteCount shouldBe 1
        summary.labels.map { it.name } shouldBe listOf("Good shot")
    }

    @Test
    fun casing_and_whitespace_fold_into_one_label() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c1", "good shot"),
                note("a3", "c2", "  Good shot  "),
            ),
        )

        summary.labels.size shouldBe 1
        summary.labels[0].count shouldBe 3
        summary.labelledNoteCount shouldBe 3
    }

    @Test
    fun display_name_and_colour_come_from_the_most_recent_annotation() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot", color = "green", createdAt = "2026-08-26T12:00:00Z"),
                note("a2", "c1", "Good Shot", color = "teal", createdAt = "2026-08-26T13:00:00Z"),
            ),
        )

        summary.labels[0].name shouldBe "Good Shot"
        summary.labels[0].colorKey shouldBe "teal"
    }

    @Test
    fun a_tie_on_created_at_breaks_on_id_so_the_colour_is_never_row_order_dependent() {
        val sameInstant = "2026-08-26T12:00:00Z"
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a2", "c1", "Good shot", color = "teal", createdAt = sameInstant),
                note("a1", "c1", "Good shot", color = "green", createdAt = sameInstant),
            ),
        )

        summary.labels[0].colorKey shouldBe "teal"
    }

    @Test
    fun labels_sort_by_count_then_by_name() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Zebra"), note("a2", "c1", "Zebra"),
                note("a3", "c1", "alpha"), note("a4", "c1", "alpha"),
                note("a5", "c2", "Mid"), note("a6", "c2", "Mid"), note("a7", "c2", "Mid"),
            ),
        )

        summary.labels.map { it.name } shouldBe listOf("Mid", "alpha", "Zebra")
    }

    @Test
    fun share_percent_rounds_half_up_and_is_not_normalised_to_a_hundred() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Four"), note("a2", "c1", "Four"),
                note("a3", "c1", "Four"), note("a4", "c1", "Four"),
                note("a5", "c2", "One"),
                note("a6", "c2", "Other"),
            ),
        )

        summary.labels.map { it.sharePercent } shouldBe listOf(67, 17, 17)
    }

    @Test
    fun top_rally_is_the_clip_with_the_most_labelled_notes() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c2", "Good shot"),
                note("a3", "c2", "Unforced error", color = "red"),
            ),
        )

        summary.topRally shouldBe TopRally(clipId = "c2", rallyIndex = 2, labelCount = 2)
    }

    @Test
    fun a_tie_on_label_count_breaks_on_the_lower_rally_index() {
        val summary = buildMatchLabelSummary(
            listOf(clip("c1", 5), clip("c2", 2)),
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c2", "Good shot"),
            ),
        )

        summary.topRally!!.clipId shouldBe "c2"
    }

    @Test
    fun annotations_for_clips_outside_the_match_are_ignored() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "pruned-clip", "Good shot"),
            ),
        )

        summary.labelledNoteCount shouldBe 1
        summary.labels.sumOf { it.count } shouldBe summary.labelledNoteCount
    }

    @Test
    fun empty_input_is_an_empty_summary_not_a_null() {
        val summary = buildMatchLabelSummary(emptyList(), emptyList())

        summary.labelledNoteCount shouldBe 0
        summary.labels.shouldBeEmpty()
        summary.topRally.shouldBeNull()
        summary.isEmpty shouldBe true
    }

    @Test
    fun strip_label_name_leaves_short_names_alone_and_truncates_long_ones() {
        stripLabelName("Unforced error") shouldBe "Unforced error"
        stripLabelName("Backhand clear too short") shouldBe "Backhand clear…"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*MatchLabelSummaryTest*"`
Expected: FAIL, unresolved reference `buildMatchLabelSummary`.

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt`:

```kotlin
package com.badmintontracker.shared.model

import kotlin.math.roundToInt

/**
 * One label's share of a match. [sharePercent] is rounded here rather than on
 * each platform so Android and iOS can never show 45% and 46% for the same row.
 */
data class LabelCount(
    val name: String,
    val colorKey: String?,
    val count: Int,
    val sharePercent: Int,
)

/** The rally carrying the most labelled notes in a match. */
data class TopRally(
    val clipId: String,
    val rallyIndex: Int,
    val labelCount: Int,
)

/**
 * How a match was tagged. Built by [buildMatchLabelSummary], which owns every
 * counting rule so the two platforms cannot drift apart on them.
 *
 * Counts labelled notes, not notes. `rally_clips.annotation_count`, which drives
 * the "N NOTES" line and the most-notes sort, counts every annotation, and the
 * schema deliberately allows one with a body and no label. The two numbers
 * differ on real data, which is why every string built from this says
 * "labelled notes".
 */
data class MatchLabelSummary(
    val labelledNoteCount: Int,
    val labels: List<LabelCount>,
    val topRally: TopRally?,
) {
    val isEmpty: Boolean get() = labelledNoteCount == 0

    companion object {
        val EMPTY = MatchLabelSummary(labelledNoteCount = 0, labels = emptyList(), topRally = null)
    }
}

/**
 * Rolls one match's annotations up into a [MatchLabelSummary]. [clips] must
 * already be filtered to the match; annotations naming a clip outside it are
 * ignored, so [MatchLabelSummary.labelledNoteCount] always equals the sum of
 * [MatchLabelSummary.labels] counts.
 *
 * Labels group on the trimmed, lowercased name. The name is a snapshot taken
 * when the note was made, so it is the only identity available, and folding
 * case matches the identity rule the DB already enforces on the live palette
 * (annotation_labels_owner_name_key, on lower(name)). Within a group the newest
 * annotation supplies the display name and colour.
 *
 * Every ordering is fully determined, down to a final tiebreak on the grouping
 * key: a strip that reorders two equal-count labels between refreshes reads as
 * a glitch.
 */
fun buildMatchLabelSummary(
    clips: List<RallyClip>,
    annotations: List<RallyAnnotation>,
): MatchLabelSummary {
    val clipsById = clips.associateBy { it.id }
    val tagged = annotations.mapNotNull { annotation ->
        val label = annotation.labelName?.trim()?.takeIf { it.isNotEmpty() }
        if (label == null || annotation.clipId !in clipsById) null else annotation to label
    }
    if (tagged.isEmpty()) return MatchLabelSummary.EMPTY

    val total = tagged.size
    val labels = tagged
        .groupBy { (_, label) -> label.lowercase() }
        .map { (key, group) ->
            val newest = group.maxWith(compareBy({ it.first.createdAt }, { it.first.id }))
            key to LabelCount(
                name = newest.second,
                colorKey = newest.first.labelColor,
                count = group.size,
                // Double division on purpose: integer division truncates, which
                // would print 16 next to a bar drawn at 17.
                sharePercent = ((group.size * 100.0) / total).roundToInt(),
            )
        }
        .sortedWith(
            compareByDescending<Pair<String, LabelCount>> { it.second.count }
                .thenBy { it.second.name.lowercase() }
                .thenBy { it.first }
        )
        .map { it.second }

    val topRally = tagged
        .groupingBy { (annotation, _) -> annotation.clipId }
        .eachCount()
        .mapNotNull { (clipId, count) ->
            clipsById[clipId]?.let { TopRally(it.id, it.rallyIndex, count) }
        }
        .sortedWith(compareByDescending<TopRally> { it.labelCount }.thenBy { it.rallyIndex })
        .firstOrNull()

    return MatchLabelSummary(labelledNoteCount = total, labels = labels, topRally = topRally)
}

/**
 * The name as the summary strip shows it. Chips sit in a fixed width row beside
 * their counts and a chevron, and a label may be up to 24 characters, so long
 * names are cut here rather than allowed to push the chevron off screen. The
 * sheet always shows the full name.
 *
 * No default argument: Kotlin defaults do not survive into the generated Swift
 * initializer, so a cap passed per platform is a cap that will drift.
 */
fun stripLabelName(name: String): String =
    if (name.length <= STRIP_LABEL_MAX) name
    else name.take(STRIP_LABEL_MAX - 1).trimEnd() + "…"

private const val STRIP_LABEL_MAX = 16
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "*MatchLabelSummaryTest*"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchLabelSummaryTest.kt
git commit -m "feat(shared): roll a match's annotations up into a label summary"
```

---

### Task 2: Reading a match's annotations in one query

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `suspend fun AnnotationsRepository.listForClips(clipIds: List<String>): Result<List<RallyAnnotation>>`
  - `suspend fun AnnotationsRepository.listForClipsOrNull(clipIds: List<String>): List<RallyAnnotation>?` in `SwiftInterop.kt`
  - `FakeAnnotationsRepository.listForClipsError` and `FakeAnnotationsRepository.listForClipsCalls`

`rally_annotations` has no `video_id`; the only link to a video runs through `rally_clips`, and every caller already holds the clip ids. Hence the name. RLS needs no change: `20260506000000_match_shares.sql:56` already grants SELECT to owners and share recipients through `rally_clips`.

- [ ] **Step 1: Write the failing test**

Add to `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt`. Add `shouldBeEmpty` to the imports (`io.kotest.matchers.collections.shouldBeEmpty`), then add this constant beside the existing `twoAnnotations`, and the three tests inside the class:

```kotlin
    private val oneAnnotation = """
      [
        {"id":"a3","clip_id":"c9","timestamp_seconds":2.0,"body":"third",
         "created_at":"2026-05-04T12:00:02Z"}
      ]
    """.trimIndent()

    @Test
    fun listForClips_filters_by_an_in_list() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(twoAnnotations)
        }
        val repo = AnnotationsRepositoryImpl(client)

        val items = repo.listForClips(listOf("c1", "c2")).getOrThrow()

        items shouldHaveSize 2
        capturedUrl!!.shouldContain("rally_annotations")
        // The operator and the ids, not the exact encoding: postgrest
        // percent-encodes the parentheses and comma, and that is not this
        // test's contract.
        capturedUrl!!.shouldContain("clip_id=in.")
        capturedUrl!!.shouldContain("c1")
        capturedUrl!!.shouldContain("c2")
    }

    @Test
    fun listForClips_issues_no_request_for_an_empty_list() = runTest {
        var requests = 0
        val client = TestSupabase.client {
            requests++
            jsonResponse("[]")
        }
        val repo = AnnotationsRepositoryImpl(client)

        repo.listForClips(emptyList()).getOrThrow().shouldBeEmpty()

        requests shouldBe 0
    }

    @Test
    fun listForClips_chunks_past_a_hundred_ids_and_merges_the_results() = runTest {
        var requests = 0
        val client = TestSupabase.client {
            requests++
            jsonResponse(if (requests == 1) twoAnnotations else oneAnnotation)
        }
        val repo = AnnotationsRepositoryImpl(client)

        val items = repo.listForClips((1..150).map { "c$it" }).getOrThrow()

        requests shouldBe 2
        items shouldHaveSize 3
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationsRepositoryTest*"`
Expected: FAIL, unresolved reference `listForClips`.

- [ ] **Step 3: Add the method to the interface and the implementation**

In `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt`. No new import is needed: `isIn` is a member of the same filter DSL receiver `eq` already comes from. Add to the interface, after `list`:

```kotlin
    /**
     * Every annotation on a set of clips, for match-level rollups. Filters on
     * clip ids rather than a video id because rally_annotations has no video
     * column, and every caller already holds the ids.
     */
    suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>>
```

And to `AnnotationsRepositoryImpl`, after `list`:

```kotlin
    override suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>> =
        runCatching {
            // An empty in.() is both pointless and malformed, so short-circuit
            // rather than issue it.
            if (clipIds.isEmpty()) return@runCatching emptyList()
            clipIds.distinct().chunked(CLIP_ID_CHUNK).flatMap { chunk ->
                client.postgrest.from("rally_annotations")
                    .select { filter { isIn("clip_id", chunk) } }
                    .decodeList<RallyAnnotation>()
            }
        }
```

No ordering is requested: the aggregation groups and sorts, and every tiebreak it makes is on a field the row carries.

At the bottom of the file, outside the class:

```kotlin
/** Guard against URL length on a long match, not a normal path. */
private const val CLIP_ID_CHUNK = 100
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationsRepositoryTest*"`
Expected: PASS. If it fails to compile on `isIn`, check the import: it is a member of the filter DSL receiver, verified present in supabase-kt 3.5.0 as `isIn(column: String, values: List<Any>)`.

- [ ] **Step 5: Add the Swift wrapper**

In `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`, below `listMatchMetadataOrNull`:

```kotlin
/** Soft-failing read: nil means "show no summary", never an error banner. */
suspend fun AnnotationsRepository.listForClipsOrNull(clipIds: List<String>): List<RallyAnnotation>? =
    listForClips(clipIds).getOrNull()
```

- [ ] **Step 6: Teach the Android fake the new method**

In `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`, add the two fields beside the existing error fields and the override beside `list`:

```kotlin
    var listForClipsError: Throwable? = null
    val listForClipsCalls = mutableListOf<List<String>>()

    override suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>> {
        listForClipsCalls += clipIds
        listForClipsError?.let { return Result.failure(it) }
        return Result.success(clipIds.flatMap { byClipId[it] ?: emptyList() })
    }
```

- [ ] **Step 7: Run the whole suite to catch any other implementor**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: PASS. A compile error here means another fake implements `AnnotationsRepository`; add the same override to it.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt
git commit -m "feat(shared): read every annotation on a match in one query"
```

---

### Task 3: The Android view model

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchSummaryViewModelTest.kt`

**Interfaces:**
- Consumes: `buildMatchLabelSummary`, `MatchLabelSummary`, `TopRally` (Task 1); `AnnotationsRepository.listForClips` (Task 2); the existing `clipRowTitle(clip: RallyClip, matchTitle: String?)` from `ClipListViewModel.kt`.
- Produces:
  - `class MatchSummaryViewModel(clips: ClipsRepository, annotations: AnnotationsRepository, videoId: String)` with `val summary: StateFlow<MatchLabelSummary?>` and `fun refresh()`
  - `internal fun topRallyName(top: TopRally, clips: List<RallyClip>, matchTitle: String?): String`

`null` on the flow means "nothing to show yet" and an empty summary means "nothing to show, ever". Both hide the strip, deliberately: a skeleton that resolves to nothing is worse than a row that was never there.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchSummaryViewModelTest.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.TopRally
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class MatchSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private fun clip(id: String, rallyIndex: Int, videoId: String = "v1", title: String? = null) =
        RallyClip(
            id = id, videoId = videoId, ownerId = "u", rallyIndex = rallyIndex,
            startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
            clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
            title = title, annotationCount = 0,
            createdAt = Instant.parse("2026-08-26T12:00:00Z"),
        )

    private fun note(id: String, clipId: String, label: String, color: String = "green") =
        RallyAnnotation(
            id = id, clipId = clipId, timestampSeconds = 1f, body = "",
            labelName = label, labelColor = color,
            createdAt = Instant.parse("2026-08-26T12:00:00Z"),
        )

    @Test
    fun builds_a_summary_from_the_videos_own_clips() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply {
            clips.value = listOf(clip("c1", 1), clip("c2", 2), clip("other", 1, videoId = "v2"))
        }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf(
                "c1" to listOf(note("a1", "c1", "Good shot"), note("a2", "c1", "Good shot")),
                "c2" to listOf(note("a3", "c2", "Unforced error", color = "red")),
                "other" to listOf(note("a4", "other", "Good shot")),
            )
        }

        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        val summary = vm.summary.value!!
        summary.labelledNoteCount shouldBe 3
        summary.labels.map { it.name } shouldBe listOf("Good shot", "Unforced error")
        summary.topRally!!.clipId shouldBe "c1"
        annotations.listForClipsCalls.single() shouldBe listOf("c1", "c2")
    }

    @Test
    fun refetches_when_the_clip_set_changes() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()
        vm.summary.value!!.labelledNoteCount shouldBe 1

        annotations.byClipId = annotations.byClipId + ("c2" to listOf(note("a2", "c2", "Good shot")))
        clips.clips.value = listOf(clip("c1", 1), clip("c2", 2))
        advanceUntilIdle()

        vm.summary.value!!.labelledNoteCount shouldBe 2
        annotations.listForClipsCalls.size shouldBe 2
    }

    @Test
    fun a_failed_fetch_leaves_the_previous_summary_on_screen() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        annotations.listForClipsError = RuntimeException("offline")
        vm.refresh()
        advanceUntilIdle()

        vm.summary.value!!.labelledNoteCount shouldBe 1
    }

    @Test
    fun pruning_the_matchs_clips_drops_the_summary() = runTest(dispatcher) {
        val clips = FakeClipsRepository().apply { clips.value = listOf(clip("c1", 1)) }
        val annotations = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to listOf(note("a1", "c1", "Good shot")))
        }
        val vm = MatchSummaryViewModel(clips, annotations, videoId = "v1")
        advanceUntilIdle()

        clips.pruneVideo("v1")
        advanceUntilIdle()

        vm.summary.value shouldBe null
    }

    @Test
    fun top_rally_name_matches_the_row_the_rally_shows_in_the_list() {
        val clips = listOf(clip("c1", 1), clip("c2", 2, title = "The long rally"))

        topRallyName(TopRally("c2", 2, 4), clips, matchTitle = null) shouldBe "The long rally"
        topRallyName(TopRally("c1", 1, 4), clips, matchTitle = null) shouldBe "Rally #1"
    }

    @Test
    fun top_rally_name_falls_back_to_the_rally_number_when_the_clip_is_gone() {
        topRallyName(TopRally("pruned", 7, 4), clips = emptyList(), matchTitle = null) shouldBe "Rally #7"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*MatchSummaryViewModelTest*"`
Expected: FAIL, unresolved reference `MatchSummaryViewModel`.

- [ ] **Step 3: Write the implementation**

Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryViewModel.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.TopRally
import com.badmintontracker.shared.model.buildMatchLabelSummary
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The label rollup for one match. Observes the clip cache itself rather than
 * taking state from [ClipListViewModel], so the two screens' view models stay
 * independent and this one can be tested with two fakes.
 *
 * A null summary means "nothing to show yet"; an empty one means "nothing to
 * show". Both hide the strip, which is why there is no loading flag: a skeleton
 * that resolves to nothing is worse than a row that was never there.
 */
class MatchSummaryViewModel(
    private val clips: ClipsRepository,
    private val annotations: AnnotationsRepository,
    private val videoId: String,
) : ViewModel() {

    private val _summary = MutableStateFlow<MatchLabelSummary?>(null)
    val summary: StateFlow<MatchLabelSummary?> = _summary.asStateFlow()

    private var clipsForVideo: List<RallyClip> = emptyList()
    private var lastFetchedIds: List<String>? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            clips.observeClips().collect { all ->
                clipsForVideo = all.filter { it.videoId == videoId }
                val ids = clipsForVideo.map { it.id }
                if (ids != lastFetchedIds) {
                    lastFetchedIds = ids
                    load()
                }
            }
        }
    }

    /** Re-reads the notes without waiting for the clip set to change. */
    fun refresh() {
        // The init collector already fetches on first composition, and the
        // screen's resume effect fires on that same entry. Without this guard
        // every entry would cancel a healthy in-flight fetch and start it over,
        // delaying the strip for no gain.
        if (loadJob?.isActive == true) return
        load()
    }

    private fun load() {
        // Cancelling the previous job keeps a slow earlier response from landing
        // on top of a newer one.
        loadJob?.cancel()
        val target = clipsForVideo
        if (target.isEmpty()) {
            // The match's clips were pruned, by a delete or a leave-share. Drop
            // the summary rather than leave a rollup of clips that are gone.
            _summary.value = null
            return
        }
        loadJob = viewModelScope.launch {
            annotations.listForClips(target.map { it.id })
                .onSuccess { _summary.value = buildMatchLabelSummary(target, it) }
            // Soft failure: keep whatever is on screen and say nothing. Same
            // contract as the metadata and shares lookups in ClipListViewModel.
            // The rally list underneath is fully usable without a summary.
        }
    }
}

/**
 * Name for the most-labelled rally in the summary sheet. Goes through the same
 * [clipRowTitle] the list row uses, so the sheet and the row can never name one
 * clip two ways. Falls back to the rally number when the clip has left the
 * list, which a prune racing an in-flight fetch can produce.
 */
internal fun topRallyName(top: TopRally, clips: List<RallyClip>, matchTitle: String?): String =
    clips.firstOrNull { it.id == top.clipId }
        ?.let { clipRowTitle(it, matchTitle) }
        ?: "Rally #${top.rallyIndex}"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*MatchSummaryViewModelTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchSummaryViewModelTest.kt
git commit -m "feat(android): hold a match's label rollup in its own view model"
```

---

### Task 4: The Android strip and sheet

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryUi.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt:158-174`

**Interfaces:**
- Consumes: `MatchLabelSummary`, `LabelCount`, `stripLabelName` (Task 1); `MatchSummaryViewModel`, `topRallyName` (Task 3); the existing `com.badmintontracker.android.clipdetail.LabelBadge(name, colorKey, modifier)`.
- Produces: `MatchLabelStrip(summary, onClick, modifier)` and `MatchSummarySheet(summary, topRallyName, onTopRallyClick, onDismiss)`.

`LabelBadge` is reused untouched. It carries an invariant comment about blank names, and folding a count into its `name` would make that pill lie about what it renders, so the count sits beside it.

- [ ] **Step 1: Write the strip and sheet**

Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryUi.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.clipdetail.LabelBadge
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelCount
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.stripLabelName

/** Two chips plus an overflow count is what fits beside the chevron at 360dp. */
private const val STRIP_CHIP_LIMIT = 2

/**
 * The match's top labels, above the rally list. The whole row is one target:
 * a chip that looks tappable but does not filter would promise something this
 * screen does not do.
 */
@Composable
fun MatchLabelStrip(
    summary: MatchLabelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = summary.labels.take(STRIP_CHIP_LIMIT)
    val overflow = summary.labels.size - shown.size
    val description = "Match summary, ${labelledNotes(summary.labelledNoteCount)}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { LabelCountChip(it) }
        if (overflow > 0) {
            Text(
                "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelCountChip(label: LabelCount) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelBadge(name = stripLabelName(label.name), colorKey = label.colorKey)
        Text(
            label.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full breakdown. [topRallyName] is resolved by the caller, which holds the
 * clips, so this stays a pure rendering of the summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchSummarySheet(
    summary: MatchLabelSummary,
    topRallyName: String?,
    onTopRallyClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                labelledNotes(summary.labelledNoteCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            summary.labels.forEach { label ->
                LabelShareRow(label)
                Spacer(Modifier.height(12.dp))
            }
            val top = summary.topRally
            if (top != null && topRallyName != null) {
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTopRallyClick)
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Most labelled · $topRallyName · ${labelCount(top.labelCount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelShareRow(label: LabelCount) {
    val swatch = LabelColor.from(label.colorKey)
    val barColor = swatch?.let { Color(it.background.toInt()) } ?: MaterialTheme.colorScheme.primary
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelBadge(name = label.name, colorKey = label.colorKey)
            Spacer(Modifier.weight(1f))
            Text(
                "${label.count}   ${label.sharePercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    // A label rounding to 0% still gets a visible sliver: the
                    // percentage stays honest, the bar stays present.
                    .fillMaxWidth((label.sharePercent / 100f).coerceIn(0.01f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
    }
}

/** "1 labelled note" / "12 labelled notes". Never "notes": see MatchLabelSummary. */
internal fun labelledNotes(count: Int): String =
    "$count labelled ${if (count == 1) "note" else "notes"}"

/** "1 label" / "6 labels". Named to match iOS's `labelCount`. */
internal fun labelCount(count: Int): String =
    "$count ${if (count == 1) "label" else "labels"}"
```

- [ ] **Step 2: Wire the strip and sheet into the screen**

In `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt`:

Add this import (`MatchSummaryViewModel` and the strip need none, being in this package):

```kotlin
import androidx.lifecycle.compose.LifecycleResumeEffect
```

Add the parameter to `MatchClipsScreen`, after `vm`:

```kotlin
    summaryVm: MatchSummaryViewModel,
```

Add state beside the existing `sheetOpen`, after the `sort` line:

```kotlin
    val summary by summaryVm.summary.collectAsStateWithLifecycle()
    var summarySheetOpen by remember { mutableStateOf(false) }

    // Covers the case the clip-set trigger cannot see: a note added inside
    // ClipDetail and then a back press.
    LifecycleResumeEffect(Unit) {
        summaryVm.refresh()
        onPauseOrDispose { }
    }
```

Change the `PullToRefreshBox` refresh to drive both:

```kotlin
            onRefresh = { vm.refresh(); summaryVm.refresh() },
```

Add the strip as the first item of the `LazyColumn`, above the existing
`match?.description?.let` item:

```kotlin
                    val currentSummary = summary
                    if (currentSummary != null && !currentSummary.isEmpty) {
                        item(key = "match-label-summary") {
                            MatchLabelStrip(
                                summary = currentSummary,
                                onClick = { summarySheetOpen = true },
                            )
                            HorizontalDivider()
                        }
                    }
```

And add the sheet at the end of the composable, beside the existing
`if (sheetOpen) { ShareSheet(...) }`:

```kotlin
    val sheetSummary = summary
    if (summarySheetOpen && sheetSummary != null && !sheetSummary.isEmpty) {
        MatchSummarySheet(
            summary = sheetSummary,
            topRallyName = sheetSummary.topRally?.let {
                topRallyName(it, clipsForMatch, match?.title)
            },
            onTopRallyClick = {
                summarySheetOpen = false
                sheetSummary.topRally
                    ?.let { top -> clipsForMatch.firstOrNull { it.id == top.clipId } }
                    ?.let(onClipClick)
            },
            onDismiss = { summarySheetOpen = false },
        )
    }
```

- [ ] **Step 3: Construct the view model at the route**

In `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`, inside
`composable<Route.MatchClips>`, after the existing `clipListVm`:

```kotlin
                    val summaryVm: MatchSummaryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                MatchSummaryViewModel(rally.clips, rally.annotations, args.videoId)
                            }
                        }
                    )
```

Add `summaryVm = summaryVm,` to the `MatchClipsScreen(...)` call, after `vm = clipListVm,`, and add the import
`import com.badmintontracker.android.cliplist.MatchSummaryViewModel`.

- [ ] **Step 4: Build and run the Android suite**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 5: Check it on a device or emulator**

Open a match that has labelled notes. Confirm: the strip sits above the description with a divider under it, two chips and a `+N` when there are more than two labels, and no strip at all on a match with no labelled notes. Open the sheet, confirm the bars match their percentages, then tap the most-labelled row and confirm it lands on that rally.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchSummaryUi.kt \
        androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git commit -m "feat(android): summarise a match's labels above its rally list"
```

---

### Task 5: The iOS top-rally name

**Files:**
- Modify: `iosApp/Sources/ClipList/MatchGrouping.swift`
- Test: `iosApp/Tests/MatchGroupingTests.swift`

**Interfaces:**
- Consumes: the existing `ClipInfo` and `clipRowTitle(_:matchTitle:)` in the same file.
- Produces: `func topRallyName(clipId: String, rallyIndex: Int32, clips: [ClipInfo], matchTitle: String?) -> String`

Takes plain values rather than the Kotlin `TopRally`, matching why `ClipInfo` exists at all: this file's helpers are testable without Kotlin construction.

- [ ] **Step 1: Write the failing test**

Add to `iosApp/Tests/MatchGroupingTests.swift`, inside the class:

```swift
    func testTopRallyNameMatchesTheRowTheRallyShowsInTheList() {
        let clips = [
            clip(id: "c1", videoId: "v1", rallyIndex: 1, createdAt: 0),
            ClipInfo(
                id: "c2", videoId: "v1", ownerId: "me", rallyIndex: 2,
                createdAtMillis: 0, title: "The long rally", durationSeconds: 10,
                annotationCount: 0
            ),
        ]

        XCTAssertEqual(
            topRallyName(clipId: "c2", rallyIndex: 2, clips: clips, matchTitle: nil),
            "The long rally"
        )
        XCTAssertEqual(
            topRallyName(clipId: "c1", rallyIndex: 1, clips: clips, matchTitle: nil),
            "Rally #1"
        )
    }

    func testTopRallyNameFallsBackToTheRallyNumberWhenTheClipIsGone() {
        XCTAssertEqual(
            topRallyName(clipId: "pruned", rallyIndex: 7, clips: [], matchTitle: nil),
            "Rally #7"
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/MatchGroupingTests
```

Expected: FAIL, cannot find `topRallyName` in scope.

- [ ] **Step 3: Write the implementation**

Add to the end of `iosApp/Sources/ClipList/MatchGrouping.swift`:

```swift
/// Name for the most-labelled rally in the summary sheet. Goes through the same
/// `clipRowTitle` the list row uses, so the sheet and the row can never name one
/// clip two ways. Falls back to the rally number when the clip has left the
/// list, which a prune racing an in-flight fetch can produce.
/// Port of Android's `topRallyName`.
func topRallyName(clipId: String, rallyIndex: Int32, clips: [ClipInfo], matchTitle: String?) -> String {
    guard let clip = clips.first(where: { $0.id == clipId }) else { return "Rally #\(rallyIndex)" }
    return clipRowTitle(clip, matchTitle: matchTitle)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same `xcodebuild test` command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/ClipList/MatchGrouping.swift iosApp/Tests/MatchGroupingTests.swift
git commit -m "feat(ios): name the most-labelled rally the way its row does"
```

---

### Task 6: The iOS strip and sheet

**Files:**
- Create: `iosApp/Sources/ClipList/MatchSummaryView.swift`
- Modify: `iosApp/Sources/ClipList/MatchClipsView.swift`

**Interfaces:**
- Consumes: `MatchLabelSummary`, `LabelCount`, `MatchLabelSummaryKt.buildMatchLabelSummary(clips:annotations:)`, `MatchLabelSummaryKt.stripLabelName(name:)` (Task 1); `SwiftInteropKt.listForClipsOrNull(_:clipIds:)` (Task 2); `topRallyName` (Task 5); the existing `LabelBadge` and `Shuttl` tokens.
- Produces: `MatchLabelStripView(summary:)` and `MatchSummarySheet(summary:topRallyName:onTopRally:)`.

- [ ] **Step 1: Write the views**

Create `iosApp/Sources/ClipList/MatchSummaryView.swift`:

```swift
import SwiftUI
import Shared

/// Two chips plus an overflow count is what fits beside the chevron on the
/// narrowest supported screen. Mirrors Android's STRIP_CHIP_LIMIT.
private let stripChipLimit = 2

/// The match's top labels, above the rally list. The whole row is one target:
/// a chip that looks tappable but does not filter would promise something this
/// screen does not do.
struct MatchLabelStripView: View {
    let summary: MatchLabelSummary

    var body: some View {
        let shown = Array(summary.labels.prefix(stripChipLimit))
        let overflow = summary.labels.count - shown.count
        HStack(spacing: 8) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, label in
                LabelCountChip(label: label)
            }
            if overflow > 0 {
                Text("+\(overflow)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Shuttl.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(Shuttl.textSecondary)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Match summary, \(labelledNotes(summary.labelledNoteCount))")
    }
}

private struct LabelCountChip: View {
    let label: LabelCount

    var body: some View {
        HStack(spacing: 4) {
            LabelBadge(
                name: MatchLabelSummaryKt.stripLabelName(name: label.name),
                colorKey: label.colorKey
            )
            Text("\(label.count)")
                .font(.system(size: 11, weight: .medium).monospacedDigit())
                .foregroundStyle(Shuttl.textSecondary)
        }
    }
}

/// The full breakdown. `topRallyName` is resolved by the caller, which holds the
/// clips, so this stays a pure rendering of the summary.
struct MatchSummarySheet: View {
    let summary: MatchLabelSummary
    let topRallyName: String?
    let onTopRally: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(summary.labels.enumerated()), id: \.offset) { _, label in
                    LabelShareRow(label: label)
                }
                if let name = topRallyName, let top = summary.topRally {
                    Button {
                        dismiss()
                        onTopRally()
                    } label: {
                        HStack {
                            Text("Most labelled · \(name) · \(labelCount(top.labelCount))")
                                .font(.subheadline)
                                .foregroundStyle(Shuttl.text)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle(labelledNotes(summary.labelledNoteCount))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct LabelShareRow: View {
    let label: LabelCount

    private var swatch: LabelColor? { LabelColor.companion.from(key: label.colorKey) }
    private var barColor: Color {
        swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.accent
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                LabelBadge(name: label.name, colorKey: label.colorKey)
                Spacer()
                Text("\(label.count)   \(label.sharePercent)%")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Shuttl.textSecondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Shuttl.bgTertiary)
                    // A label rounding to 0% still gets a visible sliver: the
                    // percentage stays honest, the bar stays present.
                    Capsule().fill(barColor)
                        .frame(width: max(geo.size.width * CGFloat(label.sharePercent) / 100, 2))
                }
            }
            .frame(height: 6)
        }
        .padding(.vertical, 4)
    }
}

/// "1 labelled note" / "12 labelled notes". Never "notes": the rally rows'
/// note count includes notes with no label. Mirrors Android's `labelledNotes`.
func labelledNotes(_ count: Int32) -> String {
    "\(count) labelled \(count == 1 ? "note" : "notes")"
}

func labelCount(_ count: Int32) -> String {
    "\(count) \(count == 1 ? "label" : "labels")"
}
```

- [ ] **Step 2: Wire it into the match page**

In `iosApp/Sources/ClipList/MatchClipsView.swift`:

Add state beside the existing `@State` properties:

```swift
    @State private var summary: MatchLabelSummary? = nil
    @State private var summarySheetOpen = false
    @State private var isLoadingSummary = false
    @State private var route: ClipRoute? = nil
```

Add these below the `sortedClips` computed property:

```swift
    private var clipIds: [String] { clips.map(\.id) }

    private func refreshSummary() async {
        // .task and .onAppear both fire on the first appearance; one fetch is enough.
        guard !isLoadingSummary, !clips.isEmpty else { return }
        isLoadingSummary = true
        defer { isLoadingSummary = false }
        let fetched = try? await SwiftInteropKt.listForClipsOrNull(rally.annotations, clipIds: clipIds)
        // Soft failure: keep whatever is on screen and say nothing.
        guard let rows = fetched.flatMap({ $0 }) else { return }
        summary = MatchLabelSummaryKt.buildMatchLabelSummary(clips: clips, annotations: rows)
    }
```

Add the route wrapper at file scope, below the struct:

```swift
/// `navigationDestination(item:)` needs an Identifiable, and the sheet's
/// "most labelled" row pushes a clip programmatically rather than through a
/// NavigationLink.
private struct ClipRoute: Identifiable, Hashable {
    let id: String
}
```

Add the strip as the first row of the `List`, above the description row:

```swift
            if let summary, !summary.isEmpty {
                Button { summarySheetOpen = true } label: {
                    MatchLabelStripView(summary: summary)
                }
                .buttonStyle(.plain)
            }
```

Replace the existing `.refreshable` and add the three new modifiers beside it:

```swift
        .refreshable {
            try? await rally.clips.refresh()
            await refreshSummary()
        }
        .task(id: clipIds) { await refreshSummary() }
        // Pushing ClipDetailView does not remove this view, so its .task is not
        // restarted on the way back, and adding a note does not change clipIds.
        // Without this the strip would go stale exactly where it matters most.
        .onAppear { Task { await refreshSummary() } }
        .sheet(isPresented: $summarySheetOpen) {
            if let summary {
                MatchSummarySheet(
                    summary: summary,
                    topRallyName: summary.topRally.map {
                        topRallyName(
                            clipId: $0.clipId, rallyIndex: $0.rallyIndex,
                            clips: clips.map(ClipInfo.init), matchTitle: matchName
                        )
                    },
                    onTopRally: {
                        if let top = summary.topRally { route = ClipRoute(id: top.clipId) }
                    }
                )
                .presentationDetents([.medium, .large])
            }
        }
        .navigationDestination(item: $route) { route in
            ClipDetailView(rally: rally, clipId: route.id)
        }
```

- [ ] **Step 3: Regenerate the Xcode project and build**

```bash
cd iosApp && xcodegen generate && cd ..
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: BUILD SUCCEEDED and all tests pass. Do not hand-edit `project.pbxproj`.

One thing to check rather than assume at this step: that the generated Swift
name really is `MatchLabelSummaryKt.stripLabelName(name:)`. Kotlin/Native can
mangle a parameter label that collides with the function's own first argument
label. If it does, rename the Kotlin parameter (to `label`, say) and update both
call sites; do not paper over it with a wrapper.

- [ ] **Step 4: Check it in the simulator**

Same checks as Android, plus the one that motivated the `.onAppear`: open a rally from the list, add a labelled note, go back, and confirm the strip's total goes up without a pull to refresh. Signing in on the simulator needs the user, since the session is Keychain only.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/ClipList/MatchSummaryView.swift \
        iosApp/Sources/ClipList/MatchClipsView.swift \
        iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): summarise a match's labels above its rally list"
```

---

### Task 7: Changelog and full verification

**Files:**
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code-facing.

- [ ] **Step 1: Add the changelog entry**

In `CHANGELOG.md`, add to the top of the existing `## [Unreleased]` / `### Added` list:

```markdown
- Label summary on the match rallies page, on both platforms. A strip above the
  rally list shows the match's most-used labels with their counts; tapping it
  opens the full breakdown, with every label's share of the match and a link
  into the rally carrying the most labels. Counts labelled notes only, so it
  can differ from the per-rally note counts, which include notes with no label.
  Labels tally case-insensitively by name, so renaming one does not split it in
  two. Matches with no labelled notes show no strip.
```

- [ ] **Step 2: Run every test on both platforms**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Then:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: all green. Report any failure with its output rather than moving on.

- [ ] **Step 3: Run the shared-match check**

Share a match that has labelled notes with a second account, open it as the
recipient, and confirm the strip shows a tally combining both accounts' notes.
This is the run that exercises the RLS assumption the whole design rests on: no
policy was changed, on the reading that `annotations: select own or shared`
already covers it. If no second account is available, record it in the design
doc as a known unverified edge rather than dropping it quietly.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: note the match label summary in the changelog"
```
