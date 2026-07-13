# iOS Parity Cleanup — Frame Stepping, Refresh, Theme, Icon, Polish (Design)

Date: 2026-07-13
Status: Approved

## Goal

Close the small iOS/Android parity gaps left after milestones 1-2, plus the deferred
review-polish items, in one low-risk milestone. Milestone 3 (analyze pipeline) follows
separately.

## Scope decisions

- App icon: generated brand icon (flat Shuttl accent green #16A34A, white shuttlecock
  glyph, sharp/flat), replaceable later.
- Custom fullscreen/orientation player handling: DEFERRED (system AVPlayer fullscreen
  suffices for now).
- AuthGate `authState` migration on Android: out of scope (internal, no user impact).
- Version bump to 0.3.1 (11) as the final task.

## 1. Frame stepping on the remote clip player

- Refactor `iosApp/Sources/LocalVideo/FrameStepBar.swift` to be player-agnostic: it
  takes an `AVPlayer` reference plus a `step(Int64)` closure (or equivalent minimal
  interface) instead of `LocalPlayerModel`. `LocalPlayerView` keeps identical behavior.
- `ClipDetailModel` gains fps loading (video track `nominalFrameRate` from the signed
  URL's `AVURLAsset`, fallback handled by `FrameStepMath`) and `stepFrames(_:)` with
  zero-tolerance seeks — same logic as `LocalPlayerModel`.
- `ClipDetailView` renders the FrameStepBar directly under the video, as Android's
  clip detail does. Existing `FrameStepMath` tests continue to pin the math; no new
  math is written.

## 2. Pull-to-refresh on match rallies

`MatchClipsView` gets `.refreshable { try? await rally.clips.refresh() }`, mirroring
Android's `PullToRefreshBox` on `MatchClipsScreen`. The observeClips loop already
re-renders on refresh.

## 3. Theme toggle

- Promote Android's `ThemePreferenceRepository` (Settings-backed) from
  `androidApp/.../data/` to `shared/commonMain` (`com.badmintontracker.shared.prefs`),
  preserving its Settings key and stored format exactly (Android installs keep their
  preference). Expose it as `RallyApp.themePrefs`. Android switches to the shared
  instance — behavior unchanged.
- iOS: a theme toggle button on the sign-in screen (top-right, Android's placement)
  and application of the preference at the root via `.preferredColorScheme(...)`.
- Parity behavior change on iOS: default becomes LIGHT (matching Android and the web
  app) instead of following the system; the toggle switches light/dark and persists
  via the shared repository.
- A commonTest pins the promoted repository's persistence roundtrip.

## 4. App icon

- Generate a 1024×1024 PNG at build-plan time (scripted, e.g. ImageMagick/PIL —
  whichever is available): flat #16A34A background, centered white shuttlecock glyph,
  no rounded corners (iOS applies its own mask).
- Add `iosApp/Assets.xcassets` with an `AppIcon.appiconset` (single 1024 universal
  asset), registered via `project.yml`; regenerate the Xcode project.

## 5. Polish batch (deferred review findings)

1. `ClipListView`: create `LocalVideoIntake` eagerly (view init or first render) and
   render the intake error banner outside `content(model)` so it is visible before the
   remote model exists.
2. `VideoPicker`: surface load/copy failures — the callback gains a failure path that
   sets the intake error ("Couldn't import the video. Please try again." — new iOS
   copy, exact) instead of silently returning.
3. `LocalThumbnails`: evict the cached image when an entry is removed (called from
   `LocalVideoIntake.remove`).
4. Replace `extension Float: @retroactive Identifiable` with a small
   `AddSheetItem: Identifiable { timestamp, id }` wrapper in `LocalPlayerView`.
5. `LocalVideoIntake`: delete the temp file when `LocalVideoFiles.store` throws.
6. `VideoPicker`: only append ".mp4" to the suggested name when it lacks a video
   extension (fixes "clip.mov.mp4").
7. Android backport: `ClipListViewModel`'s refresh failure shows
   "Couldn't refresh matches. Pull to try again." (iOS copy) instead of the raw
   exception message; update the corresponding Android test if it pins the old copy.

## 6. Versioning

Final task: `Config/Version.xcconfig` → `MARKETING_VERSION = 0.3.1`,
`CURRENT_PROJECT_VERSION = 11`; CHANGELOG entry. Both platforms pick it up
automatically.

## Testing & verification

- `FrameStepMath` tests unchanged (still pin stepping); FrameStepBar refactor is
  compile-verified plus simulator check on BOTH players.
- Shared theme repository: commonTest roundtrip.
- Android: full suite green; the backported copy change updates its test.
- iOS: full XCTest suite green; simulator walkthrough (remote clip stepping,
  match-rallies pull-to-refresh, theme toggle flips appearance and persists across
  relaunch, icon visible on the home screen).

## Out of scope

- Custom fullscreen/orientation handling (revisit with milestone 3+).
- Analyze pipeline (milestone 3).
- Android AuthGate migration to shared `authState`.
