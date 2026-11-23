# Drill Visuals Implementation Plan

> **For agentic workers:** Execute inline (Native mode approved by Coen) on branch `feat/exercise-suggestions` following TDD.

**Goal:** Add animated native-Canvas court movement diagrams and articulated badminton pose animations inside every expanded card on the Drills panel across Android and iOS, driven by one shared `:shared` visual specification.

**Architecture:** `shared/exercises/ExerciseVisuals.kt` owns every drill's half-court waypoints, pose keyframes, smoothstep sampling, and left-hand mirroring. Android (`ExerciseVisualView.kt`) and iOS (`ExerciseVisualView.swift`) draw the sampled court and articulated figure natively on Canvas using Shuttl theme tokens, animating only while expanded and honouring Reduce Motion.

**Tech Stack:** Kotlin Multiplatform (`:shared`, kotest assertions), Jetpack Compose Canvas (Foundation 1.7.6), SwiftUI Canvas + `TimelineView`, XcodeGen, XCTest.

**Spec:** `docs/plans/2026-11-23-drill-visuals-design.md`

## Global Constraints
- Branch `feat/exercise-suggestions`, no em dashes in code/comments/copy/commits, no player pronouns in copy, no `.font(` in iOS outside `Theme/` allowlist, Compose Foundation 1.7.6, Swift calls explicit non-default functions, `xcodegen generate` for new Swift files.

---

### Task 1: `:shared` `ExerciseVisuals` Data & Sampling Engine
**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseVisuals.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseVisualsTest.kt`

- [ ] Write failing `ExerciseVisualsTest.kt` pinning: every `ExerciseCatalog.all` ID has a visual; all 11 on-court drills have non-null `courtPath` with >= 2 waypoints inside half-court bounds; both off-court exercises have `courtPath == null` and no `racketTip`; every pose keyframe has non-blank label and all joints in `[0, 1]`; `samplePose` interpolates smoothly and mirrors across `x = 0.5` when `mirrorX = true`; `sampleCourtPoint` wraps cleanly for phases outside `[0, 1]` and mirrors across `Court.WIDTH_DOUBLES / 2` when `mirrorX = true`; zero em dashes or player pronouns in keyframe labels.
- [ ] Run `:shared:jvmTest --tests "*ExerciseVisualsTest*"` -> verify RED.
- [ ] Implement `ExerciseVisuals.kt` with clean, biomechanically accurate poses and half-court paths for all 13 exercises.
- [ ] Run `:shared:jvmTest` -> verify GREEN, then commit.

### Task 2: Android `ExerciseVisualView` & `ExercisesPanel` Wiring
**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExerciseVisualView.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExercisesPanel.kt`

- [ ] Implement `ExerciseVisualView(exerciseId: String, racketArm: RacketArm?, modifier: Modifier)` rendering side-by-side Half-Court + Pose tiles for on-court drills and a full-width Pose tile for off-court exercises, animating via `rememberInfiniteTransition` only when composed (expanded) and supporting Reduce Motion tap-to-step.
- [ ] Wire `ExerciseVisualView` into `ExerciseCard`'s `if (expanded)` block right above `Setup`, passing `profileArm(shownSide, sideNames)`.
- [ ] Run `:androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease` + `tools/check-container-colors.sh` -> verify GREEN, then commit.

### Task 3: iOS `ExerciseVisualView`, `ExercisesPanel` Wiring, XcodeGen & Simulator Verification
**Files:**
- Create: `iosApp/Sources/LocalAnalysis/ExerciseVisualView.swift`
- Modify: `iosApp/Sources/LocalAnalysis/ExercisesPanel.swift`
- Modify: `iosApp/Tests/ExercisesPanelTests.swift`
- Regenerate: `iosApp/iosApp.xcodeproj`

- [ ] Add Swift bridge test in `ExercisesPanelTests.swift` verifying `ExerciseVisualsKt.visualFor(exerciseId:)`, `samplePose`, and `sampleCourtPoint` work cleanly from Swift for both `.right` and `.left` mirroring.
- [ ] Implement `ExerciseVisualView.swift` (zero `.font(`, `.shuttlType(...)` everywhere, `TimelineView(.animation(paused: reduceMotion))`, tap-to-step when Reduce Motion is on) and wire into `ExerciseCard` in `ExercisesPanel.swift`.
- [ ] Run `cd iosApp && xcodegen generate`, `xcodebuild build-for-testing`, `xctest` (`ExercisesPanelTests` + `TypographyAdoptionTests`), install & launch in `iPhone 17 Pro` simulator, and verify visually.
- [ ] Commit Task 3.
