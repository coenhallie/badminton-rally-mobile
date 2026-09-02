# Design System Implementation Plan (Phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swap both clients onto the new palette, Archivo typography and rounded shape scale from the badminton-design mock, changing no screen layout.

**Architecture:** Both clients already centralise design tokens (`ui/theme/*.kt` on Android, `Theme/ShuttlTheme.swift` on iOS), so the values change in one place per platform and every screen inherits them. Two structural changes go in first: raw colour values are lifted out into a palette holding plain integers, so contrast rules can be asserted by a pure unit test with no Compose or UIKit runtime; and an `onAccent` token replaces the hardcoded `.black` / `Color.Black` scattered across accent-filled surfaces.

**Tech Stack:** Kotlin / Jetpack Compose Material 3 (Android), Swift / SwiftUI (iOS), XcodeGen for the iOS project, Gradle, XCTest, kotlin.test with kotest assertions.

**Spec:** `docs/plans/2026-09-03-home-and-analytics-redesign-design.md`

## Global Constraints

- **Phase 1 changes token values only.** No screen layout moves. A file outside `ui/theme/` or `Sources/Theme/` is touched only to route a hardcoded colour or radius through a token, never to restructure it.
- **`accent` is a fill colour, never a text colour.** Accent-coloured text uses `accentDark`.
- **`textMuted` is for display sizes only** (24pt and above). Body-sized secondary text uses `textTertiary`.
- **Contrast floors:** every text token clears **4.5:1** against `bg`, `bgSecondary` and `bgTertiary`. `textMuted` clears **3.0:1** against `bg`. `onAccent` clears **4.5:1** against `accent`.
- **Never hand-edit `iosApp/Sources/Info.plist`.** XcodeGen generates it from `iosApp/project.yml`'s `info.properties`. Edit `project.yml` and regenerate.
- **Never hand-edit `CHANGELOG.md`.** It is generated.
- **No em dashes** in code comments, commit messages or docs. Use a plain dash.
- **Commit messages:** no `Co-Authored-By` trailer, no generated-with footer.
- **Archivo** is SIL OFL 1.1. `OFL.txt` ships alongside the font files on both platforms.

### Exact palette

Dark is the mock's. Light is derived to hold the same roles at accessible contrast.

| Token | Dark | Light |
| --- | --- | --- |
| `bg` | `0x0B0C0D` | `0xFFFFFF` |
| `bgSecondary` | `0x121415` | `0xF5F7F6` |
| `bgTertiary` | `0x161819` | `0xEDF0EE` |
| `bgInput` | `0x0E0F10` | `0xEDF0EE` |
| `border` | `0x1A1D1E` | `0xE3E6E4` |
| `borderSecondary` | `0x22262A` | `0xCED3D0` |
| `textHeading` | `0xF2F4F3` | `0x0B0C0D` |
| `text` | `0xF2F4F3` | `0x16191A` |
| `textSecondary` | `0x8D938F` | `0x545C58` |
| `textTertiary` | `0x7F8682` | `0x5F6763` |
| `textMuted` | `0x5D6462` | `0x878E8A` |
| `accent` | `0x3EE27C` | `0x16A34A` |
| `onAccent` | `0x06210F` | `0x04240F` |
| `accentDark` | `0x22C55E` | `0x15803D` |
| `sideHome` | `0x14532D` | `0x15803D` |
| `sideAway` | `0x1E3A8A` | `0x1D4ED8` |
| `error` | `0xEF4444` | `0xEF4444` |
| `warning` | `0xF59E0B` | `0xF59E0B` |
| `info` | `0x3B82F6` | `0x3B82F6` |

### Exact shape scale

| Name | Radius |
| --- | --- |
| `pill` | 999 |
| `extraLarge` | 28 |
| `large` | 20 |
| `medium` | 16 |
| `small` | 12 |
| `extraSmall` | 8 |

### Exact type scale (Archivo)

| Role | Size | Weight | Tracking (em) | Line height |
| --- | --- | --- | --- | --- |
| `display` | 40 | 500 | -0.035 | 1.08 |
| `headlineLarge` | 28 | 500 | -0.030 | 1.15 |
| `headlineMedium` | 22 | 500 | -0.020 | 1.20 |
| `statNumber` | 26 | 500 | -0.030 | 1.15 |
| `titleLarge` | 16 | 600 | -0.010 | 1.30 |
| `titleMedium` | 15 | 600 | -0.010 | 1.30 |
| `bodyLarge` | 16 | 400 | 0 | 1.45 |
| `bodyMedium` | 14 | 400 | 0 | 1.45 |
| `bodySmall` | 12 | 400 | 0 | 1.40 |
| `labelSmall` | 11 | 500 | +0.050 | 1.30 |

Tracking is expressed in em here. Android's `letterSpacing` takes sp, so multiply
by the size: -0.035em at 40sp is `(-1.4).sp`. iOS's `.kerning` takes points, same
arithmetic.

### Build and test commands

```bash
# Android unit tests
./gradlew :androidApp:testDebugUnitTest

# Android debug build
./gradlew :androidApp:assembleDebug

# iOS: regenerate the Xcode project after any project.yml change
xcodegen generate --spec iosApp/project.yml --project iosApp

# iOS tests. The DEVELOPER_DIR export and the xattr sweep are required on this
# machine: xcode-select points at the Command Line Tools, and sandbox-created
# files carry com.apple.provenance xattrs that break codesign.
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

## File Structure

**Android**

| File | Responsibility |
| --- | --- |
| `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlPalette.kt` | NEW. Raw `Long` ARGB constants, light and dark. No Compose types, so unit tests read it directly. |
| `.../ui/theme/ShuttlColors.kt` | MODIFY. Builds `ColorScheme` and `ShuttlExtendedColors` from `ShuttlPalette`. Gains `onAccent`, `textMuted`. |
| `.../ui/theme/ShuttlShapes.kt` | MODIFY. Rounded scale replaces the all-0dp scale. |
| `.../ui/theme/ShuttlRadius.kt` | NEW. The `pill` constant, which M3's `Shapes` has no slot for. |
| `.../ui/theme/ShuttlType.kt` | MODIFY. Archivo family and the new scale. |
| `androidApp/src/main/res/font/` | NEW. Four static Archivo TTFs plus `OFL.txt`. |
| `androidApp/src/test/java/.../ui/theme/ShuttlPaletteTest.kt` | NEW. Contrast rules. |
| `androidApp/src/test/java/.../ui/theme/ShuttlShapesTest.kt` | NEW. Shape scale values. |
| `androidApp/src/test/java/.../ui/theme/ArchivoAssetTest.kt` | NEW. Font files present and named as `ShuttlType` expects. |

**iOS**

| File | Responsibility |
| --- | --- |
| `iosApp/Sources/Theme/ShuttlPalette.swift` | NEW. Raw `UInt32` pairs, light and dark. |
| `iosApp/Sources/Theme/ShuttlTheme.swift` | MODIFY. `Color` accessors derived from `ShuttlPalette`. Gains `onAccent`, `textMuted`. |
| `iosApp/Sources/Theme/ShuttlRadius.swift` | NEW. Shape scale. |
| `iosApp/Sources/Theme/ShuttlType.swift` | NEW. Archivo font styles mirroring `ShuttlType.kt`. |
| `iosApp/Resources/Fonts/` | NEW. Four static Archivo TTFs plus `OFL.txt`. |
| `iosApp/project.yml` | MODIFY. `Resources` added to sources, `UIAppFonts` added to `info.properties`. |
| `iosApp/Tests/ShuttlPaletteTests.swift` | NEW. Contrast rules. |
| `iosApp/Tests/ShuttlRadiusTests.swift` | NEW. Shape scale values. |
| `iosApp/Tests/ArchivoFontTests.swift` | NEW. Every weight resolves by PostScript name at runtime. |

**Call sites routed through tokens** (no layout change): `PrimaryButtonStyle.swift`,
`PointsFacet.swift`, `MatchView.swift`, `ClipListView.swift`, `LocalVideoSection.swift`,
`AddAnnotationSheet.swift`, `PlaybackControlBar.swift`, `CourtMarkingView.swift`,
`SchematicCourtGuide.swift` on iOS; `ShuttlColors.kt`'s `onPrimary` on Android.

---

## Task 1: iOS raw palette and contrast rules

The palette moves out of the `Color` accessors into plain integers so a unit test
can assert the contrast rules without a rendering context. The new values land in
the same commit, because a palette split that keeps the old values would be a
refactor with no deliverable.

**Files:**
- Create: `iosApp/Sources/Theme/ShuttlPalette.swift`
- Modify: `iosApp/Sources/Theme/ShuttlTheme.swift`
- Test: `iosApp/Tests/ShuttlPaletteTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum ShuttlPalette` with `static let <token>: (light: UInt32, dark: UInt32)` for every token in the Global Constants palette table. `Shuttl.<token>` keeps returning `Color` with the same names as today, plus `Shuttl.onAccent` and `Shuttl.textMuted`.

- [ ] **Step 1: Write the failing test**

Create `iosApp/Tests/ShuttlPaletteTests.swift`:

```swift
import XCTest
@testable import iosApp

/// The palette's accessibility rules, asserted rather than trusted. The mock is
/// dark-only, so the light values are a derivation and nothing but a test keeps
/// that derivation honest as the palette is edited.
final class ShuttlPaletteTests: XCTestCase {

    // MARK: WCAG 2.1 relative luminance and contrast

    private func channel(_ c: UInt32) -> Double {
        let v = Double(c) / 255
        return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
    }

    private func luminance(_ rgb: UInt32) -> Double {
        0.2126 * channel((rgb >> 16) & 0xFF)
            + 0.7152 * channel((rgb >> 8) & 0xFF)
            + 0.0722 * channel(rgb & 0xFF)
    }

    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    func testContrastHelperMatchesKnownValues() {
        // Black on white is the WCAG maximum.
        XCTAssertEqual(contrast(0x000000, 0xFFFFFF), 21.0, accuracy: 0.01)
        XCTAssertEqual(contrast(0xFFFFFF, 0xFFFFFF), 1.0, accuracy: 0.01)
    }

    // MARK: Rules

    /// Runs `body` once per theme, handing it a picker that pulls the right half
    /// out of a token's pair. Both themes get every rule; a light palette that
    /// only ever gets eyeballed in dark is how the derivation goes wrong.
    private func eachTheme(
        _ body: (_ name: String, _ pick: (ShuttlPalette.Pair) -> UInt32) -> Void
    ) {
        body("light") { $0.light }
        body("dark") { $0.dark }
    }

    func testBodyTextClearsAAOnEverySurface() {
        eachTheme { theme, pick in
            let backgrounds = [
                ("bg", pick(ShuttlPalette.bg)),
                ("bgSecondary", pick(ShuttlPalette.bgSecondary)),
                ("bgTertiary", pick(ShuttlPalette.bgTertiary)),
            ]
            let foregrounds = [
                ("text", pick(ShuttlPalette.text)),
                ("textHeading", pick(ShuttlPalette.textHeading)),
                ("textSecondary", pick(ShuttlPalette.textSecondary)),
                ("textTertiary", pick(ShuttlPalette.textTertiary)),
            ]
            for (fgName, fg) in foregrounds {
                for (bgName, bg) in backgrounds {
                    XCTAssertGreaterThanOrEqual(
                        contrast(fg, bg), 4.5,
                        "\(theme) \(fgName) on \(bgName) is below the 4.5:1 body threshold"
                    )
                }
            }
        }
    }

    func testMutedTextClearsLargeTextThresholdOnly() {
        eachTheme { theme, pick in
            let ratio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bg))
            // Muted is the hero line and nothing else. It is allowed to sit below
            // the body threshold, which is precisely why it is a separate token
            // from textTertiary rather than the same grey used at two sizes.
            XCTAssertGreaterThanOrEqual(ratio, 3.0, "\(theme) textMuted fails large-text contrast")
            XCTAssertLessThan(ratio, 4.5, "\(theme) textMuted now clears the body threshold; fold it into textTertiary")
        }
    }

    func testAccentCarriesOnAccentText() {
        eachTheme { theme, pick in
            XCTAssertGreaterThanOrEqual(
                contrast(pick(ShuttlPalette.onAccent), pick(ShuttlPalette.accent)), 4.5,
                "\(theme) onAccent is unreadable on the accent fill"
            )
        }
    }

    func testAccentIsAFillColourNotATextColour() {
        // The rule this encodes: accent-coloured TEXT uses accentDark. If someone
        // brightens the light accent until it passes as text, this fails and they
        // have to decide deliberately rather than by accident.
        XCTAssertLessThan(
            contrast(ShuttlPalette.accent.light, ShuttlPalette.bg.light), 4.5,
            "light accent now passes as text; the fill-only rule needs revisiting"
        )
        eachTheme { theme, pick in
            XCTAssertGreaterThanOrEqual(
                contrast(pick(ShuttlPalette.accentDark), pick(ShuttlPalette.bg)), 4.5,
                "\(theme) accentDark must be readable as text"
            )
        }
    }

    func testDarkPaletteMatchesTheMock() {
        // The dark values are lifted from Rally Home.dc.html. Pinning them stops
        // a well-meaning tweak from silently drifting away from the design.
        XCTAssertEqual(ShuttlPalette.bg.dark, 0x0B0C0D)
        XCTAssertEqual(ShuttlPalette.accent.dark, 0x3EE27C)
        XCTAssertEqual(ShuttlPalette.onAccent.dark, 0x06210F)
        XCTAssertEqual(ShuttlPalette.textMuted.dark, 0x5D6462)
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/ShuttlPaletteTests
```

Expected: compile failure, `cannot find 'ShuttlPalette' in scope`.

- [ ] **Step 3: Create the palette**

Create `iosApp/Sources/Theme/ShuttlPalette.swift`:

```swift
import Foundation

/// Every design token as a raw ARGB-less RGB integer, light and dark.
///
/// Separate from `Shuttl`'s `Color` accessors on purpose: a `Color` cannot be
/// taken apart again on a device-independent basis, so contrast rules could not
/// be asserted from a unit test if these values only existed inside one. Mirrors
/// androidApp's ShuttlPalette.kt, which exists for the same reason.
///
/// Dark is lifted from the badminton-design mock. Light is a derivation, not a
/// recolour: the mock's `#3EE27C` is 1.6:1 on white and unusable there.
enum ShuttlPalette {
    typealias Pair = (light: UInt32, dark: UInt32)

    static let bg:              Pair = (0xFFFFFF, 0x0B0C0D)
    static let bgSecondary:     Pair = (0xF5F7F6, 0x121415)
    static let bgTertiary:      Pair = (0xEDF0EE, 0x161819)
    static let bgInput:         Pair = (0xEDF0EE, 0x0E0F10)
    static let border:          Pair = (0xE3E6E4, 0x1A1D1E)
    static let borderSecondary: Pair = (0xCED3D0, 0x22262A)
    static let textHeading:     Pair = (0x0B0C0D, 0xF2F4F3)
    static let text:            Pair = (0x16191A, 0xF2F4F3)
    static let textSecondary:   Pair = (0x545C58, 0x8D938F)
    static let textTertiary:    Pair = (0x5F6763, 0x7F8682)
    /// The hero line, and nothing else. Below the 4.5:1 body threshold by
    /// design; see ShuttlPaletteTests.testMutedTextClearsLargeTextThresholdOnly.
    static let textMuted:       Pair = (0x878E8A, 0x5D6462)
    /// A fill colour. Accent-coloured TEXT uses `accentDark`.
    static let accent:          Pair = (0x16A34A, 0x3EE27C)
    /// What sits on top of an `accent` fill, in both themes. The mock's own
    /// pattern: near-black on green, which is the pairing that survives the
    /// jump from a dark-only design into a light theme.
    static let onAccent:        Pair = (0x04240F, 0x06210F)
    static let accentDark:      Pair = (0x15803D, 0x22C55E)
    static let sideHome:        Pair = (0x15803D, 0x14532D)
    static let sideAway:        Pair = (0x1D4ED8, 0x1E3A8A)
    static let error:           Pair = (0xEF4444, 0xEF4444)
    static let warning:         Pair = (0xF59E0B, 0xF59E0B)
    static let info:            Pair = (0x3B82F6, 0x3B82F6)
}
```

- [ ] **Step 4: Rewrite the Shuttl accessors on top of it**

Replace the token block in `iosApp/Sources/Theme/ShuttlTheme.swift`. Keep the
`UIColor`/`Color` convenience initialisers at the top of the file exactly as they
are; only the `enum Shuttl` token list changes. Replace the stale
"Sharp corners everywhere" doc comment, which Task 3 makes false.

```swift
/// The design tokens, resolved for the current interface style.
///
/// Values live in `ShuttlPalette`; this is the SwiftUI-facing view of them.
/// Ported from androidApp's ui/theme/ShuttlColors.kt, which mirrors this file
/// token for token. Radii live in `ShuttlRadius`, type in `ShuttlType`.
enum Shuttl {
    private static func token(_ pair: ShuttlPalette.Pair) -> Color {
        Color(light: pair.light, dark: pair.dark)
    }

    static let bg              = token(ShuttlPalette.bg)
    static let bgSecondary     = token(ShuttlPalette.bgSecondary)
    static let bgTertiary      = token(ShuttlPalette.bgTertiary)
    static let bgInput         = token(ShuttlPalette.bgInput)
    static let border          = token(ShuttlPalette.border)
    static let borderSecondary = token(ShuttlPalette.borderSecondary)
    static let textHeading     = token(ShuttlPalette.textHeading)
    static let text            = token(ShuttlPalette.text)
    static let textSecondary   = token(ShuttlPalette.textSecondary)
    static let textTertiary    = token(ShuttlPalette.textTertiary)
    static let textMuted       = token(ShuttlPalette.textMuted)
    static let accent          = token(ShuttlPalette.accent)
    static let onAccent        = token(ShuttlPalette.onAccent)
    static let accentDark      = token(ShuttlPalette.accentDark)
    static let error           = token(ShuttlPalette.error)
    static let warning         = token(ShuttlPalette.warning)
    static let info            = token(ShuttlPalette.info)

    // The two sides of the scoreboard. Deliberately not the accent green and the
    // info blue: those are interface colours sized for a chip, and these are
    // full-bleed halves carrying white numerals, so they are picked for contrast
    // against white first and family resemblance second. Deeper in dark, where a
    // lit-up half at arm's length in a dim hall is the thing to avoid. Mirrors
    // androidApp's ShuttlExtendedColors.sideHome / sideAway.
    static let sideHome        = token(ShuttlPalette.sideHome)
    static let sideAway        = token(ShuttlPalette.sideAway)

    /// Tiny uppercase tracked label - matches Android labelSmall (11sp, medium, 0.05em).
    static func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .medium))
            .kerning(0.55)
            .foregroundStyle(Shuttl.textSecondary)
    }
}
```

`sectionLabel` still uses the system font here. Task 6 moves it onto Archivo;
leaving it alone in this task keeps the palette commit to one concern.

- [ ] **Step 5: Run the test and watch it pass**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/ShuttlPaletteTests
```

Expected: all six tests pass.

- [ ] **Step 6: Run the whole iOS suite**

Same command without `-only-testing`. Expected: PASS. Nothing here changes
behaviour, so any failure is a real regression and must be fixed before the
commit, not after it.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/Theme/ShuttlPalette.swift \
        iosApp/Sources/Theme/ShuttlTheme.swift \
        iosApp/Tests/ShuttlPaletteTests.swift
git commit -m "feat(ios): new palette, with its accessibility rules under test

Dark values come from the badminton-design mock. Light is a derivation
rather than a recolour, because the mock's accent is 1.6:1 on white.

Raw values move into ShuttlPalette so the contrast rules can be asserted
from a unit test: that every text token clears 4.5:1 on all three
surfaces, that textMuted is allowed below it because it is the hero line
only, and that the accent stays a fill colour with accentDark carrying
accent-coloured text."
```

---

## Task 2: Android raw palette and contrast rules

The Android mirror of Task 1. Same values, same rules, same reason for splitting
the raw integers out: the unit test source set has no Robolectric, so a test that
needed a Compose `Color` at runtime could not run in CI.

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlPalette.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlColors.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlPaletteTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object ShuttlPalette` exposing `data class Pair(val light: Long, val dark: Long)` per token, with the same token names as `ShuttlPalette.swift`. `ShuttlExtendedColors` gains `onAccent: Color` and `textMuted: Color`.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlPaletteTest.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.pow
import kotlin.test.Test

/**
 * The palette's accessibility rules, asserted rather than trusted.
 *
 * The mirror of iosApp's ShuttlPaletteTests.swift. Reads raw Longs rather than
 * Compose Colors on purpose: this source set has no Robolectric, so anything
 * needing an Android runtime could not run here.
 */
class ShuttlPaletteTest {

    private fun channel(c: Long): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(rgb: Long): Double =
        0.2126 * channel((rgb shr 16) and 0xFF) +
        0.7152 * channel((rgb shr 8) and 0xFF) +
        0.0722 * channel(rgb and 0xFF)

    private fun contrast(a: Long, b: Long): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val themes = listOf<Pair<String, (ShuttlPalette.Tone) -> Long>>(
        "light" to { it.light },
        "dark" to { it.dark },
    )

    @Test
    fun contrast_helper_matches_known_values() {
        contrast(0x000000, 0xFFFFFF).shouldBeGreaterThanOrEqual(20.99)
        contrast(0xFFFFFF, 0xFFFFFF).shouldBeLessThan(1.01)
    }

    @Test
    fun body_text_clears_aa_on_every_surface() {
        for ((theme, pick) in themes) {
            val backgrounds = listOf(
                "bg" to pick(ShuttlPalette.bg),
                "bgSecondary" to pick(ShuttlPalette.bgSecondary),
                "bgTertiary" to pick(ShuttlPalette.bgTertiary),
            )
            val foregrounds = listOf(
                "text" to pick(ShuttlPalette.text),
                "textHeading" to pick(ShuttlPalette.textHeading),
                "textSecondary" to pick(ShuttlPalette.textSecondary),
                "textTertiary" to pick(ShuttlPalette.textTertiary),
            )
            for ((fgName, fg) in foregrounds) {
                for ((bgName, bg) in backgrounds) {
                    withClue("$theme $fgName on $bgName") {
                        contrast(fg, bg).shouldBeGreaterThanOrEqual(4.5)
                    }
                }
            }
        }
    }

    @Test
    fun muted_text_clears_large_text_threshold_only() {
        for ((theme, pick) in themes) {
            val ratio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bg))
            withClue("$theme textMuted") {
                ratio.shouldBeGreaterThanOrEqual(3.0)
                // Below the body threshold by design. It is the hero line and
                // nothing else, which is why it is a separate token rather than
                // textTertiary used at two sizes.
                ratio.shouldBeLessThan(4.5)
            }
        }
    }

    @Test
    fun accent_carries_on_accent_text() {
        for ((theme, pick) in themes) {
            withClue("$theme onAccent on accent") {
                contrast(pick(ShuttlPalette.onAccent), pick(ShuttlPalette.accent))
                    .shouldBeGreaterThanOrEqual(4.5)
            }
        }
    }

    @Test
    fun accent_is_a_fill_colour_not_a_text_colour() {
        contrast(ShuttlPalette.accent.light, ShuttlPalette.bg.light).shouldBeLessThan(4.5)
        for ((theme, pick) in themes) {
            withClue("$theme accentDark as text") {
                contrast(pick(ShuttlPalette.accentDark), pick(ShuttlPalette.bg))
                    .shouldBeGreaterThanOrEqual(4.5)
            }
        }
    }

    @Test
    fun dark_palette_matches_the_mock() {
        ShuttlPalette.bg.dark shouldBe 0x0B0C0DL
        ShuttlPalette.accent.dark shouldBe 0x3EE27CL
        ShuttlPalette.onAccent.dark shouldBe 0x06210FL
        ShuttlPalette.textMuted.dark shouldBe 0x5D6462L
    }
}
```

Add `import io.kotest.assertions.withClue` at the top; it is part of
`libs.kotest.assertions`, already on the test classpath.

- [ ] **Step 2: Run the test and watch it fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ShuttlPaletteTest*'
```

Expected: compile failure, `Unresolved reference: ShuttlPalette`.

- [ ] **Step 3: Create the palette**

Create `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlPalette.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

/**
 * Every design token as a raw RGB integer, light and dark.
 *
 * Separate from the Compose ColorScheme on purpose: the unit test source set has
 * no Robolectric, so contrast rules could only be asserted against plain values.
 * Mirrors iosApp's ShuttlPalette.swift, which exists for the same reason and
 * carries the same token names.
 *
 * Dark is lifted from the badminton-design mock. Light is a derivation, not a
 * recolour: the mock's 0x3EE27C is 1.6:1 on white and unusable there.
 */
internal object ShuttlPalette {
    data class Tone(val light: Long, val dark: Long)

    val bg              = Tone(0xFFFFFF, 0x0B0C0D)
    val bgSecondary     = Tone(0xF5F7F6, 0x121415)
    val bgTertiary      = Tone(0xEDF0EE, 0x161819)
    val bgInput         = Tone(0xEDF0EE, 0x0E0F10)
    val border          = Tone(0xE3E6E4, 0x1A1D1E)
    val borderSecondary = Tone(0xCED3D0, 0x22262A)
    val textHeading     = Tone(0x0B0C0D, 0xF2F4F3)
    val text            = Tone(0x16191A, 0xF2F4F3)
    val textSecondary   = Tone(0x545C58, 0x8D938F)
    val textTertiary    = Tone(0x5F6763, 0x7F8682)

    /**
     * The hero line, and nothing else. Below the 4.5:1 body threshold by design;
     * see ShuttlPaletteTest.muted_text_clears_large_text_threshold_only.
     */
    val textMuted       = Tone(0x878E8A, 0x5D6462)

    /** A fill colour. Accent-coloured TEXT uses [accentDark]. */
    val accent          = Tone(0x16A34A, 0x3EE27C)

    /**
     * What sits on top of an [accent] fill, in both themes. The mock's own
     * pattern: near-black on green, which is the pairing that survives the jump
     * from a dark-only design into a light theme.
     */
    val onAccent        = Tone(0x04240F, 0x06210F)
    val accentDark      = Tone(0x15803D, 0x22C55E)
    val sideHome        = Tone(0x15803D, 0x14532D)
    val sideAway        = Tone(0x1D4ED8, 0x1E3A8A)
    val error           = Tone(0xEF4444, 0xEF4444)
    val warning         = Tone(0xF59E0B, 0xF59E0B)
    val info            = Tone(0x3B82F6, 0x3B82F6)
}
```

- [ ] **Step 4: Rewrite ShuttlColors.kt on top of it**

Replace the whole body of `ShuttlColors.kt` below the imports. The private
`Color` vals at the top of the file go away; `ShuttlExtendedColors` gains
`onAccent` and `textMuted`; `onPrimary` stops being `Color.Black` and becomes the
`onAccent` token.

```kotlin
private fun Long.toColor(): Color = Color(0xFF000000L or this)

private val ShuttlPalette.Tone.lightColor: Color get() = light.toColor()
private val ShuttlPalette.Tone.darkColor: Color get() = dark.toColor()

internal val ShuttlLightColorScheme = lightColorScheme(
    primary          = ShuttlPalette.accent.lightColor,
    onPrimary        = ShuttlPalette.onAccent.lightColor,
    background       = ShuttlPalette.bg.lightColor,
    onBackground     = ShuttlPalette.textHeading.lightColor,
    surface          = ShuttlPalette.bg.lightColor,
    onSurface        = ShuttlPalette.text.lightColor,
    surfaceVariant   = ShuttlPalette.bgSecondary.lightColor,
    onSurfaceVariant = ShuttlPalette.textSecondary.lightColor,
    outline          = ShuttlPalette.borderSecondary.lightColor,
    outlineVariant   = ShuttlPalette.border.lightColor,
    error            = ShuttlPalette.error.lightColor,
    onError          = Color.White,
)

internal val ShuttlDarkColorScheme = darkColorScheme(
    primary          = ShuttlPalette.accent.darkColor,
    onPrimary        = ShuttlPalette.onAccent.darkColor,
    background       = ShuttlPalette.bg.darkColor,
    onBackground     = ShuttlPalette.textHeading.darkColor,
    surface          = ShuttlPalette.bg.darkColor,
    onSurface        = ShuttlPalette.text.darkColor,
    surfaceVariant   = ShuttlPalette.bgSecondary.darkColor,
    onSurfaceVariant = ShuttlPalette.textSecondary.darkColor,
    outline          = ShuttlPalette.borderSecondary.darkColor,
    outlineVariant   = ShuttlPalette.border.darkColor,
    error            = ShuttlPalette.error.darkColor,
    onError          = Color.White,
)

/** Extended palette beyond M3's ColorScheme. */
@Immutable
data class ShuttlExtendedColors(
    val accentDark:   Color,
    val onAccent:     Color,
    val bgInput:      Color,
    val bgTertiary:   Color,
    val textTertiary: Color,
    /** Display sizes only. See ShuttlPalette.textMuted. */
    val textMuted:    Color,
    val warning:      Color,
    val info:         Color,
    /** The home half of the scoreboard. Identifies the side, never the end. */
    val sideHome:     Color,
    val sideAway:     Color,
)

internal val ShuttlLightExtended = ShuttlExtendedColors(
    accentDark   = ShuttlPalette.accentDark.lightColor,
    onAccent     = ShuttlPalette.onAccent.lightColor,
    bgInput      = ShuttlPalette.bgInput.lightColor,
    bgTertiary   = ShuttlPalette.bgTertiary.lightColor,
    textTertiary = ShuttlPalette.textTertiary.lightColor,
    textMuted    = ShuttlPalette.textMuted.lightColor,
    warning      = ShuttlPalette.warning.lightColor,
    info         = ShuttlPalette.info.lightColor,
    sideHome     = ShuttlPalette.sideHome.lightColor,
    sideAway     = ShuttlPalette.sideAway.lightColor,
)

internal val ShuttlDarkExtended = ShuttlExtendedColors(
    accentDark   = ShuttlPalette.accentDark.darkColor,
    onAccent     = ShuttlPalette.onAccent.darkColor,
    bgInput      = ShuttlPalette.bgInput.darkColor,
    bgTertiary   = ShuttlPalette.bgTertiary.darkColor,
    textTertiary = ShuttlPalette.textTertiary.darkColor,
    textMuted    = ShuttlPalette.textMuted.darkColor,
    warning      = ShuttlPalette.warning.darkColor,
    info         = ShuttlPalette.info.darkColor,
    sideHome     = ShuttlPalette.sideHome.darkColor,
    sideAway     = ShuttlPalette.sideAway.darkColor,
)

val LocalShuttlColors = staticCompositionLocalOf { ShuttlLightExtended }

object ShuttlTheme {
    val extended: ShuttlExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalShuttlColors.current
}
```

Keep the file's existing imports and add nothing beyond what the compiler asks
for. The `// Web tokens (badminton-tracker/src/app.css) -> Compose colors.`
comment at the top is now false and is replaced with a pointer to
`ShuttlPalette.kt`.

- [ ] **Step 5: Run the test and watch it pass**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ShuttlPaletteTest*'
```

Expected: six tests pass.

- [ ] **Step 6: Run the whole Android suite**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: PASS. Then `./gradlew :androidApp:assembleDebug` to catch any caller of
`ShuttlExtendedColors`' constructor that the two new fields broke.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlPalette.kt \
        androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlColors.kt \
        androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlPaletteTest.kt
git commit -m "feat(android): new palette, with its accessibility rules under test

The mirror of the iOS change: same values, same rules, raw Longs split
out so the contrast assertions run without Robolectric.

onPrimary stops being a bare Color.Black and becomes the onAccent token,
which is the same near-black in dark and a slightly different one in
light."
```

---

## Task 3: Shape scale on both platforms

`ShuttlShapes` is currently all `0.dp`, and both theme files carry a doc comment
asserting sharp corners everywhere. The mock reverses that. The comments are
rewritten, not left contradicting the code.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlShapes.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlRadius.kt`
- Create: `iosApp/Sources/Theme/ShuttlRadius.swift`
- Test: `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlShapesTest.kt`
- Test: `iosApp/Tests/ShuttlRadiusTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: `ShuttlRadius.pill/extraLarge/large/medium/small/extraSmall` as `Dp` on Android and `CGFloat` on iOS, with identical numbers. `ShuttlShapes` keeps its `Shapes` type and its `MaterialTheme` wiring.

- [ ] **Step 1: Write the failing tests**

Create `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlShapesTest.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The shape scale, pinned so it cannot drift from iosApp's ShuttlRadiusTests.
 * Two clients rendering the same design at different radii is the exact kind of
 * divergence nobody notices until the screenshots sit side by side.
 */
class ShuttlShapesTest {
    @Test
    fun radius_scale_matches_the_design() {
        ShuttlRadius.extraSmall shouldBe 8.dp
        ShuttlRadius.small shouldBe 12.dp
        ShuttlRadius.medium shouldBe 16.dp
        ShuttlRadius.large shouldBe 20.dp
        ShuttlRadius.extraLarge shouldBe 28.dp
        ShuttlRadius.pill shouldBe 999.dp
    }

    @Test
    fun material_shapes_are_no_longer_square() {
        // Guards the reversal itself: this scale replaced an all-0dp one, and a
        // revert would silently un-round every card in the app.
        ShuttlShapes.large shouldBe androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        ShuttlShapes.medium shouldBe androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    }
}
```

Create `iosApp/Tests/ShuttlRadiusTests.swift`:

```swift
import XCTest
@testable import iosApp

/// The mirror of androidApp's ShuttlShapesTest. Same numbers, asserted on both
/// sides so the two clients cannot drift apart unnoticed.
final class ShuttlRadiusTests: XCTestCase {
    func testRadiusScaleMatchesTheDesign() {
        XCTAssertEqual(ShuttlRadius.extraSmall, 8)
        XCTAssertEqual(ShuttlRadius.small, 12)
        XCTAssertEqual(ShuttlRadius.medium, 16)
        XCTAssertEqual(ShuttlRadius.large, 20)
        XCTAssertEqual(ShuttlRadius.extraLarge, 28)
        XCTAssertEqual(ShuttlRadius.pill, 999)
    }
}
```

- [ ] **Step 2: Run both and watch them fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ShuttlShapesTest*'
```
Expected: `Unresolved reference: ShuttlRadius`.

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/ShuttlRadiusTests
```
Expected: `cannot find 'ShuttlRadius' in scope`.

- [ ] **Step 3: Implement on Android**

Create `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlRadius.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The shape scale, as raw radii.
 *
 * [pill] has no slot in M3's [androidx.compose.material3.Shapes], which is why
 * this object exists alongside ShuttlShapes rather than inside it. Mirrors
 * iosApp's ShuttlRadius.swift number for number.
 */
object ShuttlRadius {
    val extraSmall = 8.dp
    val small      = 12.dp
    val medium     = 16.dp
    val large      = 20.dp
    val extraLarge = 28.dp

    /** Buttons, chips, badges, tab pills. Larger than any surface it clips. */
    val pill       = 999.dp
}
```

Replace `ShuttlShapes.kt` in full:

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * The rounded scale from the badminton-design mock.
 *
 * This replaced an all-0dp scale ported from the web app's flat aesthetic. The
 * two are not reconcilable and the mock wins on mobile, so the web tokens and
 * these have deliberately forked. Radii live in [ShuttlRadius]; this maps them
 * onto M3's slots.
 */
internal val ShuttlShapes = Shapes(
    extraSmall = RoundedCornerShape(ShuttlRadius.extraSmall),
    small      = RoundedCornerShape(ShuttlRadius.small),
    medium     = RoundedCornerShape(ShuttlRadius.medium),
    large      = RoundedCornerShape(ShuttlRadius.large),
    extraLarge = RoundedCornerShape(ShuttlRadius.extraLarge),
)
```

- [ ] **Step 4: Implement on iOS**

Create `iosApp/Sources/Theme/ShuttlRadius.swift`:

```swift
import CoreGraphics

/// The shape scale, as raw radii.
///
/// Mirrors androidApp's ShuttlRadius.kt number for number. Use with
/// `.clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))`, or
/// `Capsule()` where Android would use `pill`.
enum ShuttlRadius {
    static let extraSmall: CGFloat = 8
    static let small: CGFloat = 12
    static let medium: CGFloat = 16
    static let large: CGFloat = 20
    static let extraLarge: CGFloat = 28

    /// Buttons, chips, badges, tab pills. `Capsule()` is equivalent and
    /// preferred in SwiftUI; this exists so the two platforms' scales can be
    /// compared token for token.
    static let pill: CGFloat = 999
}
```

- [ ] **Step 5: Run both and watch them pass**

Same two commands as Step 2. Expected: PASS on both.

- [ ] **Step 6: Run both full suites**

```bash
./gradlew :androidApp:testDebugUnitTest && ./gradlew :androidApp:assembleDebug
```
and the iOS `xcodebuild test` without `-only-testing`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlShapes.kt \
        androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlRadius.kt \
        androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlShapesTest.kt \
        iosApp/Sources/Theme/ShuttlRadius.swift \
        iosApp/Tests/ShuttlRadiusTests.swift
git commit -m "feat: rounded shape scale on both clients

Reverses the all-0dp scale ported from the web app's flat aesthetic. The
mock is rounded throughout and wins on mobile, so the web tokens and the
mobile ones have forked; the doc comments claiming sharp corners
everywhere are rewritten rather than left contradicting the code.

pill gets its own constant because M3's Shapes has no slot for it."
```

---

## Task 4: Bundle Archivo on iOS

**Files:**
- Create: `iosApp/Resources/Fonts/Archivo-Regular.ttf`, `Archivo-Medium.ttf`, `Archivo-SemiBold.ttf`, `Archivo-Bold.ttf`, `OFL.txt`
- Modify: `iosApp/project.yml`
- Test: `iosApp/Tests/ArchivoFontTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: four fonts resolvable by PostScript name `Archivo-Regular`, `Archivo-Medium`, `Archivo-SemiBold`, `Archivo-Bold`. Task 6 depends on exactly these names.

- [ ] **Step 1: Fetch the fonts**

```bash
mkdir -p iosApp/Resources/Fonts
cd /tmp
curl -L -o archivo.zip "https://fonts.google.com/download?family=Archivo"
unzip -o archivo.zip -d archivo
```

The zip contains a `static/` directory. Copy exactly four files plus the licence:

```bash
cd -
cp /tmp/archivo/static/Archivo-Regular.ttf  iosApp/Resources/Fonts/
cp /tmp/archivo/static/Archivo-Medium.ttf   iosApp/Resources/Fonts/
cp /tmp/archivo/static/Archivo-SemiBold.ttf iosApp/Resources/Fonts/
cp /tmp/archivo/static/Archivo-Bold.ttf     iosApp/Resources/Fonts/
cp /tmp/archivo/OFL.txt                     iosApp/Resources/Fonts/
shasum -a 256 iosApp/Resources/Fonts/*.ttf
```

Record the four checksums; they go in the commit message so a future font
refresh is a visible change rather than a silent one.

If the zip layout differs (Google Fonts has changed it before), find the static
instances with `find /tmp/archivo -name 'Archivo-*.ttf'`. Do not substitute the
variable font: SwiftUI cannot select a weight axis from one without
`CTFontDescriptor` variation work, which is not worth it for four weights.

- [ ] **Step 2: Confirm the PostScript names**

The name the app asks for is the PostScript name, not the filename, and they are
not always equal.

```bash
for f in iosApp/Resources/Fonts/*.ttf; do
  echo -n "$f -> "
  python3 -c "
import sys
from fontTools.ttLib import TTFont
print(TTFont(sys.argv[1])['name'].getDebugName(6))
" "$f" 2>/dev/null || echo "(install fonttools, or read the name in Font Book)"
done
```

Expected: `Archivo-Regular`, `Archivo-Medium`, `Archivo-SemiBold`, `Archivo-Bold`.
If any differs, use the reported name in Step 4's test and in Task 6, and say so
in the commit message.

- [ ] **Step 3: Write the failing test**

Create `iosApp/Tests/ArchivoFontTests.swift`:

```swift
import XCTest
import UIKit
@testable import iosApp

/// Guards the bundling, which is invisible until it is not: a missing UIAppFonts
/// entry does not crash or warn, it silently falls back to the system font on
/// every screen at once.
final class ArchivoFontTests: XCTestCase {
    private let expected = [
        "Archivo-Regular",
        "Archivo-Medium",
        "Archivo-SemiBold",
        "Archivo-Bold",
    ]

    func testEveryArchivoWeightIsRegistered() {
        for name in expected {
            XCTAssertNotNil(
                UIFont(name: name, size: 16),
                "\(name) did not resolve. Check UIAppFonts in iosApp/project.yml and rerun xcodegen."
            )
        }
    }

    func testArchivoIsNotSilentlyFallingBackToSystem() {
        // UIFont(name:) returns nil for an unknown name rather than a fallback,
        // but a typo that happened to match another installed family would pass
        // the check above. Assert the family too.
        let font = UIFont(name: "Archivo-SemiBold", size: 16)
        XCTAssertEqual(font?.familyName, "Archivo")
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/ArchivoFontTests
```

Expected: FAIL, "Archivo-Regular did not resolve".

- [ ] **Step 5: Wire the fonts into the project**

In `iosApp/project.yml`, add `Resources` to the target's sources so XcodeGen
copies the folder into the bundle:

```yaml
    sources: [Sources, Assets.xcassets, Resources]
```

and add `UIAppFonts` to the same target's `info.properties`, alongside
`UILaunchScreen`:

```yaml
        UIAppFonts:
          - Archivo-Regular.ttf
          - Archivo-Medium.ttf
          - Archivo-SemiBold.ttf
          - Archivo-Bold.ttf
```

`UIAppFonts` takes filenames as they appear in the bundle root, not paths, which
is what a folder-reference-free copy of `Resources/Fonts` produces.

**Do not edit `iosApp/Sources/Info.plist` by hand.** XcodeGen generates it from
these properties, and a hand edit is overwritten on the next generate.

Regenerate:

```bash
xcodegen generate --spec iosApp/project.yml --project iosApp
```

- [ ] **Step 6: Run it and watch it pass**

Same command as Step 4. Expected: both tests pass.

If `testEveryArchivoWeightIsRegistered` still fails, the fonts are in the bundle
but not at its root. Confirm with:

```bash
find iosApp/build/DerivedData -name 'Archivo-*.ttf' -path '*iosApp.app*'
```

They must sit directly inside `iosApp.app`, not in `iosApp.app/Fonts`.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Resources/Fonts iosApp/project.yml iosApp/Sources/Info.plist \
        iosApp/iosApp.xcodeproj iosApp/Tests/ArchivoFontTests.swift
git commit -m "feat(ios): bundle Archivo

Four static instances rather than the variable font: SwiftUI cannot pick
a weight axis out of a variable font without CTFontDescriptor variation
work, which is not worth it for four weights.

UIAppFonts goes in project.yml, not the generated Info.plist. A missing
entry here does not crash or warn, it silently falls back to the system
font on every screen at once, so ArchivoFontTests asserts each weight
resolves and that the family really is Archivo.

SIL OFL 1.1, OFL.txt included.

sha256:
  Archivo-Regular.ttf   <paste>
  Archivo-Medium.ttf    <paste>
  Archivo-SemiBold.ttf  <paste>
  Archivo-Bold.ttf      <paste>"
```

---

## Task 5: Bundle Archivo on Android

**Files:**
- Create: `androidApp/src/main/res/font/archivo_regular.ttf`, `archivo_medium.ttf`, `archivo_semibold.ttf`, `archivo_bold.ttf`
- Create: `androidApp/src/main/assets/fonts/OFL.txt` (the licence cannot live in `res/font`, which rejects non-font files)
- Test: `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ArchivoAssetTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: four font resources at `R.font.archivo_regular`, `archivo_medium`, `archivo_semibold`, `archivo_bold`. Task 6 depends on exactly these resource names.

Android resource filenames must be lowercase with underscores, so they differ
from the iOS filenames on purpose.

- [ ] **Step 1: Copy the fonts**

Reuse the download from Task 4, Step 1.

```bash
mkdir -p androidApp/src/main/res/font androidApp/src/main/assets/fonts
cp /tmp/archivo/static/Archivo-Regular.ttf  androidApp/src/main/res/font/archivo_regular.ttf
cp /tmp/archivo/static/Archivo-Medium.ttf   androidApp/src/main/res/font/archivo_medium.ttf
cp /tmp/archivo/static/Archivo-SemiBold.ttf androidApp/src/main/res/font/archivo_semibold.ttf
cp /tmp/archivo/static/Archivo-Bold.ttf     androidApp/src/main/res/font/archivo_bold.ttf
cp /tmp/archivo/OFL.txt                     androidApp/src/main/assets/fonts/OFL.txt
```

- [ ] **Step 2: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ArchivoAssetTest.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * Guards the font ASSETS, not the wiring.
 *
 * This source set has no Robolectric, so R.font cannot be resolved here and a
 * real "does the family load" test is not available. What can be checked cheaply
 * is that the four files exist under the exact resource names ShuttlType.kt
 * references, which is the failure that would otherwise surface as every screen
 * silently rendering in the system font. The wiring itself is covered by the
 * visual sweep in Task 9.
 *
 * Gradle runs unit tests with the module directory as the working directory.
 */
class ArchivoAssetTest {
    private val fontDir = File("src/main/res/font")

    @Test
    fun every_weight_referenced_by_shuttl_type_is_present() {
        val required = listOf(
            "archivo_regular.ttf",
            "archivo_medium.ttf",
            "archivo_semibold.ttf",
            "archivo_bold.ttf",
        )
        for (name in required) {
            File(fontDir, name).exists() shouldBe true
        }
    }

    @Test
    fun font_resource_names_are_valid_android_resource_names() {
        // A capital letter or a dash in res/font is a build failure with a
        // message that does not mention the file, so catch it here instead.
        val offenders = fontDir.listFiles().orEmpty()
            .map { it.name }
            .filter { !it.matches(Regex("[a-z0-9_]+\\.ttf")) }
        offenders shouldBe emptyList()
    }
}
```

- [ ] **Step 3: Run it and watch it pass immediately**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ArchivoAssetTest*'
```

This one is written after the files exist, so it passes on the first run. To
prove it can fail, temporarily rename one file, rerun, see it fail, rename it
back. Do that before committing; a guard that has never gone red is not a guard.

- [ ] **Step 4: Confirm the resources compile**

```bash
./gradlew :androidApp:assembleDebug
```

Expected: PASS. This is what actually proves `res/font` accepted the files.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/res/font androidApp/src/main/assets/fonts \
        androidApp/src/test/java/com/badmintontracker/android/ui/theme/ArchivoAssetTest.kt
git commit -m "feat(android): bundle Archivo

The same four static instances as iOS, renamed to satisfy Android's
lowercase resource naming. OFL.txt goes under assets because res/font
rejects a non-font file.

ArchivoAssetTest guards the filenames ShuttlType.kt is about to
reference. It cannot check that the family loads, since this source set
has no Robolectric; the visual sweep covers that."
```

---

## Task 6: Typography scale on both platforms

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlType.kt`
- Create: `iosApp/Sources/Theme/ShuttlType.swift`
- Modify: `iosApp/Sources/Theme/ShuttlTheme.swift` (`sectionLabel` moves onto Archivo)
- Test: `iosApp/Tests/ShuttlTypeTests.swift`
- Test: `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlTypeTest.kt`

**Interfaces:**
- Consumes: `R.font.archivo_*` (Task 5), PostScript names `Archivo-*` (Task 4).
- Produces: on iOS, `enum ShuttlType` holding a `Role` per name in the type scale table (`ShuttlType.display`, `.headlineLarge`, `.headlineMedium`, `.statNumber`, `.titleLarge`, `.titleMedium`, `.bodyLarge`, `.bodyMedium`, `.bodySmall`, `.labelSmall`), each exposing `size: CGFloat`, `weight: ShuttlType.Weight`, `trackingEm: CGFloat`, `kerning: CGFloat`, `font: Font`; plus `ShuttlType.allRoles` and the `View.shuttlType(_:)` modifier, which is how callers apply one. On Android, `internal object ShuttlScale` holding the same roles as `ShuttlScale.Role(sizeSp, weight, trackingEm, lineHeightMultiple)` with a derived `trackingSp`; `ShuttlTypography` built from it; and `ShuttlTypeExtras.display` / `.statNumber` for the two roles M3's `Typography` has no slot for.

- [ ] **Step 1: Write the failing tests**

Create `iosApp/Tests/ShuttlTypeTests.swift`:

```swift
import XCTest
@testable import iosApp

/// The type scale's numbers, pinned against androidApp's ShuttlTypeTest.
///
/// Font objects are opaque, so what is asserted here is the scale table the
/// styles are built from. That is the part that drifts between platforms.
final class ShuttlTypeTests: XCTestCase {
    func testScaleMatchesTheDesign() {
        XCTAssertEqual(ShuttlType.display.size, 40)
        XCTAssertEqual(ShuttlType.display.weight, .medium)
        XCTAssertEqual(ShuttlType.display.trackingEm, -0.035, accuracy: 0.0001)

        XCTAssertEqual(ShuttlType.headlineLarge.size, 28)
        XCTAssertEqual(ShuttlType.headlineMedium.size, 22)
        XCTAssertEqual(ShuttlType.statNumber.size, 26)
        XCTAssertEqual(ShuttlType.titleLarge.size, 16)
        XCTAssertEqual(ShuttlType.titleMedium.size, 15)
        XCTAssertEqual(ShuttlType.bodyLarge.size, 16)
        XCTAssertEqual(ShuttlType.bodyMedium.size, 14)
        XCTAssertEqual(ShuttlType.bodySmall.size, 12)
        XCTAssertEqual(ShuttlType.labelSmall.size, 11)
        XCTAssertEqual(ShuttlType.labelSmall.trackingEm, 0.05, accuracy: 0.0001)
    }

    func testTrackingIsConvertedToPoints() {
        // -0.035em at 40pt is -1.4pt. The conversion is the thing that gets got
        // wrong when the scale is edited, so it is asserted rather than assumed.
        XCTAssertEqual(ShuttlType.display.kerning, -1.4, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.labelSmall.kerning, 0.55, accuracy: 0.001)
    }

    func testEveryRoleUsesABundledArchivoWeight() {
        let bundled: Set<ShuttlType.Weight> = [.regular, .medium, .semibold, .bold]
        for role in ShuttlType.allRoles {
            XCTAssertTrue(
                bundled.contains(role.weight),
                "\(role.name) asks for a weight that is not bundled"
            )
        }
    }
}
```

Create `androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlTypeTest.kt`:

```kotlin
package com.badmintontracker.android.ui.theme

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The mirror of iosApp's ShuttlTypeTests. Asserts the scale table rather than
 * the built TextStyles, which need a font resource this source set cannot load.
 */
class ShuttlTypeTest {
    @Test
    fun scale_matches_the_design() {
        ShuttlScale.display.sizeSp shouldBe 40f
        ShuttlScale.display.trackingEm shouldBe -0.035f
        ShuttlScale.headlineLarge.sizeSp shouldBe 28f
        ShuttlScale.headlineMedium.sizeSp shouldBe 22f
        ShuttlScale.statNumber.sizeSp shouldBe 26f
        ShuttlScale.titleLarge.sizeSp shouldBe 16f
        ShuttlScale.titleMedium.sizeSp shouldBe 15f
        ShuttlScale.bodyLarge.sizeSp shouldBe 16f
        ShuttlScale.bodyMedium.sizeSp shouldBe 14f
        ShuttlScale.bodySmall.sizeSp shouldBe 12f
        ShuttlScale.labelSmall.sizeSp shouldBe 11f
        ShuttlScale.labelSmall.trackingEm shouldBe 0.05f
    }

    @Test
    fun tracking_converts_to_sp() {
        // -0.035em at 40sp is -1.4sp. Same arithmetic as iOS's kerning, and the
        // same thing that gets got wrong when the scale is edited.
        ShuttlScale.display.trackingSp shouldBe (-1.4f plusOrMinus 0.001f)
        ShuttlScale.labelSmall.trackingSp shouldBe (0.55f plusOrMinus 0.001f)
    }
}
```

Add `import io.kotest.matchers.floats.plusOrMinus`.

- [ ] **Step 2: Run both and watch them fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ShuttlTypeTest*'
```
Expected: `Unresolved reference: ShuttlScale`.

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/ShuttlTypeTests
```
Expected: `cannot find 'ShuttlType' in scope`.

- [ ] **Step 3: Implement on iOS**

Create `iosApp/Sources/Theme/ShuttlType.swift`:

```swift
import SwiftUI

/// The type scale, in Archivo.
///
/// Roles carry their raw numbers so the scale can be asserted from a unit test,
/// which a built `Font` cannot be. Mirrors androidApp's ShuttlScale number for
/// number; the two are checked against each other by ShuttlTypeTests and
/// ShuttlTypeTest.
///
/// Tracking is stored in em, the unit the design is expressed in, and converted
/// to points on the way out. Storing points would mean re-deriving the
/// conversion by hand every time a size changes.
enum ShuttlType {
    enum Weight: String, Hashable {
        case regular  = "Archivo-Regular"
        case medium   = "Archivo-Medium"
        case semibold = "Archivo-SemiBold"
        case bold     = "Archivo-Bold"
    }

    struct Role {
        let name: String
        let size: CGFloat
        let weight: Weight
        let trackingEm: CGFloat
        let lineHeightMultiple: CGFloat

        /// Tracking in points, which is what `.kerning` takes.
        var kerning: CGFloat { size * trackingEm }

        /// The SwiftUI font. Falls back to the system font at the same size if
        /// Archivo is somehow missing, so a bundling mistake degrades rather
        /// than crashes. ArchivoFontTests is what catches it properly.
        var font: Font {
            .custom(weight.rawValue, size: size)
        }
    }

    static let display          = Role(name: "display", size: 40, weight: .medium, trackingEm: -0.035, lineHeightMultiple: 1.08)
    static let headlineLarge    = Role(name: "headlineLarge", size: 28, weight: .medium, trackingEm: -0.030, lineHeightMultiple: 1.15)
    static let headlineMedium   = Role(name: "headlineMedium", size: 22, weight: .medium, trackingEm: -0.020, lineHeightMultiple: 1.20)
    static let statNumber       = Role(name: "statNumber", size: 26, weight: .medium, trackingEm: -0.030, lineHeightMultiple: 1.15)
    static let titleLarge       = Role(name: "titleLarge", size: 16, weight: .semibold, trackingEm: -0.010, lineHeightMultiple: 1.30)
    static let titleMedium      = Role(name: "titleMedium", size: 15, weight: .semibold, trackingEm: -0.010, lineHeightMultiple: 1.30)
    static let bodyLarge        = Role(name: "bodyLarge", size: 16, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.45)
    static let bodyMedium       = Role(name: "bodyMedium", size: 14, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.45)
    static let bodySmall        = Role(name: "bodySmall", size: 12, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.40)
    static let labelSmall       = Role(name: "labelSmall", size: 11, weight: .medium, trackingEm: 0.050, lineHeightMultiple: 1.30)

    static let allRoles: [Role] = [
        display, headlineLarge, headlineMedium, statNumber,
        titleLarge, titleMedium, bodyLarge, bodyMedium, bodySmall, labelSmall,
    ]
}

extension View {
    /// Applies a role's font and its tracking together, which is the only
    /// correct way to use one: the kerning is derived from the size, so setting
    /// the font without it silently ships the wrong tracking.
    func shuttlType(_ role: ShuttlType.Role) -> some View {
        self.font(role.font).kerning(role.kerning)
    }
}
```

Then update `sectionLabel` in `ShuttlTheme.swift` to use it:

```swift
    /// Tiny uppercase tracked label - matches Android labelSmall.
    static func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .shuttlType(ShuttlType.labelSmall)
            .foregroundStyle(Shuttl.textSecondary)
    }
```

- [ ] **Step 4: Implement on Android**

Replace `ShuttlType.kt` in full:

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.R

/**
 * The type scale as raw numbers.
 *
 * Split out from the TextStyles so it can be asserted from a unit test with no
 * font resource and no Android runtime. Mirrors iosApp's ShuttlType.Role number
 * for number.
 *
 * Tracking is stored in em, the unit the design is expressed in, and converted
 * on the way out; storing sp would mean re-deriving it by hand on every size
 * change.
 */
internal object ShuttlScale {
    data class Role(
        val sizeSp: Float,
        val weight: FontWeight,
        val trackingEm: Float,
        val lineHeightMultiple: Float,
    ) {
        val trackingSp: Float get() = sizeSp * trackingEm
    }

    val display        = Role(40f, FontWeight.Medium, -0.035f, 1.08f)
    val headlineLarge  = Role(28f, FontWeight.Medium, -0.030f, 1.15f)
    val headlineMedium = Role(22f, FontWeight.Medium, -0.020f, 1.20f)
    val statNumber     = Role(26f, FontWeight.Medium, -0.030f, 1.15f)
    val titleLarge     = Role(16f, FontWeight.SemiBold, -0.010f, 1.30f)
    val titleMedium    = Role(15f, FontWeight.SemiBold, -0.010f, 1.30f)
    val bodyLarge      = Role(16f, FontWeight.Normal, 0f, 1.45f)
    val bodyMedium     = Role(14f, FontWeight.Normal, 0f, 1.45f)
    val bodySmall      = Role(12f, FontWeight.Normal, 0f, 1.40f)
    val labelSmall     = Role(11f, FontWeight.Medium, 0.050f, 1.30f)
}

internal val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

private fun ShuttlScale.Role.toTextStyle() = TextStyle(
    fontFamily    = Archivo,
    fontWeight    = weight,
    fontSize      = sizeSp.sp,
    lineHeight    = (sizeSp * lineHeightMultiple).sp,
    letterSpacing = trackingSp.sp,
)

internal val ShuttlTypography = Typography(
    headlineLarge  = ShuttlScale.headlineLarge.toTextStyle(),
    headlineMedium = ShuttlScale.headlineMedium.toTextStyle(),
    titleLarge     = ShuttlScale.titleLarge.toTextStyle(),
    titleMedium    = ShuttlScale.titleMedium.toTextStyle(),
    bodyLarge      = ShuttlScale.bodyLarge.toTextStyle(),
    bodyMedium     = ShuttlScale.bodyMedium.toTextStyle(),
    bodySmall      = ShuttlScale.bodySmall.toTextStyle(),
    // Tiny uppercase tracked label. The uppercasing is the caller's job, the
    // tracking is this style's.
    labelSmall     = ShuttlScale.labelSmall.toTextStyle(),
)

/**
 * The two roles M3's [Typography] has no slot for. Phase 2's hero uses
 * [display]; phase 3's stat tiles use [statNumber].
 */
internal object ShuttlTypeExtras {
    val display    = ShuttlScale.display.toTextStyle()
    val statNumber = ShuttlScale.statNumber.toTextStyle()
}
```

- [ ] **Step 5: Run both and watch them pass**

Same two commands as Step 2. Expected: PASS on both.

- [ ] **Step 6: Run both full suites and both builds**

```bash
./gradlew :androidApp:testDebugUnitTest && ./gradlew :androidApp:assembleDebug
```
and the iOS `xcodebuild test` without `-only-testing`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlType.kt \
        androidApp/src/test/java/com/badmintontracker/android/ui/theme/ShuttlTypeTest.kt \
        iosApp/Sources/Theme/ShuttlType.swift \
        iosApp/Sources/Theme/ShuttlTheme.swift \
        iosApp/Tests/ShuttlTypeTests.swift
git commit -m "feat: Archivo type scale on both clients

The scale table is split from the built styles so it can be asserted
without a font resource or an Android runtime, and so the two platforms
can be checked against each other role by role.

Tracking is stored in em, the unit the design uses, and converted to
sp/points on the way out. Storing the converted value would mean
re-deriving it by hand on every size change, which is exactly the edit
that goes wrong quietly.

display and statNumber live outside M3's Typography, which has no slot
for them; phase 2's hero and phase 3's stat tiles are their callers."
```

---

## Task 7: Route hardcoded accent-on-black through onAccent

Eight iOS call sites paint text on an accent fill with a literal `.black`. They
happen to still look right in the new dark palette, which is exactly why they
need fixing now rather than later: the next palette edit would break them
silently.

**Files:**
- Modify: `iosApp/Sources/Components/PrimaryButtonStyle.swift:7`
- Modify: `iosApp/Sources/Components/PlaybackControlBar.swift:172`
- Modify: `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift:55`
- Modify: `iosApp/Sources/Match/PointsFacet.swift:38`
- Modify: `iosApp/Sources/Match/MatchView.swift:626,638`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift:549,563`
- Modify: `iosApp/Sources/LocalVideo/LocalVideoSection.swift:62`

**Interfaces:**
- Consumes: `Shuttl.onAccent` (Task 1).
- Produces: nothing new.

- [ ] **Step 1: Find every site**

```bash
grep -rn "foregroundStyle(\.black)\|foregroundStyle(Color\.black)\|: \.black" iosApp/Sources
```

Check each hit. A `.black` on an accent fill becomes `Shuttl.onAccent`. A
`.black` over video (`LocalPlayerView.swift:64`, `ClipDetailView.swift:43`) is a
letterbox, not a token, and stays as it is. Note which is which before editing.

- [ ] **Step 2: Replace the accent-fill sites**

For each, swap the literal for the token. `PrimaryButtonStyle.swift` is the
representative case:

```swift
// before
            .foregroundStyle(.black)
// after
            .foregroundStyle(Shuttl.onAccent)
```

In `AddAnnotationSheet.swift:55` and `PlaybackControlBar.swift:172` the literal
is inside a ternary already selecting on the accent fill:

```swift
// before
                .foregroundStyle(isSelected ? .black : Shuttl.text)
// after
                .foregroundStyle(isSelected ? Shuttl.onAccent : Shuttl.text)
```

- [ ] **Step 3: Route the court guide off its hardcoded accent**

`CourtMarkingView.swift:183,184,217` and `SchematicCourtGuide.swift:52` hardcode
`0x22C55E`, which was the old dark accent and is now `accentDark`. The guide is a
line drawn over a video frame in a dark context, so it takes `Shuttl.accent`:

```swift
// CourtMarkingView.swift:183-184, before
        let guideGreen = Color(red: 0x22/255, green: 0xC5/255, blue: 0x5E/255).opacity(0.2)
        let connectGreen = Color(red: 0x22/255, green: 0xC5/255, blue: 0x5E/255).opacity(0.6)
// after
        let guideGreen = Shuttl.accent.opacity(0.2)
        let connectGreen = Shuttl.accent.opacity(0.6)
```

```swift
// CourtMarkingView.swift:217, before
        context.stroke(path, with: .color(Color(rgb: 0x22C55E).opacity(0.6)), style: ...)
// after
        context.stroke(path, with: .color(Shuttl.accent.opacity(0.6)), style: ...)
```

```swift
// SchematicCourtGuide.swift:52, before
            let line = Color(rgb: 0x22C55E)
// after
            let line = Shuttl.accent
```

- [ ] **Step 4: Confirm nothing is left**

```bash
grep -rn "0x22C55E\|0x16A34A" iosApp/Sources | grep -v Theme/ShuttlPalette.swift
```

Expected: no output.

- [ ] **Step 5: Run the full iOS suite**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS. This is a colour-for-colour swap with no logic in it, so a
failure means a wrong site was edited.

- [ ] **Step 6: Commit**

```bash
git add iosApp/Sources
git commit -m "refactor(ios): accent-fill text goes through onAccent

Eight sites painted text on an accent fill with a literal .black. They
still look right under the new palette, which is why they are worth
fixing now: the next palette edit would break them silently.

The court guide's hardcoded 0x22C55E, which was the old dark accent, now
reads Shuttl.accent. The .black behind video stays a literal, because a
letterbox is not a token."
```

---

## Task 8: Visual sweep and breakage fixes

The token swap is done. This task finds what it broke.

**Files:** whichever the sweep implicates. No file is touched speculatively.

**Interfaces:**
- Consumes: everything from Tasks 1 to 7.
- Produces: a screenshot set committed under `docs/screenshots/2026-09-03-design-system/`.

- [ ] **Step 1: Capture the before set**

Do this from the merge base, before any of this branch's commits, so the
comparison is real:

```bash
git stash list   # confirm nothing is pending
git worktree add /tmp/shuttl-before $(git merge-base HEAD main)
```

Build and run each client from that worktree and capture, in **both light and
dark**:

Sign in, Matches list (with a local video mid-analysis if one can be staged),
Match page, Clip detail, Scoring board, New match, Labels, Court marking,
Local player, Share sheet, and on Android the Heatmap screen.

Save as `docs/screenshots/2026-09-03-design-system/before/<platform>-<screen>-<theme>.png`.

- [ ] **Step 2: Capture the after set**

The same screens from the current branch, into `after/` with identical names.

- [ ] **Step 3: Review every pair against this list**

For each pair, check:

1. **Text legibility.** Anything that got harder to read is a bug, not a taste
   call. The contrast tests cover tokens on token surfaces; they do not cover
   text over video, over a thumbnail, or over the scoreboard halves.
2. **Rounded corners against square containers.** A 20dp card inside a list that
   assumed square edges will show background bleed at the corners.
3. **Clipping from Archivo's metrics.** Archivo is not metric-compatible with
   either system font. Fixed-height rows and single-line labels are where this
   shows: look for truncated descenders and mid-word ellipses that were not
   there before.
4. **The scoreboard halves.** `sideHome` and `sideAway` are unchanged but now sit
   in a different surrounding palette. Confirm the white numerals still read.
5. **Label swatches.** `LabelsScreen` and `LabelBadge` paint user-chosen colours
   from the database, not tokens. Confirm they still sit legibly on the new card
   surface.

- [ ] **Step 4: Fix what the review found**

One commit per screen, each with its before and after attached in the message
body by filename. Fix in place: adjust a padding, a line limit, a frame height.
**Do not redesign a screen.** If a screen needs more than a padding change to
look right, stop and write it down for the phase 2 review rather than inventing
a layout with no mock behind it.

- [ ] **Step 5: Run everything**

```bash
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS on all three.

- [ ] **Step 6: Clean up the comparison worktree**

```bash
git worktree remove /tmp/shuttl-before
```

- [ ] **Step 7: Commit the screenshots**

```bash
git add docs/screenshots/2026-09-03-design-system
git commit -m "docs: before and after screenshots for the design system swap

Every screen, both themes, both clients. These are the evidence for the
phase 1 claim that no layout moved, and the baseline the phase 2 Home
work gets compared against."
```

---

## Definition of Done

- [ ] `./gradlew :androidApp:testDebugUnitTest` passes.
- [ ] `./gradlew :androidApp:assembleDebug` passes.
- [ ] The iOS suite passes on the simulator.
- [ ] `grep -rn "0x22C55E\|0x16A34A" iosApp/Sources | grep -v ShuttlPalette` is empty.
- [ ] `grep -rn "foregroundStyle(\.black)" iosApp/Sources` returns only the two letterbox sites.
- [ ] Every screen has a before and after screenshot in both themes on both platforms.
- [ ] No file outside `ui/theme/`, `Sources/Theme/`, the font directories and the Task 7 call-site list has a layout change.
- [ ] The stale "sharp corners everywhere" and "web tokens" comments are gone from both theme layers.

## Not In This Plan

Phase 2 (Home, drawer, navigation ownership) and phase 3 (Analytics) get their
own plans against the same design document. Phase 1 deliberately ships a
re-skinned app with the old structure, so the visual change and the structural
change can be reviewed separately.
