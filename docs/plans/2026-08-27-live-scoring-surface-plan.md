# Live Scoring: The Scoring Surface (L1b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the coach score a match courtside. Tap the side that won the rally, tap again to tag it from the labels he already uses, and the match on his phone is a point-by-point record with his own vocabulary on it - whether or not a video ever arrives.

**Architecture:** Every mutation of a match's log goes through one shared `MatchScorer`, so the two platforms cannot disagree about what "undo" or "reset game" means. Neither platform gains a new flow to bridge: both already observe `ScoreLogsRepository.logs` and fold on demand, which is the shape `LocalVideoRepository.entries` has used since the local video feature shipped. The surface itself is native on each side.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose / Material 3 on Android, SwiftUI on iOS. Tests: kotlin.test plus kotest-assertions, XCTest.

**Spec:** `docs/plans/2026-08-26-live-scoring-review-design.md`, section 7, layer L1.

**Depends on:**
- `docs/plans/2026-08-26-live-scoring-engine-plan.md` (L0) - **complete**, 60 tests green on both platforms.
- `docs/plans/2026-08-27-live-scoring-match-record-plan.md` (L1a) - Tasks 2 to 6 **complete**; Task 1 (the `score_logs` migration) and Tasks 7 to 12 (the platform UI) still pending. **This plan needs L1a's UI tasks done first**: it adds scoring to a match page that L1a creates, reached from a list row L1a adds.

## Scope

**In:** the shared scoring vocabulary; the courtside surface on both platforms; tagging during play; the tag tally; a text export; resuming and finishing a match; keeping the screen awake.

**Out:**
- **Binding a video onto a match, and the reconcile screen.** That is L2, and it is the payoff this makes possible.
- **A second device following the score** (L3) and **a public spectator link** (L4). Both recorded in the design doc, neither proposed.
- **An in-app recorder.** §6.2: the first draft of the design called it essential and §2 removed the need. It comes back on its own merits or not at all.

## Deviations from the spec

1. **No "switch ends" control.** §7's L1 lists it among the scoring controls. It is not here, and the reason is structural rather than an oversight: `MatchState.endsSwapCount` is *derived* by the fold from the rules, so a manual override would need a new event kind and, more importantly, a reason to distrust the fold. The board shows which end each side is on and changes it when the rules say so. If a club plays a variant the rules cannot express, that is a `ScoringRules` gap, not a button.
2. **Portrait first; landscape is Task 9 and is separable.** §7's L1 asks for a landscape surface. iOS is locked to portrait in `Info.plist` and the app has **no orientation handling anywhere today** - forcing landscape needs an `AppDelegate` with an app-wide orientation lock that every other screen then has to opt out of. That is a change with app-wide blast radius sitting underneath a feature that does not need it to work. So the surface is built portrait-first (two stacked full-width tap zones read fine at arm's length on a bench), and landscape lands last, isolated, and droppable without touching anything else.
3. **`MatchScorer` exposes no flow of its own.** The obvious design gives it a `StateFlow<MatchState?>`, which would be a new bridged flow and a second answer to "what does null mean". Instead it is a mutation vocabulary; both platforms already observe `ScoreLogsRepository.logs` and call `ScoreLog.state()`, and "the match is gone" is then the same absent-row case the match page already handles.

## Global Constraints

- No em dash in any prose, comment, commit message or user-facing string. Use a plain dash.
- No agent attribution in commit messages, and no "Generated with" footer.
- **Every mutation of a log goes through `MatchScorer`.** No screen calls `ScoreLogsRepository.replaceEvents` directly. That is the whole reason the class exists.
- **Tagging must never block scoring.** One tap scores. The tag row is already on screen; a second tap tags. No modal, no required field, no confirmation. If tagging can cost the coach the next rally he will stop using it, and the feature dies.
- **The label palette is `AnnotationLabelsRepository.labels`** - already seeded with `Good shot` / `Forced error` / `Unforced error`, already cached per owner and readable cold and offline. Do not add a second source of labels, and do not fetch on entering the surface: a sports hall has no signal and the cached `StateFlow` is the answer.
- A tag snapshots the label's name and colour onto the point, exactly as `RallyAnnotation` does. Renaming a label later must not rewrite courtside history.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`; run `cd iosApp && xcodegen generate && cd ..` after adding a Swift file.
- Test commands:

```bash
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
```

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

## File Structure

**Shared (KMP)**
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt` - the only vocabulary for changing a match.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt` - the tag tally and the text export.
- Modify `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt` - extract the label roll-up so the tally and the clip summary share one set of counting rules.
- Modify `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt` - nothing suspending is added; only a factory if Task 7 finds SKIE construction awkward.
- Create `shared/src/commonTest/.../scoring/MatchScorerTest.kt`, `ScoreTagSummaryTest.kt`.

**Android**
- Create `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt`, `ScoringScreen.kt`.
- Modify `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchScreen.kt` (from L1a) and `nav/Route.kt`, `AuthGate.kt`, `MainActivity.kt`.
- Create `androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt`.

**iOS**
- Create `iosApp/Sources/Scoring/ScoringView.swift`, `ScoringModel.swift`.
- Modify `iosApp/Sources/Scoring/ScoreMatchView.swift` (from L1a).
- Create `iosApp/Tests/ScoringModelTests.swift`.
- Task 9 only: modify `iosApp/project.yml`, `iosApp/Sources/RallyIOSApp.swift`.

**Docs**
- Modify `CHANGELOG.md`.

---

### Task 1: The scoring vocabulary

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchScorerTest.kt`

**Interfaces:**
- Consumes: `ScoreLogsRepository`, `ScoreEvent`, `undoLast`, `tagPoint`, `dropPointsFrom`, `MatchState`.
- Produces:
  - `class MatchScorer(repo: ScoreLogsRepository, scoreLogId: String)`
  - `fun state(): MatchState?` - null when the match is not on this device
  - `fun score(side: Side)`
  - `fun tagPoint(ordinal: Int, tags: List<PointTag>, comment: String?)`
  - `fun undo()`
  - `fun resetCurrentGame()`
  - `fun finish()`
  - `fun canUndo(): Boolean`, `fun canScore(): Boolean`
  - `fun firstOrdinalOfCurrentGame(state: MatchState): Int`

`tagPoint` takes an ordinal rather than meaning "the last point". The two-tap path passes `pointCount - 1`, but §7's L1 also wants a longer comment added between rallies or at the interval, and by then the last point is the wrong point. An ordinal covers both; "last point" covers only one and would have to be widened later.

- [x] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchScorerTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchScorerTest {

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    private fun repo() =
        ScoreLogsRepository(client = null, settings = MapSettings(), now = { t0 }, ownerId = { "owner-1" })

    private fun scorer(
        repo: ScoreLogsRepository = repo(),
        rules: ScoringRules = ScoringRules.BWF_21,
    ): Pair<ScoreLogsRepository, MatchScorer> {
        val log = repo.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = rules,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        return repo to MatchScorer(repo, log.id)
    }

    @Test
    fun a_scorer_for_a_match_that_is_not_here_reports_no_state_rather_than_crashing() {
        // Reachable two ways: the match was deleted on another screen while this one
        // was open, and a cold start before the cache has loaded. Both platforms get
        // the same answer from here rather than inventing one each.
        val scorer = MatchScorer(repo(), "not-a-match")
        scorer.state().shouldBeNull()
        scorer.canScore() shouldBe false
        scorer.canUndo() shouldBe false
        // And every mutation is a no-op rather than a throw.
        scorer.score(Side.HOME)
        scorer.undo()
        scorer.finish()
        scorer.state().shouldBeNull()
    }

    @Test
    fun scoring_a_point_writes_it_straight_through_to_the_store() {
        // Not "eventually". The phone goes in a bag between games and the process
        // does not survive it, so the point has to be on disk before the next rally.
        val (repo, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
        repo.logs.value.first().events shouldBe listOf(ScoreEvent.PointTo(Side.HOME))
    }

    @Test
    fun undo_removes_the_last_thing_that_happened() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.undo()
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun undoing_a_tag_leaves_the_point_it_named() {
        // The log is append-only, so a tag is its own entry and undo takes it first.
        // Two taps to remove a tagged point is correct rather than surprising: the
        // coach watches the tag go, then the point.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Forced error", "amber")), null)
        scorer.undo()
        scorer.state()?.points?.first()?.tags?.shouldBeEmpty()
        scorer.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_tag_can_land_on_a_point_that_is_no_longer_the_last_one() {
        // The interval case: three rallies have gone by and the coach finally has
        // time to write down what happened on the first one.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), "cross court winner")
        val points = scorer.state()!!.points
        points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
        points[0].comment shouldBe "cross court winner"
        points[2].tags.shouldBeEmpty()
    }

    @Test
    fun re_tagging_a_point_replaces_what_was_there() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), null)
        scorer.tagPoint(0, listOf(PointTag("Unforced error", "red")), null)
        scorer.state()!!.points[0].tags.map { it.labelName } shouldBe listOf("Unforced error")
    }

    @Test
    fun resetting_a_game_leaves_the_games_before_it_alone() {
        // The control the coach reaches for when he has been scoring the wrong side
        // for half a game. It must not cost him the game he already finished.
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }          // game one to home
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        val state = scorer.state()!!
        state.gameIndex shouldBe 1
        state.currentGame shouldBe SideScore.ZERO
        state.completedGames shouldBe listOf(SideScore(home = 21, away = 0))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun resetting_a_game_keeps_the_tags_on_the_games_before_it() {
        // The trap: ordinals are global across the match, so a cut that renumbered
        // them would slide every earlier tag onto a neighbouring rally.
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.tagPoint(0, listOf(PointTag("Good shot", "green")), null)
        repeat(20) { scorer.score(Side.HOME) }          // 21-0, game one done
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        scorer.state()!!.points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
    }

    @Test
    fun resetting_the_first_game_empties_the_match() {
        val (_, scorer) = scorer()
        scorer.score(Side.HOME)
        scorer.score(Side.AWAY)
        scorer.resetCurrentGame()
        scorer.state()!!.points.shouldBeEmpty()
        scorer.state()!!.currentGame shouldBe SideScore.ZERO
    }

    @Test
    fun the_first_ordinal_of_the_current_game_is_where_a_reset_cuts() {
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }
        scorer.score(Side.AWAY)
        scorer.firstOrdinalOfCurrentGame(scorer.state()!!) shouldBe 21
    }

    @Test
    fun the_first_ordinal_of_an_unstarted_game_is_the_point_count() {
        // Just after a game ends there are no points in the new game yet, and a cut
        // there has to be a no-op rather than eating the game that just finished.
        val (_, scorer) = scorer()
        repeat(21) { scorer.score(Side.HOME) }
        scorer.firstOrdinalOfCurrentGame(scorer.state()!!) shouldBe 21
        scorer.resetCurrentGame()
        scorer.state()!!.completedGames shouldBe listOf(SideScore(home = 21, away = 0))
    }

    @Test
    fun a_finished_match_cannot_be_scored_into() {
        val (_, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.state()!!.isOver shouldBe true
        scorer.canScore() shouldBe false
        scorer.score(Side.AWAY)
        // The fold ignores points after the end, but the log must not collect them
        // either: a stray tap is not a rally.
        scorer.state()!!.pointCount shouldBe 42
    }

    @Test
    fun a_finished_match_can_still_be_undone() {
        // The match ended because of a mis-tap. Undo is the way back.
        val (_, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.canUndo() shouldBe true
        scorer.undo()
        scorer.state()!!.isOver shouldBe false
    }

    @Test
    fun finishing_marks_the_match_unbound_rather_than_incomplete() {
        val (repo, scorer) = scorer()
        repeat(42) { scorer.score(Side.HOME) }
        scorer.finish()
        repo.logs.value.first().status shouldBe ScoreLogStatus.UNBOUND
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.MatchScorerTest"`
Expected: FAIL to compile, "Unresolved reference: MatchScorer".

- [x] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt`:

```kotlin
package com.badmintontracker.shared.scoring

/**
 * Everything that can change a match, in one place.
 *
 * Both platforms drive the courtside surface through this rather than reaching for
 * [ScoreLogsRepository.replaceEvents] themselves, so "undo" and "reset game" cannot
 * come to mean two different things on two phones. It deliberately publishes no
 * flow of its own: the surface observes [ScoreLogsRepository.logs], which it
 * already does for the match list, and folds through [ScoreLog.state].
 *
 * Every method is a no-op when the match is not on this device. That is reachable
 * two ways - deleted from another screen while this one is open, and a cold start
 * before the cache has loaded - and neither is worth a crash on a bench.
 */
class MatchScorer(
    private val repo: ScoreLogsRepository,
    private val scoreLogId: String,
) {

    /** Null when the match is not on this device. */
    fun state(): MatchState? = repo.get(scoreLogId)?.state()

    /** False once the match has a winner, so a stray tap is never recorded as a rally. */
    fun canScore(): Boolean = state()?.isOver == false

    /** True whenever there is anything at all to take back, a finished match included. */
    fun canUndo(): Boolean = repo.get(scoreLogId)?.events?.isNotEmpty() == true

    fun score(side: Side) {
        if (!canScore()) return
        edit { it + ScoreEvent.PointTo(side) }
    }

    /**
     * Sets the tags and comment on point [ordinal], replacing whatever was there.
     * An ordinal rather than "the last point", because a comment written at the
     * interval belongs to a rally three points ago.
     */
    fun tagPoint(ordinal: Int, tags: List<PointTag>, comment: String?) =
        edit { it.tagPoint(ordinal, tags, comment) }

    /** Drops the last entry, which is the tag if one was just added, and the point otherwise. */
    fun undo() = edit { it.undoLast() }

    /**
     * Clears the game being played and leaves every finished game alone. The cut is
     * by ordinal rather than by position in the list precisely so the tags on
     * earlier games keep naming the points they named.
     */
    fun resetCurrentGame() {
        val state = state() ?: return
        edit { it.dropPointsFrom(firstOrdinalOfCurrentGame(state)) }
    }

    /** Ends the match as [ScoreLogStatus.UNBOUND] - a terminal state, not a half-done one. */
    fun finish() {
        if (repo.get(scoreLogId) == null) return
        repo.finish(scoreLogId)
    }

    /**
     * Where a reset cuts. When the current game has no points yet - the instant
     * after a game ends - this is the match's point count, so the cut is a no-op
     * rather than eating the game that just finished.
     */
    fun firstOrdinalOfCurrentGame(state: MatchState): Int =
        state.points.firstOrNull { it.gameIndex == state.gameIndex }?.ordinal ?: state.pointCount

    private fun edit(transform: (List<ScoreEvent>) -> List<ScoreEvent>) {
        val log = repo.get(scoreLogId) ?: return
        repo.replaceEvents(scoreLogId, transform(log.events))
    }
}
```

- [x] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.MatchScorerTest"`
Expected: PASS, 14 tests.

- [x] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchScorer.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchScorerTest.kt
git commit -m "feat(scoring): put every change to a match behind one shared vocabulary"
```

---

### Task 2: The tag tally, counted the way the clip summary counts

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt`

**Interfaces:**
- Consumes: `LabelCount` (existing), `MatchState`, `ScoredPoint`.
- Produces:
  - `data class LabelRef(val name: String, val colorKey: String?, val recency: Long, val tieBreak: String)` in `MatchLabelSummary.kt`
  - `fun rollUpLabels(refs: List<LabelRef>): List<LabelCount>` in `MatchLabelSummary.kt`
  - `data class ScoreTagSummary(val taggedPointCount: Int, val labels: List<LabelCount>)` with `val isEmpty: Boolean`
  - `fun buildScoreTagSummary(state: MatchState): ScoreTagSummary`

§7's L1 asks for the tag tally "in the same aggregation shape as `buildMatchLabelSummary`". Same shape is not enough: they have to count and round *identically*, or the same coach sees 45% on the match page and 46% on the rally page. So the roll-up moves into one function and `buildMatchLabelSummary` is refactored onto it.

**`MatchLabelSummaryTest` must pass completely unedited afterwards.** It is the regression check that the refactor changed no counting rule. If it needs a single edit, the refactor is wrong.

- [x] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.model.LabelCount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ScoreTagSummaryTest {

    private fun stateWith(vararg tagsPerPoint: List<PointTag>): MatchState {
        val points = tagsPerPoint.indices.map { ScoreEvent.PointTo(Side.HOME) }
        val tags = tagsPerPoint.mapIndexed { i, t -> ScoreEvent.TagPoint(i, t, null) }
        return foldMatchState(
            ScoringRules.BWF_21,
            MatchSetup(doubles = false, firstServer = Side.HOME),
            points + tags,
        )
    }

    private fun tag(name: String, color: String? = "green") = listOf(PointTag(name, color))

    @Test
    fun an_untagged_match_has_an_empty_tally() {
        val summary = buildScoreTagSummary(stateWith(emptyList(), emptyList()))
        summary.taggedPointCount shouldBe 0
        summary.labels.shouldBeEmpty()
        summary.isEmpty shouldBe true
    }

    @Test
    fun tags_are_counted_and_ordered_by_how_often_they_were_used() {
        val summary = buildScoreTagSummary(
            stateWith(
                tag("Forced error", "amber"),
                tag("Good shot"),
                tag("Forced error", "amber"),
                tag("Forced error", "amber"),
            )
        )
        summary.taggedPointCount shouldBe 4
        summary.labels shouldBe listOf(
            LabelCount(name = "Forced error", colorKey = "amber", count = 3, sharePercent = 75),
            LabelCount(name = "Good shot", colorKey = "green", count = 1, sharePercent = 25),
        )
    }

    @Test
    fun one_point_carrying_two_tags_counts_once_for_each() {
        val summary = buildScoreTagSummary(
            stateWith(listOf(PointTag("Good shot", "green"), PointTag("Forced error", "amber")))
        )
        // The point count is points; the label counts are labels. A rally can be
        // both a good shot and a forced error, and both belong in the tally.
        summary.taggedPointCount shouldBe 1
        summary.labels.map { it.count } shouldBe listOf(1, 1)
    }

    @Test
    fun labels_group_case_insensitively_and_the_newest_spelling_wins() {
        // Same rule as buildMatchLabelSummary: the name is a snapshot, so it is the
        // only identity available, and folding case matches what the database
        // enforces on the live palette.
        val summary = buildScoreTagSummary(
            stateWith(tag("forced error", "amber"), tag("Forced Error", "red"))
        )
        summary.labels shouldBe listOf(
            LabelCount(name = "Forced Error", colorKey = "red", count = 2, sharePercent = 100),
        )
    }

    @Test
    fun a_blank_tag_name_is_not_a_tag() {
        buildScoreTagSummary(stateWith(listOf(PointTag("   ", "green")))).isEmpty shouldBe true
    }

    @Test
    fun ties_break_the_same_way_the_clip_summary_breaks_them() {
        // The assertion that matters most in this file. Two labels used equally
        // often must come out in the same order here as on the rally page, or the
        // same match reads two ways in two places.
        val summary = buildScoreTagSummary(tagsOf("Zebra", "Alpha"))
        summary.labels.map { it.name } shouldBe listOf("Alpha", "Zebra")
    }

    private fun tagsOf(vararg names: String) =
        stateWith(*names.map { listOf(PointTag(it, "green")) }.toTypedArray())

    @Test
    fun shares_round_rather_than_truncate() {
        // Same reason the clip summary rounds: an integer division would print 16
        // beside a bar drawn at 17.
        val summary = buildScoreTagSummary(tagsOf("A", "A", "B", "B", "B", "C"))
        summary.labels.map { it.sharePercent } shouldBe listOf(50, 33, 17)
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreTagSummaryTest"`
Expected: FAIL to compile, "Unresolved reference: buildScoreTagSummary".

- [x] **Step 3: Extract the roll-up**

In `MatchLabelSummary.kt`, add above `buildMatchLabelSummary`:

```kotlin
/**
 * One use of a label, reduced to what counting needs. [recency] breaks the tie for
 * which spelling and colour a group displays - annotations use their creation time,
 * scored points use their ordinal. Higher wins.
 */
data class LabelRef(val name: String, val colorKey: String?, val recency: Long)

/**
 * Rolls label uses up into counts. The single home of every counting rule the app
 * has: which names group together, which spelling a group shows, how a share is
 * rounded, and how equal counts are ordered.
 *
 * It exists as its own function because two features roll labels up - the rally
 * page's summary and the courtside tag tally - and a rule implemented twice is a
 * rule that will eventually disagree with itself in front of the same user.
 */
fun rollUpLabels(refs: List<LabelRef>): List<LabelCount> {
    if (refs.isEmpty()) return emptyList()
    val total = refs.size
    return refs
        .groupBy { it.name.lowercase() }
        .map { (key, group) ->
            val newest = group.maxWith(compareBy({ it.recency }, { it.name }))
            key to LabelCount(
                name = newest.name,
                colorKey = newest.colorKey,
                count = group.size,
                // Double division on purpose: integer division truncates, which
                // would print 16 next to a bar drawn at 17.
                sharePercent = ((group.size * 100.0) / total).roundToInt(),
            )
        }
        .sortedWith(
            compareByDescending<Pair<String, LabelCount>> { it.second.count }
                .thenBy { it.second.name.lowercase() }
        )
        .map { it.second }
}
```

Then rewrite the middle of `buildMatchLabelSummary` to call it, leaving its documentation and its `topRally` computation exactly as they are:

```kotlin
    val labels = rollUpLabels(
        tagged.map { (annotation, label) ->
            LabelRef(
                name = label,
                colorKey = annotation.labelColor,
                // The annotations' own ordering key, unchanged: creation time, then
                // id, so two notes made in the same second still order stably.
                recency = annotation.createdAt.toEpochMilliseconds(),
            )
        }
    )
```

**Resolved during execution.** The caveat here was real: `a_tie_on_created_at_breaks_on_id_so_the_colour_is_never_row_order_dependent` lists the winning note first, so a `(recency, name)` tie break would have passed by accident while defeating exactly what that test checks. `LabelRef` therefore carries an explicit `tieBreak` - the annotation id for annotations, an empty string for points, whose ordinals cannot tie. Recency is also microseconds rather than milliseconds, because `timestamptz` stores microseconds and collapsing to millis would invent ties. `MatchLabelSummaryTest` passes unedited.

- [x] **Step 4: Write the tally**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.model.LabelCount
import com.badmintontracker.shared.model.LabelRef
import com.badmintontracker.shared.model.rollUpLabels

/**
 * How a match was tagged courtside.
 *
 * [taggedPointCount] counts points, [labels] counts labels, and the two differ:
 * one rally can be both a good shot and a forced error. Both numbers are useful and
 * neither is a rounding of the other.
 */
data class ScoreTagSummary(
    val taggedPointCount: Int,
    val labels: List<LabelCount>,
) {
    val isEmpty: Boolean get() = taggedPointCount == 0

    companion object { val EMPTY = ScoreTagSummary(taggedPointCount = 0, labels = emptyList()) }
}

/**
 * Rolls a match's courtside tags up. Shares [rollUpLabels] with the rally page's
 * summary rather than restating its rules, so the same match cannot report one
 * share here and another there.
 */
fun buildScoreTagSummary(state: MatchState): ScoreTagSummary {
    val refs = state.points.flatMap { point ->
        point.tags.mapNotNull { tag ->
            val name = tag.labelName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LabelRef(name = name, colorKey = tag.labelColor, recency = point.ordinal.toLong())
        }
    }
    if (refs.isEmpty()) return ScoreTagSummary.EMPTY
    return ScoreTagSummary(
        taggedPointCount = state.points.count { point ->
            point.tags.any { it.labelName.isNotBlank() }
        },
        labels = rollUpLabels(refs),
    )
}
```

- [x] **Step 5: Run both suites to verify the refactor changed nothing**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.MatchLabelSummaryTest"
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreTagSummaryTest"
```

Expected: PASS both. `MatchLabelSummaryTest` **unedited** - that is the point of running it.

- [x] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt
git commit -m "feat(scoring): tally courtside tags with the rally page's counting rules"
```

---

### Task 3: The text export

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt`

**Interfaces:**
- Consumes: `ScoreLog`, `MatchState`, `ScoredPoint`, `sideLabel`, `scoreLine`.
- Produces: `fun exportMatchText(log: ScoreLog): String`

§7's L1 lists a text export among the outputs a match has even if no video ever arrives. It is the thing a coach pastes into a message to a player that evening.

- [x] **Step 1: Write the failing test**

Add to `ScoreTagSummaryTest.kt`:

```kotlin
    @Test
    fun a_match_exports_as_something_a_coach_can_paste_into_a_message() {
        val log = ScoreLog(
            id = "log-1", videoId = null, title = "Thu League",
            homePlayers = listOf("Coen"), awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
            events = listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), "cross court"),
                ScoreEvent.PointTo(Side.AWAY),
            ),
            status = ScoreLogStatus.LIVE,
            createdAt = kotlinx.datetime.Instant.parse("2026-08-27T18:00:00Z"),
            updatedAt = kotlinx.datetime.Instant.parse("2026-08-27T18:00:00Z"),
        )
        exportMatchText(log) shouldBe """
            Thu League
            Coen vs Marco
            1-1

            1. 1-0 Coen - Good shot (cross court)
            2. 1-1 Marco
        """.trimIndent()
    }

    @Test
    fun an_unstarted_match_exports_its_header_and_nothing_else() {
        val log = ScoreLog(
            id = "log-1", videoId = null, title = "Thu League",
            homePlayers = listOf("Coen"), awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
            events = emptyList(), status = ScoreLogStatus.LIVE,
            createdAt = kotlinx.datetime.Instant.parse("2026-08-27T18:00:00Z"),
            updatedAt = kotlinx.datetime.Instant.parse("2026-08-27T18:00:00Z"),
        )
        exportMatchText(log) shouldBe """
            Thu League
            Coen vs Marco
            Not started
        """.trimIndent()
    }
```

- [x] **Step 2: Run to verify it fails, then implement**

Add to `ScoreTagSummary.kt`:

```kotlin
/**
 * The match as plain text, for pasting into a message the same evening. Point
 * numbers are one-based here and only here: this is the one output a person reads
 * as a list rather than an index, and "point 0" is not a thing anyone says.
 */
fun exportMatchText(log: ScoreLog): String {
    val state = log.state()
    val header = listOf(
        log.title,
        "${sideLabel(log.homePlayers)} vs ${sideLabel(log.awayPlayers)}",
        scoreLine(state),
    )
    if (state.points.isEmpty()) return header.joinToString("\n")

    val rows = state.points.map { point ->
        val winner = if (point.wonBy == Side.HOME) sideLabel(log.homePlayers) else sideLabel(log.awayPlayers)
        val score = "${point.scoreAfter.home}-${point.scoreAfter.away}"
        val tags = point.tags.joinToString(", ") { it.labelName }.takeIf { it.isNotEmpty() }
        val comment = point.comment?.trim()?.takeIf { it.isNotEmpty() }
        buildString {
            append("${point.ordinal + 1}. $score $winner")
            if (tags != null) append(" - $tags")
            if (comment != null) append(" ($comment)")
        }
    }
    return (header + "" + rows).joinToString("\n")
}
```

- [x] **Step 3: Run the test, then commit**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreTagSummaryTest"
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummary.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreTagSummaryTest.kt
git commit -m "feat(scoring): export a match as text"
```

---

### Task 4: Android - the scoring view model

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt`

**Interfaces:**
- Consumes: `MatchScorer`, `ScoreLogsRepository`, `AnnotationLabelsRepository`, `buildScoreTagSummary`.
- Produces:
  - `data class ScoringUiState(log: ScoreLog?, state: MatchState?, labels: List<AnnotationLabel>, pendingTagOrdinal: Int?, canScore: Boolean, canUndo: Boolean)`
  - `class ScoringViewModel(scoreLogs, labels, scoreLogId)` with `score(side)`, `toggleTag(ordinal, label)`, `setComment(ordinal, text)`, `undo()`, `resetCurrentGame()`, `finish()`, `dismissTagRow()`

The tag row's visibility is state, not a dialog: `pendingTagOrdinal` is set to the point just scored and cleared by the next point or an explicit dismiss. That is what makes "one tap scores, a second tags" work without a modal ever appearing.

- [x] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt`:

```kotlin
package com.badmintontracker.android.scoring

import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.SideScore
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
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

class ScoringViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    private val goodShot = AnnotationLabel(
        id = "l1", name = "Good shot", colorKey = "green", createdAt = t0,
    )
    private val forcedError = AnnotationLabel(
        id = "l2", name = "Forced error", colorKey = "amber", createdAt = t0,
    )

    private fun repo() =
        ScoreLogsRepository(client = null, settings = MapSettings(), now = { t0 }, ownerId = { "owner-1" })

    /** The view model plus the store and the match id behind it. */
    private fun fixture(
        repo: ScoreLogsRepository = repo(),
        labels: List<AnnotationLabel> = listOf(goodShot, forcedError),
    ): Triple<ScoreLogsRepository, String, ScoringViewModel> {
        val log = repo.create(
            title = "Thu League",
            homePlayers = listOf("Coen"),
            awayPlayers = listOf("Marco"),
            rules = ScoringRules.BWF_21,
            setup = MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        return Triple(repo, log.id, ScoringViewModel(repo, FakeAnnotationLabelsRepository(labels), log.id))
    }

    @Test
    fun scoring_a_point_opens_the_tag_row_for_that_point() = runTest(dispatcher) {
        // The whole interaction. The row is already on screen when the coach looks
        // down, so the second tap costs him nothing.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 0
        vm.state.value.state?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun scoring_the_next_point_moves_the_tag_row_to_it() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.score(Side.AWAY)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 1
        // And the rally that was already tagged keeps its tag.
        vm.state.value.state!!.points[0].tags.map { it.labelName } shouldBe listOf("Good shot")
    }

    @Test
    fun tagging_does_not_close_the_row() = runTest(dispatcher) {
        // A rally can be two things at once, and re-opening the row to say so would
        // cost the coach the next point.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, forcedError)
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal shouldBe 0
        vm.toggleTag(0, goodShot)
        advanceUntilIdle()
        vm.state.value.state!!.points[0].tags.map { it.labelName } shouldBe
            listOf("Forced error", "Good shot")
    }

    @Test
    fun toggling_the_same_label_twice_removes_it() = runTest(dispatcher) {
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.toggleTag(0, goodShot)
        advanceUntilIdle()
        vm.state.value.state!!.points[0].tags.shouldBeEmpty()
        // Undoing a mis-tap must not touch the score.
        vm.state.value.state!!.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_tag_snapshots_the_labels_name_and_colour() = runTest(dispatcher) {
        // Not the label id. Renaming "Forced error" next month must not rewrite
        // what the coach recorded tonight.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.toggleTag(0, forcedError)
        advanceUntilIdle()
        val tag = vm.state.value.state!!.points[0].tags.single()
        tag.labelName shouldBe "Forced error"
        tag.labelColor shouldBe "amber"
    }

    @Test
    fun the_palette_is_whatever_was_cached_offline() = runTest(dispatcher) {
        // A sports hall has no signal, and AnnotationLabelsRepository already caches
        // per owner. The surface reads that cache and never refreshes on entry.
        val labels = FakeAnnotationLabelsRepository(listOf(goodShot, forcedError))
        val repo = repo()
        val log = repo.create(
            "Thu League", listOf("Coen"), listOf("Marco"),
            ScoringRules.BWF_21, MatchSetup(doubles = false, firstServer = Side.HOME),
        )
        val vm = ScoringViewModel(repo, labels, log.id)
        advanceUntilIdle()
        vm.state.value.labels.map { it.name } shouldBe listOf("Good shot", "Forced error")
        labels.refreshCount shouldBe 0
    }

    @Test
    fun undo_clears_a_tag_row_pointing_at_a_point_that_no_longer_exists() = runTest(dispatcher) {
        // Otherwise the next label lands on a rally that was taken back.
        val (_, _, vm) = fixture()
        vm.score(Side.HOME)
        vm.undo()
        advanceUntilIdle()
        vm.state.value.pendingTagOrdinal.shouldBeNull()
        vm.state.value.state!!.points.shouldBeEmpty()
    }

    @Test
    fun finishing_ends_the_match_and_closes_the_row() = runTest(dispatcher) {
        val (repo, id, vm) = fixture()
        vm.score(Side.HOME)
        vm.finish()
        advanceUntilIdle()
        repo.get(id)!!.status shouldBe ScoreLogStatus.UNBOUND
        vm.state.value.pendingTagOrdinal.shouldBeNull()
    }

    @Test
    fun a_deleted_match_leaves_the_screen_inert_rather_than_crashing() = runTest(dispatcher) {
        // Reachable: the match was swiped away in the list on another screen while
        // this one was still open.
        val (repo, id, vm) = fixture()
        repo.removeLocally(id)
        advanceUntilIdle()
        vm.state.value.state.shouldBeNull()
        vm.state.value.canScore shouldBe false
        vm.score(Side.HOME)
        vm.toggleTag(0, goodShot)
        vm.finish()
        advanceUntilIdle()
        vm.state.value.state.shouldBeNull()
    }
}
```

`FakeAnnotationLabelsRepository` already exists at `androidApp/src/test/java/com/badmintontracker/android/testing/`. It needs one addition for `the_palette_is_whatever_was_cached_offline`: a `refreshCount` counter incremented by `refresh()`. Add it there rather than writing a second fake.

- [x] **Step 2 to 5: Implement, run, commit**

The view model combines `scoreLogs.logs` and `labels.labels` into `ScoringUiState`, holds `pendingTagOrdinal` in a `MutableStateFlow`, and routes every mutation through `MatchScorer`. It calls no repository mutation directly.

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.scoring.ScoringViewModelTest"
git commit -m "feat(android): drive the scoring surface from the shared vocabulary"
```

---

### Task 5: Android - the scoring surface

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt`, `nav/Route.kt`, `AuthGate.kt`

The surface, portrait, in `ShuttlTheme` and never a copy of the competitor's red and blue:

- **Two tap zones, stacked, each half the screen.** Away on top, home below, so the phone sits on the bench facing the coach with his own side nearest him. Each zone shows the side's name and its points at the largest type the width allows - this has to be readable at arm's length, which is the one hard requirement on the layout.
- **The serving side is marked** with a shuttle glyph, and in doubles the serving player's name is the one marked. The service court (`ServiceCourt.RIGHT` / `LEFT`) is shown as a small left or right indicator inside the serving zone. All three come from `MatchState` and none is computed in the composable.
- **The tag row** sits between the two zones, always present, never a dialog. When `pendingTagOrdinal` is null it shows the label names greyed and inert. When a point has just been scored it lights up, and a tap toggles that label onto the rally. Labels render with the existing `LabelBadge(name, colorKey)` so a label looks the same here as on a clip. A "note" affordance at the end of the row opens an inline single-line field, not a sheet.
- **Above the zones**: the games already won ("21-18, 11-9"), and the match state line - "Game point", "Match point", "Interval", "Change ends" - driven by `gamePoint`, `matchPoint`, `isIntervalPoint` and `isChangeEndsPoint`. Announce whose game point it is by name; `SideFlags` can say both at 29-29.
- **Below**: Undo, and an overflow with Reset game, Finish match and Export. Undo is a first-class button because it is the one a coach reaches for mid-rally; the destructive two live behind a confirm.
- **When the match is over**, the zones stop accepting taps and a "Finish" call to action replaces the tag row. Undo stays live - the match may have ended on a mis-tap.
- **Keep the screen awake** for as long as the surface is on screen, reusing `MainActivity`'s existing `FLAG_KEEP_SCREEN_ON` mechanism rather than adding a second one. Drive it off a flow the same way the analyze pipeline does.

Route: `Route.Scoring(scoreLogId)`. Reached from the match page's primary action, and from creating a match - `NewMatchScreen`'s `onCreated` goes straight here rather than to the match page, because creating a match courtside means about to score it.

- [x] **Verify by hand** (none of this is reachable from a unit test): score a full game; check the serve indicator flips correctly through a doubles game against the rules in L0's Task 4; tag a point and confirm the row never steals a tap; undo across a game boundary; put the phone down for two minutes and confirm the screen stays on; kill the app mid-game from recents and confirm the score is exactly where it was.

```bash
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest
git commit -m "feat(android): add the courtside scoring surface"
```

---

### Task 6: Android - resume, tally and export on the match page

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchScreen.kt`, `ScoreMatchViewModel.kt` (both from L1a)

- The primary action becomes **"Score"** on a match with no points, **"Resume scoring"** on a live match with some, and nothing on a finished one.
- The tag tally from `buildScoreTagSummary` renders above the point list, reusing `MatchLabelStrip`'s visual language from the rally page so the two summaries read as the same thing. Its empty state is the existing "No points scored yet."
- An overflow item **"Export as text"** shares `exportMatchText(log)` through the platform share sheet.

```bash
./gradlew :androidApp:assembleDebug
git commit -m "feat(android): resume scoring, tally tags and export from the match page"
```

---

### Task 7: iOS - the scoring surface

**Files:**
- Create: `iosApp/Sources/Scoring/ScoringModel.swift`, `iosApp/Sources/Scoring/ScoringView.swift`
- Create: `iosApp/Tests/ScoringModelTests.swift`
- Modify: `iosApp/Sources/Scoring/ScoreMatchView.swift` (from L1a)

`ScoringModel` is an `@Observable @MainActor` class holding a `MatchScorer`, observing `rally.scoreLogs.logs` with `for await` - the same shape `ClipListModel` uses for `rally.clips.observeClips()` and `RootView` uses for `rally.themePrefs.mode`. `ScoreLogsRepository.logs` exports as `id<Kotlinx_coroutines_coreStateFlow>` in the ObjC header exactly as `LocalVideoRepository.entries` does, and SKIE's generated Swift layer types it - so this is the proven path, not a new one.

Construction: `MatchScorer(repo:scoreLogId:)` is a plain exported constructor. If SKIE's generated initialiser proves awkward, add a factory to `SwiftInterop.kt` rather than restructuring the Kotlin class - but check the generated header first, the way L0's Task 6 did.

The view mirrors Task 5 element for element: same two stacked zones, same always-present tag row, same state line, same Undo placement. Tags render with the iOS label chip already used in clip detail. Keep the screen awake with `UIApplication.shared.isIdleTimerDisabled`, set on appear and cleared on disappear, matching `RootView`'s existing use.

`ScoringModelTests` covers the same nine behaviours as Task 4's Android tests. They are the parity check: if the two lists of test names diverge, the two surfaces have diverged.

- [ ] **Verify by hand**, including the check this codebase has been bitten by: tap a label chip in the tag row and confirm the tap does not also register on the scoring zone underneath it. `iosAppUITests` exists because a swatch grid routed one tap to every button in a cell.

```bash
cd iosApp && xcodegen generate && cd ..
# then the iOS test command from Global Constraints
git commit -m "feat(ios): add the courtside scoring surface"
```

---

### Task 8: iOS - resume, tally and export on the match page

**Files:**
- Modify: `iosApp/Sources/Scoring/ScoreMatchView.swift`, `ScoreMatchModel.swift`

The iOS half of Task 6, element for element: same three action labels, same tally above the point list, same "Export as text" through `ShareLink`.

Screenshot both platforms' match pages side by side and compare. Same rule as L1a: "looks about right" is not the bar.

```bash
git commit -m "feat(ios): resume scoring, tally tags and export from the match page"
```

---

### Task 9: Landscape (separable, and last on purpose)

**Files:**
- Modify: `iosApp/project.yml`, `iosApp/Sources/RallyIOSApp.swift`, `iosApp/Sources/Scoring/ScoringView.swift`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt`

This is last, and separable, because it is the only task here with app-wide blast radius. Everything before it ships a working scoring surface; if this proves fragile it can be dropped without touching any of it.

**Android is nearly free.** `MainActivity` already declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, so the activity survives rotation. The work is a landscape branch in `ScoringScreen`: the two zones sit side by side rather than stacked, with the tag row down the centre. No manifest change.

**iOS is the real work.** The app declares `UISupportedInterfaceOrientations: [UIInterfaceOrientationPortrait]` and has **no orientation handling anywhere** - no `AppDelegate`, no scene delegate. Allowing landscape app-wide would let every existing screen rotate into a layout nobody has ever looked at. So:

1. Add `UIInterfaceOrientationLandscapeLeft` and `...Right` to `project.yml`'s `UISupportedInterfaceOrientations`.
2. Add an `AppDelegate` holding an app-wide mask defaulting to `.portrait`, returned from `application(_:supportedInterfaceOrientationsFor:)`, and adopt it with `@UIApplicationDelegateAdaptor` in `RallyIOSApp`.
3. `ScoringView` widens the mask to `.landscape` on appear, calls `UIWindowScene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape))` to actually rotate, and restores `.portrait` on disappear.

**Verify every other screen still refuses to rotate** - the sign-in screen, the clip list, the player, court marking, the labels screen - and that leaving the scoring surface mid-rotation does not strand the app in landscape. That check is the task; the three steps above are the easy part.

If step 3 turns out to fight SwiftUI's navigation, stop and keep portrait. A portrait scoreboard that works beats a landscape one that traps the app sideways.

```bash
git commit -m "feat: lay the scoring surface out for landscape"
```

---

### Task 10: Close it out

- [ ] **Step 1: Run everything**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Report the actual output. Do not summarise a run you did not do.

- [ ] **Step 2: Score a real match on both phones**

Not a smoke test. Score a full three-game doubles match on each platform, tagging as you go, and check afterwards that the point log, the serve rotation and the tally all agree with what happened. This feature's failure mode is being subtly wrong in a way only a real game exposes.

- [ ] **Step 3: Changelog and commit**

```markdown
- Score a match courtside. Create it, tap the side that won each rally, and tag
  the rally from your own labels with a second tap. The match keeps a full
  point-by-point record with a tag tally, and exports as text - with or without
  a video.
```

---

## What this leaves for L2

The reason any of this was built. Every piece it needs is now in place:

- **`ScoredPoint.ordinal` is the binding key** - point N maps to `rally_index` N - and `scoreBefore`, `servedBy`, `gameIndex` and `wonBy` are already the four values L2 denormalises onto `rally_clips`.
- **`score_logs.video_id` is nullable and `on delete set null`**, so binding is an UPDATE of one column and a deleted video leaves the match `unbound` with its tags intact.
- **Ordinal binding will drift** (§6.3: the detector does not produce exactly one clip per point), and there is no shared clock to fall back on (§6.1, §6.2). The reconcile screen is the mechanism, not a nicety, and it opens on one question: which detected rally is the first point.
- **`rally_clips` is written by the Modal pipeline**, so the new columns need their own revoke-then-grant-by-column pass, in the style of `0004` and `0008`.
