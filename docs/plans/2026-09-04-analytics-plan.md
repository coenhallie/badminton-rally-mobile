# Analytics Implementation Plan (Phase 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Home's second button somewhere to go: a list of matches showing which have on-device analysis, and a detail screen showing the court heatmap for those that do.

**Architecture:** A shared pure function classifies every match into one of three states, so both clients and their tests agree on what "has analysis" means without duplicating the rule. Android's existing `CourtHeatmapView` is reached through the new screen rather than rebuilt. iOS gets the identical structure and an honest empty state, because it has no on-device analysis until Stage 2 of the pipeline design lands.

**Tech Stack:** Kotlin / Jetpack Compose Material 3 (Android), Swift / SwiftUI (iOS), Kotlin Multiplatform shared module, XcodeGen, Gradle, XCTest, kotlin.test with kotest assertions.

**Spec:** `docs/plans/2026-09-03-home-and-analytics-redesign-design.md`

**Builds on:** phases 1 and 2, merged into `on-device-stage0` at `af14641`.

## Global Constraints

- **No em dashes** (U+2014) anywhere: code, comments, commit messages. Use a plain dash.
- **Commit messages:** no `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- **Stage explicit file paths.** Never `git add .` or `git add -A`.
- **`CHANGELOG.md` is generator-owned.** Never hand-edit it.
- **`iosApp/Sources/Info.plist` is generated** by XcodeGen from `iosApp/project.yml`. A new Swift file is not compiled until `xcodegen generate --spec iosApp/project.yml --project iosApp` runs, and an uncompiled Swift file produces NO build error - only tests that quietly stop running. Verify new files' `.o` object files exist in DerivedData.
- **Colour comes from a token, never from the platform.** No `.borderedProminent`, `.tint(`, `ButtonDefaults` defaults, `.accentColor`, asset colour sets, or an unset `containerColor` on a Material component. Phase 2 shipped a bottom sheet in Material's purple-tinted grey because its `containerColor` was never set, and no grep could see a colour that was never written down.
- **`accent` is a fill colour, never a text colour.** Accent-coloured text uses `accentDark`.
- **`textMuted` is display-sizes-only and only legible on `bg`** (2.920 light / 2.939 dark on `bgTertiary`). Body text on a raised surface uses `textTertiary`.
- **Every new test must be proven able to fail.** Break the thing it guards, watch it go red, restore, watch it go green, and put the failing output in the report. This project removed eleven tests that could not fail; every one was found this way.
- **Use `tools/adb-tap.sh` for every Android tap.** It taps by label from a uiautomator dump. Never `adb shell input tap` with a coordinate you computed. Four sign-outs in this project came from reading a position off a scaled screenshot. If the script refuses a tap, that is the safety net - stop and report.
- **Never** `adb uninstall`, `pm clear`, `-wipe-data`, or sign out of either device.

### The three row states

| State | Meaning | Row affordance |
| --- | --- | --- |
| `READY` | a stored player track exists for this match's local video | availability dot, opens the detail |
| `ANALYSABLE` | a local video entry exists, no track yet | "Analyse" pill, goes to court marking |
| `NOT_ON_DEVICE` | cloud or shared match, no local file | inert, subtitle says the video is not on this phone |

`NOT_ON_DEVICE` is not a failure state. A match uploaded from another phone, or shared by a coach, can never acquire a local entry, so offering to analyse it would promise something that cannot run.

### Build and test commands

```bash
./gradlew :androidApp:cleanTestDebugUnitTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
./gradlew :shared:jvmTest --rerun-tasks

xcodegen generate --spec iosApp/project.yml --project iosApp
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Baselines: **Android 183**, **`:shared:jvmTest` 436**, **iOS 136 `iosAppTests` + 3 `iosAppUITests` = 139**.

## File Structure

| File | Responsibility |
| --- | --- |
| `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowState.kt` | NEW. The three-state classifier. Pure, no platform types. |
| `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowStateTest.kt` | NEW. Its tests, run by CI's `:shared:jvmTest`. |
| `androidApp/.../analytics/AnalyticsScreen.kt` | NEW. The list. |
| `androidApp/.../analytics/AnalyticsDetailScreen.kt` | NEW. The tab shell hosting `CourtHeatmapView`. |
| `androidApp/.../nav/Route.kt` | MODIFY. Adds `Route.Analytics`. |
| `androidApp/.../AuthGate.kt` | MODIFY. Registers the two screens, wires `storedTrack`. |
| `androidApp/.../home/HomeScreen.kt` | MODIFY. Enables the Analytics pill. |
| `iosApp/Sources/Analytics/AnalyticsListView.swift` | NEW. The list. |
| `iosApp/Sources/Analytics/AnalyticsDetailView.swift` | NEW. The detail, with the empty state. |
| `iosApp/Sources/Home/HomeView.swift` | MODIFY. Enables the Analytics pill, registers the destination. |

---

## Task 1: The row-state classifier

Both clients must agree on what "has analysis" means. Putting the rule in the shared module makes that structural rather than asserted - the lesson from phase 2, where two platforms each asserted the same copy against their own local literal and could have drifted together undetected.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowState.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowStateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class AnalyticsRowState { READY, ANALYSABLE, NOT_ON_DEVICE }` and `fun analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean): AnalyticsRowState`. Tasks 2 and 4 consume both (the two list screens). Task 3 reaches the detail from a row already classified READY, so it does not call the classifier itself.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.shared.analytics

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The rule deciding what a coach can do with each match on the Analytics list.
 *
 * Lives in :shared rather than twice in the two clients, so the two cannot
 * drift apart. Phase 2 learned that the hard way: the hero copy was asserted on
 * each platform against a literal in that platform's own test file, so both
 * could have changed together and nothing would have failed.
 */
class AnalyticsRowStateTest {

    @Test
    fun a_match_with_a_stored_track_is_ready() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = true) shouldBe
            AnalyticsRowState.READY
    }

    @Test
    fun a_local_video_without_a_track_can_be_analysed() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = false) shouldBe
            AnalyticsRowState.ANALYSABLE
    }

    @Test
    fun a_match_with_no_local_video_is_not_on_this_device() {
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = false) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun a_track_without_a_local_entry_is_still_not_on_this_device() {
        // Defensive, and the one combination worth thinking about: a track can
        // outlive the video it came from, because a run's result is kept on
        // disk while the file itself can be removed. Reporting READY there
        // would send the coach to a heatmap whose match no longer exists on
        // this phone, so the local entry is what decides.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = true) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

`./gradlew :shared:jvmTest --tests '*AnalyticsRowStateTest*'`
Expected: `Unresolved reference: analyticsRowState`.

- [ ] **Step 3: Implement**

```kotlin
package com.badmintontracker.shared.analytics

/**
 * What a coach can do with one match on the Analytics list.
 *
 * NOT_ON_DEVICE is not a failure. A match uploaded from another phone, or
 * shared by a coach, has no local video and never can have one, so offering to
 * analyse it would promise something that cannot run.
 */
enum class AnalyticsRowState {
    /** A stored player track exists. Opens the heatmap. */
    READY,

    /** The video is on this phone but has not been analysed yet. */
    ANALYSABLE,

    /** No local video, and no way to get one. Inert. */
    NOT_ON_DEVICE,
}

/**
 * Classifies one match.
 *
 * The local entry decides, not the track: a track can outlive the video it came
 * from, because a run's result is kept on disk while the file itself can be
 * removed. Treating that as READY would send the coach to a heatmap whose match
 * is no longer on this phone.
 */
fun analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean): AnalyticsRowState = when {
    !hasLocalEntry -> AnalyticsRowState.NOT_ON_DEVICE
    hasStoredTrack -> AnalyticsRowState.READY
    else -> AnalyticsRowState.ANALYSABLE
}
```

- [ ] **Step 4: Run it and watch it pass**

Then `./gradlew :shared:jvmTest --rerun-tasks` in full. Expect 436 + 4 = 440. Reconcile.

- [ ] **Step 5: Prove it can fail**

Change the `!hasLocalEntry` guard to `hasStoredTrack ->` first, so a stored track wins over a missing entry. Confirm `a_track_without_a_local_entry_is_still_not_on_this_device` goes RED. Restore, confirm `git diff` empty, re-run green. Report the failing output.

- [ ] **Step 6: Confirm the framework still links and commit**

`./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` must succeed - iOS reads this type through the `Shared` framework.

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowState.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowStateTest.kt
git commit -m "feat: classify what a coach can do with each match's analysis

Three states rather than two. A match uploaded from another phone or
shared by a coach has no local video and never can have one, so offering
to analyse it would promise something that cannot run.

The local entry decides rather than the track, because a track outlives
the video it came from: a run's result is kept on disk while the file
itself can be removed, and calling that READY would open a heatmap whose
match is no longer on this phone.

In :shared so both clients read one rule. Phase 2 learned that asserting
the same value separately on each platform lets both drift together."
```

---

## Task 2: Android Analytics list

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsScreen.kt`
- Modify: `androidApp/.../nav/Route.kt`, `androidApp/.../AuthGate.kt`, `androidApp/.../home/HomeScreen.kt`

**Interfaces:**
- Consumes: `analyticsRowState` (Task 1); `localAnalysis.storedTrack(entryId)`, already used at `AuthGate.kt:215`.
- Produces: `Route.Analytics`; `@Composable fun AnalyticsScreen(rows, onOpenDetail, onAnalyse, onBack)`.

- [ ] **Step 1: Add the route and enable the pill**

`Route.kt` gains `@Serializable data object Analytics : Route`. `HomeScreen.kt`'s Analytics pill loses its disabled state and its "Coming soon" accessibility description, and navigates to `Route.Analytics`. Read the surrounding comment first - it explains why the pill was disabled - and replace it rather than leaving a comment that now contradicts the code.

- [ ] **Step 2: Build the list**

One section per group, mirroring the drawer's own grouping so a coach sees the same shape twice: local videos, then owned matches, then shared. Each row shows title, subtitle, and per state:

- `READY`: a filled dot in `MaterialTheme.colorScheme.primary`, whole row clickable, opens the detail.
- `ANALYSABLE`: a compact `ShuttlButton` reading "Analyse", going to court marking.
- `NOT_ON_DEVICE`: no control, and a subtitle line reading `Not on this phone` in `ShuttlTheme.extended.textTertiary`.

A legend above the list explains the dot. When every row is `NOT_ON_DEVICE`, show one explanatory line above the list instead of repeating the subtitle on every row.

Set `containerColor` explicitly on any Material component that takes one.

- [ ] **Step 3: Register in AuthGate**

`composable<Route.Analytics>` supplying rows built from the same view models `Route.Home` uses, with `hasStoredTrack = localAnalysis.storedTrack(id) != null`.

- [ ] **Step 4: Verify**

`./gradlew :androidApp:cleanTestDebugUnitTest :androidApp:testDebugUnitTest` (183, unchanged - this task adds no unit tests) and `assembleDebug`.

No unit test here: the screen is layout over a classifier that is already tested, and the module has no Robolectric. State that in the report rather than leaving the absence unexplained. If you find yourself writing a branch or computed value that is not just calling `analyticsRowState`, stop and report - that logic belongs in the shared function.

- [ ] **Step 5: Exercise on device and commit**

Open Analytics from Home using `./tools/adb-tap.sh "Analytics"`. Screenshot in both themes to `/tmp/`. Confirm each state renders as described, and that tapping a `READY` row goes somewhere and a `NOT_ON_DEVICE` row does nothing.

---

## Task 3: Android Analytics detail

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt`
- Modify: `androidApp/.../AuthGate.kt`

**Interfaces:**
- Consumes: `CourtHeatmapView(track: PlayerTrack, fps: Double, modifier, cellSizeM)` at `androidApp/.../localanalysis/CourtHeatmapView.kt:40`; `localAnalysis.stateFor(entryId)` and `.storedTrack(entryId)` as used at `AuthGate.kt:489-500`.
- Produces: `@Composable fun AnalyticsDetailScreen(...)`.

- [ ] **Step 1: Build the tab shell**

A pill tab row above the content. **Only the Heatmap tab ships.** `SkeletonOverlay` exists at `androidApp/.../localanalysis/SkeletonOverlay.kt` but has NO caller anywhere in the app and no host screen; giving it one is product work, not this task. Render a single tab rather than a disabled second one - a tab bar with one tab is honest, a permanently disabled tab is clutter.

Host `CourtHeatmapView` with the track resolved exactly as `Route.Heatmap` does today: in-memory if the run is still loaded, from disk otherwise, and the existing "This analysis is no longer loaded. Run it again to see the heatmap." message when neither. Read `AuthGate.kt:489-520` and preserve that behaviour - it exists because a pose run costs half an hour and losing it to a process death must not look like an empty court.

- [ ] **Step 2: Keep `Route.Heatmap` working**

`Route.Heatmap` is reached from the analysis banner as well as the list. Do NOT delete or replace it. Analytics detail is an additional entry point to the same view.

- [ ] **Step 3: Verify, exercise, commit**

Both suites unchanged. On device, open a `READY` row and confirm the heatmap renders. Screenshot both themes to `/tmp/`.

---

## Task 4: iOS Analytics list

**Files:**
- Create: `iosApp/Sources/Analytics/AnalyticsListView.swift`
- Modify: `iosApp/Sources/Home/HomeView.swift`

**Interfaces:**
- Consumes: `analyticsRowState` via the `Shared` framework; `ClipListModel`'s `ownedRows`, `shared`, `localEntries`.
- Produces: `struct AnalyticsListView: View`.

- [ ] **Step 1: Enable the pill and register the destination**

`HomeView.swift`'s Analytics pill loses its disabled state and "Coming soon" hint, and pushes `AnalyticsListView`. Replace the comment explaining why it was disabled rather than leaving it to contradict the code.

- [ ] **Step 2: Build the list**

Same three states, same grouping, same legend as Android. On iOS `hasStoredTrack` is **always false** - there is no on-device analysis until Stage 2 of `docs/plans/2026-08-31-on-device-analysis-pipeline-design.md`. Do NOT special-case iOS in the view: pass `false` and let the shared classifier produce `NOT_ON_DEVICE`, so the day tracks exist the screen starts working with no UI change.

Because every row will be `NOT_ON_DEVICE` today, the all-inert explanatory line from Task 2 is what a coach actually sees. Write it so it reads as "not yet on iPhone" rather than as a broken screen, and say in the report exactly what wording you used.

- [ ] **Step 3: Verify, exercise, commit**

iOS suite 139 unchanged. Run `xcodegen generate` and confirm the new file's `.o` exists. Screenshot both themes to `/tmp/` and describe how the empty state reads.

---

## Task 5: iOS Analytics detail

**Files:**
- Create: `iosApp/Sources/Analytics/AnalyticsDetailView.swift`

- [ ] **Step 1: Build the honest empty state**

There is no iOS heatmap renderer, and porting `CourtHeatmapView` is downstream of pipeline Stage 2, not this phase. This screen exists so the structure is in place and Stage 2's arrival is a data change rather than a screen build.

It shows the match's title, and a single explanatory paragraph saying analysis runs on Android today and is coming to iPhone. No fake chart, no placeholder skeleton, no spinner. Do not invent a visual.

- [ ] **Step 2: Verify and commit**

Suite unchanged. Screenshot both themes. Say in the report whether the screen reads as deliberate or as unfinished - if it reads as unfinished, say so rather than shipping it.

---

## Task 6: Visual sweep

**Files:** whichever the sweep implicates.

- [ ] **Step 1: Capture the new screens**

`<platform>-analytics-<theme>.png` and `<platform>-analyticsdetail-<theme>.png`, eight files, into `docs/screenshots/2026-09-03-design-system/after/`. These are additions with no baseline; note that they fall outside the 36-name parity check.

- [ ] **Step 2: Re-capture Home on both platforms**

Its Analytics pill changed from disabled to enabled. Overwrite `<platform>-home-<theme>.png`.

- [ ] **Step 3: Review**

Both platforms' Analytics screens should read as the same design. Check the three row states are visually distinguishable, the legend explains the dot, and the disabled-to-enabled pill change did not shift Home's layout.

- [ ] **Step 4: Run the DoD greps**

```bash
grep -rn "borderedProminent\|\.tint(\|ButtonDefaults\|\.accentColor" iosApp/Sources androidApp/src/main
grep -rn "ModalBottomSheet\|ModalNavigationDrawer" androidApp/src/main | grep -v "containerColor\|drawerContainerColor"
grep -rn "0x22C55E\|0x16A34A\|0x3EE27C" iosApp/Sources androidApp/src/main | grep -v ShuttlPalette
```

Each must return nothing, or a line you can justify. The second grep is new this phase: it catches a Material container whose colour was never set, which is how phase 2 shipped a purple-tinted sheet.

- [ ] **Step 5: Fix in place, then commit**

A padding, a line limit, a frame height. **Do not redesign.** Anything larger goes in the report.

## Definition of Done

- [ ] `:shared:jvmTest` passes; count reconciled from 436.
- [ ] Android `testDebugUnitTest` passes at 183; `assembleDebug` passes.
- [ ] iOS suite passes at 136 + 3 = 139.
- [ ] `:shared:linkDebugFrameworkIosSimulatorArm64` succeeds.
- [ ] The Analytics pill is enabled on both platforms and its "Coming soon" hint is gone.
- [ ] `Route.Heatmap` still works from the analysis banner.
- [ ] All three DoD greps return nothing unjustified.
- [ ] Analytics and its detail are captured on both platforms in both themes.
- [ ] No em dashes on added lines; no attribution trailers.

## Not In This Plan

The Skeleton tab. `SkeletonOverlay` has no caller and no host; giving it one is product work needing its own design.

The iOS heatmap renderer. It is downstream of Stage 2 of `docs/plans/2026-08-31-on-device-analysis-pipeline-design.md` and belongs to that design.
