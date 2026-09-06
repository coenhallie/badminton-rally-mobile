# Home and Drawer Implementation Plan (Phase 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's front door on both clients with the mock's Home screen - a rotating hero line and two buttons - and move the match list into a drawer, taking the navigation graph with it.

**Architecture:** `HomeView` / `HomeScreen` becomes the authenticated root and takes over the navigation graph that `ClipListView` / `ClipListScreen` owns today. Those two lose their navigation registrations and become pure list bodies rendered inside a drawer. Everything with logic in it - the hero's index advance, the drawer's drag arithmetic - is a pure function in its own file so it can be unit tested without a running animation or a gesture.

**Tech Stack:** Kotlin / Jetpack Compose Material 3 (Android), Swift / SwiftUI (iOS), XcodeGen, Gradle, XCTest, kotlin.test with kotest assertions.

**Spec:** `docs/plans/2026-09-03-home-and-analytics-redesign-design.md`

**Builds on:** `docs/plans/2026-09-03-design-system-plan.md` (phase 1, complete and merged into branch `design-system-phase1` at 9537713). Phase 1's `docs/screenshots/2026-09-03-design-system/after/` is this phase's visual baseline. Do not re-capture it.

## Global Constraints

- **No em dashes** (U+2014) anywhere: code, comments, commit messages. Use a plain dash. This file contains none, so a grep for the character over the repo stays a clean signal.
- **Commit messages:** no `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- **Stage explicit file paths.** Never `git add .` or `git add -A`.
- **`CHANGELOG.md` is generator-owned.** Never hand-edit it.
- **`iosApp/Sources/Info.plist` is generated** by XcodeGen from `iosApp/project.yml`. Edit `project.yml` and run `xcodegen generate --spec iosApp/project.yml --project iosApp`. New Swift files need this to be compiled at all, and an uncompiled file is not a build error - it shows up only as tests that quietly stop running.
- **Start from a clean working tree.** `git status --porcelain` must show only the user's untracked `docs/2026-08-28-what-to-build-next-for-the-coach.pdf`.
- **The wordmark is "SHUTTL."** The mock's "Rally" is placeholder. Do not rename anything.
- **The accent is a fill colour, never a text colour.** Accent-coloured text uses `accentDark`.
- **`textMuted` is for display sizes only, and only on `bg`.** It does not clear 3.0:1 on `bgTertiary` (2.920 light / 2.939 dark). Text on a raised surface uses `textTertiary`.
- **Every new test must be proven able to fail.** Break the thing it guards, watch it go red, restore, watch it go green, and put the failing output in your report. Phase 1 shipped two tests that could not fail; both were found this way.
- **A control's colour must come from a token, not from the platform.** Phase 1's greps searched for literal hex and were structurally blind to `.buttonStyle(.borderedProminent)`, `.tint`, `ButtonDefaults`, and asset colour sets, which resolve colour from the system. Every new control in this phase must set its colour explicitly from `Shuttl` / `MaterialTheme`. The DoD greps for these forms, not just literals.

### Exact copy

Hero, first line: `Your game,`
Hero, rotating second line, in this order:
1. `clipped rally by rally.`
2. `mapped as heatmaps.`
3. `tracked as skeletons.`
4. `annotated and shared.`

Bottom hint: iOS `Swipe right for your matches`, Android `Your matches`. Both tappable, both open the drawer. Android differs because on gesture navigation the system owns the left edge and swipe-to-open is unreliable there; promising a gesture the OS will eat is worse than not promising it.

Buttons: `Add new match` (primary, accent fill, `onAccent` text, plus glyph), `Analytics` (secondary, `bgTertiary` fill).

Add sheet rows, in this order: `New match`, `Record video`, `Import video`.

Drawer: title `Matches`; sections `On this phone`, `My matches`, `Shared with me`; footer `Labels`, `Sign out`, and the version string.

### Exact metrics

| Thing | Value |
| --- | --- |
| Hero dwell | 2600 ms |
| Hero transition | 380 ms |
| Hero incoming offset | 14 pt, rising to 0 |
| Hero outgoing offset | 0, rising to -12 pt |
| Hero line height | 1.08, achieved by two stacked `Text`s with explicit spacing, NOT by line-height (SwiftUI's `.lineSpacing` is additive and cannot tighten below Archivo's natural 1.088) |
| Drawer width | `min(330, screenWidth * 0.86)` |
| iOS edge zone | 26 pt |
| iOS open threshold | 70 pt of translation, or velocity > 300 pt/s |
| Button height | 60 pt |
| Gutter | 24 pt |

### Build and test commands

```bash
# Android
./gradlew :androidApp:cleanTestDebugUnitTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug

# iOS
xcodegen generate --spec iosApp/project.yml --project iosApp   # after any new file
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Baselines entering this phase: **Android 182 tests / 0 failures**, **iOS 125 (122 `iosAppTests` + 3 `iosAppUITests`) / 0 failures**. Every task reconciles its count against these. A DROP means files fell out of the build.

The iOS simulator holds a signed-in session that lives in the Keychain and **cannot be scripted back if lost**. Never sign out.

## File Structure

**New, iOS**

| File | Responsibility |
| --- | --- |
| `iosApp/Sources/Home/HeroTicker.swift` | The phrases and the index advance. Pure, no SwiftUI. |
| `iosApp/Sources/Home/HeroTickerView.swift` | Renders the two-line hero and drives the timer. |
| `iosApp/Sources/Home/DrawerDragMath.swift` | Drawer width, open threshold, offset, scrim opacity. Pure. |
| `iosApp/Sources/Home/MatchesDrawer.swift` | The drawer shell: overlay, scrim, drag, header, footer. |
| `iosApp/Sources/Home/AddMatchSheet.swift` | The three-row sheet. |
| `iosApp/Sources/Home/HomeView.swift` | The screen, and the owner of the navigation graph. |
| `iosApp/Sources/Components/VersionLabel.swift` | `versionLabel`, currently duplicated in two files. |

**New, Android**

| File | Responsibility |
| --- | --- |
| `.../android/home/HeroTicker.kt` | Phrases and index advance. Pure. |
| `.../android/home/HeroTickerView.kt` | The composable and its timer. |
| `.../android/home/MatchesDrawer.kt` | Drawer content for `ModalNavigationDrawer`. |
| `.../android/home/AddMatchSheet.kt` | `ModalBottomSheet` with three rows. |
| `.../android/home/HomeScreen.kt` | The screen. |

**Modified**

| File | Change |
| --- | --- |
| `iosApp/Sources/ClipList/ClipListView.swift` | Body becomes `MatchesList`; loses every navigation registration and `createFlowTarget`, which move to `HomeView` verbatim. |
| `iosApp/Sources/RootView.swift` | Authenticated case renders `HomeView`, not `ClipListView`. |
| `androidApp/.../cliplist/ClipListScreen.kt` | Same: becomes drawer content, reports taps upward. |
| `androidApp/.../nav/Route.kt` | Adds `Route.Home`. Removes `Route.ClipList`. |
| `androidApp/.../AuthGate.kt` | Start destination becomes `Route.Home`; `LocalBackgroundWorkClick` navigates to `Route.Home` and opens the drawer; `LocalAnalysisBanner` moves to Home. |

---

## Task 1: Hero ticker logic, both platforms

The rotating line's only logic is which phrase comes next. Extracting it makes the copy and the wrap-around testable without a timer, and gives the two platforms one shared definition to be checked against each other.

**Files:**
- Create: `iosApp/Sources/Home/HeroTicker.swift`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/HeroTicker.kt`
- Test: `iosApp/Tests/HeroTickerTests.swift`
- Test: `androidApp/src/test/java/com/badmintontracker/android/home/HeroTickerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: iOS `enum HeroTicker` with `static let phrases: [String]`, `static let leadLine: String`, `static func next(after index: Int) -> Int`. Android `object HeroTicker` with `val phrases: List<String>`, `val leadLine: String`, `fun next(after: Int): Int`. Tasks 2, 7 and 8 consume these.

- [ ] **Step 1: Write the failing tests**

`iosApp/Tests/HeroTickerTests.swift`:

```swift
import XCTest
@testable import iosApp

/// The hero's copy and its wrap-around, pinned so neither drifts from the mock
/// or from the Android side. The rotation is the one piece of logic on an
/// otherwise static screen, and a timer is not needed to test it.
final class HeroTickerTests: XCTestCase {
    func testCopyMatchesTheMockVerbatim() {
        XCTAssertEqual(HeroTicker.leadLine, "Your game,")
        XCTAssertEqual(HeroTicker.phrases, [
            "clipped rally by rally.",
            "mapped as heatmaps.",
            "tracked as skeletons.",
            "annotated and shared.",
        ])
    }

    func testAdvancesThroughEveryPhrase() {
        var seen: [Int] = [0]
        var i = 0
        for _ in 1..<HeroTicker.phrases.count {
            i = HeroTicker.next(after: i)
            seen.append(i)
        }
        XCTAssertEqual(seen, [0, 1, 2, 3], "every phrase must be reachable in order")
    }

    func testWrapsBackToTheFirstPhrase() {
        XCTAssertEqual(HeroTicker.next(after: HeroTicker.phrases.count - 1), 0)
    }

    func testOutOfRangeIndexDoesNotCrashOrEscape() {
        // Defensive: state restored from a stale value must not index out of
        // bounds. Any input lands back inside the array.
        for i in [-5, 99, Int.max] {
            let n = HeroTicker.next(after: i)
            XCTAssertTrue(HeroTicker.phrases.indices.contains(n), "next(after: \(i)) escaped the array")
        }
    }
}
```

`androidApp/src/test/java/com/badmintontracker/android/home/HeroTickerTest.kt`:

```kotlin
package com.badmintontracker.android.home

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The mirror of iosApp's HeroTickerTests. Both platforms transcribe the copy
 * independently, so this pair is what catches one side being edited alone.
 */
class HeroTickerTest {
    @Test
    fun copy_matches_the_mock_verbatim() {
        HeroTicker.leadLine shouldBe "Your game,"
        HeroTicker.phrases shouldBe listOf(
            "clipped rally by rally.",
            "mapped as heatmaps.",
            "tracked as skeletons.",
            "annotated and shared.",
        )
    }

    @Test
    fun advances_through_every_phrase() {
        val seen = mutableListOf(0)
        var i = 0
        repeat(HeroTicker.phrases.size - 1) {
            i = HeroTicker.next(i)
            seen += i
        }
        seen shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun wraps_back_to_the_first_phrase() {
        HeroTicker.next(HeroTicker.phrases.size - 1) shouldBe 0
    }

    @Test
    fun out_of_range_index_does_not_escape() {
        for (i in listOf(-5, 99, Int.MAX_VALUE)) {
            HeroTicker.phrases.indices.contains(HeroTicker.next(i)) shouldBe true
        }
    }
}
```

- [ ] **Step 2: Run both and watch them fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*HeroTickerTest*'
```
Expected: `Unresolved reference: HeroTicker`.

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/HeroTickerTests
```
Expected: `cannot find 'HeroTicker' in scope`.

- [ ] **Step 3: Implement on iOS**

```swift
import Foundation

/// The Home hero's copy and rotation.
///
/// Separate from the view so the phrases and the wrap-around can be asserted
/// without a running timer, and so the copy sits in one obvious place to edit.
/// Mirrors androidApp's HeroTicker.kt word for word; HeroTickerTests and
/// HeroTickerTest check the two against each other.
enum HeroTicker {
    /// The fixed first line. Held here rather than in the view so both lines of
    /// the hero are edited in the same file.
    static let leadLine = "Your game,"

    static let phrases = [
        "clipped rally by rally.",
        "mapped as heatmaps.",
        "tracked as skeletons.",
        "annotated and shared.",
    ]

    /// The next phrase index, wrapping at the end.
    ///
    /// Clamps rather than trusting its input: the index is view state, and state
    /// restored after a process death has been seen to arrive stale. An out of
    /// range value returns a valid index instead of trapping.
    static func next(after index: Int) -> Int {
        guard !phrases.isEmpty else { return 0 }
        let safe = index < 0 || index >= phrases.count ? 0 : index
        return (safe + 1) % phrases.count
    }
}
```

- [ ] **Step 4: Implement on Android**

```kotlin
package com.badmintontracker.android.home

/**
 * The Home hero's copy and rotation.
 *
 * Separate from the composable so the phrases and the wrap-around can be
 * asserted without a running timer. Mirrors iosApp's HeroTicker.swift word for
 * word; the two test files check them against each other.
 */
object HeroTicker {
    /** The fixed first line. Held here so both lines are edited in one file. */
    const val leadLine = "Your game,"

    val phrases = listOf(
        "clipped rally by rally.",
        "mapped as heatmaps.",
        "tracked as skeletons.",
        "annotated and shared.",
    )

    /**
     * The next phrase index, wrapping at the end.
     *
     * Clamps rather than trusting its input: the index is UI state and a value
     * restored after process death has been seen to arrive stale.
     */
    fun next(after: Int): Int {
        if (phrases.isEmpty()) return 0
        val safe = if (after < 0 || after >= phrases.size) 0 else after
        return (safe + 1) % phrases.size
    }
}
```

- [ ] **Step 5: Register the new Swift file and run both suites**

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
```

Then both suites in full. Android 182 -> 186 (4 new). iOS 122 -> 126 unit, plus 3 UI = 129. Reconcile the numbers explicitly; a drop means a file is not being compiled.

- [ ] **Step 6: Prove the tests can fail**

Change one phrase's text, run `HeroTickerTests` / `HeroTickerTest`, confirm the copy test fails and names the mismatch. Restore, confirm `git diff` is empty, re-run green. Put the failing output in your report.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/Home/HeroTicker.swift iosApp/Tests/HeroTickerTests.swift \
        iosApp/iosApp.xcodeproj/project.pbxproj \
        androidApp/src/main/java/com/badmintontracker/android/home/HeroTicker.kt \
        androidApp/src/test/java/com/badmintontracker/android/home/HeroTickerTest.kt
git commit -m "feat: the Home hero's copy and rotation, on both clients

The rotating line's only logic is which phrase comes next, so it lives in
a pure type that can be tested without a timer. The copy sits with it
rather than in the view, so both lines of the hero are edited in one
place and the two platforms can be checked against each other.

next() clamps its input instead of trusting it: the index is view state,
and state restored after a process death has been seen to arrive stale."
```

---

## Task 2: iOS typography adoption

Phase 1 built the type scale but iOS has exactly ONE `.shuttlType` call site against 84 `.font(...)` sites, so `matches` and `share` currently render Archivo and the system font side by side. Android does not have this problem: all 15 M3 slots were put on Archivo centrally. This task closes the gap before Home is built, so Home is not the only screen in the app that looks finished.

**Files:**
- Modify: every iOS view with a `.font(...)` call. Enumerate them first with the grep in Step 1.
- Test: `iosApp/Tests/TypographyAdoptionTests.swift`

**Interfaces:**
- Consumes: `ShuttlType` roles and `View.shuttlType(_:)` from phase 1.
- Produces: nothing new. Later tasks simply inherit a consistent typeface.

- [ ] **Step 1: Enumerate the sites**

```bash
grep -rn "\.font(" iosApp/Sources | grep -v "Theme/" | tee /tmp/font-sites.txt
wc -l /tmp/font-sites.txt
```

Map each `.font(.system(size:weight:))` or `.font(.body)` etc. to the nearest role in the scale:

| Existing | Role |
| --- | --- |
| `.largeTitle`, `.system(size: 34+)` | `ShuttlType.headlineLarge` |
| `.title`, `.system(size: 26...33)` | `ShuttlType.headlineMedium` |
| `.headline`, `.system(size: 16...17, weight: .semibold)` | `ShuttlType.titleLarge` |
| `.subheadline`, `.system(size: 15)` | `ShuttlType.titleMedium` |
| `.body`, `.system(size: 16)` | `ShuttlType.bodyLarge` |
| `.callout`, `.system(size: 14)` | `ShuttlType.bodyMedium` |
| `.footnote`, `.caption`, `.system(size: 11...12)` | `ShuttlType.bodySmall` |
| `.system(size: 11, weight: .medium)` + `kerning` | `ShuttlType.labelSmall` |

**Do NOT convert these**, and say in your report that you did not:
- Anything inside a `Canvas` (`CourtMarkingView`, `SchematicCourtGuide`) - those size text against a scaled drawing context, not the type scale.
- The scoreboard numerals in `ScoringView` - they are sized to fill a half-screen, not to a text role.
- Anything where the existing size has a comment explaining why it is that exact value.

- [ ] **Step 2: Write the failing test**

`iosApp/Tests/TypographyAdoptionTests.swift`:

```swift
import XCTest
import UIKit
@testable import iosApp

/// Guards typeface consistency across the iOS app.
///
/// Android gets this for free: all 15 M3 Typography slots were put on Archivo
/// centrally, so every Compose screen inherits it. SwiftUI has no equivalent
/// central object, so on iOS it has to be adopted per call site and nothing but
/// a test stops the next new view from silently reverting to San Francisco.
///
/// This counts source call sites rather than rendering anything: a rendering
/// test would need a host app and would still only cover the screens it drove.
final class TypographyAdoptionTests: XCTestCase {

    /// Call sites deliberately left on a system font, with the reason. Anything
    /// NOT in here that still calls `.font(` is a regression.
    private let allowed: Set<String> = [
        "CourtMarkingView.swift",     // sizes text inside a scaled Canvas
        "SchematicCourtGuide.swift",  // same
        "ScoringView.swift",          // scoreboard numerals fill a half screen
    ]

    private func sourceFiles() -> [URL] {
        // Walks the checked-out sources next to the test bundle. Falls back to
        // skipping rather than failing if the layout is not as expected, so this
        // never fails for an unrelated reason on CI.
        let here = URL(fileURLWithPath: #filePath)
        let root = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources")
        guard let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else { return [] }
        return e.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    func testEveryViewUsesTheTypeScale() throws {
        let files = sourceFiles()
        try XCTSkipIf(files.isEmpty, "source tree not reachable from the test bundle")
        var offenders: [String] = []
        for url in files {
            let name = url.lastPathComponent
            if allowed.contains(name) || url.path.contains("/Theme/") { continue }
            let text = try String(contentsOf: url, encoding: .utf8)
            if text.contains(".font(") {
                offenders.append(name)
            }
        }
        XCTAssertEqual(
            offenders.sorted(), [],
            "these views still set a font directly instead of using .shuttlType(...): \(offenders.sorted())"
        )
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Expected: FAIL, listing every unconverted view by name. That list is your work queue for Step 4.

- [ ] **Step 4: Convert the sites**

Work file by file, smallest first. For each, replace `.font(X)` with `.shuttlType(ShuttlType.<role>)` per the mapping table. `.shuttlType` applies font, kerning and leading together, so remove any adjacent `.kerning(...)` that the role now supplies - leaving both would double the tracking.

Change ONLY the font expression. No padding, frame, colour or structural change.

- [ ] **Step 5: Run it and watch it pass, then run the full suite**

Full iOS suite. Expected 126 unit + 3 UI = 129 after Task 1, plus 1 new here = 127 + 3 = 130. Reconcile.

- [ ] **Step 6: Screenshot the two known-mixed screens**

Re-capture `ios-matches-light.png`, `ios-matches-dark.png`, `ios-share-light.png`, `ios-share-dark.png` into `docs/screenshots/2026-09-03-design-system/after/`, overwriting. Compare against their current versions and confirm the typeface is now uniform and that nothing reflowed off-screen. Archivo is not metric-compatible with San Francisco, so expect small width changes; a truncation that was not there before is a defect to fix in place with a line limit or frame adjustment.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources iosApp/Tests/TypographyAdoptionTests.swift \
        docs/screenshots/2026-09-03-design-system/after
git commit -m "feat(ios): put every view on the type scale

Phase 1 built the scale but left iOS with one call site against 84 direct
.font(...) calls, so matches and share rendered Archivo and San Francisco
side by side. Android never had this problem because all 15 M3 slots were
put on Archivo centrally; SwiftUI has no equivalent, so adoption is per
call site and only a test keeps it that way.

Canvas text and the scoreboard numerals stay on explicit sizes - they are
sized against a drawing context and a half screen, not against a text
role - and the test lists them with that reason."
```

---

## Task 3: Drawer drag arithmetic

The iOS drawer is a custom overlay, because SwiftUI has no drawer primitive. Its arithmetic - how wide, when to commit an open, where to sit mid-drag, how dark the scrim - is pure and belongs in its own file, following the precedent of `FrameStepMath` and `CourtTapMath`.

**Files:**
- Create: `iosApp/Sources/Home/DrawerDragMath.swift`
- Test: `iosApp/Tests/DrawerDragMathTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum DrawerDragMath` with `static let edgeZoneWidth: CGFloat`, `static let openThreshold: CGFloat`, `static let velocityThreshold: CGFloat`, `static func width(forScreenWidth: CGFloat) -> CGFloat`, `static func shouldOpen(translation: CGFloat, velocity: CGFloat) -> Bool`, `static func offset(translation: CGFloat, width: CGFloat, isOpen: Bool) -> CGFloat`, `static func scrimOpacity(offset: CGFloat, width: CGFloat) -> Double`. Task 5 consumes all of it.

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
import CoreGraphics
@testable import iosApp

/// The drawer's arithmetic, tested without a gesture.
///
/// SwiftUI has no drawer primitive so this is a hand built overlay, and the
/// parts that can be wrong in a way a screenshot will not show - a threshold, a
/// clamp, an opacity ramp - are pure functions here rather than inline in the
/// view. Same reasoning as FrameStepMath and CourtTapMath.
final class DrawerDragMathTests: XCTestCase {

    func testWidthIsCappedOnLargeScreensAndProportionalOnSmall() {
        // 393pt is the mock's canvas. 86% of it is 337.98, ABOVE the cap, so it
        // takes 330 - which is exactly the drawer width the mock draws. The cap
        // and the mock agree at the design's own size, which is the point of it.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 393), 330, accuracy: 0.01)
        // A small phone falls below the cap and scales instead, so the drawer
        // never eats the whole screen on a 375pt device.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 375), 375 * 0.86, accuracy: 0.01)
        // A tablet takes the cap, so the drawer never becomes a full page.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 1024), 330, accuracy: 0.01)
    }

    func testOpensOnADeliberateDrag() {
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 71, velocity: 0))
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: 69, velocity: 0))
    }

    func testOpensOnAFastFlickThatDidNotTravelFar() {
        // A flick is a real gesture: short travel, high speed. Without this the
        // drawer would refuse to open for anyone who swipes quickly.
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 20, velocity: 900))
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: 20, velocity: 100))
    }

    func testABackwardDragNeverOpens() {
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: -200, velocity: -900))
    }

    func testOffsetIsClampedToTheDrawerWidth() {
        let w: CGFloat = 330
        // Closed, mid drag: sits between fully hidden and fully open.
        XCTAssertEqual(DrawerDragMath.offset(translation: 100, width: w, isOpen: false), -230, accuracy: 0.01)
        // Over dragging past open does not push the drawer past its own edge.
        XCTAssertEqual(DrawerDragMath.offset(translation: 999, width: w, isOpen: false), 0, accuracy: 0.01)
        // Dragging backwards from closed does not pull it further off screen.
        XCTAssertEqual(DrawerDragMath.offset(translation: -999, width: w, isOpen: false), -w, accuracy: 0.01)
    }

    func testScrimIsInvisibleWhenClosedAndFullWhenOpen() {
        let w: CGFloat = 330
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: -w, width: w), 0, accuracy: 0.001)
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: 0, width: w), 1, accuracy: 0.001)
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: -w / 2, width: w), 0.5, accuracy: 0.001)
    }

    func testScrimOpacityNeverEscapesZeroToOne() {
        // Guards the overlay against a stale or overshooting offset making the
        // scrim opaque over a closed drawer, which would block the whole screen.
        for o in [-9999, -331, 0, 1, 9999].map(CGFloat.init) {
            let v = DrawerDragMath.scrimOpacity(offset: o, width: 330)
            XCTAssertTrue((0...1).contains(v), "opacity \(v) escaped 0...1 at offset \(o)")
        }
    }

    func testDegenerateWidthDoesNotDivideByZero() {
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: 0, width: 0), 0, accuracy: 0.001)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Expected: `cannot find 'DrawerDragMath' in scope`.

- [ ] **Step 3: Implement**

```swift
import CoreGraphics

/// The matches drawer's arithmetic.
///
/// SwiftUI has no drawer primitive, so the drawer is a hand built overlay and
/// this is the part of it that can be wrong invisibly: a threshold that makes
/// the gesture feel dead, a clamp that lets the panel slide past its own edge,
/// an opacity ramp that leaves an invisible scrim swallowing taps. Pure, so all
/// of it is covered by DrawerDragMathTests without driving a gesture.
enum DrawerDragMath {
    /// How far in from the left edge a drag may start. Matches the mock's 26px.
    static let edgeZoneWidth: CGFloat = 26

    /// Travel that commits an open on its own.
    static let openThreshold: CGFloat = 70

    /// Speed that commits an open regardless of travel, in points per second.
    /// Without this a quick flick, which is how most people open a drawer, would
    /// be rejected for not having travelled far enough.
    static let velocityThreshold: CGFloat = 300

    /// Capped rather than purely proportional: on an iPad an 86% drawer would be
    /// a full page with a sliver of scrim, which reads as a broken screen rather
    /// than as a panel over the one behind it.
    static func width(forScreenWidth screenWidth: CGFloat) -> CGFloat {
        min(330, screenWidth * 0.86)
    }

    static func shouldOpen(translation: CGFloat, velocity: CGFloat) -> Bool {
        guard translation > 0 else { return false }
        return translation >= openThreshold || velocity >= velocityThreshold
    }

    /// Where the panel sits, measured from fully hidden (-width) to open (0).
    static func offset(translation: CGFloat, width: CGFloat, isOpen: Bool) -> CGFloat {
        let base: CGFloat = isOpen ? 0 : -width
        return min(0, max(-width, base + translation))
    }

    /// Clamped at both ends: an offset arriving from stale state must never
    /// leave a fully opaque scrim over a closed drawer, which would swallow
    /// every tap on the screen underneath with nothing visible to explain it.
    static func scrimOpacity(offset: CGFloat, width: CGFloat) -> Double {
        guard width > 0 else { return 0 }
        return Double(min(1, max(0, 1 - abs(offset) / width)))
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Then the full iOS suite, reconciling against the count after Task 2.

- [ ] **Step 5: Prove the tests can fail**

Change `openThreshold` to 0, run, confirm `testOpensOnADeliberateDrag` fails. Restore, confirm empty `git diff`, re-run green. Report the failing output.

- [ ] **Step 6: Commit**

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
git add iosApp/Sources/Home/DrawerDragMath.swift iosApp/Tests/DrawerDragMathTests.swift \
        iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): the drawer's drag arithmetic, as a pure type

SwiftUI has no drawer primitive, so the matches drawer is a hand built
overlay. The parts of it that can be wrong invisibly live here: the open
threshold, the offset clamp, and the scrim ramp, which is clamped at both
ends because an opaque scrim over a closed drawer would swallow every tap
on the screen underneath with nothing on screen to explain it.

Opens on travel OR on velocity: a flick is short and fast, and a
travel-only threshold rejects the gesture most people actually make."
```

---

## Task 4: Add-match sheet, both platforms

The mock collapses today's three-item "+" menu into one button. The three actions survive behind a sheet.

**Files:**
- Create: `iosApp/Sources/Home/AddMatchSheet.swift`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/AddMatchSheet.kt`

**Interfaces:**
- Consumes: `ShuttlType`, `ShuttlRadius`, `Shuttl` / `MaterialTheme` tokens.
- Produces: iOS `struct AddMatchSheet: View` taking `onNewMatch: () -> Void`, `onRecord: () -> Void`, `onImport: () -> Void`. Android `@Composable fun AddMatchSheet(onNewMatch: () -> Unit, onRecord: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit)`. Tasks 7 and 8 present them.

There is no unit test here: the sheet is three rows that call three closures, with no logic to assert. Its verification is the visual check in Task 9 and the fact that the three actions still reach the same code paths they do today. Say so in your report rather than inventing a test that asserts a closure was stored.

- [ ] **Step 1: Read what the three actions do today**

`iosApp/Sources/ClipList/ClipListView.swift:72-85` and `androidApp/.../cliplist/ClipListScreen.kt`'s `onRecord` / `onImport` / `onNewMatch` parameters. The sheet must call exactly these, unchanged. Record what each does in your report.

- [ ] **Step 2: Implement the iOS sheet**

```swift
import SwiftUI

/// The three ways a match starts, behind Home's one "Add new match" button.
///
/// The mock shows a single button where the list had a three item menu. The
/// actions are unchanged, so nothing a coach could do before is gone; only the
/// way in is. Presented as a sheet rather than a menu because a 60pt primary
/// button implies a destination, not a popover.
struct AddMatchSheet: View {
    let onNewMatch: () -> Void
    let onRecord: () -> Void
    let onImport: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            row("New match", systemImage: "plus.circle", action: onNewMatch)
            Divider().overlay(Shuttl.border)
            row("Record video", systemImage: "video", action: onRecord)
            Divider().overlay(Shuttl.border)
            row("Import video", systemImage: "square.and.arrow.down", action: onImport)
        }
        .padding(.vertical, 8)
        .presentationDetents([.height(220)])
        .presentationDragIndicator(.visible)
        .background(Shuttl.bgSecondary)
    }

    private func row(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .foregroundStyle(Shuttl.accentDark)
                    .frame(width: 24)
                Text(title)
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.text)
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
```

Note `Shuttl.accentDark` for the glyph, not `Shuttl.accent`: these are icons on a surface, which is text-like usage, and `accent` does not clear 4.5:1 on a light background.

- [ ] **Step 3: Implement the Android sheet**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * The three ways a match starts, behind Home's one "Add new match" button.
 *
 * Mirrors iosApp's AddMatchSheet.swift. The actions are unchanged from the
 * three item menu this replaces; only the way in is different.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMatchSheet(
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row_("New match", onNewMatch)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_("Record video", onRecord)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row_("Import video", onImport)
        }
    }
}

@Composable
private fun Row_(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

- [ ] **Step 4: Build both**

`./gradlew :androidApp:assembleDebug` and the full iOS suite. Counts unchanged from Task 3; this task adds no tests.

- [ ] **Step 5: Commit**

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
git add iosApp/Sources/Home/AddMatchSheet.swift iosApp/iosApp.xcodeproj/project.pbxproj \
        androidApp/src/main/java/com/badmintontracker/android/home/AddMatchSheet.kt
git commit -m "feat: the add-a-match sheet on both clients

One button on Home where the list had a three item menu. All three
actions survive behind it unchanged, so nothing a coach could do before
is gone.

The row glyphs use accentDark rather than accent: an icon on a surface is
text-like usage and accent does not clear 4.5:1 on the light background."
```

---

## Task 5: iOS drawer

`ClipListView`'s body becomes drawer content; the drawer shell wraps it.

**Files:**
- Create: `iosApp/Sources/Home/MatchesDrawer.swift`
- Create: `iosApp/Sources/Components/VersionLabel.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/Sources/SignIn/SignInView.swift:95-99` (use the shared `versionLabel`)

**Interfaces:**
- Consumes: `DrawerDragMath` (Task 3).
- Produces: `struct MatchesList: View` (the renamed `ClipListView`), and `struct MatchesDrawer<Content: View>: View` taking `isOpen: Binding<Bool>`, `onLabels: () -> Void`, `onSignOut: () -> Void`, `@ViewBuilder content: () -> Content`. `func versionLabel() -> String` in `VersionLabel.swift`. Task 7 consumes both.

- [ ] **Step 1: Extract the shared version label**

`versionLabel` is currently duplicated verbatim in `ClipListView.swift:601-605` and `SignInView.swift:95-99`. Create `iosApp/Sources/Components/VersionLabel.swift`:

```swift
import Foundation

/// The app version as shown to a coach, from the bundle.
///
/// Was duplicated in the sign in screen and the match list. It gains a third
/// caller with the drawer footer, which is one too many copies of four lines.
func versionLabel() -> String {
    let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    return "Version \(v) (\(b))"
}
```

Delete both private copies and point their call sites at this.

- [ ] **Step 2: Turn ClipListView's body into drawer content**

Rename `ClipListView` to `MatchesList` **in place in the same file**, and:

The name matters: on Android `MatchesDrawerContent` is the wrapper that adds the
header and footer, because `ModalNavigationDrawer` supplies the panel itself. On
iOS the panel is hand built, so `MatchesDrawer` is the shell and `MatchesList` is
just the list inside it. Using one name for both roles would make the two
platforms look parallel where they are not.

- Delete every `.navigationDestination(...)` and the `createFlowTarget` state. They move to `HomeView` in Task 7, verbatim. **Copy `CreateFlowDestination` and its full doc comment into `HomeView.swift` before deleting it here.** That comment records a confirmed on-device failure where a pop on one binding racing a push on another left a destination blank for a full thirty second wait; the single binding shape is the fix and must survive the move intact.
- Delete `.navigationTitle`, `.navigationBarTitleDisplayMode` and the `.toolbar` block. Home owns the bar now.
- Replace them with closure parameters the drawer's host supplies: `onMatchTap: (MatchRoute) -> Void`, `onCourtMarking: (CourtMarkingRoute) -> Void`, `onLocalPlayer: (LocalPlayerRoute) -> Void`, `onNewMatchCreated: (String) -> Void`.

Keep everything else exactly as it is: the sections, the swipe actions, the confirmation dialogs, the thumbnails, the progress subscriptions, `resultEntry`'s auto alert. This is a move, not a rewrite.

- [ ] **Step 3: Build the drawer shell**

```swift
import SwiftUI

/// The matches drawer: the list that used to be the app's front door, now
/// behind Home.
///
/// A hand built overlay because SwiftUI has no drawer primitive. Arithmetic
/// lives in DrawerDragMath; this is the presentation.
///
/// One deliberate limitation: a close-drag is accepted on the scrim and on this
/// panel's own header, but NOT from inside the list body. The rows carry
/// `.swipeActions`, and a horizontal drag there is ambiguous between revealing
/// a row's Delete and dismissing the whole drawer. Losing a gesture is better
/// than a list where swiping a row sometimes closes the screen.
struct MatchesDrawer<Content: View>: View {
    @Binding var isOpen: Bool
    let onLabels: () -> Void
    let onSignOut: () -> Void
    @ViewBuilder let content: () -> Content

    @State private var dragTranslation: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let width = DrawerDragMath.width(forScreenWidth: geo.size.width)
            let offset = DrawerDragMath.offset(
                translation: dragTranslation, width: width, isOpen: isOpen
            )
            let scrim = DrawerDragMath.scrimOpacity(offset: offset, width: width)

            ZStack(alignment: .leading) {
                Color.black.opacity(0.55 * scrim)
                    .ignoresSafeArea()
                    .allowsHitTesting(scrim > 0.05)
                    .onTapGesture { withAnimation(.snappy(duration: 0.24)) { isOpen = false } }

                panel(width: width)
                    .offset(x: offset)
            }
            .animation(dragTranslation == 0 ? .snappy(duration: 0.24) : nil, value: isOpen)
        }
    }

    private func panel(width: CGFloat) -> some View {
        VStack(spacing: 0) {
            header
            content()
            footer
        }
        .frame(width: width)
        .background(Shuttl.bgInput)
        .overlay(alignment: .trailing) { Rectangle().fill(Shuttl.border).frame(width: 1) }
        .ignoresSafeArea(edges: .bottom)
    }

    private var header: some View {
        HStack {
            Text("Matches")
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)
            Spacer()
            Button { withAnimation(.snappy(duration: 0.24)) { isOpen = false } } label: {
                Image(systemName: "xmark")
                    .foregroundStyle(Shuttl.textSecondary)
            }
            .accessibilityLabel("Close matches")
        }
        .padding(.horizontal, 24)
        .padding(.top, 26)
        .padding(.bottom, 16)
        .contentShape(Rectangle())
        .gesture(closeDrag)
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider().overlay(Shuttl.border)
            Button("Labels", action: onLabels)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.text)
                .padding(.horizontal, 24).padding(.vertical, 14)
            Button("Sign out", action: onSignOut)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.text)
                .padding(.horizontal, 24).padding(.vertical, 14)
            Text(versionLabel())
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
                .padding(.horizontal, 24).padding(.bottom, 20)
        }
    }

    private var closeDrag: some Gesture {
        DragGesture()
            .onChanged { dragTranslation = min(0, $0.translation.width) }
            .onEnded { value in
                let shouldClose = value.translation.width < -DrawerDragMath.openThreshold
                    || value.velocity.width < -DrawerDragMath.velocityThreshold
                dragTranslation = 0
                withAnimation(.snappy(duration: 0.24)) { isOpen = !shouldClose }
            }
    }
}
```

Note the footer uses `Shuttl.textTertiary` for the version string, not `textMuted` - `textMuted` is display-sizes-only and fails contrast on raised surfaces.

- [ ] **Step 4: Build and run the full suite**

Counts unchanged; this task adds no tests. A compile error in `ClipListView` is expected until Task 7 supplies the new closure parameters - if you cannot get a green build without `HomeView`, say so and merge Tasks 5 and 7 rather than committing a broken tree.

- [ ] **Step 5: Commit**

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
git add iosApp/Sources/Home/MatchesDrawer.swift iosApp/Sources/Components/VersionLabel.swift \
        iosApp/Sources/ClipList/ClipListView.swift iosApp/Sources/SignIn/SignInView.swift \
        iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): the matches drawer

The list that was the app's front door becomes a panel behind Home. A
hand built overlay, because SwiftUI has no drawer primitive.

A close-drag works on the scrim and on the drawer's own header but not
inside the list body: those rows carry swipeActions, and a horizontal
drag there cannot be told apart from revealing a row's Delete. Losing one
gesture beats a list where swiping a row sometimes closes the screen.

versionLabel was duplicated in two files and was about to gain a third
caller, so it moves to Components."
```

---

## Task 6: Android drawer

The Android mirror. Simpler, because `ModalNavigationDrawer` exists.

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/MatchesDrawer.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `@Composable fun MatchesDrawerContent(onLabels: () -> Unit, onSignOut: () -> Unit, content: @Composable () -> Unit)`. Task 8 wraps it in `ModalNavigationDrawer`.

- [ ] **Step 1: Strip ClipListScreen's chrome**

Remove its `Scaffold`, `TopAppBar` and the `ThemeToggleButton` at `ClipListScreen.kt:189`. Home owns the bar. Keep every list section, row, dialog and callback exactly as it is.

- [ ] **Step 2: Write the drawer content composable**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.ui.theme.ShuttlTheme

/**
 * The matches drawer's contents: the list that used to be the app's front door,
 * plus an account footer.
 *
 * Mirrors iosApp's MatchesDrawer.swift. The shell differs by platform - Compose
 * has ModalNavigationDrawer, SwiftUI has nothing and needs a hand built overlay
 * - so this is only the contents.
 */
@Composable
fun MatchesDrawerContent(
    onLabels: () -> Unit,
    onSignOut: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Text(
            text = "Matches",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 16.dp),
        )
        Box(Modifier.weight(1f)) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FooterRow("Labels", onLabels)
        FooterRow("Sign out", onSignOut)
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun FooterRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
```

- [ ] **Step 3: Build**

`./gradlew :androidApp:assembleDebug` and the full suite, 186 expected. `ClipListScreen`'s callers break until Task 8; if you cannot reach a green build, merge Tasks 6 and 8.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/home/MatchesDrawer.kt \
        androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt
git commit -m "feat(android): the matches drawer's contents

The list loses its Scaffold and TopAppBar, which Home now owns, and gains
an account footer holding Labels, Sign out and the version string.

Contents only: the shell differs by platform, since Compose has
ModalNavigationDrawer and SwiftUI needs a hand built overlay."
```

---

## Task 7: iOS Home and navigation ownership

The riskiest task in the phase. `HomeView` becomes the `NavigationStack` root and takes over every registration `ClipListView` owned.

**Files:**
- Create: `iosApp/Sources/Home/HomeView.swift`
- Create: `iosApp/Sources/Home/HeroTickerView.swift`
- Modify: `iosApp/Sources/RootView.swift:17`

**Interfaces:**
- Consumes: `HeroTicker` (T1), `DrawerDragMath` (T3), `AddMatchSheet` (T4), `MatchesDrawer` + `MatchesList` + `versionLabel()` (T5).
- Produces: `struct HomeView: View` taking `rally: RallyApp`, `analyze: AnalyzeCoordinator`.

- [ ] **Step 1: Move `CreateFlowDestination` verbatim**

Copy the `private enum CreateFlowDestination` declaration **and its entire doc comment** from `ClipListView.swift` into `HomeView.swift`. Do not reword the comment. It records a confirmed on-device failure and the single-binding shape is the fix.

- [ ] **Step 2: Build the hero view**

```swift
import SwiftUI

/// The rotating hero line.
///
/// Two stacked Texts with explicit spacing rather than one Text with a line
/// height: SwiftUI's .lineSpacing is additive and cannot tighten below Archivo's
/// natural 1.088 leading, and the mock's hero is 1.08. Stacking sidesteps
/// leading entirely and hits the figure exactly.
///
/// The second line is textMuted, which is the only place that token is allowed:
/// it clears the 3:1 large-text threshold at this size but not the 4.5:1 body
/// threshold, and it is only legible on `bg`.
struct HeroTickerView: View {
    @State private var index = 0
    @State private var leaving = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Paused while the drawer covers the screen: an animation nobody can see
    /// still costs a wake per tick.
    let isPaused: Bool

    private let dwell: Duration = .milliseconds(2600)

    var body: some View {
        VStack(alignment: .leading, spacing: 40 * 0.08) {
            Text(HeroTicker.leadLine)
                .foregroundStyle(Shuttl.textHeading)
            Text(HeroTicker.phrases[index])
                .foregroundStyle(Shuttl.textMuted)
                .opacity(leaving ? 0 : 1)
                .offset(y: leaving ? -12 : 0)
                .animation(.easeOut(duration: 0.38), value: leaving)
                .animation(.easeOut(duration: 0.38), value: index)
        }
        .shuttlType(ShuttlType.display)
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: isPaused) { await run() }
    }

    private func run() async {
        // Reduce Motion shows the first phrase and stops. A line of copy that
        // rewrites itself every 2.6 seconds is exactly the motion that setting
        // exists to switch off.
        guard !reduceMotion, !isPaused else { return }
        while !Task.isCancelled {
            try? await Task.sleep(for: dwell)
            if Task.isCancelled { return }
            leaving = true
            try? await Task.sleep(for: .milliseconds(380))
            if Task.isCancelled { return }
            index = HeroTicker.next(after: index)
            leaving = false
        }
    }
}
```

- [ ] **Step 3: Build HomeView**

It must contain, in this order: the `CreateFlowDestination` enum from Step 1; state for `drawerOpen`, `showAddSheet`, and every `@State` moved off `ClipListView` that the navigation needs; a `NavigationStack` whose root is the Home layout; and every `.navigationDestination` that `ClipListView` used to carry, unchanged.

Home's layout: top bar with a hamburger on the LEFT (where the drawer comes from and where the swipe starts), the "SHUTTL." wordmark, and an ellipsis menu on the right holding Labels, Sign out, the theme toggle and the version string. Then `LocalAnalysisBanner` if it applies. Then the hero at a fixed offset from the top, not centred, so the line does not shift as phrases of different lengths cycle. Then a spacer. Then the two 60pt pill buttons and the hint line, in a 24pt gutter.

The whole thing is wrapped in `MatchesDrawer`, and the left edge carries a `DragGesture` limited to `DrawerDragMath.edgeZoneWidth`.

Deliberate deviation from the mock, which puts the hamburger on the right: a hamburger opposite the edge its panel slides from reads as an unrelated control. Put this in a comment.

- [ ] **Step 4: Point RootView at Home**

`iosApp/Sources/RootView.swift:17`: `NavigationStack { ClipListView(...) }` becomes `HomeView(rally: rally, analyze: analyze)`. `HomeView` owns its own `NavigationStack`.

- [ ] **Step 5: Run the full suite**

Expected unchanged from Task 5. **`MatchModelTests` and the create-and-finish flow must pass untouched.** Any test that needs editing to pass is a signal that behaviour moved when it should not have - stop and report rather than editing the test.

- [ ] **Step 6: Exercise the create-and-finish flow on device**

This is the flow `CreateFlowDestination` exists to protect and no unit test covers it. By hand: Add new match -> New match -> create -> score a point -> finish -> confirm the match page appears and is NOT blank. Wait thirty seconds on it. If it comes up blank, the single-binding shape was broken in the move; report BLOCKED with what you see.

- [ ] **Step 7: Commit**

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
git add iosApp/Sources/Home iosApp/Sources/RootView.swift iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): Home becomes the front door and owns the navigation

The app opens on the hero and two buttons; the match list moves behind
the drawer. HomeView takes over every navigation destination ClipListView
registered, including CreateFlowDestination, whose doc comment moves with
it unchanged - it records a confirmed on-device failure where a pop on
one binding racing a push on another left a destination blank for a full
thirty second wait, and the single binding shape is the fix.

The hamburger sits on the left, not on the right as the mock draws it: a
hamburger opposite the edge its panel slides from reads as an unrelated
control."
```

---

## Task 8: Android Home and navigation ownership

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/HomeScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/HeroTickerView.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt:100,155-165,183-186`

**Interfaces:**
- Consumes: `HeroTicker` (T1), `AddMatchSheet` (T4), `MatchesDrawerContent` (T6).
- Produces: `Route.Home`, `@Composable fun HomeScreen(...)`.

- [ ] **Step 1: Add the route, remove the old one**

In `Route.kt`, add `@Serializable data object Home : Route` and delete `ClipList`. In `AuthGate.kt:100`, the authenticated start destination becomes `Route.Home`.

- [ ] **Step 2: Repoint the background-work indicator**

`AuthGate.kt:155-165`'s `LocalBackgroundWorkClick` navigates to `Route.ClipList` with `popUpTo` plus `launchSingleTop`. It becomes `Route.Home` with the same two guards, and must also open the drawer on arrival - the indicator's job is to show the run, which now lives in the drawer's list. Without both guards, tapping it repeatedly stacks Home screens.

- [ ] **Step 3: Move the analysis banner**

`LocalAnalysisBanner` currently sits above the clip list so an in-flight run is visible on return. Move it to `HomeScreen`, above the hero. It is more visible there, not less, and the reason it was placed above the list rather than inside it still holds.

- [ ] **Step 4: Write the hero composable**

Mirror `HeroTickerView.swift`: two stacked `Text`s, `ShuttlTypeExtras.display`, 2600ms dwell, 380ms transition, second line in `ShuttlTheme.extended.textMuted`.

Reduce Motion on Android is `Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f`. Read it once in a `remember`; when zero, show the first phrase and do not start the loop.

- [ ] **Step 5: Write HomeScreen**

`ModalNavigationDrawer` with `MatchesDrawerContent` as `drawerContent`, `gesturesEnabled = true`, and a `TopAppBar` whose navigation icon is the hamburger and whose actions hold the overflow menu.

`gesturesEnabled` is a bonus, not the route in: on gesture navigation the system owns the left edge and swipe-to-open loses to the back gesture. The hamburger is the primary affordance and the hint line reads "Your matches", not "Swipe right for your matches". Put this in a comment so nobody later "fixes" the copy to match iOS.

- [ ] **Step 6: Build and run everything**

`./gradlew :androidApp:testDebugUnitTest` (186) and `assembleDebug`. **`ClipListViewModelTest` must pass untouched.**

- [ ] **Step 7: Exercise the drawer under both navigation modes**

On the emulator, test edge-swipe-to-open under gesture navigation AND under three-button navigation, and the hamburger under both. Report what actually happens in each of the four combinations. This is the claim the copy difference rests on; confirm it rather than assuming it.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/home \
        androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git commit -m "feat(android): Home becomes the front door and owns the navigation

Route.Home replaces Route.ClipList as the authenticated start
destination, the list moves into a ModalNavigationDrawer, and the
background-work indicator navigates to Home and opens the drawer, keeping
both the popUpTo and launchSingleTop guards that stop it stacking screens.

The analysis banner moves to Home for the same reason it sat above the
list: an on-device run takes minutes and belongs where it is visible on
return.

Swipe-to-open is enabled but is a bonus, not the route in. On gesture
navigation the system owns the left edge, so the hamburger is primary and
the hint reads Your matches rather than promising a swipe the OS eats."
```

---

## Task 9: Deferred cleanup from phase 1

Small, self-contained items phase 1's final review triaged as carry-forward. Grouped into one task because each is a one-line change with no test of its own.

**Files:**
- Modify: `iosApp/Sources/Theme/ShuttlPalette.swift`, `androidApp/.../ui/theme/ShuttlPalette.kt` (add `onError`)
- Modify: `iosApp/Sources/Components/ErrorBanner.swift:9`, `androidApp/.../ui/components/ErrorBanner.kt`
- Modify: `androidApp/.../scoring/ScoringScreen.kt:398,428,446`, `iosApp/Sources/Scoring/ScoringView.swift:321,333,343`
- Modify: `androidApp/.../localvideo/court/CourtMarkingScreen.kt:413`
- Test: extend `ShuttlPaletteTests.swift` / `ShuttlPaletteTest.kt`, `ShuttlShapesTest.kt`

- [ ] **Step 1: Add an `onError` token**

`ErrorBanner` hardcodes white on the `error` fill, measured at 3.76:1 - below the 4.5:1 body threshold. Add `onError` to both palettes, pick a value that clears 4.5:1 on `0xEF4444` (white does not; a near-black does), and extend the existing contrast tests to assert it the same way `onAccent` is asserted.

- [ ] **Step 2: Close the shape-test hole**

`ShuttlShapes.extraSmall` could be reverted to `0.dp` today and both shape tests would still pass. Extend `ShuttlShapesTest.material_shapes_are_no_longer_square` to assert all five M3 slots, not two.

- [ ] **Step 3: Route the off-scale radii**

Six sites use a literal `6.dp` / `cornerRadius: 6`, which is not on the scale. Change them to `ShuttlRadius.extraSmall` (8) unless 8 visibly breaks the layout, in which case leave the site and report it.

- [ ] **Step 4: Convert Android's last system-font text**

`CourtMarkingScreen.kt:413` is the only Android text still rendering in the system font. Give it a `MaterialTheme.typography` style.

- [ ] **Step 5: Run everything and commit**

Both suites, both builds, counts reconciled.

---

## Task 10: Visual sweep

**Files:** whichever the sweep implicates.

- [ ] **Step 1: Capture the new screens**

`home` and `drawer`, both themes, both platforms, into `docs/screenshots/2026-09-03-design-system/after/` as `<platform>-home-<theme>.png` and `<platform>-drawer-<theme>.png`. These are new: they have no `before/` counterpart and are not part of the parity diff. Note that in your report.

- [ ] **Step 2: Re-capture every existing screen**

All 36, overwriting `after/`. Every screen's navigation now arrives through a different owner, so every screen is potentially affected.

- [ ] **Step 3: Review against phase 1's `after/` set**

The baseline is phase 1's committed `after/`, not `before/`: this phase's question is what phase 2 changed, and phase 1's visual result was already reviewed and accepted.

Check, per screen: the typeface is uniform (Task 2 should have made every iOS screen Archivo); nothing reflowed or truncated; the drawer opens and closes from every entry point; the buttons are pill.

- [ ] **Step 4: Run the DoD greps**

```bash
# Implicit-colour controls, the blind spot that let phase 1's misses through
grep -rn "borderedProminent\|\.tint(\|ButtonDefaults\|\.accentColor" iosApp/Sources androidApp/src/main
# Direct fonts outside the allowed list
grep -rn "\.font(" iosApp/Sources | grep -v "Theme/\|CourtMarkingView\|SchematicCourtGuide\|ScoringView"
# Hardcoded palette hex
grep -rn "0x22C55E\|0x16A34A\|0x3EE27C" iosApp/Sources androidApp/src/main | grep -v ShuttlPalette
```

Each must return nothing, or a line you can justify in the report.

- [ ] **Step 5: Fix in place, then commit**

A padding, a line limit, a frame height. **Do not redesign a screen.** Anything needing more goes in the report for phase 3.

---

## Definition of Done

- [ ] `:androidApp:testDebugUnitTest` passes; count reconciled from 182.
- [ ] iOS suite passes; count reconciled from 122 unit + 3 UI.
- [ ] `assembleDebug` passes.
- [ ] The create-and-finish flow works on an iOS device, verified by hand, with no blank destination after a thirty second wait.
- [ ] `CreateFlowDestination`'s doc comment survives the move to `HomeView` unchanged.
- [ ] `MatchModelTests`, `ClipListViewModelTest` and the scoring suites pass **untouched**.
- [ ] The three DoD greps in Task 10 Step 4 return nothing unjustified.
- [ ] Home and drawer screenshots exist for both platforms in both themes.
- [ ] Android drawer behaviour reported for all four combinations of gesture/three-button navigation and swipe/hamburger.
- [ ] No em dashes on any added line; no attribution trailers.

## Not In This Plan

Phase 3 (Analytics) gets its own plan against the same design document. The iOS heatmap and skeleton renderers are downstream of Stage 2 of `docs/plans/2026-08-31-on-device-analysis-pipeline-design.md` and belong to that design, not this one.
