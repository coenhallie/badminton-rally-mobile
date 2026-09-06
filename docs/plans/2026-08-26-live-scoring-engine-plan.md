# Live Scoring Engine (L0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure scoring engine the courtside match flow rests on: an append-only event log per match, and one fold that turns it into the full state of a badminton match - score, games, serve, service court, doubles rotation, interval, change of ends, game point, match point, winner.

**Architecture:** One package in `shared/commonMain`, no mutable score anywhere. A match is a `List<ScoreEvent>` plus a `ScoringRules` and a `MatchSetup`; `foldMatchState` is the only producer of a `MatchState`. Undo is re-folding a shorter log, so it cannot drift from the forward path, and the same log is the history view, the text export and (in L2) the thing that binds to rally clips. Same shape as `buildMatchLabelSummary`: one pure shared function both platforms call, so Android and iOS cannot disagree about the score.

**Tech Stack:** Kotlin Multiplatform (kotlinx-serialization 1.10.0, kotlin 2.3.20), SKIE 0.10.13 for the Swift surface. Tests: kotlin.test plus kotest-assertions on the Kotlin side, XCTest on iOS.

**Spec:** `docs/plans/2026-08-26-live-scoring-review-design.md`, section 7, layer L0.

## Scope

This plan is **L0 only**. It ships no UI, no database migration and no user-visible behaviour. It exists so that L1 (create a match, score it, tag it) has a scoring engine that is already correct and already tested, and so that the first plan on this branch carries no migration.

Deliberately **not** in this plan:

- The `score_logs` table, its RLS and its column grants. The spec puts persistence in L1, and the migration carries the column-grant hazard of §6.1.
- Local-first persistence of a match in progress (L1).
- The scoring surface, the tag palette and match creation (L1).
- The match list showing score-only matches beside video-backed ones (L1, §7.1 - the spec calls this the largest single piece of UI work in the feature).
- Anything that touches a video, a clip or an annotation (L2).

## Deviations from the spec sketch

§7's L0 sketch gives the event set as `PointTo(side) | Undo | StartGame | EndMatch | Interval | ChangeEnds`. That list contradicts the prose immediately below it, which says undo is "drop the last event and re-fold" and that `MatchState` carries the interval and the change of ends "derived and never stored". This plan follows the prose. Three deltas, each stated here so an executor comparing the plan against the design doc does not reconcile them the other way:

1. **`Undo` is not an event.** It is `List<ScoreEvent>.undoLast()`, a log operation. An event that means "ignore the previous event" would make the log's meaning depend on a scan the fold has to do twice, and it makes replay ambiguous.
2. **`Interval` and `ChangeEnds` are not events.** They are derived by the fold from the score and the rules, exactly as the prose requires. Logging them would create a second source of truth that a re-fold could contradict.
3. **`StartGame` is not an event.** A game boundary is fully determined by the score and the rules, so logging it lets a log exist that says the game ended when the score says it did not.

`EndMatch` survives as `Retire(side)`, which is the only match ending the score cannot determine on its own. A match simply abandoned needs no event: its log stays as it is and the state stays live, which is what L1 wants for "come back to this later".

**Tags are their own event, and the log stays append-only.** `TagPoint(pointOrdinal, tags, comment)` annotates a point that was already played rather than being carried on `PointTo`. Carrying tags on the point event would mean tagging rewrites an earlier entry in place, and §7's L3 note leans on the log being append-only so a reconnecting device can replay rather than reconcile. Appending keeps that property true. The cost is that a point scored and then tagged takes two `undoLast()` calls to remove, which is correct rather than surprising: the second undo is the tag, and the coach sees it go.

## Global Constraints

- No em dash in any prose, comment, commit message or user-facing string. Use a plain dash.
- No agent attribution in commit messages, and no "Generated with" footer.
- Everything in this plan lives in `com.badmintontracker.shared.scoring`, in `shared/src/commonMain` and `shared/src/commonTest`. No Android or Compose code. The only file outside `shared` is the Swift bridge test in Task 6.
- **No database migration in this plan.** L0 touches no table.
- **No CHANGELOG entry in this plan.** `CHANGELOG.md` is user-facing and L0 ships nothing a user can see; the entry lands with L1.
- **The fold is total.** Every log folds to a state. A log that scores past the end of the match, or tags a point that undo removed, folds as if those entries were not there. Nothing in this package throws except `ScoringRules`' own constructor check, which is documented as unreachable from the UI path.
- **No default arguments on anything Swift constructs.** Kotlin defaults do not survive into the generated Swift initializer (see the note on `stripLabelName` in `MatchLabelSummary.kt`), so a default is a value that will drift between platforms. `MatchSetup` is the one exception and Task 4 documents it.
- **No nullable `Int` on the state surface.** A Kotlin `Int?` reaches Swift as `KotlinInt?`, which is miserable to use. Optional positions are modelled as enums (`PairPlayer`, `ServiceCourt`, `Side`) so they bridge as plain Swift optionals. `ScoringRules.cap` and `ScoringRules.intervalAt` are the exception: they are configuration, set once from a preset, and never read in a hot path.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`; it is xcodegen output. Run `cd iosApp && xcodegen generate && cd ..` after adding a Swift file.
- Test commands:

```bash
./gradlew :shared:jvmTest
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.*"
```

and for iOS:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

## File Structure

**Shared (KMP)**

- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoringRules.kt` - `ScoringRules`, its presets, and `scoringRulesProblem`. The whole definition of what a playable rule set is lives here.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt` - `Side`, `PointTag`, `ScoreEvent` and the three log operations. This file owns point-ordinal arithmetic; nothing else counts `PointTo` events.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt` - `SideScore`, `SideFlags`, `ServiceCourt`, `PairPlayer`, `MatchSetup`, `ScoredPoint`, `MatchState`, `gameWinner` and `foldMatchState`. The whole scoring contract lives here and nowhere else.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoringRulesTest.kt`.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEventTest.kt`.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchStateTest.kt`.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ServeRotationTest.kt`.
- Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchMilestonesTest.kt`.

Three implementation files, split by what changes together: rules change when a club variant appears, the log changes when a new kind of entry appears, the fold changes when a scoring rule is wrong. Five test files rather than three because `MatchState.kt` grows across three tasks and each task's tests should be readable on their own.

**iOS**

- Create `iosApp/Tests/ScoringEngineTests.swift` - proves the engine is usable through the SKIE bridge.

---

### Task 1: The rule set

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoringRules.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoringRulesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class ScoringRules(pointsToWin: Int, winBy: Int, cap: Int?, intervalAt: Int?, gamesToWin: Int, changeEndsAt: Int?)`, `@Serializable`
  - `ScoringRules.Companion.BWF_21`, `CLUB_15`, `STRAIGHT_15`, `PRESETS: List<ScoringRules>`
  - `fun scoringRulesProblem(pointsToWin: Int, winBy: Int, cap: Int?, intervalAt: Int?, gamesToWin: Int, changeEndsAt: Int?): String?`

The coach said "15 or 21" and clubs disagree about what 15 means. Rules are a parameter so that answer costs a preset rather than a redesign, which is what lets the engine be built before he answers §9's first open question.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoringRulesTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ScoringRulesTest {

    @Test
    fun bwf_preset_is_the_21_point_game() {
        val rules = ScoringRules.BWF_21
        rules.pointsToWin shouldBe 21
        rules.winBy shouldBe 2
        rules.cap shouldBe 30
        rules.intervalAt shouldBe 11
        rules.gamesToWin shouldBe 2
        rules.changeEndsAt shouldBe 11
    }

    @Test
    fun both_fifteen_point_presets_are_playable() {
        // The two variants clubs actually mean by "to 15". Neither is a guess the
        // engine has to make: they are both here, and L1 picks.
        ScoringRules.CLUB_15.winBy shouldBe 2
        ScoringRules.CLUB_15.cap shouldBe 21
        ScoringRules.STRAIGHT_15.winBy shouldBe 1
        ScoringRules.STRAIGHT_15.cap shouldBe null
        ScoringRules.PRESETS shouldBe listOf(
            ScoringRules.BWF_21, ScoringRules.CLUB_15, ScoringRules.STRAIGHT_15,
        )
    }

    @Test
    fun a_playable_rule_set_reports_no_problem() {
        scoringRulesProblem(21, 2, 30, 11, 2, 11) shouldBe null
        // No cap, no interval and no change of ends are all legitimate.
        scoringRulesProblem(15, 1, null, null, 1, null) shouldBe null
    }

    @Test
    fun an_unplayable_rule_set_reports_a_sentence_a_user_can_read() {
        scoringRulesProblem(0, 2, null, null, 2, null) shouldBe
            "A game needs at least one point."
        scoringRulesProblem(21, 0, null, null, 2, null) shouldBe
            "A game has to be won by at least one point."
        scoringRulesProblem(21, 2, null, null, 0, null) shouldBe
            "A match needs at least one game."
        scoringRulesProblem(21, 2, 20, null, 2, null) shouldBe
            "The cap cannot be below the target score."
        scoringRulesProblem(21, 2, 30, 21, 2, null) shouldBe
            "The interval has to fall inside the game."
        scoringRulesProblem(21, 2, 30, 11, 2, 0) shouldBe
            "The change of ends has to fall inside the game."
    }

    @Test
    fun constructing_an_unplayable_rule_set_fails_loudly() {
        // The UI calls scoringRulesProblem first, so this is unreachable from the
        // app. It stays an exception rather than a silent clamp because a rule set
        // nobody validated must not quietly score a match wrong.
        val failure = assertFailsWith<IllegalArgumentException> {
            ScoringRules(pointsToWin = 21, winBy = 2, cap = 5, intervalAt = null, gamesToWin = 2, changeEndsAt = null)
        }
        failure.message shouldBe "The cap cannot be below the target score."
    }

    @Test
    fun rules_survive_a_json_round_trip() {
        // Rules travel with the match: the same log under different rules is a
        // different score, so they are stored beside it rather than re-derived.
        val json = Json.encodeToString(ScoringRules.serializer(), ScoringRules.BWF_21)
        json shouldBe """{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11}"""
        Json.decodeFromString(ScoringRules.serializer(), json) shouldBe ScoringRules.BWF_21
    }

    @Test
    fun decoding_an_unplayable_stored_rule_set_throws_rather_than_scoring_wrong() {
        // kotlinx-serialization 1.10.0 calls the constructor from the generated
        // deserializer and does not wrap what it throws, so the init check's own
        // sentence arrives intact. Verified against this version rather than
        // assumed: a version that wrapped it would surface a SerializationException
        // here instead, and this test is where that would be caught.
        val stored = """{"points_to_win":21,"win_by":2,"cap":5,"games_to_win":2}"""
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(ScoringRules.serializer(), stored)
        }.message shouldBe "The cap cannot be below the target score."
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoringRulesTest"`
Expected: FAIL to compile, "Unresolved reference: ScoringRules".

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoringRules.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a match is scored.
 *
 * A parameter rather than an enum, because the coach said "15 or 21" and clubs
 * disagree about what a 15 point game is - win by two or a straight race, capped
 * or not, interval or none. A wrong guess about one club's variant then costs a
 * preset, not a redesign.
 *
 * Serializable because the rules travel with the match. The same log folded under
 * different rules is a different score, so the rules are stored beside the log
 * rather than re-derived at read time from whatever the app's current default is.
 */
@Serializable
data class ScoringRules(
    @SerialName("points_to_win")  val pointsToWin: Int,
    /** Lead needed to take a game. 2 for BWF, 1 for a straight race to the target. */
    @SerialName("win_by")         val winBy: Int,
    /** Score at which the lead requirement is dropped and the next point takes the game. Null means no cap. */
                                  val cap: Int? = null,
    /** Score at which a game pauses for the interval. Null means no interval. */
    @SerialName("interval_at")    val intervalAt: Int? = null,
    /** Games one side must win to take the match. 2 is best of three. */
    @SerialName("games_to_win")   val gamesToWin: Int,
    /** Score at which ends change in the deciding game. Null means no mid-game change. */
    @SerialName("change_ends_at") val changeEndsAt: Int? = null,
) {
    init {
        scoringRulesProblem(pointsToWin, winBy, cap, intervalAt, gamesToWin, changeEndsAt)
            ?.let { throw IllegalArgumentException(it) }
    }

    companion object {
        /** BWF: 21, win by two, capped at 30, interval at 11, best of three, ends change at 11 in the third. */
        val BWF_21 = ScoringRules(
            pointsToWin = 21, winBy = 2, cap = 30, intervalAt = 11, gamesToWin = 2, changeEndsAt = 11,
        )

        /** Club 15: win by two, capped at 21, interval at 8. */
        val CLUB_15 = ScoringRules(
            pointsToWin = 15, winBy = 2, cap = 21, intervalAt = 8, gamesToWin = 2, changeEndsAt = 8,
        )

        /** Club 15: first to 15, no setting. */
        val STRAIGHT_15 = ScoringRules(
            pointsToWin = 15, winBy = 1, cap = null, intervalAt = 8, gamesToWin = 2, changeEndsAt = 8,
        )

        /** Declaration order, which is also the order a rules picker renders. */
        val PRESETS: List<ScoringRules> = listOf(BWF_21, CLUB_15, STRAIGHT_15)
    }
}

/**
 * The one place a rule set's own constraints are spelled out, so a settings screen
 * and the constructor above cannot drift into disagreeing about what is playable.
 * Returns a ready to display sentence, or null when the combination is fine.
 *
 * Separate from the constructor because a Kotlin exception crossing the ObjC bridge
 * aborts the iOS app rather than surfacing as an error: Swift calls this first and
 * only constructs once it has returned null.
 */
fun scoringRulesProblem(
    pointsToWin: Int,
    winBy: Int,
    cap: Int?,
    intervalAt: Int?,
    gamesToWin: Int,
    changeEndsAt: Int?,
): String? = when {
    pointsToWin < 1 -> "A game needs at least one point."
    winBy < 1 -> "A game has to be won by at least one point."
    gamesToWin < 1 -> "A match needs at least one game."
    cap != null && cap < pointsToWin -> "The cap cannot be below the target score."
    intervalAt != null && (intervalAt < 1 || intervalAt >= pointsToWin) ->
        "The interval has to fall inside the game."
    changeEndsAt != null && (changeEndsAt < 1 || changeEndsAt >= pointsToWin) ->
        "The change of ends has to fall inside the game."
    else -> null
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoringRulesTest"`
Expected: PASS, 7 tests.

If the round-trip test fails on key order, fix the test to match the declaration order rather than reordering the properties: the property order is the schema.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoringRules.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoringRulesTest.kt
git commit -m "feat(scoring): add the rule set the fold is parameterised by"
```

---

### Task 2: The event log

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEventTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `enum class Side { HOME, AWAY }` with `val other: Side`
  - `data class PointTag(labelName: String, labelColor: String?)`, `@Serializable`
  - `sealed interface ScoreEvent` with `data class PointTo(side: Side)`, `data class TagPoint(pointOrdinal: Int, tags: List<PointTag>, comment: String?)`, `data class Retire(side: Side)` - all `@Serializable`, discriminators `"point"`, `"tag"`, `"retire"`
  - `fun List<ScoreEvent>.undoLast(): List<ScoreEvent>`
  - `fun List<ScoreEvent>.tagPoint(pointOrdinal: Int, tags: List<PointTag>, comment: String?): List<ScoreEvent>`
  - `fun List<ScoreEvent>.dropPointsFrom(fromOrdinal: Int): List<ScoreEvent>`

This file owns point-ordinal arithmetic. The rule that the Nth `PointTo` is point N is stated once, here, because L2's binding depends on it and a second implementation of the same count is a second chance to be off by one.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEventTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ScoreEventTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val logSerializer = ListSerializer(ScoreEvent.serializer())

    private fun point(side: Side) = ScoreEvent.PointTo(side)

    @Test
    fun a_side_knows_its_opponent() {
        Side.HOME.other shouldBe Side.AWAY
        Side.AWAY.other shouldBe Side.HOME
    }

    @Test
    fun the_stored_shape_of_a_log_is_pinned() {
        // The discriminators are the wire format of every stored match. They are
        // short SerialNames rather than class names precisely so renaming a Kotlin
        // class cannot silently invalidate every log already on a coach's phone.
        val log = listOf(
            point(Side.HOME),
            ScoreEvent.TagPoint(pointOrdinal = 0, tags = listOf(PointTag("Forced error", "amber")), comment = "net"),
            ScoreEvent.Retire(Side.AWAY),
        )
        json.encodeToString(logSerializer, log) shouldBe
            """[{"type":"point","side":"home"},""" +
            """{"type":"tag","point_ordinal":0,"tags":[{"label_name":"Forced error","label_color":"amber"}],"comment":"net"},""" +
            """{"type":"retire","side":"away"}]"""
    }

    @Test
    fun a_log_survives_a_round_trip() {
        val log = listOf(
            point(Side.HOME),
            point(Side.AWAY),
            ScoreEvent.TagPoint(1, listOf(PointTag("Good shot", "green")), null),
        )
        json.decodeFromString(logSerializer, json.encodeToString(logSerializer, log)) shouldBe log
    }

    @Test
    fun undo_drops_the_last_entry_whatever_it_is() {
        val log = listOf(point(Side.HOME), ScoreEvent.TagPoint(0, emptyList(), "sloppy"))
        // The tag was the last thing done, so it is the first thing undone.
        log.undoLast() shouldBe listOf(point(Side.HOME))
        log.undoLast().undoLast().shouldBeEmpty()
    }

    @Test
    fun undoing_an_empty_log_is_a_no_op_rather_than_a_crash() {
        // The Undo control is disabled on an empty log. If that ever slips, a
        // courtside crash is a far worse answer than nothing happening.
        emptyList<ScoreEvent>().undoLast().shouldBeEmpty()
    }

    @Test
    fun tagging_appends_rather_than_rewriting_the_point() {
        val log = listOf(point(Side.HOME), point(Side.AWAY))
        log.tagPoint(0, listOf(PointTag("Unforced error", "red")), null) shouldBe
            log + ScoreEvent.TagPoint(0, listOf(PointTag("Unforced error", "red")), null)
    }

    @Test
    fun cutting_at_an_ordinal_takes_that_point_and_everything_after_it() {
        val log = listOf(
            point(Side.HOME),                                   // ordinal 0
            ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
            point(Side.AWAY),                                   // ordinal 1
            point(Side.HOME),                                   // ordinal 2
            ScoreEvent.TagPoint(2, emptyList(), "long rally"),
        )
        // Reset-to-here: point 1 onward goes, and so do the tags naming them. The
        // tag on point 0 stays, and its ordinal still names the right point
        // because nothing below the cut moved.
        log.dropPointsFrom(1) shouldBe listOf(
            point(Side.HOME),
            ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
        )
    }

    @Test
    fun cutting_removes_a_retirement_as_well() {
        // A cut re-opens the match, so a retirement recorded after the cut point
        // cannot survive it - it would leave the log saying the match is over at a
        // score that never happened.
        val log = listOf(point(Side.HOME), ScoreEvent.Retire(Side.AWAY))
        log.dropPointsFrom(1) shouldBe listOf(point(Side.HOME))
    }

    @Test
    fun cutting_at_zero_empties_the_log() {
        listOf(point(Side.HOME), point(Side.AWAY)).dropPointsFrom(0).shouldBeEmpty()
    }

    @Test
    fun cutting_beyond_the_last_point_changes_nothing() {
        val log = listOf(point(Side.HOME), ScoreEvent.TagPoint(0, emptyList(), "x"))
        log.dropPointsFrom(9) shouldBe log
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreEventTest"`
Expected: FAIL to compile, "Unresolved reference: ScoreEvent".

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two competitors. Not court ends: ends change during a match and these do
 * not, so a side identifies the same player or pair for the whole log.
 */
@Serializable
enum class Side {
    @SerialName("home") HOME,
    @SerialName("away") AWAY;

    val other: Side get() = if (this == HOME) AWAY else HOME
}

/**
 * A label as it was when the coach tapped it. Snapshotted rather than referenced,
 * exactly as [com.badmintontracker.shared.model.RallyAnnotation] snapshots
 * label_name and label_color, and for the same reason: renaming or deleting a
 * label months later must not rewrite or orphan courtside history.
 */
@Serializable
data class PointTag(
    @SerialName("label_name")  val labelName: String,
    @SerialName("label_color") val labelColor: String?,
)

/**
 * One entry in a match's log. The log is the whole truth about a match: the score,
 * the history view, the text export and (in L2) the binding onto rally clips are
 * all folds over it, so there is no second copy of the score that can drift.
 *
 * Append-only. A tag names an already played point by ordinal rather than being
 * carried on the point itself, which keeps replay-from-scratch valid for a later
 * live-sync layer and makes undo mean "drop the last thing I did".
 *
 * The SerialName on each entry is the stored wire format of every match a coach
 * has ever scored. Treat it as a schema: rename the Kotlin class freely, never
 * these strings.
 */
@Serializable
sealed interface ScoreEvent {

    /** One rally, won by [side]. The Nth PointTo in a log is point N, counting from zero. */
    @Serializable
    @SerialName("point")
    data class PointTo(val side: Side) : ScoreEvent

    /**
     * Sets the tags and the comment on the point at [pointOrdinal], replacing
     * whatever an earlier TagPoint for the same ordinal set - the fold reads the
     * last entry for an ordinal, so re-tagging is another append rather than an
     * edit. An ordinal naming no point, which undo leaves behind, is ignored.
     */
    @Serializable
    @SerialName("tag")
    data class TagPoint(
        @SerialName("point_ordinal") val pointOrdinal: Int,
        val tags: List<PointTag>,
        val comment: String?,
    ) : ScoreEvent

    /**
     * [side] retires or is disqualified and the other side takes the match. The
     * only ending the score cannot determine on its own, which is why it is the
     * only non-scoring entry the log has. A match merely abandoned needs no entry:
     * its log stays as it is and its state stays live.
     */
    @Serializable
    @SerialName("retire")
    data class Retire(val side: Side) : ScoreEvent
}

/**
 * Drops the last entry. Undo is defined as re-folding a shorter log rather than as
 * an inverse operation on the score, so it can never drift from the forward path.
 *
 * An empty log comes back unchanged rather than throwing. The control that calls
 * this is disabled on an empty log, and if that ever slips, a no-op is a much
 * better answer courtside than a crash.
 */
fun List<ScoreEvent>.undoLast(): List<ScoreEvent> = if (isEmpty()) this else dropLast(1)

/**
 * Appends the tags and comment for [pointOrdinal]. Named rather than left as a
 * bare list append so that callers never have to know that appending, rather than
 * rewriting, is what keeps the log replayable.
 */
fun List<ScoreEvent>.tagPoint(
    pointOrdinal: Int,
    tags: List<PointTag>,
    comment: String?,
): List<ScoreEvent> = this + ScoreEvent.TagPoint(pointOrdinal, tags, comment)

/**
 * Drops the point at [fromOrdinal] and everything after it, tags and retirement
 * included. This is what "reset game" is built from: fold to find the first
 * ordinal of the current game, then cut there.
 *
 * Entries below the cut are untouched, so every surviving tag still names the
 * point it named before - no renumbering, and therefore no way for a tag to slide
 * onto a neighbouring rally.
 */
fun List<ScoreEvent>.dropPointsFrom(fromOrdinal: Int): List<ScoreEvent> {
    val kept = ArrayList<ScoreEvent>(size)
    var ordinal = 0
    for (event in this) {
        when (event) {
            is ScoreEvent.PointTo -> {
                if (ordinal < fromOrdinal) kept += event
                ordinal += 1
            }
            is ScoreEvent.TagPoint -> if (event.pointOrdinal < fromOrdinal) kept += event
            is ScoreEvent.Retire -> Unit
        }
    }
    return kept
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreEventTest"`
Expected: PASS, 10 tests.

If `the_stored_shape_of_a_log_is_pinned` fails on the discriminator key, the expected key is `type`, which is kotlinx-serialization's default. Do not configure a different one: it would have to be configured identically at every call site that ever reads a log.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreEvent.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreEventTest.kt
git commit -m "feat(scoring): add the append-only match log and its operations"
```

---

### Task 3: The fold - points, games and the match result

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchStateTest.kt`

**Interfaces:**
- Consumes: `ScoringRules` (Task 1); `Side`, `PointTag`, `ScoreEvent` (Task 2).
- Produces:
  - `data class SideScore(home: Int, away: Int)` with `fun of(side: Side): Int`, `fun plusOne(side: Side): SideScore`, `companion object { val ZERO }`
  - `data class MatchSetup(doubles: Boolean, firstServer: Side)` - Task 4 adds two more properties
  - `data class ScoredPoint(ordinal: Int, gameIndex: Int, wonBy: Side, scoreBefore: SideScore, scoreAfter: SideScore, servedBy: Side, tags: List<PointTag>, comment: String?)`
  - `data class MatchState(rules, setup, currentGame: SideScore, gameIndex: Int, completedGames: List<SideScore>, gamesWon: SideScore, points: List<ScoredPoint>, server: Side?, winner: Side?)` with `val isOver: Boolean` and `val pointCount: Int` - Tasks 4 and 5 add more properties
  - `fun gameWinner(score: SideScore, rules: ScoringRules): Side?`
  - `fun foldMatchState(rules: ScoringRules, setup: MatchSetup, events: List<ScoreEvent>): MatchState`

`ScoredPoint` is the unit L1's history view renders and the unit L2 binds to a rally clip, which is why `ordinal`, `gameIndex`, `wonBy`, `scoreBefore` and `servedBy` are all on it: those are exactly the columns §7's L2 denormalises onto `rally_clips` as `score_at_start`, `serving_side`, `game_index` and `point_won_by`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchStateTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MatchStateTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)

    private fun fold(events: List<ScoreEvent>, rules: ScoringRules = ScoringRules.BWF_21) =
        foldMatchState(rules, singles, events)

    /**
     * Points reaching [home]-[away], ordered so the sides stay as level as the
     * target allows. Order matters far more than it looks: scoring all of one
     * side's points first would win the game partway through and leave the rest
     * landing in the next one, so a naive "21 then 19" helper silently tests
     * 21-0 followed by 0-19.
     */
    private fun toScore(home: Int, away: Int): List<ScoreEvent> {
        val events = ArrayList<ScoreEvent>(home + away)
        var h = 0
        var a = 0
        while (h < home || a < away) {
            if (h < home && (h <= a || a >= away)) {
                events += ScoreEvent.PointTo(Side.HOME); h += 1
            } else {
                events += ScoreEvent.PointTo(Side.AWAY); a += 1
            }
        }
        return events
    }

    @Test
    fun an_empty_log_is_a_match_about_to_start() {
        val state = fold(emptyList())
        state.currentGame shouldBe SideScore.ZERO
        state.gameIndex shouldBe 0
        state.gamesWon shouldBe SideScore.ZERO
        state.completedGames.shouldBeEmpty()
        state.points.shouldBeEmpty()
        state.server shouldBe Side.HOME     // the coin toss, from MatchSetup
        state.winner shouldBe null
        state.isOver shouldBe false
    }

    @Test
    fun a_point_scores_and_the_winner_serves_next() {
        val state = fold(listOf(ScoreEvent.PointTo(Side.AWAY)))
        state.currentGame shouldBe SideScore(home = 0, away = 1)
        state.server shouldBe Side.AWAY
        state.pointCount shouldBe 1
    }

    @Test
    fun every_point_records_the_situation_it_was_played_in() {
        // This is the record L2 denormalises onto a rally clip, so it is asserted
        // here rather than trusted to a later layer.
        val state = fold(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.AWAY)))
        state.points shouldBe listOf(
            ScoredPoint(
                ordinal = 0, gameIndex = 0, wonBy = Side.HOME,
                scoreBefore = SideScore(0, 0), scoreAfter = SideScore(1, 0),
                servedBy = Side.HOME, tags = emptyList(), comment = null,
            ),
            ScoredPoint(
                ordinal = 1, gameIndex = 0, wonBy = Side.AWAY,
                scoreBefore = SideScore(1, 0), scoreAfter = SideScore(1, 1),
                // Home won the previous rally, so home served this one and lost it.
                servedBy = Side.HOME, tags = emptyList(), comment = null,
            ),
        )
    }

    @Test
    fun a_tag_lands_on_the_point_it_names() {
        val state = fold(
            listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.PointTo(Side.AWAY),
                ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), "cross court"),
            )
        )
        state.points[0].tags shouldBe listOf(PointTag("Good shot", "green"))
        state.points[0].comment shouldBe "cross court"
        state.points[1].tags.shouldBeEmpty()
        state.points[1].comment shouldBe null
    }

    @Test
    fun re_tagging_a_point_replaces_the_earlier_tag() {
        val state = fold(
            listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.TagPoint(0, listOf(PointTag("Good shot", "green")), null),
                ScoreEvent.TagPoint(0, listOf(PointTag("Unforced error", "red")), "wrong call"),
            )
        )
        state.points[0].tags shouldBe listOf(PointTag("Unforced error", "red"))
        state.points[0].comment shouldBe "wrong call"
    }

    @Test
    fun a_tag_naming_no_point_is_ignored() {
        // Undo drops a point and leaves its tag behind. That log must still fold.
        val state = fold(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.TagPoint(7, emptyList(), "orphan")))
        state.pointCount shouldBe 1
        state.points[0].comment shouldBe null
    }

    @Test
    fun a_tag_recorded_before_its_point_still_lands_on_it() {
        // Order independence is what lets a sync layer deliver entries out of order
        // without the fold producing a different match.
        val state = fold(listOf(ScoreEvent.TagPoint(0, emptyList(), "early"), ScoreEvent.PointTo(Side.HOME)))
        state.points[0].comment shouldBe "early"
    }

    @Test
    fun twenty_one_to_nineteen_takes_a_game_and_the_winner_serves_the_next_one() {
        val state = fold(toScore(home = 21, away = 19))
        state.completedGames shouldBe listOf(SideScore(21, 19))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
        state.gameIndex shouldBe 1
        state.currentGame shouldBe SideScore.ZERO
        state.server shouldBe Side.HOME
        state.winner shouldBe null
    }

    @Test
    fun a_one_point_lead_at_the_target_does_not_take_the_game() {
        val state = fold(toScore(20, 20) + ScoreEvent.PointTo(Side.HOME))   // 21-20
        state.currentGame shouldBe SideScore(21, 20)
        state.completedGames.shouldBeEmpty()
        state.gamesWon shouldBe SideScore.ZERO
    }

    @Test
    fun a_two_point_lead_past_the_target_takes_the_game() {
        val state = fold(toScore(20, 20) + toScore(home = 2, away = 0))         // 22-20
        state.completedGames shouldBe listOf(SideScore(22, 20))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun the_cap_ends_setting() {
        val state = fold(toScore(29, 29) + ScoreEvent.PointTo(Side.AWAY))   // 29-30
        state.completedGames shouldBe listOf(SideScore(29, 30))
        state.gamesWon shouldBe SideScore(home = 0, away = 1)
    }

    @Test
    fun a_straight_race_needs_no_lead_at_all() {
        val state = fold(toScore(14, 14) + ScoreEvent.PointTo(Side.HOME), ScoringRules.STRAIGHT_15)
        state.completedGames shouldBe listOf(SideScore(15, 14))
        state.gamesWon shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun two_games_take_the_match_and_the_final_score_stays_on_screen() {
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.winner shouldBe Side.HOME
        state.isOver shouldBe true
        state.gamesWon shouldBe SideScore(home = 2, away = 0)
        state.completedGames shouldBe listOf(SideScore(21, 0), SideScore(21, 0))
        // The last game's score, not a reset board: the match page shows how it ended.
        state.currentGame shouldBe SideScore(21, 0)
        state.server shouldBe null
    }

    @Test
    fun points_scored_after_the_match_ended_are_ignored() {
        // The UI disables scoring once the match is over. This is the backstop, and
        // it matters because a log arriving from another device is not our UI.
        val state = fold(toScore(21, 0) + toScore(21, 0) + toScore(5, 5))
        state.pointCount shouldBe 42
        state.currentGame shouldBe SideScore(21, 0)
        state.winner shouldBe Side.HOME
    }

    @Test
    fun a_retirement_hands_the_match_to_the_other_side() {
        val state = fold(toScore(11, 5) + ScoreEvent.Retire(Side.HOME))
        state.winner shouldBe Side.AWAY
        state.isOver shouldBe true
        state.currentGame shouldBe SideScore(11, 5)      // the score it was abandoned at
        state.gamesWon shouldBe SideScore.ZERO           // nobody completed a game
        state.server shouldBe null
    }

    @Test
    fun undo_re_folds_rather_than_reversing_the_score() {
        // toScore(5, 3) ends on a home point, so undoing it gives 4-3 - and the
        // state has to be indistinguishable from having never scored that point,
        // serve and all, not merely to show the right number.
        val log = toScore(5, 3)
        val undone = foldMatchState(ScoringRules.BWF_21, singles, log.undoLast())
        undone.currentGame shouldBe SideScore(4, 3)
        undone shouldBe foldMatchState(ScoringRules.BWF_21, singles, toScore(4, 3))
    }

    @Test
    fun undo_crosses_a_game_boundary() {
        // The point that took the game is undone like any other, and the board goes
        // back to the game that was still being played. Nothing has to remember that
        // a game ended, because nothing recorded that it did.
        val state = fold(toScore(21, 19).undoLast())
        state.gameIndex shouldBe 0
        state.currentGame shouldBe SideScore(20, 19)
        state.completedGames.shouldBeEmpty()
        state.gamesWon shouldBe SideScore.ZERO
    }

    @Test
    fun a_game_won_by_a_single_point_is_a_playable_rule_set() {
        val sudden = ScoringRules(
            pointsToWin = 1, winBy = 1, cap = null, intervalAt = null, gamesToWin = 1, changeEndsAt = null,
        )
        val state = fold(listOf(ScoreEvent.PointTo(Side.AWAY)), sudden)
        state.winner shouldBe Side.AWAY
    }

    @Test
    fun the_winner_of_a_game_can_be_read_without_folding_a_log() {
        gameWinner(SideScore(21, 19), ScoringRules.BWF_21) shouldBe Side.HOME
        gameWinner(SideScore(21, 20), ScoringRules.BWF_21) shouldBe null
        gameWinner(SideScore(20, 20), ScoringRules.BWF_21) shouldBe null
        gameWinner(SideScore(30, 29), ScoringRules.BWF_21) shouldBe Side.HOME
        gameWinner(SideScore(0, 0), ScoringRules.BWF_21) shouldBe null
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.MatchStateTest"`
Expected: FAIL to compile, "Unresolved reference: MatchSetup".

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt`:

```kotlin
package com.badmintontracker.shared.scoring

/**
 * A pair of per-side integers: points within a game, or games won within a match.
 * One type for both because they are the same shape and the same accessor, and two
 * near-identical pair types is two places to get [of] backwards.
 */
data class SideScore(val home: Int, val away: Int) {
    fun of(side: Side): Int = if (side == Side.HOME) home else away

    fun plusOne(side: Side): SideScore =
        if (side == Side.HOME) copy(home = home + 1) else copy(away = away + 1)

    companion object { val ZERO = SideScore(home = 0, away = 0) }
}

/**
 * What a match needs beyond its rules before a point can be scored. Deliberately
 * not the match's name, its players or its date: those belong to the match record
 * that L1 creates, and the fold must not need any of them to produce a score.
 */
data class MatchSetup(
    val doubles: Boolean,
    /** The side serving the first point of the first game. The coin toss sets this. */
    val firstServer: Side,
)

/**
 * One played point, as the fold resolved it.
 *
 * This is the unit L1's history view renders and the unit L2 binds to a rally
 * clip - [ordinal] is the binding key, since point N maps to rally_index N - and
 * [scoreBefore], [servedBy], [gameIndex] and [wonBy] are exactly the four values
 * L2 denormalises onto rally_clips as score_at_start, serving_side, game_index and
 * point_won_by.
 */
data class ScoredPoint(
    val ordinal: Int,
    val gameIndex: Int,
    val wonBy: Side,
    /** The game's score before the rally. */
    val scoreBefore: SideScore,
    val scoreAfter: SideScore,
    /** The side that served the rally, which is whoever won the one before it. */
    val servedBy: Side,
    val tags: List<PointTag>,
    val comment: String?,
)

/**
 * The whole state of a match, derived and never stored. Produced only by
 * [foldMatchState].
 */
data class MatchState(
    val rules: ScoringRules,
    val setup: MatchSetup,
    /** Points in the game being played, or in the final game once the match is over. */
    val currentGame: SideScore,
    /** Zero-based index of [currentGame]. */
    val gameIndex: Int,
    /** The final score of each finished game, in order. */
    val completedGames: List<SideScore>,
    val gamesWon: SideScore,
    val points: List<ScoredPoint>,
    /** Null once the match is over. */
    val server: Side?,
    val winner: Side?,
) {
    val isOver: Boolean get() = winner != null
    val pointCount: Int get() = points.size
}

/**
 * The side that has won [score] under [rules], or null while the game is still
 * live. Public because the game point and match point tests in L1 read more
 * clearly against it than against a folded log, and because keeping the rule in
 * one function is what stops "has this game been won" from being written twice.
 */
fun gameWinner(score: SideScore, rules: ScoringRules): Side? {
    val leader = when {
        score.home > score.away -> Side.HOME
        score.away > score.home -> Side.AWAY
        else -> return null                       // level, so nobody has won
    }
    val top = score.of(leader)
    val chase = score.of(leader.other)
    if (top < rules.pointsToWin) return null
    // The cap ends setting: at the cap the next point takes the game regardless of
    // the lead, which is why it is checked before winBy rather than after.
    if (rules.cap != null && top >= rules.cap) return leader
    return if (top - chase >= rules.winBy) leader else null
}

/**
 * Folds a log into the score. The only way a [MatchState] is ever produced: there
 * is no mutable score anywhere in this package, so undo (re-fold a shorter log)
 * and replay (fold from scratch) cannot disagree with the forward path.
 *
 * Total by construction. A log that scores past the end of the match, or tags a
 * point that undo removed, folds to the same state as the log without those
 * entries rather than throwing. Both are prevented by the UI, and neither is worth
 * a crash on a bench between rallies - and a log arriving from another device is
 * not our UI.
 */
fun foldMatchState(
    rules: ScoringRules,
    setup: MatchSetup,
    events: List<ScoreEvent>,
): MatchState {
    // Resolved up front so a tag is order independent: a sync layer that delivers
    // a tag before its point must not fold to a different match. Later entries for
    // the same ordinal overwrite earlier ones, which is what makes re-tagging an
    // append rather than an edit.
    val tagsByOrdinal = HashMap<Int, ScoreEvent.TagPoint>()
    for (event in events) if (event is ScoreEvent.TagPoint) tagsByOrdinal[event.pointOrdinal] = event

    var currentGame = SideScore.ZERO
    var gameIndex = 0
    val completedGames = ArrayList<SideScore>()
    var gamesWon = SideScore.ZERO
    var server = setup.firstServer
    var winner: Side? = null
    val points = ArrayList<ScoredPoint>()

    for (event in events) {
        if (winner != null) break
        when (event) {
            is ScoreEvent.TagPoint -> Unit                      // already resolved above
            is ScoreEvent.Retire -> winner = event.side.other
            is ScoreEvent.PointTo -> {
                val scoreBefore = currentGame
                val scoreAfter = currentGame.plusOne(event.side)
                val tag = tagsByOrdinal[points.size]
                points += ScoredPoint(
                    ordinal = points.size,
                    gameIndex = gameIndex,
                    wonBy = event.side,
                    scoreBefore = scoreBefore,
                    scoreAfter = scoreAfter,
                    servedBy = server,
                    tags = tag?.tags ?: emptyList(),
                    comment = tag?.comment,
                )

                // Rally point scoring: the side that wins the rally serves the next one.
                server = event.side
                currentGame = scoreAfter

                val wonGame = gameWinner(scoreAfter, rules)
                if (wonGame != null) {
                    completedGames += scoreAfter
                    gamesWon = gamesWon.plusOne(wonGame)
                    if (gamesWon.of(wonGame) >= rules.gamesToWin) {
                        // currentGame is deliberately left at the final score rather
                        // than reset: the match page shows how the match ended.
                        winner = wonGame
                    } else {
                        gameIndex += 1
                        currentGame = SideScore.ZERO
                        server = wonGame
                    }
                }
            }
        }
    }

    return MatchState(
        rules = rules,
        setup = setup,
        currentGame = currentGame,
        gameIndex = gameIndex,
        completedGames = completedGames,
        gamesWon = gamesWon,
        points = points,
        server = if (winner != null) null else server,
        winner = winner,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.MatchStateTest"`
Expected: PASS, 19 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchStateTest.kt
git commit -m "feat(scoring): fold a log into points, games and a match result"
```

---

### Task 4: Serve - service court and the doubles rotation

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ServeRotationTest.kt`

**Interfaces:**
- Consumes: everything Task 3 produced.
- Produces:
  - `enum class ServiceCourt { RIGHT, LEFT }`
  - `enum class PairPlayer { FIRST, SECOND }` with `val other: PairPlayer`
  - `MatchSetup` gains `homeStartsRight: PairPlayer = PairPlayer.FIRST` and `awayStartsRight: PairPlayer = PairPlayer.FIRST`, so its full shape becomes `MatchSetup(doubles: Boolean, firstServer: Side, homeStartsRight: PairPlayer, awayStartsRight: PairPlayer)`
  - `MatchState` gains `serviceCourt: ServiceCourt?`, `servingPlayer: PairPlayer?`, `receivingPlayer: PairPlayer?`

The rules being modelled, so a reviewer can check the code against them rather than against a memory of a match:

- The server serves from the **right** service court when their own side's score is **even**, from the **left** when it is odd.
- The serving pair **swaps service courts only when it wins the rally**, which is why the same player serves twice in a row from alternating courts.
- The receiving pair **never swaps on winning**; the new server is simply whichever of them is standing in the court their new score calls for.
- The receiver is the opponent standing in the same-named service court, since a serve crosses diagonally.
- Ends and arrangements reset at every game start.

The two `MatchSetup` defaults are the one place this plan allows a default argument, because they are meaningless in singles and every Kotlin caller in a singles test would otherwise carry two irrelevant values. Swift has no defaults and must pass all four; Task 6 does exactly that.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ServeRotationTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ServeRotationTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)
    private val doubles = MatchSetup(
        doubles = true,
        firstServer = Side.HOME,
        homeStartsRight = PairPlayer.FIRST,
        awayStartsRight = PairPlayer.FIRST,
    )

    private fun fold(setup: MatchSetup, vararg winners: Side) =
        foldMatchState(ScoringRules.BWF_21, setup, winners.map { ScoreEvent.PointTo(it) })

    @Test
    fun the_first_serve_of_a_match_goes_from_the_right() {
        fold(singles).serviceCourt shouldBe ServiceCourt.RIGHT
        fold(doubles).serviceCourt shouldBe ServiceCourt.RIGHT
    }

    @Test
    fun the_service_court_follows_the_servers_own_score() {
        // Home leads 1-0 and serves: odd, so from the left.
        fold(singles, Side.HOME).serviceCourt shouldBe ServiceCourt.LEFT
        // Home leads 2-0 and serves: even, so from the right.
        fold(singles, Side.HOME, Side.HOME).serviceCourt shouldBe ServiceCourt.RIGHT
        // Away takes one back and serves at 2-1: away's own score is odd, so left.
        fold(singles, Side.HOME, Side.HOME, Side.AWAY).serviceCourt shouldBe ServiceCourt.LEFT
    }

    @Test
    fun singles_has_no_player_within_a_pair() {
        val state = fold(singles, Side.HOME)
        state.servingPlayer shouldBe null
        state.receivingPlayer shouldBe null
    }

    @Test
    fun the_opening_doubles_serve_is_first_to_first() {
        val state = fold(doubles)
        state.server shouldBe Side.HOME
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun the_serving_pair_swaps_courts_on_winning_so_the_same_player_serves_again() {
        // Home wins its own serve. Home's pair swaps courts, home's score is now
        // odd so the serve comes from the left - and the player standing there is
        // the one who just served.
        val state = fold(doubles, Side.HOME)
        state.serviceCourt shouldBe ServiceCourt.LEFT
        state.servingPlayer shouldBe PairPlayer.FIRST
        // The other opponent now receives, because the receiving pair did not move.
        state.receivingPlayer shouldBe PairPlayer.SECOND
    }

    @Test
    fun two_won_serves_return_the_pair_to_where_it_started() {
        val state = fold(doubles, Side.HOME, Side.HOME)
        state.serviceCourt shouldBe ServiceCourt.RIGHT
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun the_receiving_pair_does_not_swap_when_it_wins_the_serve_back() {
        // Home wins twice, away then wins the rally at 2-0. Away's pair has not
        // moved all game, and away's score is 1, so the serve comes from away's
        // left court - where the second player is standing.
        val state = fold(doubles, Side.HOME, Side.HOME, Side.AWAY)
        state.server shouldBe Side.AWAY
        state.serviceCourt shouldBe ServiceCourt.LEFT
        state.servingPlayer shouldBe PairPlayer.SECOND
        state.receivingPlayer shouldBe PairPlayer.SECOND
    }

    @Test
    fun a_pair_may_start_the_match_the_other_way_round() {
        val flipped = doubles.copy(homeStartsRight = PairPlayer.SECOND)
        val state = foldMatchState(ScoringRules.BWF_21, flipped, emptyList())
        state.servingPlayer shouldBe PairPlayer.SECOND
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun a_new_game_restores_the_starting_arrangement() {
        // 21-0 to home: home's pair swapped courts on all 21 of its own serves, an
        // odd number, so without a reset it would start game two the wrong way up.
        val gameOne = List(21) { ScoreEvent.PointTo(Side.HOME) }
        val state = foldMatchState(ScoringRules.BWF_21, doubles, gameOne)
        state.gameIndex shouldBe 1
        state.server shouldBe Side.HOME
        state.serviceCourt shouldBe ServiceCourt.RIGHT
        state.servingPlayer shouldBe PairPlayer.FIRST
        state.receivingPlayer shouldBe PairPlayer.FIRST
    }

    @Test
    fun a_finished_match_has_nobody_serving() {
        val twoGames = List(42) { ScoreEvent.PointTo(Side.HOME) }
        val state = foldMatchState(ScoringRules.BWF_21, doubles, twoGames)
        state.isOver shouldBe true
        state.server shouldBe null
        state.serviceCourt shouldBe null
        state.servingPlayer shouldBe null
        state.receivingPlayer shouldBe null
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ServeRotationTest"`
Expected: FAIL to compile, "Unresolved reference: ServiceCourt".

- [ ] **Step 3: Write the implementation**

In `MatchState.kt`, add the two enums above `SideScore`:

```kotlin
/**
 * Which service court a serve is delivered from. Right when the server's own side
 * score is even, left when it is odd.
 */
enum class ServiceCourt { RIGHT, LEFT }

/**
 * Which member of a pair. An enum rather than an index because a nullable Kotlin
 * Int reaches Swift as KotlinInt?, and "which of the two" is not arithmetic.
 */
enum class PairPlayer {
    FIRST, SECOND;

    val other: PairPlayer get() = if (this == FIRST) SECOND else FIRST
}
```

Extend `MatchSetup`:

```kotlin
data class MatchSetup(
    val doubles: Boolean,
    /** The side serving the first point of the first game. The coin toss sets this. */
    val firstServer: Side,
    /**
     * Doubles only: which player of each side starts a game in the right service
     * court. Reset at every game start, because a pair may rearrange between games
     * and the app has no way to know that it did - a stale arrangement is a wrong
     * name on screen, and resetting keeps it from also being a wrong name in the
     * game after that.
     *
     * These are the one place this package allows a default argument: they are
     * meaningless in singles. Swift has no defaults and passes all four.
     */
    val homeStartsRight: PairPlayer = PairPlayer.FIRST,
    val awayStartsRight: PairPlayer = PairPlayer.FIRST,
)
```

Extend `MatchState` with three properties, placed directly after `server`:

```kotlin
    /** Null once the match is over. */
    val serviceCourt: ServiceCourt?,
    /** Doubles only. Null in singles, and null once the match is over. */
    val servingPlayer: PairPlayer?,
    val receivingPlayer: PairPlayer?,
```

Add the helper below `gameWinner`:

```kotlin
/**
 * Which member of a pair stands in [court], given which member is currently in
 * that pair's right service court. The pair occupies both courts, so the other
 * player is in the other one - that is the whole rule.
 */
private fun playerIn(court: ServiceCourt, rightCourtPlayer: PairPlayer): PairPlayer =
    if (court == ServiceCourt.RIGHT) rightCourtPlayer else rightCourtPlayer.other
```

In `foldMatchState`, declare the arrangement alongside the other accumulators:

```kotlin
    var homeRight = setup.homeStartsRight
    var awayRight = setup.awayStartsRight
```

Inside the `PointTo` branch, before `server = event.side`, add the swap:

```kotlin
                // The serving pair swaps service courts only when it wins the rally,
                // which is exactly why the same player then serves again from the
                // other court. The receiving pair never swaps on winning: the new
                // server is simply whoever is standing in the court their new score
                // calls for.
                if (setup.doubles && event.side == server) {
                    if (server == Side.HOME) homeRight = homeRight.other else awayRight = awayRight.other
                }
```

In the game-won branch, restore the arrangement beside the other per-game resets, directly after `server = wonGame`:

```kotlin
                        homeRight = setup.homeStartsRight
                        awayRight = setup.awayStartsRight
```

Finally, derive the three new values just before the `return`:

```kotlin
    val servingSide = if (winner != null) null else server
    val court = servingSide?.let {
        if (currentGame.of(it) % 2 == 0) ServiceCourt.RIGHT else ServiceCourt.LEFT
    }
    // A serve crosses diagonally, so the receiver stands in the same-named court on
    // the other side of the net.
    val servingPlayer = if (!setup.doubles || servingSide == null || court == null) null else {
        playerIn(court, if (servingSide == Side.HOME) homeRight else awayRight)
    }
    val receivingPlayer = if (!setup.doubles || servingSide == null || court == null) null else {
        playerIn(court, if (servingSide.other == Side.HOME) homeRight else awayRight)
    }
```

and pass them into the constructor, replacing the existing `server = ...` line:

```kotlin
        server = servingSide,
        serviceCourt = court,
        servingPlayer = servingPlayer,
        receivingPlayer = receivingPlayer,
```

- [ ] **Step 4: Run both fold test classes to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.*"`
Expected: PASS. `MatchStateTest` must still pass unchanged - nothing in this task changes a score.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ServeRotationTest.kt
git commit -m "feat(scoring): derive the service court and the doubles rotation"
```

---

### Task 5: Interval, change of ends, game point and match point

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchMilestonesTest.kt`

**Interfaces:**
- Consumes: everything Tasks 3 and 4 produced.
- Produces:
  - `data class SideFlags(home: Boolean, away: Boolean)` with `fun of(side: Side): Boolean`, `val any: Boolean`, `companion object { val NONE }`
  - `MatchState` gains `isIntervalPoint: Boolean`, `endsSwapCount: Int`, `isChangeEndsPoint: Boolean`, `gamePoint: SideFlags`, `matchPoint: SideFlags`

Two shapes, deliberately, because the spec asks for two different things. **`endsSwapCount` is durable state**: it says which physical end each side is on right now, and L1 draws the board from it. **`isIntervalPoint` and `isChangeEndsPoint` are edges**: they say the point just played triggered the thing, which is what a banner keys off. An edge is safe in a fold because it is re-derived from the same log every time, so an undo un-announces the interval exactly as it un-scores the point.

`SideFlags` rather than a single boolean because at 29-29 with a cap of 30 **both** sides are at game point, and a board that can only say "game point" cannot say whose.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchMilestonesTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MatchMilestonesTest {

    private val singles = MatchSetup(doubles = false, firstServer = Side.HOME)

    private fun fold(events: List<ScoreEvent>, rules: ScoringRules = ScoringRules.BWF_21) =
        foldMatchState(rules, singles, events)

    /** Same helper as MatchStateTest: points ordered so a game is never won early. */
    private fun toScore(home: Int, away: Int): List<ScoreEvent> {
        val events = ArrayList<ScoreEvent>(home + away)
        var h = 0
        var a = 0
        while (h < home || a < away) {
            if (h < home && (h <= a || a >= away)) {
                events += ScoreEvent.PointTo(Side.HOME); h += 1
            } else {
                events += ScoreEvent.PointTo(Side.AWAY); a += 1
            }
        }
        return events
    }

    @Test
    fun reaching_the_interval_score_announces_the_interval_once() {
        fold(toScore(10, 0)).isIntervalPoint shouldBe false
        fold(toScore(11, 0)).isIntervalPoint shouldBe true
        // The next rally clears it, and the interval does not come round again when
        // the other side also reaches 11.
        fold(toScore(12, 0)).isIntervalPoint shouldBe false
        fold(toScore(11, 11)).isIntervalPoint shouldBe false
    }

    @Test
    fun undoing_the_interval_point_un_announces_the_interval() {
        // The edge is re-derived from the log, so it cannot get stuck on.
        fold(toScore(11, 0).undoLast()).isIntervalPoint shouldBe false
    }

    @Test
    fun a_new_game_brings_the_interval_back() {
        fold(toScore(21, 0) + toScore(11, 0)).isIntervalPoint shouldBe true
    }

    @Test
    fun rules_without_an_interval_never_announce_one() {
        val noInterval = ScoringRules(
            pointsToWin = 21, winBy = 2, cap = 30, intervalAt = null, gamesToWin = 2, changeEndsAt = null,
        )
        fold(toScore(11, 0), noInterval).isIntervalPoint shouldBe false
    }

    @Test
    fun ends_change_after_every_game() {
        fold(toScore(5, 5)).endsSwapCount shouldBe 0
        val afterGameOne = fold(toScore(21, 0))
        afterGameOne.endsSwapCount shouldBe 1
        afterGameOne.isChangeEndsPoint shouldBe true
        // And the announcement clears on the next rally of the new game.
        fold(toScore(21, 0) + toScore(1, 0)).isChangeEndsPoint shouldBe false
    }

    @Test
    fun ends_change_again_at_eleven_in_the_deciding_game() {
        val decider = toScore(21, 0) + toScore(0, 21)          // one game each
        fold(decider).endsSwapCount shouldBe 2
        val atEleven = fold(decider + toScore(11, 0))
        atEleven.endsSwapCount shouldBe 3
        atEleven.isChangeEndsPoint shouldBe true
        // Only once per game, whoever gets there second.
        fold(decider + toScore(11, 11)).endsSwapCount shouldBe 3
    }

    @Test
    fun eleven_in_a_non_deciding_game_does_not_change_ends() {
        fold(toScore(11, 0)).endsSwapCount shouldBe 0
        fold(toScore(11, 0)).isChangeEndsPoint shouldBe false
    }

    @Test
    fun the_match_ending_is_not_a_change_of_ends() {
        // Nobody walks to the other end of an empty court.
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.isOver shouldBe true
        state.endsSwapCount shouldBe 1               // only the between-games change
        state.isChangeEndsPoint shouldBe false
    }

    @Test
    fun a_side_one_point_from_the_game_is_at_game_point() {
        val state = fold(toScore(20, 19))
        state.gamePoint shouldBe SideFlags(home = true, away = false)
        state.gamePoint.of(Side.HOME) shouldBe true
        state.gamePoint.any shouldBe true
    }

    @Test
    fun level_at_the_target_is_game_point_for_nobody() {
        fold(toScore(20, 20)).gamePoint shouldBe SideFlags.NONE      // 20-20
    }

    @Test
    fun at_the_point_before_the_cap_both_sides_are_at_game_point() {
        // 29-29: whoever wins the rally reaches the cap and takes the game, so a
        // board that could only say "game point" could not say whose.
        fold(toScore(29, 29)).gamePoint shouldBe SideFlags(home = true, away = true)
    }

    @Test
    fun game_point_is_only_match_point_on_the_last_game_a_side_needs() {
        // First game: home is one point from the game, two games from the match.
        fold(toScore(20, 0)).matchPoint shouldBe SideFlags.NONE
        // Second game, home already one game up: now it is both.
        val second = fold(toScore(21, 0) + toScore(20, 0))
        second.gamePoint shouldBe SideFlags(home = true, away = false)
        second.matchPoint shouldBe SideFlags(home = true, away = false)
    }

    @Test
    fun a_finished_match_is_at_no_kind_of_point() {
        val state = fold(toScore(21, 0) + toScore(21, 0))
        state.gamePoint shouldBe SideFlags.NONE
        state.matchPoint shouldBe SideFlags.NONE
        state.isIntervalPoint shouldBe false
    }

    @Test
    fun a_retirement_clears_the_milestones_too() {
        val state = fold(toScore(20, 19) + ScoreEvent.Retire(Side.AWAY))
        state.winner shouldBe Side.HOME
        state.gamePoint shouldBe SideFlags.NONE
        state.matchPoint shouldBe SideFlags.NONE
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.MatchMilestonesTest"`
Expected: FAIL to compile, "Unresolved reference: isIntervalPoint".

- [ ] **Step 3: Write the implementation**

In `MatchState.kt`, add `SideFlags` directly below `SideScore`:

```kotlin
/**
 * A pair of per-side flags, for the states both sides can be in at once. At 29-29
 * under a cap of 30 both sides are at game point, so a single boolean would have
 * to choose one of them to be wrong about.
 */
data class SideFlags(val home: Boolean, val away: Boolean) {
    fun of(side: Side): Boolean = if (side == Side.HOME) home else away

    val any: Boolean get() = home || away

    companion object { val NONE = SideFlags(home = false, away = false) }
}
```

Extend `MatchState` with five properties, after `receivingPlayer`:

```kotlin
    /**
     * The point just played took a side to the interval score without ending the
     * game. An edge rather than durable state: it is re-derived from the log on
     * every fold, so an undo un-announces the interval exactly as it un-scores the
     * point, and no "interval dismissed" flag has to be persisted anywhere.
     */
    val isIntervalPoint: Boolean,
    /**
     * How many times the sides have changed ends. Durable, unlike the edge above:
     * an even count means both sides are on the ends they started on, which is what
     * the board draws from.
     */
    val endsSwapCount: Int,
    /** The point just played triggered a change of ends. */
    val isChangeEndsPoint: Boolean,
    /** Sides one point from taking the current game. */
    val gamePoint: SideFlags,
    /** Sides one point from taking the match, which is game point on the last game they need. */
    val matchPoint: SideFlags,
```

In `foldMatchState`, add four accumulators beside the others:

```kotlin
    var endsSwapCount = 0
    var isIntervalPoint = false
    var isChangeEndsPoint = false
    var intervalTakenThisGame = false
    var endsChangedThisGame = false
```

Inside the `PointTo` branch, clear both edges as soon as the point is recorded - directly after `currentGame = scoreAfter`:

```kotlin
                // Both announcements belong to the point that triggered them and to
                // no other, so every point clears them before deciding its own.
                isIntervalPoint = false
                isChangeEndsPoint = false
```

In the game-won branch, when the match continues, record the between-games change of ends beside the other per-game resets:

```kotlin
                        endsSwapCount += 1
                        isChangeEndsPoint = true
                        intervalTakenThisGame = false
                        endsChangedThisGame = false
```

and add an `else` to the `if (wonGame != null)` block for the mid-game milestones:

```kotlin
                } else {
                    val reached = scoreAfter.of(event.side)
                    if (!intervalTakenThisGame && rules.intervalAt != null && reached == rules.intervalAt) {
                        intervalTakenThisGame = true
                        isIntervalPoint = true
                    }
                    // Only in the deciding game: the change of ends between games
                    // covers every other one.
                    if (!endsChangedThisGame &&
                        rules.changeEndsAt != null &&
                        gameIndex == 2 * rules.gamesToWin - 2 &&
                        reached == rules.changeEndsAt
                    ) {
                        endsChangedThisGame = true
                        endsSwapCount += 1
                        isChangeEndsPoint = true
                    }
                }
```

Derive the two flag pairs just before the `return`, beside the serve derivation:

```kotlin
    // "One point from the game" asked of the rule that decides games, rather than
    // spelled out a second time here where it could drift from gameWinner.
    val gamePoint = if (winner != null) SideFlags.NONE else SideFlags(
        home = gameWinner(currentGame.plusOne(Side.HOME), rules) == Side.HOME,
        away = gameWinner(currentGame.plusOne(Side.AWAY), rules) == Side.AWAY,
    )
    val matchPoint = if (winner != null) SideFlags.NONE else SideFlags(
        home = gamePoint.home && gamesWon.home + 1 >= rules.gamesToWin,
        away = gamePoint.away && gamesWon.away + 1 >= rules.gamesToWin,
    )
```

and pass all five into the constructor:

```kotlin
        isIntervalPoint = winner == null && isIntervalPoint,
        endsSwapCount = endsSwapCount,
        isChangeEndsPoint = winner == null && isChangeEndsPoint,
        gamePoint = gamePoint,
        matchPoint = matchPoint,
```

- [ ] **Step 4: Run the whole scoring suite to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.*"`
Expected: PASS. All five classes, including the three written before this task.

- [ ] **Step 5: Run the full shared suite to verify nothing else moved**

Run: `./gradlew :shared:jvmTest`
Expected: PASS. This package is new and imports nothing outside itself, so any failure elsewhere is a real regression, not a knock-on.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/MatchMilestonesTest.kt
git commit -m "feat(scoring): derive interval, change of ends, game point and match point"
```

---

### Task 6: Prove the engine from Swift

**Files:**
- Create: `iosApp/Tests/ScoringEngineTests.swift`
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj` (regenerated by xcodegen, never hand-edited)

**Interfaces:**
- Consumes: the whole `com.badmintontracker.shared.scoring` package, through the SKIE-generated `Shared` framework.
- Produces: nothing Kotlin depends on.

This task exists because L1's iOS scoring surface is the first thing in this app to read a Kotlin `sealed interface`, a `List` of one, and a companion-object constant from Swift, and none of those has a caller in `iosApp/Sources` today. A bridge problem found here costs a rename; found in L1 it costs a redesign of the surface that was built on top of it.

**What SKIE 0.10.13 actually generates**, confirmed by reading
`shared/build/skie/binaries/debugFramework/DEBUG/iosSimulatorArm64/swift/generated/`
rather than assumed:

- Nested classes flatten as predicted: `ScoreEventPointTo`, `ScoreEventTagPoint`, `ScoreEventRetire`.
- Kotlin enums become real Swift enums with lowerCamelCase cases: `Side.home`, `PairPlayer.first`, `ServiceCourt.left`. `onEnum(of:)` gives exhaustive switching over `ScoreEvent`.
- Companion constants read as `ScoringRules.companion.BWF_21` and `.PRESETS`.
- Top-level functions are emitted **twice**: as `MatchStateKt.foldMatchState(...)` and as a plain Swift free function `foldMatchState(rules:setup:events:)`. Prefer the free function; it is the idiomatic generated surface.
- Extensions on `List<ScoreEvent>` land on `ScoreEventKt` with the receiver first: `ScoreEventKt.undoLast(_:)`, `ScoreEventKt.dropPointsFrom(_:fromOrdinal:)`.
- **`ScoringRules.cap` and `intervalAt` arrive as `KotlinInt?`**, so Swift cannot pass an integer literal: `scoringRulesProblem(cap: KotlinInt(int: 5), ...)`. This is the cost of the two nullable Ints the Global Constraints already flag, and it is confined to rule-set construction.

If a future SKIE version rejects one of these, read that directory and use the name it declares rather than inventing one - and if a name is materially worse than the Kotlin one, fix it in Kotlin while this package still has few callers.

- [ ] **Step 1: Write the failing test**

Create `iosApp/Tests/ScoringEngineTests.swift`:

```swift
import XCTest
import Shared
@testable import iosApp

/// Proves the scoring engine is usable from Swift before L1's scoring surface is
/// built on top of it. Correctness of the fold is covered by the Kotlin suite;
/// what is tested here is that the types survive the bridge.
final class ScoringEngineTests: XCTestCase {

    private let doubles = MatchSetup(
        doubles: true,
        firstServer: .home,
        homeStartsRight: .first,
        awayStartsRight: .first
    )

    private func fold(_ events: [ScoreEvent]) -> MatchState {
        MatchStateKt.foldMatchState(
            rules: ScoringRules.companion.BWF_21,
            setup: doubles,
            events: events
        )
    }

    func testAPresetCrossesTheBridge() {
        XCTAssertEqual(ScoringRules.companion.BWF_21.pointsToWin, 21)
        XCTAssertEqual(ScoringRules.companion.PRESETS.count, 3)
    }

    func testFoldingADoublesLogGivesTheServeAndTheScore() {
        let state = fold([
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .away),
        ])
        XCTAssertEqual(state.currentGame.home, 2)
        XCTAssertEqual(state.currentGame.away, 1)
        XCTAssertEqual(state.server, .away)
        XCTAssertEqual(state.serviceCourt, .left)
        XCTAssertEqual(state.servingPlayer, .second)
        XCTAssertEqual(state.receivingPlayer, .second)
        XCTAssertEqual(state.pointCount, 3)
        XCTAssertFalse(state.isOver)
    }

    func testATaggedPointArrivesWithItsLabelSnapshot() {
        let state = fold([
            ScoreEventPointTo(side: .home),
            ScoreEventTagPoint(
                pointOrdinal: 0,
                tags: [PointTag(labelName: "Forced error", labelColor: "amber")],
                comment: "pushed wide"
            ),
        ])
        XCTAssertEqual(state.points.first?.tags.first?.labelName, "Forced error")
        XCTAssertEqual(state.points.first?.tags.first?.labelColor, "amber")
        XCTAssertEqual(state.points.first?.comment, "pushed wide")
    }

    func testAnInvalidRuleSetIsReportedRatherThanThrown() {
        // A Kotlin exception crossing this bridge aborts the app, so the validator
        // is the only thing Swift is ever allowed to construct rules through.
        let problem = ScoringRulesKt.scoringRulesProblem(
            pointsToWin: 21, winBy: 2, cap: 5, intervalAt: nil, gamesToWin: 2, changeEndsAt: nil
        )
        XCTAssertEqual(problem, "The cap cannot be below the target score.")
        XCTAssertNil(
            ScoringRulesKt.scoringRulesProblem(
                pointsToWin: 21, winBy: 2, cap: 30, intervalAt: 11, gamesToWin: 2, changeEndsAt: 11
            )
        )
    }

    func testUndoIsReFoldingAShorterLog() {
        let log: [ScoreEvent] = [
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .home),
        ]
        let undone = MatchStateKt.foldMatchState(
            rules: ScoringRules.companion.BWF_21,
            setup: doubles,
            events: ScoreEventKt.undoLast(log)
        )
        XCTAssertEqual(undone.currentGame.home, 1)
    }
}
```

- [ ] **Step 2: Regenerate the Xcode project and run the test to verify it fails**

```bash
cd iosApp && xcodegen generate && cd ..
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: FAIL. Either the framework has not been rebuilt yet, or a generated name differs from the one assumed above.

- [ ] **Step 3: Reconcile any name the bridge rejects**

No Kotlin change should be needed. If one is:

- A generated name that is merely ugly: use it in the Swift test and leave Kotlin alone.
- A generated name that is ambiguous or actively misleading: rename the Kotlin declaration now, re-run `./gradlew :shared:jvmTest`, and update the Kotlin tests with it. This package has no other callers yet, which is the whole reason this task sits before L1 rather than after it.
- Do not add ObjC-only annotations or a Swift-facing wrapper file to work around a name. `SwiftInterop.kt` exists for `Result` and `suspend`, neither of which appears anywhere in this package.

- [ ] **Step 4: Run the test to verify it passes**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS, 5 tests in `ScoringEngineTests`, and the existing iOS suite still green.

- [ ] **Step 5: Verify Android still builds**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: PASS. Android has no scoring code yet, so this is confirming that a new `commonMain` package broke nothing, not testing the engine.

- [ ] **Step 6: Commit**

```bash
git add iosApp/Tests/ScoringEngineTests.swift iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "test(ios): prove the scoring engine is usable through the Swift bridge"
```

---

## What this leaves for L1

Recorded here so the next plan does not have to re-derive it:

- **The engine has no clock.** No entry carries a timestamp. L1 decides whether a match needs a started-at, and §6.1 is the reason it cannot be recovered later from the video.
- **Names live in the match record, not in `MatchSetup`.** `Side.HOME` and `PairPlayer.FIRST` are positions; mapping them to "Marco" is L1's.
- **The tag tally is not built here.** §7's L1 asks for the match's tag totals in the same shape as `buildMatchLabelSummary`, but that function takes clips and annotations. L1 needs its own aggregation over `MatchState.points`, and the two should share their sorting and rounding rules rather than being written twice.
- **"Reset game" is `dropPointsFrom`, and L1 supplies the ordinal.** Fold, take the first `ScoredPoint` whose `gameIndex` equals `state.gameIndex`, cut at its `ordinal`.
- **iOS is portrait-locked.** `iosApp/project.yml` declares `UISupportedInterfaceOrientations: [UIInterfaceOrientationPortrait]`, and §7's L1 asks for a landscape scoring surface. Android has no such lock - its single activity already declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`. Allowing landscape app-wide on iOS would rotate every existing screen, so L1 needs a per-view orientation policy, and that is a real decision rather than a line in a plist.
- **Keeping the screen awake** during a match reuses the `UIApplication.shared.isIdleTimerDisabled` pattern already in `RootView.swift`, driven off the analyze coordinator's `hasActiveUpload`.
