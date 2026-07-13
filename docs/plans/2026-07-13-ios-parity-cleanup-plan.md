# iOS Parity Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the small iOS/Android parity gaps (remote-clip frame stepping, match-rallies pull-to-refresh, theme toggle, app icon) and land the seven deferred polish findings, bumping to 0.3.1 (11).

**Architecture:** One more Settings-backed repository (`ThemePreferenceRepository`) promotes to `shared/commonMain` following the established pattern; `FrameStepBar` becomes player-agnostic so the remote clip player reuses it; the icon is a generated flat-brand PNG in a new asset catalog; the polish items are surgical single-file fixes.

**Tech Stack:** Kotlin 2.3.20 KMP, SwiftUI iOS 17, AVFoundation, Python3/PIL (one-shot icon generation), XcodeGen.

**Spec:** `docs/plans/2026-07-13-ios-parity-cleanup-design.md`

## Global Constraints

- Theme preference persistence stays byte-compatible: Settings key `"theme_mode"`, stored values `"LIGHT"`/`"DARK"`, default LIGHT on missing/corrupt. Android behavior unchanged.
- iOS theme parity behavior: default LIGHT (no longer system-following); toggle persists via the shared repository; accessibility labels "Switch to dark mode"/"Switch to light mode" (Android's copy).
- New iOS copy (exact): "Couldn't import the video. Please try again."
- Android backport copy (exact): "Couldn't refresh matches. Pull to try again."
- Frame stepping math: reuse `FrameStepMath` unchanged — no new math.
- Icon: flat #16A34A background, white shuttlecock glyph, 1024×1024, no rounded corners (iOS masks it).
- Version bump ONLY in the final task: `Config/Version.xcconfig` → `MARKETING_VERSION = 0.3.1`, `CURRENT_PROJECT_VERSION = 11`.
- ENVIRONMENT: prefix xcodebuild/iOS-gradle-link commands with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; run `xattr -cr iosApp` before each xcodebuild; `export DEVICE="iPhone 17 Pro"`; test command: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`. Install/launch: `xcrun simctl install booted iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app && xcrun simctl launch booted com.badmintontracker.ios`. After adding/removing files: `cd iosApp && xcodegen generate && cd ..` and commit the regenerated project.
- Commit hygiene: stage only the files each task names.
- Do NOT sign out in the simulator (the session must survive for verification).

---

### Task 1: Promote ThemePreferenceRepository to shared

**Files:**
- Move (git mv, then edit): `androidApp/src/main/java/com/badmintontracker/android/data/ThemePreferenceRepository.kt` → `shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs/ThemePreferenceRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify (imports only): `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt` + every Android file importing `com.badmintontracker.android.data.{ThemePreferenceRepository,ThemeMode}` (compiler-driven: known candidates `MainActivity.kt`, `ui/components/ThemeToggleButton.kt`, `ui/theme/Theme.kt`, `AuthGate.kt`, `signin/SignInScreen.kt` — fix whatever `:androidApp:compileDebugKotlin` flags)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/prefs/ThemePreferenceRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class ThemeMode { LIGHT, DARK }` and `class ThemePreferenceRepository(settings: Settings)` (`mode: StateFlow<ThemeMode>`, `set(mode)`, `toggle()`) in `com.badmintontracker.shared.prefs`; `RallyApp.themePrefs: ThemePreferenceRepository`. Task 2's Swift consumes `rally.themePrefs.mode` (SKIE AsyncSequence) and `rally.themePrefs.toggle()`.

- [ ] **Step 1: Write the failing test**

`shared/src/commonTest/kotlin/com/badmintontracker/shared/prefs/ThemePreferenceRepositoryTest.kt`:
```kotlin
package com.badmintontracker.shared.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferenceRepositoryTest {
    @Test
    fun defaults_to_light() {
        assertEquals(ThemeMode.LIGHT, ThemePreferenceRepository(MapSettings()).mode.value)
    }

    @Test
    fun toggle_flips_and_persists_across_instances() {
        val settings = MapSettings()
        val repo = ThemePreferenceRepository(settings)
        repo.toggle()
        assertEquals(ThemeMode.DARK, repo.mode.value)
        assertEquals(ThemeMode.DARK, ThemePreferenceRepository(settings).mode.value)
    }

    @Test
    fun corrupt_stored_value_defaults_to_light() {
        val settings = MapSettings().apply { putString("theme_mode", "PLAID") }
        assertEquals(ThemeMode.LIGHT, ThemePreferenceRepository(settings).mode.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.prefs.ThemePreferenceRepositoryTest" 2>&1 | tail -4`
Expected: FAIL — unresolved reference (package doesn't exist yet).

- [ ] **Step 3: Move the file**

```bash
mkdir -p shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs
git mv androidApp/src/main/java/com/badmintontracker/android/data/ThemePreferenceRepository.kt \
       shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs/ThemePreferenceRepository.kt
```
Edit ONLY the package line to `package com.badmintontracker.shared.prefs`. The file is already pure Kotlin (Settings + StateFlow) — key `"theme_mode"`, values via `ThemeMode.name`, corrupt fallback `LIGHT` all stay untouched.

- [ ] **Step 4: Expose on RallyApp and update Android**

In `RallyApp.kt`, add `import com.badmintontracker.shared.prefs.ThemePreferenceRepository` and, next to the `localVideos`/`localAnnotations` properties:
```kotlin
    val themePrefs: ThemePreferenceRepository = ThemePreferenceRepository(settings)
```
In `RallyAndroidApp.kt`: change the import to the shared package and replace `themePrefs = ThemePreferenceRepository(settings)` with `themePrefs = rally.themePrefs`. Then run `./gradlew :androidApp:compileDebugKotlin` and fix remaining files by swapping imports `com.badmintontracker.android.data.ThemeMode` / `...data.ThemePreferenceRepository` → `com.badmintontracker.shared.prefs.ThemeMode` / `...prefs.ThemePreferenceRepository`. Imports only — no call-site changes.

- [ ] **Step 5: Verify**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL everywhere; the new test class green.

- [ ] **Step 6: Commit**

```bash
git add -A -- shared/src androidApp/src
git commit -m "refactor(shared): promote ThemePreferenceRepository to commonMain"
```
(Verify `git status --short` shows nothing outside those trees first.)

---

### Task 2: iOS theme toggle

**Files:**
- Modify: `iosApp/Sources/RootView.swift`
- Modify: `iosApp/Sources/SignIn/SignInView.swift`

**Interfaces:**
- Consumes: `rally.themePrefs.mode` (SKIE AsyncSequence of `ThemeMode`, cases `.light`/`.dark`), `rally.themePrefs.toggle()`, `rally.themePrefs.mode.value` for the initial value.

- [ ] **Step 1: Apply the preference at the root**

In `RootView.swift`, add a state and observation, and apply `preferredColorScheme`:
```swift
struct RootView: View {
    let rally: RallyApp
    @State private var authState: AuthState? = nil
    @State private var themeMode: ThemeMode = .light

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                NavigationStack { ClipListView(rally: rally) }
            case .unauthenticated:
                SignInView(rally: rally)
            case .some:
                SplashView()
            }
        }
        .preferredColorScheme(themeMode == .dark ? .dark : .light)
        .task {
            for await state in rally.authState {
                authState = state
            }
        }
        .task {
            for await mode in rally.themePrefs.mode {
                themeMode = mode
            }
        }
    }
}
```
(SKIE enum cases: `.light`/`.dark`. If the AsyncSequence element needs a cast, use `mode as? ThemeMode ?? .light` and note it.)

- [ ] **Step 2: Add the toggle to the sign-in screen (Android's placement: top-right)**

In `SignInView.swift`:
1. Add state: `@State private var themeMode: ThemeMode = .light`
2. Wrap the existing `ScrollView` in a `ZStack(alignment: .topTrailing) { ... }` and add the button as the second child, after the ScrollView:
```swift
            Button {
                rally.themePrefs.toggle()
            } label: {
                Image(systemName: themeMode == .dark ? "sun.max" : "moon")
                    .font(.system(size: 15))
                    .foregroundStyle(Shuttl.text)
                    .frame(width: 36, height: 36)
                    .background(Shuttl.bgSecondary)
                    .overlay(Rectangle().stroke(Shuttl.border, lineWidth: 1))
            }
            .accessibilityLabel(themeMode == .dark ? "Switch to light mode" : "Switch to dark mode")
            .padding(.trailing, 16)
            .padding(.top, 8)
```
3. Keep the observation in sync: add to the ZStack (outermost view) —
```swift
        .task {
            for await mode in rally.themePrefs.mode {
                themeMode = mode
            }
        }
```
(The `.background(Shuttl.bg)` modifier stays on the ScrollView or moves to the ZStack — either compiles; keep it on the outermost so the whole screen is themed.)

- [ ] **Step 3: Verify**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **`.

Persistence check without tapping: `xcrun simctl spawn booted defaults write com.badmintontracker.ios theme_mode -string DARK`, terminate + relaunch the app, screenshot — the UI must render dark (background #0D0D0D). Then `defaults write ... theme_mode -string LIGHT`, relaunch, screenshot — light again. Finally `defaults delete com.badmintontracker.ios theme_mode 2>/dev/null || true` to restore the default. This exercises load-from-Settings and the root `preferredColorScheme` wiring end-to-end; the toggle button's tap path is user-walkthrough.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/RootView.swift iosApp/Sources/SignIn/SignInView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): light/dark theme toggle backed by shared preference"
```

---

### Task 3: Player-agnostic FrameStepBar, clip-detail stepping, match-rallies refresh

**Files:**
- Modify: `iosApp/Sources/LocalVideo/FrameStepBar.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift` (call site)
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailView.swift`
- Modify: `iosApp/Sources/ClipList/MatchClipsView.swift`

**Interfaces:**
- Consumes: `FrameStepMath.targetSeconds(currentSeconds:fps:delta:durationSeconds:)` (existing, unchanged).
- Produces: `FrameStepBar(player: AVPlayer, step: @escaping (Int64) -> Void)`.

- [ ] **Step 1: Make FrameStepBar player-agnostic**

In `FrameStepBar.swift`, add `import AVFoundation` and change the outer struct only (HoldButton stays as-is, including its `.onDisappear` cleanup):
```swift
/// Port of Android's FrameStepBar: tap = single frame step; hold-left = -3
/// frames every 100ms; hold-right = real playback until release. 400ms hold
/// activation, like Android's HoldButton.
struct FrameStepBar: View {
    let player: AVPlayer
    let step: (Int64) -> Void

    var body: some View {
        HStack(spacing: 0) {
            HoldButton(
                text: "Previous frame",
                onTap: { step(-1) },
                onHoldTick: { step(-3) }
            )
            HoldButton(
                text: "Next frame",
                onTap: { step(1) },
                onHoldActivate: { player.play() },
                onRelease: { if player.rate != 0 { player.pause() } }
            )
        }
    }
}
```
In `LocalPlayerView.swift`, change the call site to:
```swift
            FrameStepBar(player: model.player, step: { model.stepFrames($0) })
```

- [ ] **Step 2: Add fps + stepFrames to ClipDetailModel**

In `ClipDetailModel.swift` add a stored `private var fps: Float = 0`, load it when a player is created, and add `stepFrames`. Concretely: at the end of the `do` branch in `sign(clip:)` (after `observeFailure(of: newPlayer)`), add:
```swift
                await loadFps(url: url)
```
and add these methods:
```swift
    private func loadFps(url: URL) async {
        let asset = AVURLAsset(url: url)
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let rate = try? await track.load(.nominalFrameRate) {
            fps = rate
        }
    }

    func stepFrames(_ delta: Int64) {
        guard let player else { return }
        if player.rate != 0 { player.pause() }
        let current = CMTimeGetSeconds(player.currentTime())
        let durationTime = player.currentItem?.duration
        let duration = (durationTime?.isNumeric == true) ? CMTimeGetSeconds(durationTime!) : 0
        let target = FrameStepMath.targetSeconds(
            currentSeconds: current.isFinite ? current : 0,
            fps: fps,
            delta: delta,
            durationSeconds: duration.isFinite ? duration : 0
        )
        player.seek(
            to: CMTime(seconds: target, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }
```

- [ ] **Step 3: Render the bar in ClipDetailView**

In `ClipDetailView.swift`'s `content(_:)`, directly after the `VideoPlayer(...)`/error ZStack-or-group (i.e. between the player area and the title HStack), insert:
```swift
            if let player = model.player {
                FrameStepBar(player: player, step: { model.stepFrames($0) })
            }
```
(Match the actual structure of the file: the player is `VideoPlayer(player: model.player)` followed by the optional error VStack; the bar goes after those and before the title `HStack`.)

- [ ] **Step 4: Pull-to-refresh on MatchClipsView**

In `MatchClipsView.swift`, add after `.listStyle(.plain)`:
```swift
        .refreshable { try? await rally.clips.refresh() }
```

- [ ] **Step 5: Verify**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (FrameStepMathTests still pin the math).
Then build+install+launch and screenshot a remote clip detail (navigating there needs a tap — if tap automation per the controller's cliclick recipe is unavailable, verify by code inspection and say so).

- [ ] **Step 6: Commit**

```bash
git add iosApp/Sources/LocalVideo/FrameStepBar.swift iosApp/Sources/LocalVideo/LocalPlayerView.swift \
        iosApp/Sources/ClipDetail/ClipDetailModel.swift iosApp/Sources/ClipDetail/ClipDetailView.swift \
        iosApp/Sources/ClipList/MatchClipsView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): frame stepping on remote clips, pull-to-refresh on match rallies"
```

---

### Task 4: App icon

**Files:**
- Create: `iosApp/Assets.xcassets/Contents.json`
- Create: `iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`
- Create: `iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png` (generated)
- Modify: `iosApp/project.yml` (register the asset catalog)

**Interfaces:**
- Consumes: nothing. Produces: the AppIcon set the existing `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` build setting already expects.

- [ ] **Step 1: Generate the icon PNG**

Write this script to the scratchpad and run it with `/usr/bin/python3` (PIL is available):
```python
from PIL import Image, ImageDraw

S = 1024
img = Image.new("RGB", (S, S), "#16A34A")   # Shuttl accent green
d = ImageDraw.Draw(img)
W = "#FFFFFF"

cx = S // 2
# Shuttlecock pointing down-center: cork at bottom, feather skirt fanning up.
cork_cy, cork_r = 700, 88
# Skirt: trapezoid from just above the cork up to a wide top edge.
skirt = [(cx - 70, 640), (cx + 70, 640), (cx + 230, 330), (cx - 230, 330)]
d.polygon(skirt, fill=W)
# Feather separations: two green notches splitting the skirt into three feathers.
for offset in (-77, 77):
    d.polygon(
        [(cx + offset - 14, 330), (cx + offset + 14, 330), (cx + offset // 3, 620)],
        fill="#16A34A",
    )
# Top band of the skirt.
d.rectangle([cx - 230, 300, cx + 230, 330], fill=W)
# Cork.
d.ellipse([cx - cork_r, cork_cy - cork_r, cx + cork_r, cork_cy + cork_r], fill=W)
# Cork band (green stripe across the cork for definition).
d.rectangle([cx - cork_r, cork_cy - 12, cx + cork_r, cork_cy + 12], fill="#0E7A38")

img.save("iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
print("written")
```
Create the directories first (`mkdir -p iosApp/Assets.xcassets/AppIcon.appiconset`). Then Read the generated PNG to visually confirm it looks like a shuttlecock (white skirt fanning up, cork ball at the bottom, green field). Minor coordinate tweaks for visual balance are allowed — note them.

- [ ] **Step 2: Asset catalog JSON**

`iosApp/Assets.xcassets/Contents.json`:
```json
{
  "info" : { "author" : "xcode", "version" : 1 }
}
```

`iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json`:
```json
{
  "images" : [
    {
      "filename" : "AppIcon.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
```

- [ ] **Step 3: Register in project.yml**

In `iosApp/project.yml`, change the iosApp target's sources line:
```yaml
    sources: [Sources, Assets.xcassets]
```
Then `cd iosApp && xcodegen generate && cd ..`.

- [ ] **Step 4: Verify**

Run: `xattr -cr iosApp` then the standard xcodebuild test command → `** TEST SUCCEEDED **`.
Then build+install, go to the simulator home screen (`osascript -e 'tell application "Simulator" to activate' -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'`), screenshot, and Read it — the Shuttl icon must appear on the app tile. Relaunch the app afterwards (`xcrun simctl launch booted com.badmintontracker.ios`).

- [ ] **Step 5: Commit**

```bash
git add iosApp/Assets.xcassets iosApp/project.yml iosApp/iosApp.xcodeproj
git commit -m "feat(ios): generated Shuttl app icon"
```

---

### Task 5: Polish batch (seven deferred findings)

**Files:**
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/Sources/LocalVideo/VideoPicker.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalThumbnails.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalVideoIntake.swift`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`

**Interfaces:**
- Consumes: everything already exists; this task only adjusts internals. VideoPicker's initializer gains `onFailed: () -> Void` — ClipListView is its only call site.

- [ ] **Step 1: Eager intake + always-visible intake error (ClipListView)**

1. Replace `@State private var intake: LocalVideoIntake?` with a non-optional created in an explicit init:
```swift
    @State private var intake: LocalVideoIntake

    init(rally: RallyApp) {
        self.rally = rally
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }
```
2. Remove the `if intake == nil { intake = LocalVideoIntake(rally: rally) }` line from the `.task`; drop the `?` from every `intake?.` use.
3. Move the intake error banner out of `content(model)`: wrap the body's `Group` in
```swift
        VStack(spacing: 0) {
            if let intakeError = intake.error {
                ErrorBanner(message: intakeError)
            }
            Group { ... existing switch content ... }
        }
```
and delete the `if let intakeError = intake?.error { ErrorBanner... }` block inside the List.

- [ ] **Step 2: VideoPicker failure surfacing + extension fix**

In `VideoPicker.swift`:
1. Add `let onFailed: () -> Void` below `onPicked`.
2. In the delegate: user cancellation (`results.isEmpty`) stays silent; but when a provider exists and loading fails, call the failure path. Replace the load block with:
```swift
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("import-\(UUID().uuidString).mp4")
                guard let url, (try? FileManager.default.copyItem(at: url, to: temp)) != nil else {
                    DispatchQueue.main.async { self.parent.onFailed() }
                    return
                }
                DispatchQueue.main.async {
                    self.parent.onPicked(temp, suggestedName ?? "video.mp4")
                }
            }
```
3. Extension fix — replace the suggestedName line with:
```swift
            let suggestedName = provider.suggestedName.map { name in
                ["mp4", "mov", "m4v"].contains((name as NSString).pathExtension.lowercased())
                    ? name : "\(name).mp4"
            }
```
4. In `ClipListView.swift`, update the call site:
```swift
            VideoPicker(
                onPicked: { tempURL, suggestedName in
                    Task { await intake.add(tempURL: tempURL, suggestedName: suggestedName, isRecording: false) }
                },
                onFailed: { intake.error = "Couldn't import the video. Please try again." }
            )
```
(`LocalVideoIntake.error` must be settable from outside — it already is a `var`.)

- [ ] **Step 3: Thumbnail eviction**

In `LocalThumbnails.swift` add:
```swift
    func evict(id: String) {
        images[id] = nil
    }
```
In `ClipListView.swift`'s local row `onRemove` closure, add `thumbnails.evict(id: entry.id)` next to `intake.remove(entry: entry)`.

- [ ] **Step 4: AddSheetItem wrapper (LocalPlayerView)**

Replace the `extension Float: @retroactive Identifiable` (delete it entirely) with:
```swift
private struct AddSheetItem: Identifiable {
    let timestamp: Float
    let id = UUID()
}
```
Change `@State private var addSheetTimestamp: Float?` to `@State private var addSheet: AddSheetItem?`; the + button sets `addSheet = AddSheetItem(timestamp: model.currentTimestampSeconds())`; the sheet becomes `.sheet(item: $addSheet) { item in AddAnnotationSheet { kind, body in model.add(kind: kind, body: body, atSeconds: item.timestamp) } ... }`.

- [ ] **Step 5: Temp cleanup on store() throw (LocalVideoIntake)**

In the `catch` branch around `LocalVideoFiles.store(tempURL:)`, add `try? FileManager.default.removeItem(at: tempURL)` before setting the error.

- [ ] **Step 6: Android refresh-copy backport**

In `androidApp/.../cliplist/ClipListViewModel.kt`, change
```kotlin
                    runCatching { clips.refresh() }.onFailure { errors.value = it.message }
```
to
```kotlin
                    runCatching { clips.refresh() }
                        .onFailure { errors.value = "Couldn't refresh matches. Pull to try again." }
```
Then `grep -rn "it.message" androidApp/src/test --include='*.kt' | grep -i cliplist` — if any test pins the old raw-message behavior, update its expectation to the new copy (none is known to exist).

- [ ] **Step 7: Verify**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command, plus `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`.
Expected: `** TEST SUCCEEDED **` and BUILD SUCCESSFUL. Launch + screenshot the clip list to confirm nothing regressed visually.

- [ ] **Step 8: Commit**

```bash
git add iosApp/Sources/ClipList/ClipListView.swift iosApp/Sources/LocalVideo/VideoPicker.swift \
        iosApp/Sources/LocalVideo/LocalThumbnails.swift iosApp/Sources/LocalVideo/LocalPlayerView.swift \
        iosApp/Sources/LocalVideo/LocalVideoIntake.swift iosApp/iosApp.xcodeproj \
        androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt
git commit -m "fix: polish batch — intake errors, picker failures, thumbnail eviction, sheet item, refresh copy"
```
(If Step 6 updated a test file, stage it too and name it in the message.)

---

### Task 6: Version bump to 0.3.1 (11) + changelog

**Files:**
- Modify: `Config/Version.xcconfig`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump**

`Config/Version.xcconfig`: `MARKETING_VERSION = 0.3.1`, `CURRENT_PROJECT_VERSION = 11` (comment header unchanged).

- [ ] **Step 2: Changelog**

Under `## [Unreleased]`, add to `### Added`:
```markdown
- iOS: frame stepping on remote clips, pull-to-refresh on match rallies, light/dark
  theme toggle (shared preference), app icon.
```
and a `### Fixed` heading (create if absent) with:
```markdown
- iOS: import failures now surface an error; stale thumbnails evicted on remove;
  assorted intake polish. Android: friendlier refresh-error message.
```
Existing entries untouched.

- [ ] **Step 3: Verify**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` then `xattr -cr iosApp` + the standard xcodebuild test command; then build+install+launch and check the built Info.plist: `plutil -p iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app/Info.plist | grep -E 'CFBundleShortVersionString|CFBundleVersion'` → 0.3.1 / 11.
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add Config/Version.xcconfig CHANGELOG.md
git commit -m "chore(version): bump to 0.3.1 (11) for parity cleanup"
```

---

## Verification checklist (end of plan)

1. All suites green on both platforms; iOS framework links.
2. Simulator: dark-mode render via seeded preference; icon on home screen; clip list unregressed.
3. User walkthrough items (tap-gated): theme toggle button on sign-in, remote-clip frame stepping, match-rallies pull-to-refresh, import-failure banner.
4. Both platforms report Version 0.3.1 (11).

## Known watch-points (for the executor)

- SKIE: `rally.themePrefs.mode` is a StateFlow — same AsyncSequence pattern as `rally.localVideos.entries`; `ThemeMode` bridges with cases `.light`/`.dark`.
- `ClipListView`'s new explicit `init` must not break the `NavigationStack { ClipListView(rally: rally) }` call site (same signature).
- The icon PNG must be committed as a binary file — do not run image-optimization tools on it.
