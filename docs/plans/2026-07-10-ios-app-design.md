# iOS App — Remote-Clips Parity + Shared Versioning (Design)

Date: 2026-07-10
Status: Approved

## Goal

Ship the first iOS app for badminton-rally-mobile: a SwiftUI client with parity for the
remote-clips feature set (sign-in, clip list, clip detail with annotations, match sharing),
consuming the existing `:shared` KMP module unchanged. Alongside it, introduce a single
shared version source so any commit builds Android and iOS with identical versions.

## Context

- The `:shared` module already declares `iosX64`/`iosArm64`/`iosSimulatorArm64` targets
  producing a static `Shared` framework; `compileKotlinIosArm64` passes today and CI
  link-checks `linkDebugFrameworkIosSimulatorArm64` on macOS runners.
- `commonMain` has no Android leakage and no `expect`/`actual` gaps. All business logic
  (six repositories, Supabase client factory, models, session persistence, TUS upload,
  processing polling) is iOS-ready. `RallyApp` is the composition root.
- All video analysis is server-side (Supabase Edge Function `process-video`); no on-device
  ML. The backend is platform-agnostic (RLS keys off `auth.uid()`).
- Presentation logic lives entirely in `androidApp` (7 Android ViewModels, ~4.4k LOC).
  None of it is shared; iOS builds its own thin presentation layer per PLAN.md "Approach A".
- Auth is email/password only. Google OAuth exists in the shared repo but is unwired;
  registration is closed.

## Decisions

1. **Scope: remote-clips parity first.** Local video intake, the analyze pipeline, court
   marking, and frame-accurate stepping are follow-up milestones. OAuth/Apple sign-in and
   App Store release automation are out of scope.
2. **Versioning: single shared version file** read by both platforms, plus git tags.
3. **Architecture: native SwiftUI + SKIE.** No shared ViewModels, no Compose Multiplatform.
   SwiftUI consumes the shared repositories directly; SKIE bridges Flows to AsyncSequence.

## 1. Version sync

Single source of truth: `Config/Version.xcconfig` at the repo root:

```
MARKETING_VERSION = 0.2.0
CURRENT_PROJECT_VERSION = 9
```

- **Xcode** includes it as a build configuration file; `MARKETING_VERSION` →
  `CFBundleShortVersionString`, `CURRENT_PROJECT_VERSION` → `CFBundleVersion`.
- **Android** (`androidApp/build.gradle.kts`) parses the same file at configuration time:
  `MARKETING_VERSION` → `versionName`, `CURRENT_PROJECT_VERSION` → `versionCode`.
  The hardcoded `versionName`/`versionCode` are removed.
- **Git tags** `vX.Y.Z` mark release commits. A CI job triggered on tag push fails if the
  tag doesn't match `MARKETING_VERSION` in the file.
- Both apps show `version (build)` — e.g. `0.2.0 (9)` — in the sign-in screen footer, so
  sync between installed builds is verifiable at a glance. Android reads it from
  `BuildConfig`; iOS from the bundle's Info.plist values.

"In sync" is thereby a property of the commit: two builds from the same commit always
carry the same version; two builds with the same displayed version came from the same
tagged release line.

## 2. iOS project structure

- New `iosApp/` directory with a committed plain `.xcodeproj` (no XcodeGen/Tuist),
  SwiftUI lifecycle, minimum deployment target iOS 17, iPhone-first.
- Framework integration via the standard KMP run-script build phase invoking
  `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.
- **SKIE** (Touchlab) added to `shared/build.gradle.kts` so Kotlin `Flow`s surface as
  Swift `AsyncSequence`, sealed classes as Swift enums, and suspend functions as
  async/await with cancellation.
- **Config:** `iosApp/Config/Supabase.xcconfig` (gitignored) holds `SUPABASE_URL` and
  `SUPABASE_ANON_KEY`, with a committed `Supabase.xcconfig.example` mirroring
  `local.properties.example`. Values flow through Info.plist keys into Swift and are
  passed to the existing `SupabaseConfig`/`RallyApp`.
- **Entry point:** the SwiftUI `App` instantiates `RallyApp` once (matching
  `RallyAndroidApp`'s manual-DI approach) and injects it into the environment. No new DI
  framework on either platform.

Directory sketch:

```
iosApp/
  iosApp.xcodeproj
  Config/
    Supabase.xcconfig          (gitignored)
    Supabase.xcconfig.example
  Sources/
    RallyIOSApp.swift          (App entry, RallyApp instantiation)
    AuthGateView.swift
    SignIn/
    ClipList/
    ClipDetail/
    Share/
    Theme/                     (Shuttl design tokens in SwiftUI)
    Components/                (error banner, badges, buttons)
Config/
  Version.xcconfig             (repo root — shared with Android)
```

## 3. Screens (remote-clips parity)

SwiftUI counterparts of the existing Android features, each a thin view + observable
view-state over the shared repositories:

- **Auth gate** — observes `AuthRepository.sessionFlow` (as AsyncSequence via SKIE);
  routes between splash, sign-in, and the main NavigationStack, mirroring `AuthGate.kt`.
- **Sign-in** — email/password via `AuthRepository.signInEmail`; same closed-registration
  messaging and error mapping as `AuthErrorMessages.kt`; version footer (section 1).
- **Clip list** — clips grouped by match via `ClipsRepository.observeClips`; thumbnails
  from `MediaRepository.signedThumbnailUrl` rendered with `AsyncImage`; pull-to-refresh
  calling `refresh()`; received-shares section per Android's `ClipListScreen`.
- **Match clips** — clips of a single match, mirroring `MatchClipsScreen`.
- **Clip detail** — AVPlayer (`VideoPlayer` / `AVPlayerViewController` for fullscreen)
  playing `MediaRepository.signedClipUrl`; annotation list with kind badges
  (good shot / forced error / unforced error), tap-to-seek, add-annotation sheet, delete;
  clip title shown read-only, matching Android (`ClipsRepository.updateTitle` exists but
  is unused by clip detail on either platform). Frame-accurate stepping is NOT in scope
  (belongs to the local-video milestone).
- **Share sheet** — modal sheet for email-based match sharing over `SharesRepository`
  (share, unshare, list), mirroring `ShareSheet.kt`; errors map through the shared
  `ShareError` hierarchy.
- **Theme** — Shuttl design tokens (colors incl. dark mode, typography, shapes) ported
  from `ui/theme/*` as SwiftUI constants; reusable components: error banner, annotation
  kind badge, primary/secondary buttons, text fields.

## 4. CI

Additions to `.github/workflows/ci.yml`:

- **`ios-app`** (macos-14): after linking the shared framework, run `xcodebuild build`
  for the iOS Simulator destination against `iosApp.xcodeproj` (using the example
  Supabase config), so Swift compilation is gated on every push/PR like the other jobs.
- **`version-tag-check`** (tag pushes matching `v*`): fail if the tag version ≠
  `MARKETING_VERSION` in `Config/Version.xcconfig`.

## 5. Testing & error handling

- Shared logic keeps its existing commonTest suite (already compiles for iOS).
- Swift view-state logic beyond trivial binding (e.g. clip grouping, error mapping,
  annotation sorting) gets XCTest unit tests in an `iosAppTests` target, run in the
  `ios-app` CI job.
- Error handling mirrors Android: auth errors map to the same user-facing messages;
  repository failures render in the shared-style error banner; `ShareError` cases get
  the same copy as `ShareSheetViewModel`.

## Environment prerequisites

- Xcode is installed at `/Applications/Xcode.app`, but `xcode-select` points at Command
  Line Tools. Before the first build: `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.
- No Apple Developer account needed for this milestone (simulator builds only).

## Out of scope (future milestones)

- Local video record/import, upload/analyze pipeline, local annotations (milestone 2).
- Court marking and frame-accurate stepping (milestone 3).
- OAuth (Google) and Sign in with Apple; the `badmintontracker://login` URL scheme.
- App Store signing, TestFlight, release automation for either store.
- Promoting `AnalyzeCoordinator`/local repositories into `:shared` (decide at milestone 2).
