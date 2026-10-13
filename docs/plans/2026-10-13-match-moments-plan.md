# Match Moments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a coach log labels and notes on the scoring board without scoring. These are "moments": logged while the label row has no rally to tag, then shown on the match page, in the tally and in the export.

**Architecture:** A moment is a new `ScoreEvent.Moment` in the match's append-only log. It carries its own id, an explicit `afterPoints` anchor and a timestamp. The fold resolves moments into `MatchState.moments`, and nothing that edits the score removes one. Every rule lives in `shared`: what the row targets, the caption, the strip's notices, the timeline, the tally, the export and the score line. Android and iOS only render those rules and dispatch taps.

**Tech Stack:** Kotlin Multiplatform (`shared`), kotlinx.serialization 1.10, kotlinx-datetime 0.6.1, kotest assertions, Jetpack Compose (release builds resolve Compose Foundation **1.7.6**), SwiftUI with SKIE, XCTest.

**Spec:** `docs/plans/2026-10-13-match-moments-design.md`

## Global Constraints

- Branch `feat/match-moments`, stacked on `feat/heatmap-rallies` (PR #15). Do not rebase onto main.
- The app is not in production: no compatibility layer, no tolerant decoder, no migration.
- The wire names are a schema: `"type":"moment"`, `id`, `after_points`, `logged_at`, `tags`, `comment`.
- Every rule and every user-facing sentence in this plan comes from `shared`, except the match page's empty state, which stays platform copy as it is today.
- Copy, verbatim:
  - captions: "Tap a side to score, or a label to log a moment", "1 moment logged", "N moments logged", "Match over · N moments logged", "New moment", "New moment: A, B";
  - strip notices: "Moment added: A, B", "Moment added", "Moment removed: A", "Moment removed", with actions "Undo" and "Redo";
  - controls: menu item "Remove last moment", placeholder "Note on this moment";
  - match page: score line "N moments" / "1 moment", tally headline "N rallies tagged · N moments", button "Resume", moment row label "Moment", empty state "Nothing logged yet" / "Points you score and moments you log are listed here.";
  - export: "At 19:42: Good shot (note)".
- Clock times use the 24-hour `HH:mm` form, matching `defaultMatchTitle`.
- No em dashes in code comments, copy or commit messages. Use " - ".
- Compose: nothing from Foundation 1.8+. In particular no `FlowRow(itemVerticalAlignment = …)`, and only `HapticFeedbackType.LongPress` / `TextHandleMove` exist.
- Swift sees no Kotlin default arguments. Every shared function Swift calls has an overload without defaults.
- Commits: no Co-Authored-By trailer.
- Every build or test command needs:
  - `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
  - for iOS, also `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.

## Review Focus

1. **A moment logged with the composer open while the coach scores.** The composer must save first, with the pre-score anchor, and close. The score must land as a rally, and the field must not reopen as a rally note. Pinned in Task 6 (`scoring_with_the_composer_open_saves_it_first_and_closes_it`) and Task 9.
2. **Note-only moments.** A composer with text and no label is a moment, and an empty one is not. Whitespace-only text counts as empty. Pinned in Task 2 (`an_empty_draft_logs_nothing`, `a_note_alone_is_a_moment`).
3. **A label renamed or deleted after use.** Moments keep their snapshot. In composer mode the chip's ✓ matches by name, as rally tags do. Pinned in Task 2 (`a_moment_snapshots_the_label`).
4. **Undo to zero rallies with moments present.** The row must fall back to moments, not to a dead row, and the SERVE switch must come back. Pinned in Task 6 (`undoing_the_only_rally_points_the_row_back_at_moments`).
5. **Large text.** The strip notice and the two-line caption at Android font 2x and iOS AX5 must truncate cleanly and never overlap Edit score or Note. Pinned by the device pass in Task 12, because no unit test can see it.

---

## File structure

**shared (`shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/`)**

| File | Change | Responsibility |
| --- | --- | --- |
| `ScoreEvent.kt` | modify | `ScoreEvent.Moment`; `dropPointsFrom` keeps moments |
| `MatchState.kt` | modify | `LoggedMoment`; `MatchState.moments`; fold resolution |
| `Moments.kt` | **create** | `MomentDraft`, `LoggedMoment.toEvent()`, `clockTime` |
| `MatchScorer.kt` | modify | log, compose, remove, restore moments; first-server rule |
| `ScoreLogsRepository.kt` | modify | `now` becomes `internal` so the scorer stamps with the store's clock |
| `ScoreEdit.kt` | modify | re-anchor moments inside the edited game |
| `BoardPresentation.kt` | **create** | `BoardTarget`, `boardTarget`, `boardCaption`, `BoardNotice` and its message and action |
| `MatchTimeline.kt` | **create** | `TimelineEntry`, `matchTimeline` |
| `ScoreTagSummary.kt` | modify | `momentCount`, `headline`, export with moments |
| `ScoreMatchCard.kt` | modify | `scoreLine` with moments, `scoreButtonLabel` |

**Android (`androidApp/src/main/java/com/badmintontracker/android/`)**

| File | Change |
| --- | --- |
| `scoring/ScoringViewModel.kt` | target, draft, notice, moment actions |
| `scoring/ScoringScreen.kt` | live chips, flash and haptic, composer, strip notice, menu item, caption from state |
| `match/PointsFacet.kt` | timeline rows, moment row, wrapping badges and tally, shared headline and button |
| `match/MatchScreen.kt` | pass the timeline |

**iOS (`iosApp/Sources/`)**

| File | Change |
| --- | --- |
| `Scoring/ScoringModel.swift` | mirror of the view model |
| `Scoring/ScoringView.swift` | mirror of the screen |
| `Match/PointsFacet.swift` | mirror of the Points facet |
| `Match/MatchView.swift` | pass the timeline |

**Tests**

- `shared/src/commonTest/.../scoring/`:
  - create `MomentsTest.kt` and `BoardPresentationTest.kt`;
  - extend `ScoreEditTest.kt`, `ScoreTagSummaryTest.kt` and `ScoreMatchCardTest.kt`.
- `androidApp/src/test/.../scoring/ScoringViewModelTest.kt`.
- `iosApp/Tests/ScoringModelTests.swift`.
- `iosApp/UITests/ScoringBoardUITests.swift`.

---

### Task 1: The moment event and the fold

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt` (the sealed interface and `dropPointsFrom`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt` (`MatchState`, `foldMatchState`)
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/Moments.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MomentsTest.kt`

**Interfaces:**
- Produces:
  - `ScoreEvent.Moment(id: String, afterPoints: Int, loggedAt: Instant, tags: List<PointTag>, comment: String?)`;
  - `LoggedMoment(id, afterPoints, loggedAt, tags, comment)`;
  - `MatchState.moments: List<LoggedMoment>`, sorted by `(afterPoints, loggedAt)`, tombstones removed, anchors clamped;
  - `internal fun LoggedMoment.toEvent(): ScoreEvent.Moment`;
  - `fun clockTime(at: Instant, zone: TimeZone): String` and `fun clockTime(at: Instant): String`.

- [ ] **Step 1: Write the failing tests**

Create `MomentsTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MomentsTest {

    private val t0 = Instant.parse("2026-10-13T18:00:00Z")
    private val rules = ScoringRules.BWF_21
    private val setup = MatchSetup(firstServer = Side.HOME)
    private val good = PointTag("Good shot", "green")
    private val net = PointTag("Net", "slate")

    private fun moment(id: String, after: Int, at: Instant = t0, tags: List<PointTag> = listOf(good), comment: String? = null) =
        ScoreEvent.Moment(id = id, afterPoints = after, loggedAt = at, tags = tags, comment = comment)

    private fun fold(vararg events: ScoreEvent) = foldMatchState(rules, setup, events.toList())

    @Test
    fun a_moment_folds_without_touching_the_score() {
        val state = fold(moment("m1", 0))
        state.points.shouldBeEmpty()
        state.currentGame shouldBe SideScore.ZERO
        state.server shouldBe Side.HOME
        state.moments.map { it.id } shouldBe listOf("m1")
        state.moments.single().tags shouldBe listOf(good)
    }

    @Test
    fun the_last_version_of_a_moment_wins() {
        val state = fold(moment("m1", 0), moment("m1", 0, tags = listOf(net), comment = "short"))
        state.moments.single().tags shouldBe listOf(net)
        state.moments.single().comment shouldBe "short"
    }

    @Test
    fun a_version_with_nothing_on_it_removes_the_moment() {
        fold(moment("m1", 0), moment("m1", 0, tags = emptyList(), comment = null)).moments.shouldBeEmpty()
    }

    @Test
    fun a_blank_comment_alone_is_nothing_on_it() {
        fold(moment("m1", 0, tags = emptyList(), comment = "   ")).moments.shouldBeEmpty()
    }

    @Test
    fun an_anchor_past_the_last_rally_clamps_to_it() {
        // Logged after rally 2, and undo then took rally 2 back.
        val state = fold(ScoreEvent.PointTo(Side.HOME), moment("m1", 2))
        state.moments.single().afterPoints shouldBe 1
    }

    @Test
    fun moments_are_ordered_by_place_then_time() {
        val state = fold(
            ScoreEvent.PointTo(Side.HOME),
            moment("late", 0, at = Instant.parse("2026-10-13T18:05:00Z")),
            moment("early", 0, at = Instant.parse("2026-10-13T18:01:00Z")),
            moment("after", 1, at = Instant.parse("2026-10-13T18:00:30Z")),
        )
        state.moments.map { it.id } shouldBe listOf("early", "late", "after")
    }

    @Test
    fun a_moment_after_match_point_still_folds() {
        val oneGame = ScoringRules(pointsToWin = 1, winBy = 1, cap = null, gamesToWin = 1, intervalAt = null, changeEndsAt = null)
        val state = foldMatchState(oneGame, setup, listOf(ScoreEvent.PointTo(Side.AWAY), moment("m1", 1)))
        state.winner shouldBe Side.AWAY
        state.moments.map { it.id } shouldBe listOf("m1")
    }

    @Test
    fun undo_and_reset_keep_moments() {
        val events = listOf(ScoreEvent.PointTo(Side.HOME), moment("m1", 1), ScoreEvent.PointTo(Side.AWAY))
        events.undoLastPoint() shouldBe listOf(ScoreEvent.PointTo(Side.HOME), moment("m1", 1))
        events.dropPointsFrom(0) shouldBe listOf(moment("m1", 1))
    }

    @Test
    fun a_moment_is_stored_under_its_wire_names() {
        val json = Json.encodeToString(ScoreEvent.serializer(), moment("m1", 3, comment = "short"))
        json shouldBe """{"type":"moment","id":"m1","after_points":3,"logged_at":"2026-10-13T18:00:00Z",""" +
            """"tags":[{"label_name":"Good shot","label_color":"green"}],"comment":"short"}"""
        Json.decodeFromString(ScoreEvent.serializer(), json) shouldBe moment("m1", 3, comment = "short")
    }

    @Test
    fun clock_time_is_the_24_hour_wall_clock() {
        clockTime(Instant.parse("2026-10-13T07:05:00Z"), TimeZone.UTC) shouldBe "07:05"
        clockTime(Instant.parse("2026-10-13T19:42:00Z"), TimeZone.of("Europe/Amsterdam")) shouldBe "21:42"
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*MomentsTest*"`
Expected: compilation failure, `Unresolved reference: Moment`.

- [ ] **Step 3: Add the event**

In `ScoreEvent.kt`, add `import kotlinx.datetime.Instant` and, after `Retire`, inside the sealed interface:

```kotlin
    /**
     * A label or a note, or both, logged when the row had no rally to put it on:
     * a match nobody is scoring, the start of a game, after the match is finished.
     * Not part of the score, so nothing that edits the score removes one.
     *
     * [afterPoints] is how many rallies had been played, which is its place among
     * them. Explicit rather than read off its position in the list, for the same
     * reason a tag names its rally by ordinal: the score editor rewrites the list.
     *
     * A later Moment with the same [id] replaces this one, and one with no tags and
     * no comment removes it, so changing or removing a moment is an append.
     */
    @Serializable
    @SerialName("moment")
    data class Moment(
        val id: String,
        @SerialName("after_points") val afterPoints: Int,
        @SerialName("logged_at") val loggedAt: Instant,
        val tags: List<PointTag>,
        val comment: String?,
    ) : ScoreEvent
```

In `dropPointsFrom`, add the branch. A moment is not part of the score, so no cut takes it:

```kotlin
            is ScoreEvent.Retire -> Unit
            // Not part of the score, so no cut takes it. The fold clamps its anchor.
            is ScoreEvent.Moment -> kept += event
```

Update the KDoc on `undoLastPoint` and `dropPointsFrom` to say moments survive.

- [ ] **Step 4: Fold moments**

In `MatchState.kt`, add after `ScoredPoint`:

```kotlin
/** One moment as the fold resolved it: its latest version, placed among the rallies. */
data class LoggedMoment(
    val id: String,
    /** Rallies played before it. Never more than the match has. */
    val afterPoints: Int,
    val loggedAt: Instant,
    val tags: List<PointTag>,
    val comment: String?,
)
```

Add `import kotlinx.datetime.Instant`. Add a property to `MatchState`, after `points`:

```kotlin
    /** Labels and notes logged without a rally, in order among the rallies. See [ScoreEvent.Moment]. */
    val moments: List<LoggedMoment>,
```

In `foldMatchState`, beside `tagsByOrdinal`:

```kotlin
    // The same rule as tags: the last version of each moment wins.
    val latestMoments = LinkedHashMap<String, ScoreEvent.Moment>()
    for (event in events) if (event is ScoreEvent.Moment) latestMoments[event.id] = event
```

In the `when (event)`, add `is ScoreEvent.Moment -> Unit                         // resolved above and below`.

Before `return MatchState(`:

```kotlin
    // Read apart from the loop's break, so a moment logged after match point folds.
    // Sorted with a stable sort, so moments at the same place and time keep the
    // order they were first logged in.
    val moments = latestMoments.values
        .filter { it.tags.isNotEmpty() || !it.comment.isNullOrBlank() }
        .map { LoggedMoment(it.id, it.afterPoints.coerceIn(0, points.size), it.loggedAt, it.tags, it.comment) }
        .sortedWith(compareBy({ it.afterPoints }, { it.loggedAt }))
```

Pass `moments = moments,` to the constructor.

- [ ] **Step 5: Create `Moments.kt`**

```kotlin
package com.badmintontracker.shared.scoring

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The event that would write [this] back, for putting a removed moment back. */
internal fun LoggedMoment.toEvent(): ScoreEvent.Moment =
    ScoreEvent.Moment(id = id, afterPoints = afterPoints, loggedAt = loggedAt, tags = tags, comment = comment)

/**
 * "19:42". The 24 hour clock for the same reason [defaultMatchTitle] uses it: a
 * moment's time is read beside rally scores, not as a date in the phone's locale.
 */
fun clockTime(at: Instant, zone: TimeZone): String {
    val local = at.toLocalDateTime(zone)
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

/** [clockTime] in the phone's own zone. An overload, not a default: Swift sees no defaults. */
fun clockTime(at: Instant): String = clockTime(at, TimeZone.currentSystemDefault())
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew --offline :shared:jvmTest --tests "*MomentsTest*" --tests "*MatchStateTest*" --tests "*ScoreEventTest*"`
Expected: PASS. Then run all of `:shared:jvmTest` and expect PASS (the new `MatchState` parameter is only ever built by the fold).

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/Moments.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MomentsTest.kt
git commit -m "feat(scoring): a moment in the match log, folded beside the rallies and kept by every cut"
```

---

### Task 2: Logging, composing, removing and restoring moments

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt:41` (`private val now` becomes `internal val now`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/Moments.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchScorerTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `data class MomentDraft(tags: List<PointTag>, text: String, openedAt: Instant, afterPoints: Int)`, with `isEmpty`, `toggle(tag: PointTag)` and `withText(text: String)`;
  - on `MatchScorer`:
    - `logMoment(tags: List<PointTag>): LoggedMoment?`
    - `openMomentDraft(): MomentDraft?`
    - `commitMomentDraft(draft: MomentDraft): LoggedMoment?`
    - `removeMoment(id: String): LoggedMoment?`
    - `restoreMoment(moment: LoggedMoment)`
    - `lastMoment(): LoggedMoment?`
  - `canSwitchFirstServer()` stays true while only moments are logged.

- [ ] **Step 1: Write the failing tests**

Append to `MatchScorerTest.kt`. Reuse its existing fixture and look at its top before writing. If it has no helper returning `(repo, scorer)`, add this one:

```kotlin
    private fun momentBoard(): Pair<ScoreLogsRepository, MatchScorer> {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-10-13T18:00:00Z") }, ownerId = { "owner-1" })
        val log = repo.create(title = "Club night", rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME))
        return repo to MatchScorer(repo, log.id)
    }

    private val good = PointTag("Good shot", "green")
    private val net = PointTag("Net", "slate")

    @Test
    fun a_quick_moment_is_logged_after_the_rallies_played_so_far() {
        val (_, scorer) = momentBoard()
        scorer.score(Side.HOME)
        val m = scorer.logMoment(listOf(good))!!
        m.afterPoints shouldBe 1
        m.tags shouldBe listOf(good)
        m.comment shouldBe null
        m.loggedAt shouldBe Instant.parse("2026-10-13T18:00:00Z")
        scorer.state()!!.moments shouldBe listOf(m)
    }

    @Test
    fun every_quick_moment_is_its_own() {
        val (_, scorer) = momentBoard()
        scorer.logMoment(listOf(good))
        scorer.logMoment(listOf(good))
        scorer.state()!!.moments.size shouldBe 2
    }

    @Test
    fun a_moment_snapshots_the_label() {
        val (_, scorer) = momentBoard()
        scorer.logMoment(listOf(PointTag("Good shot", "green")))
        // A later rename on the Labels screen is a different PointTag; the logged one is unchanged.
        scorer.state()!!.moments.single().tags.single() shouldBe PointTag("Good shot", "green")
    }

    @Test
    fun a_draft_toggles_labels_and_commits_as_one_moment() {
        val (_, scorer) = momentBoard()
        val draft = scorer.openMomentDraft()!!.toggle(good).toggle(net).toggle(good).withText("  short  ")
        val m = scorer.commitMomentDraft(draft)!!
        m.tags shouldBe listOf(net)
        m.comment shouldBe "short"
        scorer.state()!!.moments.single() shouldBe m
    }

    @Test
    fun a_note_alone_is_a_moment() {
        val (_, scorer) = momentBoard()
        scorer.commitMomentDraft(scorer.openMomentDraft()!!.withText("backhand clear"))!!.comment shouldBe "backhand clear"
    }

    @Test
    fun an_empty_draft_logs_nothing() {
        val (repo, scorer) = momentBoard()
        val id = repo.logs.value.single().id
        scorer.commitMomentDraft(scorer.openMomentDraft()!!.withText("   ")) shouldBe null
        repo.get(id)!!.events shouldBe emptyList()
    }

    @Test
    fun a_draft_keeps_the_place_it_was_opened_at() {
        val (_, scorer) = momentBoard()
        val draft = scorer.openMomentDraft()!!.toggle(good)
        scorer.score(Side.HOME)
        scorer.commitMomentDraft(draft)!!.afterPoints shouldBe 0
    }

    @Test
    fun removing_a_moment_hides_it_and_restoring_puts_it_back() {
        val (_, scorer) = momentBoard()
        val m = scorer.logMoment(listOf(good))!!
        scorer.removeMoment(m.id) shouldBe m
        scorer.state()!!.moments shouldBe emptyList()
        scorer.restoreMoment(m)
        scorer.state()!!.moments shouldBe listOf(m)
    }

    @Test
    fun the_last_moment_is_the_last_one_logged() {
        val (_, scorer) = momentBoard()
        scorer.score(Side.HOME)
        val later = scorer.logMoment(listOf(net))!!
        scorer.lastMoment() shouldBe later
        scorer.removeMoment(later.id)
        scorer.lastMoment() shouldBe null
    }

    @Test
    fun moments_leave_the_opening_serve_switchable() {
        val (_, scorer) = momentBoard()
        scorer.logMoment(listOf(good))
        scorer.canSwitchFirstServer() shouldBe true
        scorer.score(Side.HOME)
        scorer.canSwitchFirstServer() shouldBe false
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*MatchScorerTest*"`
Expected: compilation failure, `Unresolved reference: logMoment`.

- [ ] **Step 3: Implement**

In `ScoreLogsRepository.kt`, change the constructor parameter `private val now: () -> Instant` to `internal val now: () -> Instant`, with the comment `// Internal so MatchScorer stamps moments with the same clock the store stamps rows with.`

Append to `Moments.kt`:

```kotlin
/**
 * The composer's moment while Note is open. Held by the board, not the log:
 * nothing is written until it is committed, so typing appends nothing.
 *
 * [openedAt] and [afterPoints] are fixed when it opens, because that is when the
 * thing being written about was seen.
 */
data class MomentDraft(
    val tags: List<PointTag>,
    val text: String,
    val openedAt: Instant,
    val afterPoints: Int,
) {
    val isEmpty: Boolean get() = tags.isEmpty() && text.isBlank()

    /** Adds [tag], or takes it off if it is already on. By name, as a rally's tags are. */
    fun toggle(tag: PointTag): MomentDraft =
        if (tags.any { it.labelName == tag.labelName }) copy(tags = tags.filterNot { it.labelName == tag.labelName })
        else copy(tags = tags + tag)

    fun withText(text: String): MomentDraft = copy(text = text)
}
```

In `MatchScorer.kt`, add `import com.badmintontracker.shared.util.randomUuid`. Then:
- replace the body of `canSwitchFirstServer` with
  ```kotlin
  return log.isPlayable() && log.events.none { it is ScoreEvent.PointTo || it is ScoreEvent.Retire }
  ```
  and update its KDoc to say moments do not count;
- add these methods after `tagPoint`:

```kotlin
    /** One tap on a label with no rally to put it on: a moment of its own, now. */
    fun logMoment(tags: List<PointTag>): LoggedMoment? {
        val state = state() ?: return null
        return append(ScoreEvent.Moment(randomUuid(), state.pointCount, repo.now(), tags, null))
    }

    /** The composer, opened here and now. Null when the match is not on this device. */
    fun openMomentDraft(): MomentDraft? {
        val state = state() ?: return null
        return MomentDraft(tags = emptyList(), text = "", openedAt = repo.now(), afterPoints = state.pointCount)
    }

    /** Writes [draft] as one moment. Null, and nothing written, when it is empty. */
    fun commitMomentDraft(draft: MomentDraft): LoggedMoment? {
        if (draft.isEmpty || repo.get(scoreLogId) == null) return null
        val comment = draft.text.trim().ifEmpty { null }
        return append(ScoreEvent.Moment(randomUuid(), draft.afterPoints, draft.openedAt, draft.tags, comment))
    }

    /** Takes a moment off by appending an empty version. Returns what it was, for putting back. */
    fun removeMoment(id: String): LoggedMoment? {
        val moment = state()?.moments?.firstOrNull { it.id == id } ?: return null
        edit { it + ScoreEvent.Moment(id, moment.afterPoints, moment.loggedAt, emptyList(), null) }
        return moment
    }

    /** Puts [moment] back exactly as it was. */
    fun restoreMoment(moment: LoggedMoment) = edit { it + moment.toEvent() }

    /** The moment logged or changed most recently, by its place in the log. */
    fun lastMoment(): LoggedMoment? {
        val log = repo.get(scoreLogId) ?: return null
        val live = log.state().moments.associateBy { it.id }
        return log.events.asReversed().firstNotNullOfOrNull { (it as? ScoreEvent.Moment)?.let { m -> live[m.id] } }
    }

    private fun append(moment: ScoreEvent.Moment): LoggedMoment? {
        edit { it + moment }
        return state()?.moments?.firstOrNull { it.id == moment.id }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --offline :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchScorerTest.kt
git commit -m "feat(scoring): log, compose, remove and restore moments; moments leave the serve switchable"
```

---

### Task 3: The score editor keeps moments between the same rallies

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEdit.kt:88` (`val base = …`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEditTest.kt`

**Interfaces:**
- Consumes: Task 1, and `MatchScorer.logMoment` from Task 2.
- Produces: `planScoreEdit(...).events` keeps every moment, re-anchored.

- [ ] **Step 1: Write the failing tests**

Add to `ScoreEditTest.kt`, using its `board()` helper:

```kotlin
    @Test
    fun a_removed_rally_does_not_slide_a_moment_onto_another() {
        // Home, Away, Home, then a moment, then Away: 2-2. Taking Home's latest
        // rally back (the third) must leave the moment just before Away's second.
        val b = board()
        b.play(H, A, H)
        b.scorer.logMoment(listOf(PointTag("Net", "slate")))
        b.play(A)
        val plan = b.log.planScoreEdit(home = 1, away = 2, nextServer = null)
        plan.canSave shouldBe true
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.points.map { it.wonBy } shouldBe listOf(H, A, A)
        after.moments.single().afterPoints shouldBe 2
    }

    @Test
    fun added_rallies_go_after_a_moment_at_the_start_of_the_game() {
        val b = board()
        b.scorer.logMoment(listOf(PointTag("Net", "slate")))
        val plan = b.log.planScoreEdit(home = 2, away = 0, nextServer = null)
        val after = foldMatchState(b.log.rules, b.log.setup, plan.events)
        after.pointCount shouldBe 2
        after.moments.single().afterPoints shouldBe 0
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*ScoreEditTest*"`
Expected: FAIL. `afterPoints` is 3 clamped to 3 rather than 2, because the anchor was not remapped.

- [ ] **Step 3: Implement**

Replace `val base = events.dropPointsFrom(start)` with:

```kotlin
    // Moments survive the cut (dropPointsFrom keeps them), but a removal shifts
    // every rally after it, so a moment inside this game is re-anchored after the
    // same kept rallies it followed. The same promise this edit makes for tags.
    val base = events.dropPointsFrom(start).map { event ->
        if (event is ScoreEvent.Moment && event.afterPoints > start) {
            event.copy(afterPoints = start + kept.count { it.ordinal < event.afterPoints })
        } else {
            event
        }
    }
```

`kept` is declared above this line already (`val kept = gamePoints.filterNot …`). Confirm that before running.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --offline :shared:jvmTest --tests "*ScoreEditTest*"`
Expected: PASS, including every existing case.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEdit.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEditTest.kt
git commit -m "feat(scoring): the score editor keeps a moment between the rallies it was logged between"
```

---

### Task 4: What the row targets, its caption, and the strip's notices

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/BoardPresentation.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt` (add `isLive` and `reverse`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/BoardPresentationTest.kt`

**Interfaces:**
- Consumes: Tasks 1 and 2, and `UndoneRally` / `ScoreLog.undoneNotice` (`MatchScorer.kt`).
- Produces:
  - `sealed class BoardTarget { data class Rally(val ordinal: Int); data object Moments }`. A sealed *class*, not an interface, as is `BoardNotice`: an interface reaches Swift as a protocol, which is not `Equatable` (so `.task(id:)` cannot key on it), and an extension property on it becomes a facade function rather than a member;
  - `fun boardTarget(match: MatchState, pendingOrdinal: Int?): BoardTarget`;
  - `fun boardCaption(match: MatchState, target: BoardTarget, hasBoardLabels: Boolean, draft: MomentDraft?): String`;
  - `sealed class BoardNotice { RallyUndone(undone), MomentAdded(moment), MomentRemoved(moment) }`;
  - `fun ScoreLog.noticeMessage(notice: BoardNotice): String`;
  - `val BoardNotice.actionLabel: String`;
  - `MatchScorer.isLive(notice: BoardNotice): Boolean`;
  - `MatchScorer.reverse(notice: BoardNotice)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class BoardPresentationTest {

    private val t0 = Instant.parse("2026-10-13T18:00:00Z")
    private val good = PointTag("Good shot", "green")
    private val net = PointTag("Net", "slate")

    private fun board(rules: ScoringRules = ScoringRules.BWF_21): Pair<ScoreLogsRepository, MatchScorer> {
        val repo = ScoreLogsRepository(MapSettings(), now = { t0 }, ownerId = { "owner-1" })
        val log = repo.create(title = "Club night", rules = rules, setup = MatchSetup(firstServer = Side.HOME))
        return repo to MatchScorer(repo, log.id)
    }

    @Test
    fun the_row_targets_a_rally_when_there_is_one_and_moments_otherwise() {
        val (_, s) = board()
        boardTarget(s.state()!!, null) shouldBe BoardTarget.Moments
        s.score(Side.HOME)
        boardTarget(s.state()!!, 0) shouldBe BoardTarget.Rally(0)
        boardTarget(s.state()!!, null) shouldBe BoardTarget.Moments
        boardTarget(s.state()!!, 5) shouldBe BoardTarget.Moments   // a rally undo took back
    }

    @Test
    fun captions() {
        val (_, s) = board()
        val m = { s.state()!! }
        boardCaption(m(), BoardTarget.Moments, hasBoardLabels = true, draft = null) shouldBe
            "Tap a side to score, or a label to log a moment"
        boardCaption(m(), BoardTarget.Moments, hasBoardLabels = false, draft = null) shouldBe
            "Tap a side to score, or SERVE to switch server"
        s.logMoment(listOf(good))
        boardCaption(m(), BoardTarget.Moments, true, null) shouldBe "1 moment logged"
        s.logMoment(listOf(net))
        boardCaption(m(), BoardTarget.Moments, true, null) shouldBe "2 moments logged"
        val draft = s.openMomentDraft()!!
        boardCaption(m(), BoardTarget.Moments, true, draft) shouldBe "New moment"
        boardCaption(m(), BoardTarget.Moments, true, draft.toggle(good).toggle(net)) shouldBe "New moment: Good shot, Net"
        s.score(Side.AWAY)
        boardCaption(m(), BoardTarget.Rally(0), true, null) shouldBe "Point 1: 0-1"
    }

    @Test
    fun captions_once_the_match_is_over() {
        val (_, s) = board(ScoringRules(pointsToWin = 1, winBy = 1, cap = null, gamesToWin = 1, intervalAt = null, changeEndsAt = null))
        s.score(Side.HOME)
        boardCaption(s.state()!!, BoardTarget.Rally(0), true, null) shouldBe "Match over"
        boardCaption(s.state()!!, BoardTarget.Moments, true, null) shouldBe "Match over"
        s.logMoment(listOf(good))
        boardCaption(s.state()!!, BoardTarget.Moments, true, null) shouldBe "Match over · 1 moment logged"
    }

    @Test
    fun notices_say_what_happened_and_how_to_reverse_it() {
        val (repo, s) = board()
        val log = { repo.logs.value.single() }
        val m = s.logMoment(listOf(good, net))!!
        val added = BoardNotice.MomentAdded(m)
        log().noticeMessage(added) shouldBe "Moment added: Good shot, Net"
        added.actionLabel shouldBe "Undo"
        s.isLive(added) shouldBe true

        s.reverse(added)
        s.state()!!.moments shouldBe emptyList()
        s.isLive(added) shouldBe false

        val noteOnly = s.commitMomentDraft(s.openMomentDraft()!!.withText("short"))!!
        log().noticeMessage(BoardNotice.MomentAdded(noteOnly)) shouldBe "Moment added"

        val removed = BoardNotice.MomentRemoved(s.removeMoment(noteOnly.id)!!)
        log().noticeMessage(removed) shouldBe "Moment removed"
        s.isLive(removed) shouldBe true
        s.reverse(removed)
        s.state()!!.moments shouldBe listOf(noteOnly)

        s.score(Side.HOME)
        val undone = BoardNotice.RallyUndone(s.undo()!!)
        log().noticeMessage(undone) shouldBe "Point to Home undone"
        undone.actionLabel shouldBe "Redo"
        s.reverse(undone)
        s.state()!!.pointCount shouldBe 1
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*BoardPresentationTest*"`
Expected: compilation failure, `Unresolved reference: boardTarget`.

- [ ] **Step 3: Implement `BoardPresentation.kt`**

```kotlin
package com.badmintontracker.shared.scoring

/**
 * What a chip tap on the board does. One rule for both platforms: the row tags the
 * last rally whenever there is one to tag, and logs moments when there is not.
 */
sealed class BoardTarget {
    data class Rally(val ordinal: Int) : BoardTarget()
    data object Moments : BoardTarget()
}

/** [pendingOrdinal] if it still names a rally, otherwise moments. */
fun boardTarget(match: MatchState, pendingOrdinal: Int?): BoardTarget =
    pendingOrdinal?.takeIf { it in 0 until match.pointCount }?.let { BoardTarget.Rally(it) } ?: BoardTarget.Moments

/**
 * What the row is pointing at, so a tap on a chip is never a guess. Was written
 * once per platform (`tagRowCaption`, `ScoringView.caption`); one copy now.
 */
fun boardCaption(match: MatchState, target: BoardTarget, hasBoardLabels: Boolean, draft: MomentDraft?): String {
    if (draft != null) {
        return if (draft.tags.isEmpty()) "New moment"
        else "New moment: " + draft.tags.joinToString(", ") { it.labelName }
    }
    val moments = match.moments.size
    val logged = if (moments == 1) "1 moment logged" else "$moments moments logged"
    return when (target) {
        is BoardTarget.Rally -> if (match.isOver) "Match over" else match.points[target.ordinal].let {
            "Point ${it.ordinal + 1}: ${it.scoreAfter.home}-${it.scoreAfter.away}"
        }
        BoardTarget.Moments -> when {
            match.isOver -> if (moments == 0) "Match over" else "Match over · $logged"
            moments > 0 -> logged
            // With no chips there is nothing to log a moment with, so the hint
            // stays the one it was.
            !hasBoardLabels -> if (match.points.isEmpty()) "Tap a side to score, or SERVE to switch server" else "Tap a side to score"
            else -> "Tap a side to score, or a label to log a moment"
        }
    }
}

/**
 * What the board's strip says for a few seconds after something it cannot show on
 * its own, and the one action that reverses it.
 */
sealed class BoardNotice {
    data class RallyUndone(val undone: UndoneRally) : BoardNotice()
    data class MomentAdded(val moment: LoggedMoment) : BoardNotice()
    data class MomentRemoved(val moment: LoggedMoment) : BoardNotice()
}

fun ScoreLog.noticeMessage(notice: BoardNotice): String = when (notice) {
    is BoardNotice.RallyUndone -> undoneNotice(notice.undone)
    is BoardNotice.MomentAdded -> "Moment added" + notice.moment.labelSuffix()
    is BoardNotice.MomentRemoved -> "Moment removed" + notice.moment.labelSuffix()
}

val BoardNotice.actionLabel: String
    get() = if (this is BoardNotice.RallyUndone) "Redo" else "Undo"

private fun LoggedMoment.labelSuffix(): String =
    if (tags.isEmpty()) "" else tags.joinToString(", ", prefix = ": ") { it.labelName }
```

In `MatchScorer`, add:

```kotlin
    /** True while [notice]'s action would still do what it says. */
    fun isLive(notice: BoardNotice): Boolean = when (notice) {
        is BoardNotice.RallyUndone -> canRedo(notice.undone)
        is BoardNotice.MomentAdded -> state()?.moments?.any { it == notice.moment } == true
        is BoardNotice.MomentRemoved -> state()?.moments?.none { it.id == notice.moment.id } == true
    }

    /** The strip's action: Redo for an undo, Undo for a moment added or removed. */
    fun reverse(notice: BoardNotice) {
        if (!isLive(notice)) return
        when (notice) {
            is BoardNotice.RallyUndone -> redo(notice.undone)
            is BoardNotice.MomentAdded -> removeMoment(notice.moment.id)
            is BoardNotice.MomentRemoved -> restoreMoment(notice.moment)
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --offline :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/BoardPresentation.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/BoardPresentationTest.kt
git commit -m "feat(scoring): one shared rule for what the board's row targets, its caption and the strip's notices"
```

---

### Task 5: The timeline, the tally, the export and the score line

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchTimeline.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt:37-42`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt`, `ScoreMatchCardTest.kt`, `MomentsTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `sealed interface TimelineEntry { Rally(point: ScoredPoint); Moment(moment: LoggedMoment) }`;
  - `fun matchTimeline(state: MatchState): List<TimelineEntry>`, oldest first;
  - `ScoreTagSummary(taggedPointCount: Int, momentCount: Int, labels: List<LabelCount>)` with `val headline: String`;
  - `fun exportMatchText(log: ScoreLog, zone: TimeZone): String`;
  - `fun scoreButtonLabel(state: MatchState): String`.

- [ ] **Step 1: Write the failing tests**

Add to `MomentsTest.kt`:

```kotlin
    @Test
    fun the_timeline_puts_each_moment_before_the_rally_that_followed_it() {
        val state = fold(
            moment("before", 0),
            ScoreEvent.PointTo(Side.HOME),
            ScoreEvent.PointTo(Side.AWAY),
            moment("between", 1),
            moment("end", 2),
        )
        matchTimeline(state).map {
            when (it) {
                is TimelineEntry.Rally -> "r${it.point.ordinal}"
                is TimelineEntry.Moment -> it.moment.id
            }
        } shouldBe listOf("before", "r0", "between", "r1", "end")
    }
```

Add to `ScoreTagSummaryTest.kt`. Read its `stateWith` helper first. These tests build state through `foldMatchState` directly:

```kotlin
    @Test
    fun moments_count_in_the_tally_beside_tagged_rallies() {
        val state = foldMatchState(ScoringRules.BWF_21, MatchSetup(Side.HOME), listOf(
            ScoreEvent.PointTo(Side.HOME),
            ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
            ScoreEvent.Moment("m1", 1, Instant.parse("2026-10-13T18:00:00Z"), listOf(PointTag("Good shot", "green")), null),
            ScoreEvent.Moment("m2", 1, Instant.parse("2026-10-13T18:01:00Z"), emptyList(), "short"),
        ))
        val summary = buildScoreTagSummary(state)
        summary.taggedPointCount shouldBe 1
        summary.momentCount shouldBe 2
        summary.labels.single().count shouldBe 2
        summary.headline shouldBe "1 rally tagged · 2 moments"
        summary.isEmpty shouldBe false
    }

    @Test
    fun the_headline_names_only_what_there_is() {
        ScoreTagSummary(3, 0, emptyList()).headline shouldBe "3 rallies tagged"
        ScoreTagSummary(0, 1, emptyList()).headline shouldBe "1 moment"
        ScoreTagSummary(0, 6, emptyList()).headline shouldBe "6 moments"
    }

    @Test
    fun the_export_lists_moments_among_the_rallies() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-10-13T19:42:00Z") }, ownerId = { "o" })
        val log = repo.create(title = "Club night", rules = ScoringRules.BWF_21, setup = MatchSetup(Side.HOME))
        val scorer = MatchScorer(repo, log.id)
        scorer.logMoment(listOf(PointTag("Unforced error", "red"), PointTag("Net", "slate")))
        scorer.commitMomentDraft(scorer.openMomentDraft()!!.withText("backhand clear lands short"))
        exportMatchText(repo.get(log.id)!!, TimeZone.UTC) shouldBe """
            Club night
            2 moments

            At 19:42: Unforced error, Net
            At 19:42: backhand clear lands short
        """.trimIndent()
        scorer.score(Side.HOME)
        exportMatchText(repo.get(log.id)!!, TimeZone.UTC).lines().last() shouldBe "1. 1-0 Home"
    }
```

Add any imports the test file lacks (`MapSettings`, `Instant`, `TimeZone`). Before relying on the header lines above, check how `playersLine()` treats a match with no players by reading `ScoreLog`'s extension: the expected text assumes it returns null.

Add to `ScoreMatchCardTest.kt`:

```kotlin
    @Test
    fun a_match_with_only_moments_says_how_many() {
        val at = Instant.parse("2026-10-13T18:00:00Z")
        val moment = { id: String -> ScoreEvent.Moment(id, 0, at, listOf(PointTag("Net", "slate")), null) }
        scoreLine(foldMatchState(ScoringRules.BWF_21, MatchSetup(Side.HOME), listOf(moment("a")))) shouldBe "1 moment"
        val two = foldMatchState(ScoringRules.BWF_21, MatchSetup(Side.HOME), listOf(moment("a"), moment("b")))
        scoreLine(two) shouldBe "2 moments"
        scoreButtonLabel(two) shouldBe "Resume"
        scoreButtonLabel(foldMatchState(ScoringRules.BWF_21, MatchSetup(Side.HOME), emptyList())) shouldBe "Score"
        scoreButtonLabel(foldMatchState(ScoringRules.BWF_21, MatchSetup(Side.HOME), listOf(ScoreEvent.PointTo(Side.HOME)))) shouldBe "Resume scoring"
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "*MomentsTest*" --tests "*ScoreTagSummaryTest*" --tests "*ScoreMatchCardTest*"`
Expected: compilation failure (`matchTimeline`, `momentCount`, `scoreButtonLabel`).

- [ ] **Step 3: Create `MatchTimeline.kt`**

```kotlin
package com.badmintontracker.shared.scoring

/** One row of a match's history: a rally, or a moment logged between rallies. */
sealed interface TimelineEntry {
    data class Rally(val point: ScoredPoint) : TimelineEntry
    data class Moment(val moment: LoggedMoment) : TimelineEntry
}

/**
 * Rallies and moments in the order they happened, oldest first. A moment anchored
 * after k rallies sits just before rally k. One copy for the match page on both
 * platforms and for the export.
 */
fun matchTimeline(state: MatchState): List<TimelineEntry> {
    val out = ArrayList<TimelineEntry>(state.points.size + state.moments.size)
    var next = 0
    for (point in state.points) {
        while (next < state.moments.size && state.moments[next].afterPoints <= point.ordinal) {
            out += TimelineEntry.Moment(state.moments[next++])
        }
        out += TimelineEntry.Rally(point)
    }
    while (next < state.moments.size) out += TimelineEntry.Moment(state.moments[next++])
    return out
}
```

- [ ] **Step 4: Update `ScoreTagSummary.kt`**

Replace the data class and `buildScoreTagSummary`:

```kotlin
data class ScoreTagSummary(
    val taggedPointCount: Int,
    /** Moments logged, labelled or not. */
    val momentCount: Int,
    val labels: List<LabelCount>,
) {
    val isEmpty: Boolean get() = taggedPointCount == 0 && momentCount == 0

    /** "3 rallies tagged · 6 moments", naming only what there is. */
    val headline: String
        get() = listOfNotNull(
            taggedPointCount.takeIf { it > 0 }?.let { if (it == 1) "1 rally tagged" else "$it rallies tagged" },
            momentCount.takeIf { it > 0 }?.let { if (it == 1) "1 moment" else "$it moments" },
        ).joinToString(" · ")

    companion object { val EMPTY = ScoreTagSummary(taggedPointCount = 0, momentCount = 0, labels = emptyList()) }
}

fun buildScoreTagSummary(state: MatchState): ScoreTagSummary {
    // Recency is the position in the timeline, so a rally's tags and a moment's
    // share one scale.
    val refs = matchTimeline(state).flatMapIndexed { index, entry ->
        val tags = when (entry) {
            is TimelineEntry.Rally -> entry.point.tags
            is TimelineEntry.Moment -> entry.moment.tags
        }
        tags.mapNotNull { tag ->
            val name = tag.labelName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LabelRef(name = name, colorKey = tag.labelColor, recency = index.toLong(), tieBreak = "")
        }
    }
    val tagged = state.points.count { point -> point.tags.any { it.labelName.isNotBlank() } }
    if (tagged == 0 && state.moments.isEmpty()) return ScoreTagSummary.EMPTY
    return ScoreTagSummary(taggedPointCount = tagged, momentCount = state.moments.size, labels = rollUpLabels(refs))
}
```

Replace `exportMatchText`, keeping its KDoc:

```kotlin
fun exportMatchText(log: ScoreLog): String = exportMatchText(log, TimeZone.currentSystemDefault())

/** [exportMatchText] with an explicit zone for a moment's clock time. */
fun exportMatchText(log: ScoreLog, zone: TimeZone): String {
    val state = log.state()
    val header = listOfNotNull(log.title, log.playersLine(), scoreLine(state))
    val timeline = matchTimeline(state)
    if (timeline.isEmpty()) return header.joinToString("\n")

    val rows = timeline.map { entry ->
        when (entry) {
            is TimelineEntry.Rally -> {
                val point = entry.point
                val score = "${point.scoreAfter.home}-${point.scoreAfter.away}"
                val tags = point.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
                val comment = point.comment?.trim()?.takeIf { it.isNotEmpty() }
                buildString {
                    append("${point.ordinal + 1}. $score ${log.sideName(point.wonBy)}")
                    if (tags != null) append(" - $tags")
                    if (comment != null) append(" ($comment)")
                }
            }
            is TimelineEntry.Moment -> {
                val m = entry.moment
                val tags = m.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
                val comment = m.comment?.trim()?.takeIf { it.isNotEmpty() }
                val body = when {
                    tags != null && comment != null -> "$tags ($comment)"
                    tags != null -> tags
                    else -> comment.orEmpty()
                }
                "At ${clockTime(m.loggedAt, zone)}: $body"
            }
        }
    }
    return (header + "" + rows).joinToString("\n")
}
```

Add `import kotlinx.datetime.TimeZone`.

- [ ] **Step 5: Update `ScoreMatchCard.kt`**

In `scoreLine`, replace both `return "Not started"` sites with `return notStarted(state)`. Then add:

```kotlin
/** A match with no rally: how many moments it has, or that it has not started. */
private fun notStarted(state: MatchState): String = when (val n = state.moments.size) {
    0 -> "Not started"
    1 -> "1 moment"
    else -> "$n moments"
}

/** The match page's way back to the board. Shared so the two pages cannot word it differently. */
fun scoreButtonLabel(state: MatchState): String = when {
    state.points.isNotEmpty() -> "Resume scoring"
    state.moments.isNotEmpty() -> "Resume"
    else -> "Score"
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew --offline :shared:jvmTest`
Expected: PASS. An existing `ScoreTagSummaryTest` case that builds `ScoreTagSummary(…)` positionally needs `momentCount = 0`; add it.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/
git commit -m "feat(scoring): moments in the match timeline, the tally, the export and the score line"
```

---

### Task 6: Android view model

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 2 and 4.
- Produces, on `ScoringUiState`:
  - `target: BoardTarget?`
  - `momentDraft: MomentDraft?`
  - `caption: String`
  - `canRemoveMoment: Boolean`
  - `notice: BoardNotice?`
  - `noticeMessage: String?`
  - `noticeAction: String?`
  
  `undoneNotice` and `undone` are removed.
- Produces, on `ScoringViewModel`:
  - `tapMomentLabel(label: AnnotationLabel)`
  - `openMomentDraft()`
  - `setMomentDraftText(text: String)`
  - `commitMomentDraft()`
  - `removeLastMoment()`
  - `reverseNotice()`, which replaces `redo()`
  - `dismissNotice()`, which replaces `dismissUndoNotice()`

- [ ] **Step 1: Write the failing tests**

First update the existing tests mechanically:
- `undoneNotice` becomes `noticeMessage`;
- `vm.redo()` becomes `vm.reverseNotice()`;
- `vm.dismissUndoNotice()` becomes `vm.dismissNotice()`.

Then append:

```kotlin
    @Test
    fun the_board_opens_on_the_last_rally_of_a_match_already_scored() = runTest(dispatcher) {
        val (repo, id, first) = fixture()
        first.score(Side.HOME); first.score(Side.AWAY)
        advanceUntilIdle()
        val reopened = ScoringViewModel(repo, FakeAnnotationLabelsRepository(listOf(goodShot)), id)
        advanceUntilIdle()
        reopened.state.value.pendingTagOrdinal shouldBe 1
        reopened.state.value.target shouldBe BoardTarget.Rally(1)
    }

    @Test
    fun at_nil_all_a_label_logs_a_moment_of_its_own_per_tap() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        advanceUntilIdle()
        vm.state.value.target shouldBe BoardTarget.Moments
        vm.state.value.caption shouldBe "Tap a side to score, or a label to log a moment"
        vm.tapMomentLabel(goodShot)
        vm.tapMomentLabel(goodShot)
        advanceUntilIdle()
        vm.state.value.match!!.moments.size shouldBe 2
        vm.state.value.match!!.points.shouldBeEmpty()
        vm.state.value.noticeMessage shouldBe "Moment added: Good shot"
        vm.state.value.noticeAction shouldBe "Undo"
        vm.state.value.caption shouldBe "2 moments logged"
        vm.state.value.canSwitchFirstServer shouldBe true
    }

    @Test
    fun undo_in_the_strip_takes_the_moment_back() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.tapMomentLabel(goodShot)
        advanceUntilIdle()
        vm.reverseNotice()
        advanceUntilIdle()
        vm.state.value.match!!.moments.shouldBeEmpty()
        vm.state.value.noticeMessage.shouldBeNull()
    }

    @Test
    fun the_composer_collects_labels_and_text_into_one_moment() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.openMomentDraft()
        vm.tapMomentLabel(goodShot)
        vm.tapMomentLabel(forcedError)
        vm.tapMomentLabel(goodShot)
        vm.setMomentDraftText("late on the lift")
        advanceUntilIdle()
        vm.state.value.caption shouldBe "New moment: Forced error"
        vm.state.value.match!!.moments.shouldBeEmpty()
        vm.commitMomentDraft()
        advanceUntilIdle()
        val m = vm.state.value.match!!.moments.single()
        m.tags.map { it.labelName } shouldBe listOf("Forced error")
        m.comment shouldBe "late on the lift"
        vm.state.value.momentDraft.shouldBeNull()
        vm.state.value.noticeMessage shouldBe "Moment added: Forced error"
    }

    @Test
    fun scoring_with_the_composer_open_saves_it_first_and_closes_it() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.openMomentDraft()
        vm.setMomentDraftText("tight")
        vm.score(Side.HOME)
        advanceUntilIdle()
        vm.state.value.momentDraft.shouldBeNull()
        vm.state.value.match!!.moments.single().afterPoints shouldBe 0
        vm.state.value.target shouldBe BoardTarget.Rally(0)
    }

    @Test
    fun undoing_the_only_rally_points_the_row_back_at_moments() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.tapMomentLabel(goodShot)
        vm.score(Side.HOME)
        advanceUntilIdle()
        vm.state.value.canSwitchFirstServer shouldBe false
        vm.undo()
        advanceUntilIdle()
        vm.state.value.target shouldBe BoardTarget.Moments
        vm.state.value.canSwitchFirstServer shouldBe true
        vm.state.value.match!!.moments.size shouldBe 1
    }

    @Test
    fun remove_last_moment_offers_it_back() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        advanceUntilIdle()
        vm.state.value.canRemoveMoment shouldBe false
        vm.tapMomentLabel(goodShot)
        advanceUntilIdle()
        vm.state.value.canRemoveMoment shouldBe true
        vm.removeLastMoment()
        advanceUntilIdle()
        vm.state.value.match!!.moments.shouldBeEmpty()
        vm.state.value.noticeMessage shouldBe "Moment removed: Good shot"
        vm.reverseNotice()
        advanceUntilIdle()
        vm.state.value.match!!.moments.size shouldBe 1
    }

    @Test
    fun after_finish_a_label_logs_a_moment() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.finish()
        advanceUntilIdle()
        vm.state.value.target shouldBe BoardTarget.Moments
        vm.tapMomentLabel(goodShot)
        advanceUntilIdle()
        vm.state.value.match!!.moments.single().afterPoints shouldBe 1
    }
```

Add imports: `com.badmintontracker.shared.scoring.BoardTarget`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew --offline :androidApp:testDebugUnitTest --tests "*ScoringViewModelTest*"`
Expected: compilation failure (`target`, `tapMomentLabel`, …).

- [ ] **Step 3: Implement**

Replace `ScoringUiState`'s `undoneNotice` and `undone` fields with:

```kotlin
    /** What a chip tap does: tag [pendingTagOrdinal]'s rally, or log a moment. Null with no match. */
    val target: BoardTarget? = null,
    /** The open moment composer. Only ever open while [target] is [BoardTarget.Moments]. */
    val momentDraft: MomentDraft? = null,
    /** The line beside Edit score. See [boardCaption]. */
    val caption: String = "",
    /** Whether the menu offers "Remove last moment". */
    val canRemoveMoment: Boolean = false,
    /**
     * The strip's notice while its action can still do what it says, for example
     * "Moment added: Good shot" with Undo. A new one restarts the timer even when
     * the words match.
     */
    val notice: BoardNotice? = null,
    val noticeMessage: String? = null,
    val noticeAction: String? = null,
```

In the view model:

```kotlin
    // Opens on the last rally, so a board reopened mid-match tags it, as the
    // board would have the moment it was scored.
    private val pending = MutableStateFlow(lastOrdinal())
    private val draft = MutableStateFlow<MomentDraft?>(null)
    private val lastNotice = MutableStateFlow<BoardNotice?>(null)

    val state = combine(
        combine(scoreLogs.logs, labels.scoreboardLabels, labels.labels, ::Triple),
        pending, draft, lastNotice,
    ) { (logs, board, all), p, d, n -> build(logs, board, all, p, d, n) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            build(scoreLogs.logs.value, labels.scoreboardLabels.value, labels.labels.value, pending.value, draft.value, lastNotice.value),
        )

    private fun build(
        logs: List<ScoreLog>,
        boardLabels: List<AnnotationLabel>,
        allLabels: List<AnnotationLabel>,
        pendingOrdinal: Int?,
        openDraft: MomentDraft?,
        notice: BoardNotice?,
    ): ScoringUiState {
        val log = logs.firstOrNull { it.id == scoreLogId }
        val match = log?.state()
        val target = match?.let { boardTarget(it, pendingOrdinal) }
        val liveDraft = openDraft?.takeIf { target == BoardTarget.Moments }
        val liveNotice = notice?.takeIf { log != null && scorer.isLive(it) }
        return ScoringUiState(
            log = log,
            match = match,
            labels = boardLabels,
            pendingTagOrdinal = (target as? BoardTarget.Rally)?.ordinal,
            target = target,
            momentDraft = liveDraft,
            caption = if (match != null && target != null) boardCaption(match, target, boardLabels.isNotEmpty(), liveDraft) else "",
            canScore = match?.isOver == false,
            canUndo = (match?.pointCount ?: 0) > 0,
            canEditScore = log != null && scorer.canEditScore(),
            canSwitchFirstServer = log != null && scorer.canSwitchFirstServer(),
            hasAnyLabels = allLabels.isNotEmpty(),
            canRemoveMoment = match?.moments?.isNotEmpty() == true,
            notice = liveNotice,
            noticeMessage = liveNotice?.let { log!!.noticeMessage(it) },
            noticeAction = liveNotice?.actionLabel,
        )
    }
```

`pending` has to be declared after `scorer`, which is above it already. `lastOrdinal()` is a function, so the forward reference is fine.

Rewrite the actions. Every action that changes the log commits an open composer first: a target change must never leave a draft behind (Review Focus 1).

```kotlin
    fun score(side: Side) {
        commitMomentDraft()
        scorer.score(side)
        lastNotice.value = null
        pending.value = lastOrdinal()
    }

    /**
     * A chip tap while the row targets moments: a ✓ on the composer's moment
     * while it is open, otherwise a moment of its own.
     */
    fun tapMomentLabel(label: AnnotationLabel) {
        val tag = PointTag(labelName = label.name, labelColor = label.colorKey)
        draft.value?.let { draft.value = it.toggle(tag); return }
        lastNotice.value = BoardNotice.MomentAdded(scorer.logMoment(listOf(tag)) ?: return)
    }

    fun openMomentDraft() {
        if (draft.value == null) draft.value = scorer.openMomentDraft()
    }

    /** Kept here so the draft can be saved by a score tap; the field reads its own copy. */
    fun setMomentDraftText(text: String) {
        draft.value = draft.value?.withText(text)
    }

    fun commitMomentDraft() {
        val open = draft.value ?: return
        draft.value = null
        scorer.commitMomentDraft(open)?.let { lastNotice.value = BoardNotice.MomentAdded(it) }
    }

    fun removeLastMoment() {
        commitMomentDraft()
        val last = scorer.lastMoment() ?: return
        lastNotice.value = BoardNotice.MomentRemoved(scorer.removeMoment(last.id) ?: return)
    }

    fun undo() {
        commitMomentDraft()
        lastNotice.value = BoardNotice.RallyUndone(scorer.undo() ?: return)
        pending.value = lastOrdinal()
    }

    /** The strip's action: Redo after an undo, Undo after a moment was added or removed. */
    fun reverseNotice() {
        val notice = lastNotice.value ?: return
        lastNotice.value = null
        commitMomentDraft()
        scorer.reverse(notice)
        if (notice is BoardNotice.RallyUndone) pending.value = lastOrdinal()
    }

    fun dismissNotice() {
        lastNotice.value = null
    }
```

In `editScore`, `resetCurrentGame` and `finish`:
- call `commitMomentDraft()` first;
- replace `lastUndo.value = null` with `lastNotice.value = null`.

Delete `redo`, `dismissUndoNotice` and `lastUndo`. Update imports: drop `UndoneRally` and `undoneNotice`, add `BoardNotice`, `BoardTarget`, `MomentDraft`, `actionLabel`, `boardCaption`, `boardTarget` and `noticeMessage`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --offline :androidApp:testDebugUnitTest --tests "*ScoringViewModelTest*"`
Expected: PASS. An existing test that asserted `pendingTagOrdinal` is null on a board *created* over a scored match now expects the last ordinal; none does today (checked: every null assertion follows reset, finish or delete).

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt
git commit -m "feat(android): the board's view model logs moments, composes them and opens on the last rally"
```

---

### Task 7: Android board UI

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt`: `ScoringScreen` (lines 111-190), `StatusStrip` (lines 300-375), `ControlBar` (lines 540-677), `TagChip` (lines 680-709). Delete `tagRowCaption` (line 712).

**Interfaces:**
- Consumes: Task 6's state and actions.

- [ ] **Step 1: Wire the screen**

In `ScoringScreen`:
- Replace the undo-notice `LaunchedEffect(state.undone)` with:

```kotlin
    // Keyed on the notice rather than its words, so a second notice with the same
    // words still gets its full time.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_MILLIS)
            vm.dismissNotice()
        }
    }
    // A rally note belongs to a rally. When the row stops pointing at one, the
    // field closes rather than reopening on the next rally scored.
    LaunchedEffect(state.target is BoardTarget.Moments) {
        if (state.target is BoardTarget.Moments) noteOpen = false
    }
```

  Rename `UNDO_NOTICE_MILLIS` to `NOTICE_MILLIS` and say in its KDoc that it covers moment notices too.

- Pass the new values to `StatusStrip`:
  - `notice = state.noticeMessage`, `noticeAction = state.noticeAction`, `onNoticeAction = vm::reverseNotice`;
  - `canRemoveMoment = state.canRemoveMoment`, `onRemoveMoment = vm::removeLastMoment`;
  - drop `undoneNotice` and `onRedo`.
- Pass `ControlBar`:
  - `state`, so the bar reads `target`, `momentDraft`, `caption`, `labels`, `hasAnyLabels` and `canEditScore` from it;
  - `noteOpen`;
  - `onToggleTag = vm::toggleTag`, `onMomentLabel = vm::tapMomentLabel`, `onSetComment = vm::setComment`;
  - `onNote`, as below;
  - `onDraftText = vm::setMomentDraftText`, `onDraftClose = vm::commitMomentDraft`;
  - `onEditScore`, `onDone`.

```kotlin
            onNote = {
                when {
                    state.target is BoardTarget.Rally -> noteOpen = !noteOpen
                    state.momentDraft == null -> vm.openMomentDraft()
                    else -> vm.commitMomentDraft()
                }
            },
```

- [ ] **Step 2: The strip**

In `StatusStrip`:
- replace `undoneNotice`/`onRedo` with `notice: String?`, `noticeAction: String?` and `onNoticeAction: () -> Unit`;
- `val announcement = notice ?: announcementFor(log, match)`;
- render the action as `if (notice != null && noticeAction != null) TextButton(onClick = onNoticeAction) { Text(noticeAction) }`;
- in the menu, directly after "Undo last rally":

```kotlin
                    // Only when there is one. Beside Undo last rally rather than
                    // replacing it: after Reset game the row logs moments while
                    // earlier games still have rallies to take back.
                    if (canRemoveMoment) {
                        DropdownMenuItem(
                            text = { Text("Remove last moment") },
                            onClick = { menuOpen = false; onRemoveMoment() },
                        )
                    }
```

- [ ] **Step 3: The label row and the composer**

Replace `ControlBar`'s body up to the action row:

```kotlin
    val match = state.match ?: return
    val target = state.target ?: return
    val draft = state.momentDraft
    val point = (target as? BoardTarget.Rally)?.let { match.points.getOrNull(it.ordinal) }
    val haptics = LocalHapticFeedback.current

    // ... Surface / Column as today ...
            if (state.labels.isEmpty()) {
                // (empty-palette text unchanged)
            } else {
                FlowRow(/* as today */) {
                    state.labels.forEach { label ->
                        val quick = target == BoardTarget.Moments && draft == null
                        TagChip(
                            label = label,
                            selected = when {
                                draft != null -> draft.tags.any { it.labelName == label.name }
                                point != null -> point.tags.any { it.labelName == label.name }
                                else -> false
                            },
                            flashOnTap = quick,
                            onClickLabel = when {
                                point != null -> "Tag this rally"
                                draft != null -> "Add to this moment"
                                else -> "Log a moment"
                            },
                            onClick = {
                                if (point != null) {
                                    onToggleTag(point.ordinal, label)
                                } else {
                                    // A quick moment changes nothing else on the board,
                                    // so it is felt as well as seen.
                                    if (quick) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onMomentLabel(label)
                                }
                            },
                        )
                    }
                }
            }

            if (draft != null) {
                key(draft.openedAt) {
                    MomentNoteField(onTextChange = onDraftText, onClose = onDraftClose)
                }
            } else if (noteOpen && point != null) {
                // the rally note field, unchanged
            }
```

Add the composable:

```kotlin
/**
 * The composer's note. Its text is pushed to the view model as it is typed, so a
 * score tap can save the moment, but the field reads its own copy: reading text
 * back through a flow is how a cursor ends up jumping.
 */
@Composable
private fun MomentNoteField(onTextChange: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // Saved once, when the field goes away for any reason.
    DisposableEffect(Unit) { onDispose { onClose() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onTextChange(it) },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text("Note on this moment") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onClose() }),
    )
}
```

In the action row:
- the caption becomes `Text(text = state.caption, …)`, with the existing `maxLines = 2` and `overflow = Ellipsis`;
- the Note button loses `enabled = point != null` and becomes `TextButton(onClick = onNote, …)`. Note is always live now.

- [ ] **Step 4: The chip**

Replace `TagChip`. Notes on the change:
- The click is a `clickable` placed after `clip(shape)`, so its ripple is bounded by the pill. Today's chip puts `Modifier.clickable` on the `Surface`'s own modifier, which comes before the Surface's clip. Check today's ripple corners on the device first (Task 12) and note what you saw in the commit.
- `clickable(onClickLabel = …)` is what gives TalkBack the action's name.
- The flash is a press-in plus a white wash. Neither changes the chip's size, so the row never reflows under the coach's finger, and nothing overlaps a neighbour.

```kotlin
@Composable
private fun TagChip(
    label: AnnotationLabel,
    selected: Boolean,
    flashOnTap: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val swatch = LabelColor.from(label.colorKey)
    val container = swatch?.let { Color(it.background.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val scale = remember { Animatable(1f) }
    val wash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val shape = RoundedCornerShape(50)

    Surface(
        shape = shape,
        color = container,
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(shape)
            .clickable(onClickLabel = onClickLabel) {
                onClick()
                if (flashOnTap) {
                    scope.launch {
                        launch { scale.animateTo(0.94f, tween(80)); scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
                        launch { wash.snapTo(0.32f); wash.animateTo(0f, tween(260)) }
                    }
                }
            }
            .semantics { this.selected = selected },
    ) {
        Text(
            text = if (selected) "✓ ${label.name}" else label.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = onContainer,
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    drawRect(Color.White.copy(alpha = wash.value))
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 1,
        )
    }
}
```

Imports to add:
- `androidx.compose.animation.core.Animatable`, `spring`, `tween`;
- `androidx.compose.foundation.text.KeyboardActions`, `KeyboardOptions`;
- `androidx.compose.ui.draw.drawWithContent`;
- `androidx.compose.ui.graphics.graphicsLayer`;
- `androidx.compose.ui.draw.clip`, `androidx.compose.ui.semantics.selected`;
- `androidx.compose.ui.text.input.ImeAction`;
- `kotlinx.coroutines.launch`;
- `com.badmintontracker.shared.scoring.BoardTarget`.

Delete `tagRowCaption`.

Before keeping the bold-on-selected weight, check the chip's KDoc and the iOS comment about width change: iOS chose one weight so the chip does not change width. Android keeps today's bold. That pre-existing difference is reported in Task 12 rather than changed here.

- [ ] **Step 5: Build and run the unit tests**

Run: `./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`. `assembleRelease` is in the list because release resolves Compose Foundation 1.7.6: an API from 1.8+ fails only there. Grep the output for `BUILD SUCCESSFUL`, because a background task's exit code is the shell's.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt
git commit -m "feat(android): the board's labels log moments at 0-0, with a composer, a strip notice and Remove last moment"
```

---

### Task 8: Android match page

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/PointsFacet.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt:450`

**Interfaces:**
- Consumes: Task 5: `matchTimeline`, `TimelineEntry`, `ScoreTagSummary.headline`, `scoreButtonLabel` and `clockTime`.

- [ ] **Step 1: Pass the timeline**

In `MatchScreen.kt:450`, replace `points = matchState.match?.points.orEmpty()` with `timeline = matchState.match?.let(::matchTimeline).orEmpty(), match = matchState.match`. Import `matchTimeline`.

- [ ] **Step 2: Render it**

Change `pointsFacet`:
- The parameters `points: List<ScoredPoint>` become `timeline: List<TimelineEntry>, match: MatchState?`.
- The header button reads `Text(match?.let(::scoreButtonLabel) ?: "Score")`.
- `TagTally` reads `tally.headline`. Its `Row` becomes `FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp))`. Today's `Row` runs off the right edge with four or more labels: a pre-existing defect on the surface this task changes.
- Empty state, when `timeline.isEmpty()`: title "Nothing logged yet", body "Points you score and moments you log are listed here."
- The list:

```kotlin
        // Newest first: courtside, the rally you care about is the last one.
        items(timeline.asReversed(), key = {
            when (it) {
                is TimelineEntry.Rally -> "point-${it.point.ordinal}"
                is TimelineEntry.Moment -> "moment-${it.moment.id}"
            }
        }) { entry ->
            when (entry) {
                is TimelineEntry.Rally -> PointRow(entry.point, winner = log.sideName(entry.point.wonBy))
                is TimelineEntry.Moment -> MomentRow(entry.moment)
            }
            HorizontalDivider()
        }
```

In `PointRow`, the tags `Row` becomes `FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp))`. Same defect, same reason.

Add:

```kotlin
/**
 * A moment, shaped like a rally's row so the two read as one list: the time where
 * a rally has its score, and "Moment" where it has who won it.
 */
@Composable
private fun MomentRow(moment: LoggedMoment) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MatchPageGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = clockTime(moment.loggedAt), fontWeight = FontWeight.Medium)
            Text(text = "Moment", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (moment.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                moment.tags.forEach { LabelBadge(name = it.labelName, colorKey = it.labelColor) }
            }
        }
        moment.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
```

Add `@OptIn(ExperimentalLayoutApi::class)` where `FlowRow` is used, and the imports.

- [ ] **Step 3: Build**

Run: `./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease 2>&1 | grep -E "BUILD|error:|e: "`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/match/
git commit -m "feat(android): the match page lists moments among the rallies; badges and the tally wrap"
```

---

### Task 9: iOS model

**Files:**
- Modify: `iosApp/Sources/Scoring/ScoringModel.swift`
- Test: `iosApp/Tests/ScoringModelTests.swift`

**Interfaces:**
- Consumes: Tasks 2 and 4 through SKIE. Kotlin names in Swift:
  - `BoardTargetMoments.shared`, `BoardTargetRally`;
  - `BoardTargetKt.boardTarget(match:pendingOrdinal:)`, `BoardTargetKt.boardCaption(...)`;
  - `BoardNoticeMomentAdded(moment:)`;
  - `log.noticeMessage(notice:)`, `notice.actionLabel`.
  
  The file-facade class name follows the Kotlin file, so it is `BoardPresentationKt`, not `BoardTargetKt`. Confirm every name in the generated header at `shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h` before writing the Swift.
- Produces, mirroring Task 6 name for name:
  - properties `target`, `momentDraft`, `caption`, `canRemoveMoment`, `notice`, `noticeMessage`, `noticeAction`;
  - methods `tapMomentLabel(_:)`, `openMomentDraft()`, `setMomentDraftText(_:)`, `commitMomentDraft()`, `removeLastMoment()`, `reverseNotice()`, `dismissNotice()`.

- [ ] **Step 1: Write the failing tests**

First update the existing tests mechanically:
- `model.undoneNotice` becomes `model.noticeMessage`;
- `model.redo()` becomes `model.reverseNotice()`;
- `model.dismissUndoNotice()` becomes `model.dismissNotice()`.

Then add these eight cases. They are `ScoringViewModelTest`'s new cases name for name, as the file's header requires:

```swift
    func testTheBoardOpensOnTheLastRallyOfAMatchAlreadyScored() {
        let (repo, id, first) = fixture()
        first.score(.home)
        first.score(.away)
        let reopened = ScoringModel(scoreLogs: repo, scoreboardLabels: [goodShot], scoreLogId: id)
        XCTAssertEqual(reopened.pendingTagOrdinal, 1)
        XCTAssertEqual((reopened.target as? BoardTargetRally)?.ordinal, 1)
    }

    func testAtNilAllALabelLogsAMomentOfItsOwnPerTap() {
        let (_, _, model) = fixture()
        XCTAssertTrue(model.target is BoardTargetMoments)
        XCTAssertEqual(model.caption, "Tap a side to score, or a label to log a moment")
        model.tapMomentLabel(goodShot)
        model.tapMomentLabel(goodShot)
        XCTAssertEqual(model.match?.moments.count, 2)
        XCTAssertEqual(model.match?.points.count, 0)
        XCTAssertEqual(model.noticeMessage, "Moment added: Good shot")
        XCTAssertEqual(model.noticeAction, "Undo")
        XCTAssertEqual(model.caption, "2 moments logged")
        XCTAssertTrue(model.canSwitchFirstServer)
    }

    func testUndoInTheStripTakesTheMomentBack() {
        let (_, _, model) = fixture()
        model.tapMomentLabel(goodShot)
        model.reverseNotice()
        XCTAssertEqual(model.match?.moments.count, 0)
        XCTAssertNil(model.noticeMessage)
    }

    func testTheComposerCollectsLabelsAndTextIntoOneMoment() {
        let (_, _, model) = fixture()
        model.openMomentDraft()
        model.tapMomentLabel(goodShot)
        model.tapMomentLabel(forcedError)
        model.tapMomentLabel(goodShot)
        model.setMomentDraftText("late on the lift")
        XCTAssertEqual(model.caption, "New moment: Forced error")
        XCTAssertEqual(model.match?.moments.count, 0)
        model.commitMomentDraft()
        let moment = model.match?.moments.first
        XCTAssertEqual(moment?.tags.map(\.labelName), ["Forced error"])
        XCTAssertEqual(moment?.comment, "late on the lift")
        XCTAssertNil(model.momentDraft)
        XCTAssertEqual(model.noticeMessage, "Moment added: Forced error")
    }

    func testScoringWithTheComposerOpenSavesItFirstAndClosesIt() {
        let (_, _, model) = fixture()
        model.openMomentDraft()
        model.setMomentDraftText("tight")
        model.score(.home)
        XCTAssertNil(model.momentDraft)
        XCTAssertEqual(model.match?.moments.first?.afterPoints, 0)
        XCTAssertEqual(model.pendingTagOrdinal, 0)
    }

    func testUndoingTheOnlyRallyPointsTheRowBackAtMoments() {
        let (_, _, model) = fixture()
        model.tapMomentLabel(goodShot)
        model.score(.home)
        XCTAssertFalse(model.canSwitchFirstServer)
        model.undo()
        XCTAssertTrue(model.target is BoardTargetMoments)
        XCTAssertTrue(model.canSwitchFirstServer)
        XCTAssertEqual(model.match?.moments.count, 1)
    }

    func testRemoveLastMomentOffersItBack() {
        let (_, _, model) = fixture()
        XCTAssertFalse(model.canRemoveMoment)
        model.tapMomentLabel(goodShot)
        XCTAssertTrue(model.canRemoveMoment)
        model.removeLastMoment()
        XCTAssertEqual(model.match?.moments.count, 0)
        XCTAssertEqual(model.noticeMessage, "Moment removed: Good shot")
        model.reverseNotice()
        XCTAssertEqual(model.match?.moments.count, 1)
    }

    func testAfterFinishALabelLogsAMoment() {
        let (_, _, model) = fixture()
        model.score(.home)
        model.finish()
        XCTAssertTrue(model.target is BoardTargetMoments)
        model.tapMomentLabel(goodShot)
        XCTAssertEqual(model.match?.moments.first?.afterPoints, 1)
    }
```

- [ ] **Step 2: Implement**

Before writing, open `shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h` (build it with `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` if missing) and confirm these names:
- the file facade `BoardPresentationKt`;
- the classes `BoardTargetRally`, `BoardTargetMoments`, `BoardNoticeRallyUndone`, `BoardNoticeMomentAdded`, `BoardNoticeMomentRemoved` and `MomentDraft`;
- the members `actionLabel`, `isLive(notice:)`, `reverse(notice:)`, `logMoment(tags:)`, `openMomentDraft()`, `commitMomentDraft(draft:)`, `removeMoment(id:)`, `lastMoment()`, `toggle(tag:)` and `withText(text:)`.

If a label differs, use the header's.

In `ScoringModel`, delete `lastUndo`, `undone`, `undoneNotice`, `redo()` and `dismissUndoNotice()`. Add:

```swift
    /// The open moment composer. Only ever open while `target` is moments.
    private(set) var momentDraft: MomentDraft? = nil

    /// The strip's last notice, until its action is taken or it times out.
    private var lastNotice: BoardNotice? = nil

    /// What a chip tap does: tag `pendingTagOrdinal`'s rally, or log a moment.
    /// Nil with no match. See `boardTarget`.
    var target: BoardTarget? {
        guard let match else { return nil }
        return BoardPresentationKt.boardTarget(
            match: match,
            pendingOrdinal: pendingTagOrdinal.map { KotlinInt(int: Int32($0)) }
        )
    }

    /// The line beside Edit score. See `boardCaption`.
    var caption: String {
        guard let match, let target else { return "" }
        return BoardPresentationKt.boardCaption(
            match: match, target: target, hasBoardLabels: !labels.isEmpty,
            draft: target is BoardTargetMoments ? momentDraft : nil
        )
    }

    /// Whether the menu offers "Remove last moment".
    var canRemoveMoment: Bool { !(match?.moments.isEmpty ?? true) }

    /// The strip's notice while its action can still do what it says. Reads `log`
    /// first so the view is re-asked when it changes.
    var notice: BoardNotice? {
        guard log != nil, let lastNotice, scorer.isLive(notice: lastNotice) else { return nil }
        return lastNotice
    }
    var noticeMessage: String? { notice.flatMap { n in log?.noticeMessage(notice: n) } }
    var noticeAction: String? { notice?.actionLabel }
```

In `init`, directly after `readStore()`:

```swift
        // Opens on the last rally, so a board reopened mid-match tags it, as the
        // board would have the moment it was scored.
        pendingTagOrdinal = lastOrdinal
```

Replace the action methods. Every one that changes the log commits an open composer first, so a target change never leaves a draft behind:

```swift
    func score(_ side: Side) {
        commitMomentDraft()
        scorer.score(side: side)
        lastNotice = nil
        readStore()
        pendingTagOrdinal = lastOrdinal
    }

    /// A chip tap while the row targets moments: a ✓ on the composer's moment
    /// while it is open, otherwise a moment of its own.
    func tapMomentLabel(_ label: AnnotationLabel) {
        let tag = PointTag(labelName: label.name, labelColor: label.colorKey)
        if let draft = momentDraft {
            momentDraft = draft.toggle(tag: tag)
            return
        }
        guard let moment = scorer.logMoment(tags: [tag]) else { return }
        lastNotice = BoardNoticeMomentAdded(moment: moment)
        readStore()
    }

    func openMomentDraft() {
        if momentDraft == nil { momentDraft = scorer.openMomentDraft() }
    }

    /// Kept here so a score tap can save the draft; the field reads its own copy.
    func setMomentDraftText(_ text: String) {
        momentDraft = momentDraft?.withText(text: text)
    }

    func commitMomentDraft() {
        guard let draft = momentDraft else { return }
        momentDraft = nil
        if let moment = scorer.commitMomentDraft(draft: draft) {
            lastNotice = BoardNoticeMomentAdded(moment: moment)
        }
        readStore()
    }

    func removeLastMoment() {
        commitMomentDraft()
        guard let last = scorer.lastMoment(), let removed = scorer.removeMoment(id: last.id) else { return }
        lastNotice = BoardNoticeMomentRemoved(moment: removed)
        readStore()
    }

    func undo() {
        commitMomentDraft()
        guard let taken = scorer.undo() else { return }
        lastNotice = BoardNoticeRallyUndone(undone: taken)
        readStore()
        pendingTagOrdinal = lastOrdinal
    }

    /// The strip's action: Redo after an undo, Undo after a moment was added or removed.
    func reverseNotice() {
        guard let notice = lastNotice else { return }
        lastNotice = nil
        commitMomentDraft()
        scorer.reverse(notice: notice)
        readStore()
        if notice is BoardNoticeRallyUndone { pendingTagOrdinal = lastOrdinal }
    }

    /// The notice timed out. What it reported stands.
    func dismissNotice() {
        lastNotice = nil
    }
```

`editScore`, `resetCurrentGame` and `finish` keep their bodies. Each gains `commitMomentDraft()` as its first line, and `lastUndo = nil` becomes `lastNotice = nil`.

`readStore()` already clears a `pendingTagOrdinal` past the rallies, so `target` falls back to moments after an undo to zero with no extra code.

`canSwitchFirstServer` is unchanged: it already asks `scorer.canSwitchFirstServer()`, which Task 2 fixed.

- [ ] **Step 3: Run the tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO -only-testing:iosAppTests/ScoringModelTests \
  2>&1 | tee /tmp/ios-test.log | grep -E "Executed|error:|FAILED|TEST (SUCCEEDED|FAILED)"
```

Expected: `TEST SUCCEEDED`, with the `ScoringModelTests` count up by eight.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/Scoring/ScoringModel.swift iosApp/Tests/ScoringModelTests.swift
git commit -m "feat(ios): the board's model logs moments, composes them and opens on the last rally"
```

---

### Task 10: iOS board UI

**Files:**
- Modify: `iosApp/Sources/Scoring/ScoringView.swift`: `board` (the `.task(id: model.undone)` block, lines 116-125), `statusStrip` (lines 230-290), `controlBar` (lines 405-505), `tagChip` (lines 515-545). Delete `caption` (line 549).
- Test: `iosApp/UITests/ScoringBoardUITests.swift`

- [ ] **Step 1: The strip and the timer**

- The `.task(id: model.undone)` block becomes `.task(id: model.notice)`. It announces `model.noticeMessage` and calls `model.dismissNotice()`. Rename `undoNoticeDuration` to `noticeDuration`.
- In `statusStrip`:
  - `let notice = model.noticeMessage`;
  - `let announcement = notice ?? Self.announcement(log, match)`;
  - the action button is `if let action = model.noticeAction { Button(action) { model.reverseNotice() } … }`, styled as today's Redo.
- In the menu, after "Undo last rally":

```swift
                // Only when there is one. Beside Undo last rally rather than
                // replacing it: after Reset game the row logs moments while
                // earlier games still have rallies to take back.
                if model.canRemoveMoment {
                    Button("Remove last moment") { model.removeLastMoment() }
                }
```

- [ ] **Step 2: The label row, the composer and Note**

In `controlBar`:

```swift
        let target = model.target
        let draft = model.momentDraft
        let point = model.pendingPoint
        let quick = target is BoardTargetMoments && draft == nil
```

The chip call:

```swift
                        tagChip(
                            label: label,
                            selected: draft.map { d in d.tags.contains { $0.labelName == label.name } }
                                ?? point?.tags.contains { $0.labelName == label.name } ?? false,
                            hint: point != nil ? "Tags this rally" : (draft != nil ? "Adds it to this moment" : "Logs a moment"),
                            flashes: quick
                        ) {
                            if let ordinal = model.pendingTagOrdinal {
                                model.toggleTag(ordinal: ordinal, label: label)
                            } else {
                                if quick { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
                                model.tapMomentLabel(label)
                            }
                        }
```

The fields:

```swift
            if let draft {
                MomentNoteField(
                    onChange: { model.setMomentDraftText($0) },
                    onClose: { model.commitMomentDraft() }
                )
                .id(draft.openedAt.toEpochMilliseconds())
                .padding(.horizontal, 8)
            } else if noteOpen, let point, let ordinal = model.pendingTagOrdinal {
                // the rally NoteField, unchanged
            }
```

- The caption is `Text(model.caption)`, with the same modifiers as today.
- The Note button:

```swift
                Button("Note") {
                    if target is BoardTargetRally {
                        noteOpen.toggle()
                    } else if model.momentDraft == nil {
                        model.openMomentDraft()
                    } else {
                        model.commitMomentDraft()
                    }
                }
                    .tint(Shuttl.accent)
```

  Remove `.disabled(point == nil)`.
- Add, beside the existing `.task` modifiers on `board`:

```swift
        // A rally note belongs to a rally. When the row stops pointing at one, the
        // field closes rather than reopening on the next rally scored.
        .onChange(of: model.target is BoardTargetMoments) { _, moments in if moments { noteOpen = false } }
```

- [ ] **Step 3: The chip**

Replace `tagChip`. The flash is a press-in plus a white wash: the chip's size never changes, so the row never reflows. Under Reduce Motion the press-in is dropped and the wash and the haptic stay.

```swift
    @State private var flashing: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @ViewBuilder
    private func tagChip(
        label: AnnotationLabel,
        selected: Bool,
        hint: String,
        flashes: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let swatch = LabelColor.companion.from(key: label.colorKey)
        let container = swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary
        let onContainer = swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text
        let lit = flashing == label.id

        Button {
            action()
            guard flashes else { return }
            withAnimation(.easeOut(duration: 0.08)) { flashing = label.id }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(90))
                withAnimation(.easeOut(duration: 0.26)) { if flashing == label.id { flashing = nil } }
            }
        } label: {
            Text(selected ? "✓ \(label.name)" : label.name)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(onContainer)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(container)
                .overlay(Color.white.opacity(lit ? 0.32 : 0))
                .clipShape(Capsule())
                .scaleEffect(lit && !reduceMotion ? 0.94 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityHint(hint)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
```

Update the comment that explained the greyed state: the row is never inert now.

- [ ] **Step 4: The composer's field**

Add beside `NoteField`:

```swift
/// The composer's note. Pushes its text to the model as it is typed, so a score
/// tap can save the moment, and saves it once when the field goes away.
private struct MomentNoteField: View {
    let onChange: (String) -> Void
    let onClose: () -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("Note on this moment", text: $text)
            .textFieldStyle(.roundedBorder)
            .focused($focused)
            .submitLabel(.done)
            .onSubmit { onClose() }
            .onChange(of: text) { _, new in onChange(new) }
            .onAppear { focused = true }
            .onDisappear { onClose() }
    }
}
```

- [ ] **Step 5: UI test**

In `ScoringBoardUITests.testTappingALabelChipDoesNotAlsoScoreARally`:
- the 0-0 assertion becomes `app.staticTexts["Tap a side to score, or a label to log a moment"]`, because the sim account has board labels;
- after it, add:

```swift
        // At 0-0 a chip logs a moment and must not score a rally either.
        app.buttons["Good shot"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Moment added: Good shot"].waitForExistence(timeout: 5), "a chip at 0-0 did not log a moment")
        XCTAssertTrue(app.staticTexts["1 moment logged"].exists, "a chip at 0-0 also scored a rally")
```

- [ ] **Step 6: Build and run every iOS test**

Run the Task 9 command without `-only-testing`.
Expected: `TEST SUCCEEDED` for both `iosAppTests` and `iosAppUITests`. Grep both suite names: the UI suite reports last.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/Scoring/ScoringView.swift iosApp/UITests/ScoringBoardUITests.swift
git commit -m "feat(ios): the board's labels log moments at 0-0, with a composer, a strip notice and Remove last moment"
```

---

### Task 11: iOS match page

**Files:**
- Modify: `iosApp/Sources/Match/PointsFacet.swift`
- Modify: `iosApp/Sources/Match/MatchView.swift:748`

- [ ] **Step 1: Pass the timeline**

In `MatchView.swift:748`, replace `points: matchModel.match?.points ?? []` with `timeline: matchModel.match.map { MatchTimelineKt.matchTimeline(state: $0) } ?? [], match: matchModel.match`.

- [ ] **Step 2: Render it**

In `PointsFacet.swift`:
- `let points: [ScoredPoint]` becomes `let timeline: [TimelineEntry]` and `let match: MatchState?`.
- The button text is `match.map { ScoreMatchCardKt.scoreButtonLabel(state: $0) } ?? "Score"`.
- The tally headline is `Text(tally.headline)`. Its `HStack(spacing: 12)` becomes `ChipFlow(spacing: 12)`: the pre-existing overflow, fixed as on Android.
- The empty state, when `timeline.isEmpty`: title "Nothing logged yet", message "Points you score and moments you log are listed here."
- The list:

```swift
            // Newest first: courtside, the rally you care about is the last one.
            Section {
                ForEach(timeline.reversed().map(TimelineRow.init)) { row in
                    switch onEnum(of: row.entry) {
                    case .rally(let rally): pointRow(rally.point).pointsListRow(top: 10, bottom: 10)
                    case .moment(let moment): momentRow(moment.moment).pointsListRow(top: 10, bottom: 10)
                    }
                }
            }
```

At file scope:

```swift
/// A timeline entry with an id that survives a new row arriving. Offsets would
/// re-identify, and so re-animate, every row below a newly logged moment.
private struct TimelineRow: Identifiable {
    let entry: TimelineEntry
    var id: String {
        switch onEnum(of: entry) {
        case .rally(let r): return "point-\(r.point.ordinal)"
        case .moment(let m): return "moment-\(m.moment.id)"
        }
    }
}
```

In `pointRow`, the tags `HStack(spacing: 6)` becomes `ChipFlow(spacing: 6)`. Add:

```swift
    /// A moment, shaped like a rally's row so the two read as one list: the time
    /// where a rally has its score, and "Moment" where it has who won it.
    @ViewBuilder
    private func momentRow(_ moment: LoggedMoment) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text(MomentsKt.clockTime(at: moment.loggedAt))
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.text)
                Text("Moment")
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            if !moment.tags.isEmpty {
                ChipFlow(spacing: 6) {
                    ForEach(moment.tags, id: \.labelName) { tag in
                        LabelBadge(name: tag.labelName, colorKey: tag.labelColor)
                    }
                }
            }
            if let comment = moment.comment, !comment.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(comment).shuttlType(ShuttlType.bodySmall).foregroundStyle(Shuttl.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
```

If `onEnum(of:)` is not generated for `TimelineEntry`, check SKIE's sealed-class setting in `shared/build.gradle.kts`, and fall back to `if let r = entry as? TimelineEntryRally`.

- [ ] **Step 3: Build and run every iOS test**

Run the Task 10 command.
Expected: `TEST SUCCEEDED` for both suites.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/Match/
git commit -m "feat(ios): the match page lists moments among the rallies; badges and the tally wrap"
```

---

### Task 12: On a real phone, screenshots, and the changelog

The UI/UX bar for this feature is pixel level. Every check below is looked at, not assumed, on both platforms, in light and dark.

**Files:**
- Create: screenshots under `docs/screenshots/` named `2026-10-13-<platform>-moments-<state>-<theme>.png`
- Modify: `CHANGELOG.md` (under `## [Unreleased]` / `### Added`)

- [ ] **Step 1: Install**

- **Android:** `ANDROID_SERIAL=<S23 serial> ./gradlew :androidApp:installDebug`. Pin the serial: `installDebug` hits every attached device.
- **iOS:** build and run on the iPhone 17 Pro simulator. Sign-in on the sim needs the user, so hand that step over.

- [ ] **Step 2: Walk the board, capturing each state to a scratch path and viewing it before copying it into `docs/screenshots`**

1. A new match at 0-0:
   - chips at full colour;
   - caption "Tap a side to score, or a label to log a moment" on two lines beside Edit score, not clipped;
   - Note enabled.
2. Tap "Good shot" three times:
   - each tap gives a press-in, a wash and a light haptic;
   - the strip shows "Moment added: Good shot" with Undo, then clears after 5 seconds;
   - the caption reads "3 moments logged";
   - no chip moves and no row reflows.
3. Tap Undo in the strip: the caption reads "2 moments logged".
4. Note:
   - the field opens focused, with "Note on this moment";
   - tap two chips: they get ✓, and the caption reads "New moment: A, B";
   - type, then press Done on the keyboard: the strip shows "Moment added: A, B".
5. Open Note again, type, and tap a half to score: one moment is saved, the field closes, and the caption reads "Point 1: 1-0".
6. The menu shows "Remove last moment". Tap it: the strip offers Undo.
7. Undo the only rally: the row is back on moments, and the SERVE pill can be switched.
8. Repeat states 1, 2 and 4 at Android font size 2x and at iOS AX5. Look specifically at:
   - the strip's notice next to Undo and the menu: ellipsis, no overlap;
   - the caption next to Edit score;
   - the composer field above the keyboard.
9. TalkBack and VoiceOver on one chip in each mode: the hint reads "Logs a moment", "Adds it to this moment" or "Tags this rally", and the notice is announced.

- [ ] **Step 3: Walk the match page**

1. A moments-only match:
   - the header shows "6 moments" and the button reads "Resume";
   - the tally headline is "6 moments" and its badges wrap;
   - moment rows show their time and "Moment", with badges wrapping onto a second line when there are many, and the note under them;
   - the row is aligned to the 24dp gutter like a rally row.
2. A mixed match: moments sit before rally 1 and between the right rallies, newest first.
3. Share: the export text reads as in Task 5.
4. The match list: a moments-only match has no "#" score glyph and its score line reads "6 moments".

- [ ] **Step 4: Report, do not fix silently**

List anything below the bar with a screenshot, plus these known pre-existing items:
- Android draws a selected chip in bold while iOS keeps one weight, so the Android chip changes width when ticked.
- The rally note field has no keyboard Done action.

Ask before fixing anything outside this feature's surfaces.

- [ ] **Step 5: Changelog**

Add under `### Added`:

```markdown
- **Labels and notes without a score.** At 0-0, or in a match nobody is
  scoring, tapping a label on the board logs a moment: the label and the time.
  Note writes a longer one, with as many labels as it needs. "Moment added"
  in the bar at the top offers Undo, and "Remove last moment" in the menu takes
  one back later. Moments are listed with the rallies on the match page, counted
  in the tally and included when the match is shared. A board reopened on a
  match already being scored now points at its last rally, so its labels work
  straight away.
```

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md docs/screenshots/2026-10-13-*
git commit -m "docs: moments on the board and the match page, with screenshots and the changelog"
```

- [ ] **Step 7: Hand over**

The last check needs a real phone in a real hall. Hand it to the owner explicitly instead of closing the plan out: log moments through one practice game without scoring, then review the match page that evening.
