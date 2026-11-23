# Post-Analysis Exercise Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive honest movement and positioning metrics from a completed video analysis (`PlayerTrack` + bounded `RallyWindow`s) and surface 1 to 4 ranked, evidence-backed badminton exercise suggestions inside the Match Page Analysis section on both Android and iOS.

**Architecture:** Three clean layers with zero backend, database, or ML pipeline changes. `:analysis` computes a player-relative `MovementProfile` from already-stored court-plane ankle tracks and rally windows. `:shared` evaluates quality gates and 7 deterministic singles rules against a curated catalog of 13 original exercises, plus exposes `AnalyticsPanel.Drills` and all copy. Android (`ExercisesPanel.kt`) and iOS (`ExercisesPanel.swift`) run the evaluation off the main thread and render identical summary tiles, expandable cards, empty/insufficient states, and `SideToggle` switching.

**Tech Stack:** Kotlin Multiplatform (`:analysis`, `:shared`, kotest assertions), Jetpack Compose (Foundation **1.7.6**, Material 3, `ShuttlTheme`, `ShuttlStatTile`, `ShuttlPillTabs`), SwiftUI (`Shuttl`, `ShuttlType`, `ShuttlStatTile`, `SideToggle`, SKIE 0.10.13), XcodeGen, XCTest.

**Spec:** `docs/plans/2026-11-22-exercise-suggestions-design.md`

## Global Constraints

- Stay on the current branch (`feat/player-profiles`). Never push or rebase.
- No em dashes anywhere in code comments, user-facing copy, or commit messages. Use `" - "` or plain punctuation.
- No pronouns (`he`, `she`, `they`, `their`, `his`, `her`) for a player in user-facing copy.
- Every rule, threshold, exercise description, formatted measurement, and user-facing sentence lives in `:analysis` or `:shared` (`shared/exercises/`). Android and iOS only render and dispatch.
- Swift sees no Kotlin default arguments: every `:analysis` / `:shared` entry point Swift calls must either be invoked with all parameters explicit or expose a no-default overload.
- Compose: nothing from Foundation 1.8+. Run `:androidApp:assembleRelease` and `tools/check-container-colors.sh` on Android UI tasks.
- iOS: no `.font(` anywhere outside `Theme/` and the `TypographyAdoptionTests` allowlist (including comments). Always use `.shuttlType(...)`. After adding any new Swift file, run `cd iosApp && xcodegen generate` and verify its `.o` exists in `iosApp/build/DerivedData`.
- Commits: no `Co-Authored-By` trailer.
- Build environment on this Mac:
  - Always set `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
  - For iOS also set `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` and run `xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null`.
  - Always inspect explicit pass/fail lines (`BUILD SUCCESSFUL`, `BUILD SUCCEEDED`, `Executed ... 0 failures`).
- Commands:
  - Analysis tests: `./gradlew --offline :analysis:jvmTest --tests "*<Class>*"`
  - Shared tests: `./gradlew --offline :shared:jvmTest --tests "*<Class>*"`
  - Android gate: `./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease`
  - iOS build: `xcodebuild build-for-testing -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`
  - iOS unit tests (boot simulator first; `xcodebuild test` hangs on macOS 27.2 / Xcode 26.6):
    ```bash
    P=$PWD/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator
    PL=$DEVELOPER_DIR/Platforms/iPhoneSimulator.platform/Developer
    SIMCTL_CHILD_DYLD_FRAMEWORK_PATH="$P/iosApp.app/Frameworks:$P:$PL/Library/Frameworks:$PL/usr/lib:$P/iosApp.app" \
    SIMCTL_CHILD_DYLD_LIBRARY_PATH="$P/iosApp.app:$P:$PL/usr/lib:$PL/Library/Frameworks" \
    xcrun simctl spawn booted "$PL/Library/Xcode/Agents/xctest" -XCTest <Class> "$P/iosApp.app/PlugIns/iosAppTests.xctest"
    ```

## Review Focus

Five edge/failure modes the spec implies that are pinned by explicit tests below:

1. **Unsorted or duplicate-frame samples (VFR video):** `movementProfile` must sort samples by frame and never divide by zero when consecutive samples share a frame number (pinned in Task 1 `MovementProfileTest`).
2. **Tracking dropout (`gap > 0.5 s`) inside a rally:** A gap longer than `MAX_GAP_S` (0.5 s) followed by a distant position must reset path resampling, split contiguous speed runs, and cap zone dwell at 0.5 s rather than fabricating bursts or huge jumps (pinned in Task 1 `MovementProfileTest`).
3. **Degenerate inputs (`fps <= 0`, empty track, unbounded/overlapping windows):** `movementProfile` returns an empty zeroed profile with no `NaN`/exception, and `adviseExercises` returns `INSUFFICIENT_DATA` (pinned in Tasks 1 and 3).
4. **Simultaneous multi-rule triggers, exact threshold boundaries, and deduplication:** Values exactly at a strict `>` or `<` threshold must not trigger; 5+ simultaneous findings must cap at 4 unique exercises ordered by severity then `FindingKind.ordinal`; a single finding includes its secondary exercise (pinned in Task 3 `ExerciseAdvisorTest`).
5. **Two-player cloud switching where one side is valid and the other is thin/empty, or has a different `RacketArm`:** Switching `SideToggle` must keep the toggle visible above the message ladder and recompute with the shown side's own `RacketArm` (pinned in Tasks 5 and 6).

---

## File Structure

- **`:analysis`**
  - Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/MovementProfile.kt` - `MovementProfile` model and pure `movementProfile(...)` computation.
  - Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/MovementProfileTest.kt` - unit tests for all metrics, NEAR/FAR mirroring, gaps, VFR duplicates, and degenerate inputs.
- **`:shared`**
  - Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseModels.kt` - Swift-friendly data classes and enums (`ExerciseCategory`, `FocusArea`, `FindingKind`, `ExerciseReportStatus`, `Exercise`, `ExerciseSuggestion`, `MovementSummary`, `ExerciseReport`).
  - Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExercisesCopy.kt` - all panel labels, status messages, evidence helpers, and honesty caption.
  - Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalog.kt` - 13 original curated exercises (11 on-court, 2 off-court) indexed by stable `id`.
  - Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisor.kt` - quality gates, 7 rules, handedness-aware evidence copy, ranking, deduplication, and `adviseExercises(...)`.
  - Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalogTest.kt` - catalog invariants (unique IDs, required fields, non-blank steps/cues, no em dashes or player pronouns).
  - Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisorTest.kt` - gates, all 7 rules + exact boundaries, handedness wording, ranking, cap, and secondary inclusion.
  - Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsPanel.kt` - add `Drills("Drills")` and return it in `availablePanels` when `hasTrack && hasBoundedClips`.
  - Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalysisCapability.kt` - add `PanelCapability` row for `AnalyticsPanel.Drills`.
  - Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageCopy.kt` - update `ANALYSIS_PANELS` doc comment.
  - Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AvailablePanelsTest.kt` - expect `Drills` alongside `Base`.
- **`androidApp`**
  - Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExercisesPanel.kt` - `ExercisesPanel` composable + pure `exercisesMessage(...)` ladder.
  - Modify: `androidApp/src/main/java/com/badmintontracker/android/match/AnalysisSection.kt` - wire `AnalyticsPanel.Drills -> ExercisesPanel(...)`.
  - Create: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/ExercisesPanelTest.kt` - `exercisesMessage` ladder + `profileArm` isolation tests.
  - Modify: `androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt` - update expected panel lists for `AnalyticsPanel.Drills`.
- **`iosApp`**
  - Create: `iosApp/Sources/LocalAnalysis/ExercisesPanel.swift` - `ExercisesPanel` view + pure `exercisesMessage(...)` ladder.
  - Modify: `iosApp/Sources/Match/AnalysisSection.swift` - wire `case .drills: ExercisesPanel(...)`.
  - Create: `iosApp/Tests/ExercisesPanelTests.swift` - `exercisesMessage` ladder + side/arm switching logic tests.
  - Modify: `iosApp/Tests/CloudAnalysisPanelsTests.swift` - update expected panel lists for `.drills`.
  - Regenerate: `iosApp/iosApp.xcodeproj` via `xcodegen generate`.

---

### Task 1: `:analysis` `MovementProfile` Measurement Engine

**Files:**
- Commit spec: `docs/plans/2026-11-22-exercise-suggestions-design.md`, `docs/plans/2026-11-22-exercise-suggestions-plan.md`
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/MovementProfile.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/MovementProfileTest.kt`

**Interfaces:**
- Consumes: `Court` (`CourtGeometry.kt`), `PlayerTrack`, `PlayerSample`, `CourtSide` (`NearPlayer.kt`, `PlayerTrack.kt`), `RallyWindow`, `BasePositions`, `basePositions`, `MIN_RALLY_SECONDS` (`BasePosition.kt`), `RallyFrames` (`RallyFrames.kt`), `CourtOccupancy.MAX_GAP_S` (`CourtOccupancy.kt`).
- Produces:
  ```kotlin
  package com.badmintontracker.analysis.player

  const val MIN_TREND_RALLIES: Int = 6
  const val FRONT_ZONE_MAX_M: Double = 2.2
  const val MID_ZONE_MAX_M: Double = 4.5
  const val PATH_STEP_S: Double = 0.25
  const val SPEED_WINDOW_MIN_S: Double = 0.45
  const val SPEED_WINDOW_MAX_S: Double = 0.65
  const val MAX_PLAUSIBLE_SPEED_MPS: Double = 8.0

  data class MovementProfile(
      val countedRallies: Int,
      val rallyCoverage: Double,
      val totalRallySeconds: Double,
      val overallBehindServiceLineM: Double?,
      val overallLateralFromCentreM: Double?,
      val baseSpreadM: Double?,
      val baseDepthDriftM: Double?,
      val frontShare: Double,
      val midShare: Double,
      val rearShare: Double,
      val leftShare: Double,
      val rightShare: Double,
      val medianRallyPathM: Double?,
      val medianPeakSpeedMps: Double?,
      val earlyPeakSpeedMps: Double?,
      val latePeakSpeedMps: Double?,
      val speedDropFraction: Double?,
  )

  fun movementProfile(
      track: PlayerTrack,
      fps: Double,
      windows: List<RallyWindow>,
      side: CourtSide,
      minSeconds: Double = MIN_RALLY_SECONDS,
  ): MovementProfile
  ```

- [ ] **Step 1: Write failing tests in `MovementProfileTest.kt`**

Write unit tests in `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/MovementProfileTest.kt` using `kotlin.test.Test` and `io.kotest.matchers` covering:
1. `near_and_far_sides_mirror_depth_and_lateral_coordinates`:
   - `NEAR` standing at `(3.55, 10.68)` (`behind = 2.00 m`, `lateral = +0.50 m`) and `FAR` standing at `(2.55, 2.72)` (`behind = 2.00 m`, `lateral = +0.50 m`) across 2 rallies of 2 s at 30 fps both yield `overallBehindServiceLineM == 2.0` (±1e-6), `overallLateralFromCentreM == 0.5` (±1e-6), `midShare == 1.0`, `rightShare == 1.0`.
2. `zone_shares_split_front_mid_rear_and_left_right_with_gap_capping`:
   - 1 rally of 3 s at 10 fps with 1 s in front-left (`y = 8.0, x = 2.0` -> `depth = 1.3`, `lat = -1.05`), 1 s in mid-right (`y = 10.0, x = 4.0` -> `depth = 3.3`, `lat = +0.95`), 1 s in rear-right (`y = 12.0, x = 4.0` -> `depth = 5.3`, `lat = +0.95`). Assert `frontShare + midShare + rearShare` within `1e-6` of `1.0`, each ~`1/3`, `leftShare` ~`1/3`, `rightShare` ~`2/3`.
   - Also test a 2.0 s tracking dropout inside a rally: the sample before the gap is credited at most `0.5 s` (`CourtOccupancy.MAX_GAP_S`), not `2.0 s`.
3. `base_spread_and_depth_drift_require_minimum_rallies`:
   - 5 rallies at increasing depths (`1.0, 1.2, 1.4, 1.6, 1.8 m` behind service line) -> `countedRallies == 5`, `baseSpreadM` > 0 (`0.2` m), `baseDepthDriftM == null` (`< 6` rallies).
   - 6 rallies (`1.0, 1.0, 1.5, 1.5, 2.0, 2.0 m` behind service line) -> `countedRallies == 6`, `baseDepthDriftM == 1.0` (±1e-6).
4. `path_length_and_peak_speed_and_late_speed_drop`:
   - 6 rallies of 2 s at 20 fps: first 2 rallies move at constant `4.0 m/s` (in `y`), middle 2 at `3.0 m/s`, last 2 at `2.0 m/s`. Assert `earlyPeakSpeedMps` ~ `4.0`, `latePeakSpeedMps` ~ `2.0`, `speedDropFraction` ~ `0.5`, `medianPeakSpeedMps` ~ `3.0`, and `medianRallyPathM` matches 0.25 s resampled travel (`~6.0 m` on a 2 s `3.0 m/s` rally within `0.8 m`).
5. `dropout_and_teleport_do_not_fabricate_path_or_speed`:
   - Inside a rally, player stands at `(3.0, 9.0)` for 1 s, disappears for 1.0 s (`> 0.5 s`), then stands at `(3.0, 12.5)` for 1 s. Assert `medianRallyPathM == 0.0` and `medianPeakSpeedMps == 0.0`.
   - Also test a single-window 0.5 s jump of `6.0 m` (`12.0 m/s > MAX_PLAUSIBLE_SPEED_MPS`): rejected from peak speed.
6. `unsorted_and_duplicate_vfr_frames_do_not_divide_by_zero`:
   - Shuffled samples with repeated identical frame indices yield valid finite `MovementProfile` with no `NaN` or exceptions.
7. `degenerate_inputs_return_empty_zeroed_profile`:
   - `fps = 0.0`, empty `PlayerTrack`, and unbounded `RallyWindow(1, 0.0, 0.0)` each return `countedRallies == 0`, `rallyCoverage == 0.0`, `totalRallySeconds == 0.0`, nullable fields `null`, shares `0.0`.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :analysis:jvmTest --tests "*MovementProfileTest*"
```
Expected: FAIL (unresolved reference `movementProfile` / `MovementProfile`).

- [ ] **Step 3: Implement `MovementProfile.kt`**

Create `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/MovementProfile.kt`:
- Sort `track.samples` once by `frame` and deduplicate exact duplicate frames by keeping the first sample for each frame index when computing path/speed (while preserving raw sorted samples for `basePositions` and `RallyFrames`/`dt` dwell so `totalRallySeconds` and `rallyCoverage` stay identical to `CourtOccupancy` and `BasePositionPanel`).
- Compute `bases = basePositions(track, fps, windows, minSeconds)`.
- Compute `rallyFrames = RallyFrames.of(windows, fps)` and `rallyCoverage = rallyFrames?.coverage(track) ?: 0.0`.
- Compute player-relative `depthFromNet(p, side)` and `lateralFromCentre(p, side)` matching `BasePositionFormat.kt:27-36`.
- Compute zone dwell shares by iterating sorted samples filtered by `rallyFrames`, capping each sample's `frames` to `min(maxFrames, max(1, next.frame - sample.frame))` and clamping to `rallyEnd - sample.frame + 1` (same logic as `CourtOccupancy.addAll`, `MAX_GAP_S = 0.5`). Note: for duplicate VFR frames where `next.frame == sample.frame`, use `0.0` for the earlier duplicate or `max(1, ...)` matching `CourtOccupancy.kt:74`; using `CourtOccupancy`'s exact `max(1, next.frame - sample.frame)` keeps 100% consistency with the Heatmap panel.
- For each qualifying rally (same `windows.sortedBy { it.startSeconds }` filter as `basePositions`: `isBounded` and `count >= ceil(minSeconds * fps)`), collect its deduplicated-by-frame samples inside `w.frames(fps)` and compute:
  - `pathM`: anchor at first sample; when `(cur.frame - prev.frame) / fps > CourtOccupancy.MAX_GAP_S` reset anchor to `cur`; else when `(cur.frame - anchor.frame) / fps >= PATH_STEP_S` add `hypot(cur - anchor)` and advance anchor to `cur`.
  - `peakSpeedMps`: split into contiguous runs wherever `(cur.frame - prev.frame) / fps > CourtOccupancy.MAX_GAP_S`; within each run, for each `i`, advance `j` to first index with `dt = (run[j].frame - run[i].frame) / fps >= SPEED_WINDOW_MIN_S`; if `dt <= SPEED_WINDOW_MAX_S`, compute `v = hypot(run[j] - run[i]) / dt` and keep if `v <= MAX_PLAUSIBLE_SPEED_MPS`.
- Compute `baseSpreadM` (median Euclidean distance from `bases.overall` when `bases.rallies.size >= 2`), `baseDepthDriftM` (first-third vs last-third median of `behindServiceLineM` with `k = n / 3` when `n >= MIN_TREND_RALLIES`), and `earlyPeakSpeedMps` / `latePeakSpeedMps` / `speedDropFraction` (requiring at least 2 non-null rally peak speeds in both thirds, and `early > 0.0`).

- [ ] **Step 4: Run `:analysis:jvmTest` to verify it passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :analysis:jvmTest --tests "*MovementProfileTest*"
```
Expected: `BUILD SUCCESSFUL` with all tests in `MovementProfileTest` passing.

- [ ] **Step 5: Commit Task 1 (including design & plan docs)**

```bash
git add docs/plans/2026-11-22-exercise-suggestions-design.md docs/plans/2026-11-22-exercise-suggestions-plan.md \
  analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/MovementProfile.kt \
  analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/MovementProfileTest.kt
git commit -m "feat(analysis): compute player-relative MovementProfile from stored tracks"
```

---

### Task 2: `:shared` Exercise Models, Copy & Curated Catalog

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseModels.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExercisesCopy.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalog.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalogTest.kt`

**Interfaces:**
- Consumes: nothing outside Kotlin stdlib.
- Produces:
  ```kotlin
  package com.badmintontracker.shared.exercises

  enum class ExerciseCategory(val label: String) {
      ON_COURT("On-court"),
      OFF_COURT("Off-court"),
  }

  enum class FocusArea(val label: String) {
      BASE_RECOVERY("Base recovery"),
      COURT_COVERAGE("Court coverage"),
      LATERAL_BALANCE("Lateral balance"),
      ENDURANCE("Endurance"),
      EXPLOSIVE_SPEED("Explosive speed"),
  }

  enum class FindingKind {
      DEEP_BASE,
      SHALLOW_BASE,
      LATERAL_BIAS,
      BASE_DRIFT_LATE,
      INCONSISTENT_BASE,
      LOW_FRONT_PRESENCE,
      LATE_SPEED_DROP,
  }

  enum class ExerciseReportStatus {
      INSUFFICIENT_DATA,
      BALANCED,
      SUGGESTIONS,
  }

  data class Exercise(
      val id: String,
      val title: String,
      val category: ExerciseCategory,
      val focus: FocusArea,
      val prescription: String,
      val setup: String,
      val steps: List<String>,
      val coachingCue: String,
  )

  data class ExerciseSuggestion(
      val exercise: Exercise,
      val finding: FindingKind,
      val evidence: String,
      val severity: Double,
  )

  data class MovementSummary(
      val countedRallies: Int,
      val coveragePercent: Int,
      val peakSpeedText: String?,
      val frontPercent: Int,
      val midPercent: Int,
      val rearPercent: Int,
  )

  data class ExerciseReport(
      val status: ExerciseReportStatus,
      val message: String?,
      val summary: MovementSummary?,
      val suggestions: List<ExerciseSuggestion>,
  )
  ```
  `ExerciseCatalog` exposes `val all: List<Exercise>` (13 entries matching the 13 slugs in Spec Section 5.3) and `fun byId(id: String): Exercise`.
  `ExercisesCopy` exposes:
  - `SUMMARY_HEADING = "MOVEMENT SUMMARY"`
  - `SUGGESTIONS_HEADING = "SUGGESTED EXERCISES"`
  - `RALLIES_LABEL`, `PEAK_SPEED_LABEL = "Peak speed (0.5 s)"`, `SETUP_LABEL = "Setup"`, `CUE_LABEL = "Cue"`
  - `HONESTY_CAPTION = "Based on singles rally movement from this video. Doubles play and isolated feeding drills may skew positioning."`
  - `NOT_LOADED = "This analysis is no longer loaded. Run it again to see suggested exercises."`
  - `NO_BOUNDED_WINDOWS = "The rally windows for this analysis were not kept, so there is nothing to measure per rally. Run the analysis again."`
  - `NO_COUNTED_RALLIES = "Not enough tracked rally movement in this video to suggest exercises."`
  - `fun ralliesCaption(coveragePercent: Int): String` (`"$coveragePercent% tracked"`)
  - `fun zoneBreakdown(frontPercent: Int, midPercent: Int, rearPercent: Int): String` (`"Front $frontPercent% · Mid $midPercent% · Rear $rearPercent%"`)
  - `fun insufficientData(rallies: Int, coveragePercent: Int, minRallies: Int, minCoveragePercent: Int): String`
  - `fun balanced(rallies: Int): String`

- [ ] **Step 1: Write failing tests in `ExerciseCatalogTest.kt`**

Write `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalogTest.kt` verifying:
1. `catalog_contains_all_thirteen_unique_exercises`: `ExerciseCatalog.all.size shouldBe 13`, all `id`s distinct, 11 `ON_COURT` and 2 `OFF_COURT`, every slug from Spec Section 5.3 resolves via `ExerciseCatalog.byId(id)`.
2. `every_exercise_has_complete_concise_copy`: every exercise has non-blank `title`, `prescription`, `setup`, `coachingCue`, and `steps.size in 3..4` with non-blank steps.
3. `catalog_and_copy_contain_no_em_dashes_or_player_pronouns`: scan every string in `ExerciseCatalog.all` and `ExercisesCopy` constants/helpers for absence of `\u2014` (em dash) and whole-word player pronouns (`he`, `she`, `his`, `her`, `they`, `their`).

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest --tests "*ExerciseCatalogTest*"
```
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement `ExerciseModels.kt`, `ExercisesCopy.kt`, and `ExerciseCatalog.kt`**

Create the three files under `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/` matching the exact signatures above and the 13 drills in Spec Section 5.3. Keep every setup to 1-2 crisp lines, 3-4 imperative steps, 1 crisp coaching cue, plain language, zero em dashes, zero player pronouns.

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest --tests "*ExerciseCatalogTest*"
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseModels.kt \
  shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExercisesCopy.kt \
  shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalog.kt \
  shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseCatalogTest.kt
git commit -m "feat(shared): add exercise models, copy, and curated 13-drill catalog"
```

---

### Task 3: `:shared` `ExerciseAdvisor` Rule Engine & Formatting

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisor.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisorTest.kt`

**Interfaces:**
- Consumes: `MovementProfile`, `movementProfile`, `PlayerTrack`, `RallyWindow`, `CourtSide`, `MIN_RALLY_SECONDS`, `MIN_TREND_RALLIES` (`:analysis`), `RacketArm` (`shared/prefs`), `metres` (`shared/local/MetricsFormat.kt`), `ExerciseModels`, `ExerciseCatalog`, `ExercisesCopy`.
- Produces:
  ```kotlin
  package com.badmintontracker.shared.exercises

  object ExerciseThresholds {
      const val MIN_RALLIES = 4
      const val MIN_COVERAGE = 0.50
      const val MAX_SUGGESTIONS = 4
      const val DEEP_BASE_M = 2.00
      const val SHALLOW_BASE_M = 0.75
      const val LATERAL_OFFSET_M = 0.40
      const val LATERAL_SKEW_SHARE = 0.65
      const val BASE_DRIFT_M = 0.45
      const val BASE_SPREAD_M = 0.85
      const val REAR_HEAVY_SHARE = 0.55
      const val FRONT_LOW_SHARE = 0.12
      const val MIN_EARLY_SPEED_MPS = 2.5
      const val SPEED_DROP_FRACTION = 0.20
  }

  /** Evaluates a precomputed [MovementProfile] against the 7 singles rules. */
  fun adviseExercises(profile: MovementProfile, racketArm: RacketArm?): ExerciseReport

  /** End-to-end entry point for Android and iOS panels (no default args for Swift ergonomics). */
  fun adviseExercisesForTrack(
      track: PlayerTrack,
      fps: Double,
      windows: List<RallyWindow>,
      side: CourtSide,
      racketArm: RacketArm?,
  ): ExerciseReport
  ```

- [ ] **Step 1: Write failing tests in `ExerciseAdvisorTest.kt`**

Write `shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisorTest.kt` using a clean `profile(...)` helper (defaults to a balanced 8-rally profile: `coverage = 0.85`, `behind = 1.40`, `lateral = 0.05`, `spread = 0.35`, `drift = 0.10`, `front = 0.20, mid = 0.50, rear = 0.30`, `left = 0.50, right = 0.50`, `early = 4.2, late = 4.0, drop = 0.05`) covering:
1. `insufficient_data_gates`:
   - `countedRallies = 3, coverage = 0.90` -> `INSUFFICIENT_DATA`, `suggestions` empty, `summary != null`, message mentions `"4"` and `"3"`.
   - `countedRallies = 6, coverage = 0.49` -> `INSUFFICIENT_DATA`.
   - `countedRallies = 0` -> `INSUFFICIENT_DATA`, `summary == null`.
   - `countedRallies = 4, coverage = 0.50` -> passes gate (`BALANCED`).
2. `balanced_profile_returns_no_suggestions`:
   - Balanced 8-rally profile returns `BALANCED`, `suggestions` empty, `message` mentions `"8 rallies"`, `summary` has `frontPercent + midPercent + rearPercent == 100` and `peakSpeedText == "4.1"`.
3. `each_of_the_seven_rules_triggers_above_threshold_and_not_at_exact_boundary`:
   - `DEEP_BASE`: `2.00` does NOT trigger; `2.01` triggers `DEEP_BASE` with primary `"base-mark-6-corner-shadow"` + secondary `"clear-to-base-multi-shuttle"` (2 cards since single finding).
   - `SHALLOW_BASE`: `0.75` does NOT trigger; `0.74` triggers `SHALLOW_BASE` with `"net-to-rear-recovery"` + `"front-back-2-corner-feed"`.
   - `LATERAL_BIAS`: `lateral = 0.40, rightShare = 0.64` does NOT trigger; `lateral = 0.41` triggers; `lateral = 0.20, leftShare = 0.65` triggers.
   - `BASE_DRIFT_LATE`: `countedRallies = 5, drift = 0.80` does NOT trigger (`< 6`); `countedRallies = 6, drift = 0.45` does NOT trigger; `countedRallies = 6, drift = 0.46` triggers `"fatigue-6-corner-shadow"` + `"split-squat-endurance"`.
   - `INCONSISTENT_BASE`: `0.85` does NOT trigger; `0.86` triggers `"cone-touch-base-discipline"` (1 card since no secondary).
   - `LOW_FRONT_PRESENCE`: `rear = 0.55, front = 0.10` does NOT trigger; `rear = 0.60, front = 0.12` does NOT trigger; `rear = 0.56, front = 0.11` triggers `"drop-and-follow-net-attack"` + `"forecourt-lunge-net-kill"`.
   - `LATE_SPEED_DROP`: `early = 2.4, drop = 0.30` does NOT trigger (`early < 2.5`); `early = 3.0, drop = 0.19` does NOT trigger; `countedRallies = 6, early = 3.0, late = 2.4, drop = 0.20` triggers `"repeat-burst-multi-shuttle"` + `"lateral-bounds-split-jumps"`.
4. `lateral_bias_copy_adapts_to_racket_arm`:
   - Positive `lateral = +0.55` with `RacketArm.RIGHT` mentions `"forehand side"` and `"backhand side"`.
   - Positive `lateral = +0.55` with `RacketArm.LEFT` mentions `"backhand side"` and `"forehand side"`.
   - Positive `lateral = +0.55` with `null` mentions `"right of centre"`; negative `-0.55` with `null` mentions `"left of centre"`.
5. `ranking_deduplication_and_four_card_cap`:
   - Profile triggering 5 findings (`DEEP_BASE`, `LATERAL_BIAS`, `BASE_DRIFT_LATE`, `INCONSISTENT_BASE`, `LATE_SPEED_DROP`) returns `suggestions.size == 4`, all `exercise.id`s unique, sorted strictly by descending `severity` (ties broken by `FindingKind.ordinal`), and picks 1 primary exercise per finding rather than expanding secondaries when 2+ findings trigger.
6. `end_to_end_track_advice_works_and_handles_degenerate_input`:
   - `adviseExercisesForTrack` on a synthetic 4-rally deep-base `NEAR` track returns `SUGGESTIONS` containing `DEEP_BASE`.
   - `adviseExercisesForTrack` with `fps = 0.0` returns `INSUFFICIENT_DATA`.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest --tests "*ExerciseAdvisorTest*"
```
Expected: FAIL (unresolved reference `adviseExercises`).

- [ ] **Step 3: Implement `ExerciseAdvisor.kt`**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisor.kt`:
- Use `metres()` from `com.badmintontracker.shared.local.metres` for distance strings and a small private `oneDecimal(value: Double): String` (rounding `(abs(value) * 10).roundToLong()`, avoiding platform `String.format` in `commonMain`, matching `MetricsFormat.kt:31-60`).
- Build `MovementSummary` whenever `profile.countedRallies > 0`:
  - `frontPercent = (profile.frontShare * 100).roundToInt()`, `rearPercent = (profile.rearShare * 100).roundToInt()`, `midPercent = (100 - frontPercent - rearPercent).coerceAtLeast(0)` (ensures displayed front/mid/rear always sums to 100% with no 99%/101% rounding mismatch!).
  - `peakSpeedText = profile.medianPeakSpeedMps?.let { oneDecimal(it) }` (plain number like `"4.6"`, paired with unit `" m/s"` in `ShuttlStatTile`).
- Apply exact severity formulas and selection/cap rules from Spec Section 5.2:
  - Sort triggered findings by `severity` descending, then `finding.ordinal` ascending.
  - First pass: add each finding's primary exercise (deduped by `exercise.id`) up to `MAX_SUGGESTIONS = 4`.
  - Second pass: only when triggered findings size == 1 and that finding has a secondary exercise, append the secondary exercise (with the same finding/evidence/severity).
- Ensure no em dashes and no player pronouns in any generated evidence sentence.

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest --tests "*ExerciseAdvisorTest*"
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit Task 3**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisor.kt \
  shared/src/commonTest/kotlin/com/badmintontracker/shared/exercises/ExerciseAdvisorTest.kt
git commit -m "feat(shared): implement deterministic ExerciseAdvisor rule engine"
```

---

### Task 4: Wire `AnalyticsPanel.Drills` & `AnalysisCapability` in `:shared`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsPanel.kt:4-39`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalysisCapability.kt:34-55`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageCopy.kt:8-9`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AvailablePanelsTest.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AnalysisCapabilityTest.kt` (already pins `panelCapabilities` to `AnalyticsPanel.entries`)

**Interfaces:**
- Produces: `AnalyticsPanel.Drills` (`label = "Drills"`), returned last by `availablePanels(...)` whenever `hasTrack && hasBoundedClips` (same condition as `Base`).

- [ ] **Step 1: Update `AvailablePanelsTest.kt` to expect `Drills` alongside `Base`**

In `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AvailablePanelsTest.kt`:
- Update every expectation where `hasTrack && hasBoundedClips` is `true` to include `AnalyticsPanel.Drills` at the end (order: `Heatmap`, `Base`, `[Skeleton]`, `Drills`).
- Rename `all_three_keep_the_order_heatmap_base_skeleton` to `all_four_keep_the_order_heatmap_base_skeleton_drills`.
- Add `drills_follows_the_same_inputs_as_base_and_needs_no_video` asserting `availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = false, hasVideo = false) == listOf(Heatmap, Base, Drills)`.

- [ ] **Step 2: Run shared analytics tests to verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest --tests "*AvailablePanelsTest*" --tests "*AnalysisCapabilityTest*"
```
Expected: FAIL (`AnalyticsPanel.Drills` unresolved).

- [ ] **Step 3: Update `AnalyticsPanel.kt`, `AnalysisCapability.kt`, and `MatchPageCopy.kt`**

1. In `AnalyticsPanel.kt`:
   - Add `Drills("Drills"),` after `Skeleton("Skeleton"),`.
   - In `availablePanels(...)`, append `if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Drills)` after `Skeleton`.
2. In `AnalysisCapability.kt`:
   - Append `PanelCapability(panel = AnalyticsPanel.Drills, cloud = "Both players", device = "The near player")` to `panelCapabilities`.
3. In `MatchPageCopy.kt`:
   - Update comment on `ANALYSIS_PANELS` to mention `Heatmap | Base | Skeleton | Drills`.

- [ ] **Step 4: Run full `:shared:jvmTest` to verify everything in `:shared` passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :shared:jvmTest
```
Expected: `BUILD SUCCESSFUL`, all shared tests pass (including `AnalysisCapabilityTest` pinning `panelCapabilities` to `AnalyticsPanel.entries`).

- [ ] **Step 5: Commit Task 4**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsPanel.kt \
  shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalysisCapability.kt \
  shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageCopy.kt \
  shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AvailablePanelsTest.kt
git commit -m "feat(shared): expose AnalyticsPanel.Drills and capability row"
```

---

### Task 5: Android `ExercisesPanel` & `AnalysisSection` Wiring

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExercisesPanel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/AnalysisSection.kt:136-149`
- Create: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/ExercisesPanelTest.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt:95-150`

**Interfaces:**
- Consumes: `AnalyticsPanel.Drills`, `CourtSideNames`, `profileArm` (`shared/analytics`), `adviseExercisesForTrack`, `ExerciseReport`, `ExerciseReportStatus`, `ExerciseSuggestion`, `MovementSummary`, `ExercisesCopy` (`shared/exercises`), `ShuttlStatTile` (`ui/components`), `SideToggle`, `heatmapSource`, `whyEmpty`, `PanelMessage`, `COURT_GUTTER` (`localanalysis`).
- Produces:
  - `@Composable fun ExercisesPanel(entryId: String, runner: LocalAnalysisRunner, windows: List<RallyWindow>, sideNames: CourtSideNames?, modifier: Modifier = Modifier)`
  - `internal fun exercisesMessage(track: PlayerTrack?, windows: List<RallyWindow>, report: ExerciseReport?, measured: Boolean): String?`

- [ ] **Step 1: Write failing Android tests (`ExercisesPanelTest.kt` and update `CloudAnalysisPanelsTest.kt`)**

1. Create `androidApp/src/test/java/com/badmintontracker/android/localanalysis/ExercisesPanelTest.kt` testing `exercisesMessage`:
   - `track == null` -> `ExercisesCopy.NOT_LOADED`.
   - `track.samples.isEmpty()` -> `whyEmpty(track)`.
   - `windows.none { it.isBounded }` -> `ExercisesCopy.NO_BOUNDED_WINDOWS`.
   - `measured == false` -> `null` (waits without flashing).
   - `measured == true` with `report == null` -> `ExercisesCopy.NO_COUNTED_RALLIES`.
   - `measured == true` with valid `report` (whether `INSUFFICIENT_DATA`, `BALANCED`, or `SUGGESTIONS`) -> `null` (panel renders summary + report message/cards rather than hiding summary behind `PanelMessage`).
   - Also verify per-side arm isolation: building `reportsBySide` via `profileArm(side, sideNames)` hands `nearArm` to `NEAR` and `farArm` to `FAR`.
2. Update `androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt` so tests expecting `[Heatmap, Base]` expect `[Heatmap, Base, Drills]` and `[Heatmap, Base, Skeleton]` expects `[Heatmap, Base, Skeleton, Drills]`.

- [ ] **Step 2: Run Android unit tests to verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :androidApp:testDebugUnitTest --tests "*ExercisesPanelTest*" --tests "*CloudAnalysisPanelsTest*"
```
Expected: FAIL (unresolved `exercisesMessage`, `AnalyticsPanel.Drills` mismatch in `AnalysisSection` exhaustive `when`).

- [ ] **Step 3: Implement `ExercisesPanel.kt` and wire `AnalysisSection.kt`**

1. Create `androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExercisesPanel.kt`:
   - Follow `BasePositionPanel.kt` structure verbatim:
     - `produceState<Measured?>(null, entryId, done, windows, sideNames)` running on `Dispatchers.Default`: resolves `source = heatmapSource(done, runner.storedTrack(entryId))` and computes `reportsBySide = source?.tracks?.associate { it.side to adviseExercisesForTrack(it.track, source.fps, windows, it.side, profileArm(it.side, sideNames)) } ?: emptyMap()`.
     - Centered `CircularProgressIndicator` while `measured == null`.
     - `var side by rememberSaveable { mutableStateOf(CourtSide.NEAR) }`; show `SideToggle` above the empty/content ladder when `source != null && source.tracks.size > 1 && shown != null`.
     - Run `exercisesMessage(shown?.track, windows, report, measured = true)`; if non-null, render `PanelMessage(message)` and return.
     - Otherwise render:
       1. When `report.summary != null`: uppercase section label `ExercisesCopy.SUMMARY_HEADING`, a 2-tile row of `ShuttlStatTile` (`labelMaxLines = 2`, `IntrinsicSize.Min`, `COURT_GUTTER = 24.dp`) showing:
          - Tile 1: `value = summary.countedRallies.toString()`, `unit = if (summary.countedRallies == 1) " rally" else " rallies"`, `label = ExercisesCopy.ralliesCaption(summary.coveragePercent)`
          - Tile 2: `value = summary.peakSpeedText ?: ABSENT_METRIC`, `unit = if (summary.peakSpeedText != null) " m/s" else ""`, `label = ExercisesCopy.PEAK_SPEED_LABEL`
          - Below the tiles: `ExercisesCopy.zoneBreakdown(summary.frontPercent, summary.midPercent, summary.rearPercent)` in `bodySmall` / `onSurfaceVariant`.
       2. Based on `report.status`:
          - `INSUFFICIENT_DATA` or `BALANCED`: `PanelMessage(report.message ?: ExercisesCopy.NO_COUNTED_RALLIES)` below the summary (if any).
          - `SUGGESTIONS`: uppercase section label `ExercisesCopy.SUGGESTIONS_HEADING`, followed by expandable `ExerciseCard`s (`remember(shownSide, report)` set of expanded IDs initialized with `report.suggestions.firstOrNull()?.exercise?.id`).
            - Each `ExerciseCard` uses `RoundedCornerShape(ShuttlRadius.large)`, `surfaceVariant` background, subtle `outline` border (1.dp), 16.dp padding, clickable header with `Role.Button` + state description (`"Expanded"` / `"Collapsed"`), showing category label (`On-court` / `Off-court`), prescription text, exercise title (`titleMedium`), evidence line (`bodySmall`, `onSurfaceVariant`), and when expanded: `Setup`, numbered `steps`, and `Cue`.
       3. Subtle footer caption `ExercisesCopy.HONESTY_CAPTION` in `bodySmall` / `ShuttlTheme.extended.textTertiary` (`COURT_GUTTER` horizontal padding) when `report.status != INSUFFICIENT_DATA`.
   - Use Compose Foundation 1.7.6-safe APIs only (standard `Column`, `Row`, `Box`, `Spacer`, `Icons.Rounded.ExpandMore` / `ExpandLess` or `KeyboardArrowDown` / `KeyboardArrowUp`).
2. Wire `AnalysisSection.kt:136-149`:
   - Add `AnalyticsPanel.Drills -> ExercisesPanel(entryId = analysisId, runner = localAnalysis, windows = windows, sideNames = sideNames)`.

- [ ] **Step 4: Run Android gate (tests, debug, release, container-color lint)**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease
tools/check-container-colors.sh
```
Expected: `BUILD SUCCESSFUL` and `every Material container sets its own colour`.

- [ ] **Step 5: Commit Task 5**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/ExercisesPanel.kt \
  androidApp/src/main/java/com/badmintontracker/android/match/AnalysisSection.kt \
  androidApp/src/test/java/com/badmintontracker/android/localanalysis/ExercisesPanelTest.kt \
  androidApp/src/test/java/com/badmintontracker/android/match/CloudAnalysisPanelsTest.kt
git commit -m "feat(android): add Drills panel with post-analysis exercise suggestions"
```

---

### Task 6: iOS `ExercisesPanel`, `AnalysisSection` Wiring & XcodeGen

**Files:**
- Create: `iosApp/Sources/LocalAnalysis/ExercisesPanel.swift`
- Modify: `iosApp/Sources/Match/AnalysisSection.swift:118-135`
- Create: `iosApp/Tests/ExercisesPanelTests.swift`
- Modify: `iosApp/Tests/CloudAnalysisPanelsTests.swift:99-158`
- Regenerate: `iosApp/iosApp.xcodeproj`

**Interfaces:**
- Consumes: `Shared` (`AnalyticsPanel.drills`, `CourtSide`, `CourtSideNames`, `CourtSideNamesKt.profileArm`, `ExerciseAdvisorKt.adviseExercisesForTrack`, `ExerciseReport`, `ExerciseReportStatus`, `ExerciseSuggestion`, `MovementSummary`, `ExercisesCopy`, `MetricsFormatKt.ABSENT_METRIC`), `HeatmapSource`, `SideToggle`, `ShuttlStatTile`, `PanelMessage`, `whyEmpty`, `Shuttl`, `ShuttlType`, `ShuttlGutter`, `ShuttlRadius`.
- Produces:
  - `struct ExercisesPanel: View` (inputs: `source: HeatmapSource?`, `windows: [RallyWindow]`, `sideNames: CourtSideNames?`)
  - `func exercisesMessage(track: PlayerTrack?, windows: [RallyWindow], report: ExerciseReport?, measured: Bool) -> String?`

- [ ] **Step 1: Write `ExercisesPanelTests.swift` and update `CloudAnalysisPanelsTests.swift`**

1. Create `iosApp/Tests/ExercisesPanelTests.swift` mirroring `androidApp`'s `ExercisesPanelTest` and `BasePositionPanelTests`:
   - `testUnloadedAnalysisSaysSo` -> `ExercisesCopy.shared.NOT_LOADED`
   - `testEmptyTrackUsesHeatmapWording` -> `whyEmpty(empty)`
   - `testUnboundedWindowsSaysWindowsWereNotKept` -> `ExercisesCopy.shared.NO_BOUNDED_WINDOWS`
   - `testWaitsForMeasurementBeforeReporting` -> `nil` when `measured == false`
   - `testMeasuredNilReportExplainsNoCountedRallies` -> `ExercisesCopy.shared.NO_COUNTED_RALLIES`
   - `testMeasuredReportRendersPanelContent` -> `nil` for `INSUFFICIENT_DATA`, `BALANCED`, and `SUGGESTIONS`
   - `testAdviseExercisesForTrackWorksFromSwiftForNearAndFar` -> invokes `ExerciseAdvisorKt.adviseExercisesForTrack(...)` on synthetic 4-rally deep-base `NEAR` and `FAR` tracks with `RacketArm.right` and asserts `.suggestions` and handedness-aware output.
2. Update `iosApp/Tests/CloudAnalysisPanelsTests.swift` so `[.heatmap, .base]` becomes `[.heatmap, .base, .drills]` and `[.heatmap, .base, .skeleton]` becomes `[.heatmap, .base, .skeleton, .drills]`.

- [ ] **Step 2: Implement `ExercisesPanel.swift` and wire `AnalysisSection.swift`**

1. Create `iosApp/Sources/LocalAnalysis/ExercisesPanel.swift` (strictly NO `.font(` anywhere, not even in comments - use `.shuttlType(...)` everywhere):
   - Mirrors `BasePositionPanel.swift` structure:
     - `@State private var report: ExerciseReport? = nil`
     - `@State private var resolved = false`
     - `@State private var side: CourtSide = .near`
     - `@State private var expandedIds: Set<String> = []`
     - `SideToggle` above the `inner` ladder when `source.tracks.count > 1`.
     - Flattened `private struct Inputs: Equatable` carrying `samples: Int`, `side: CourtSide`, `fps: Double`, `bounds: [Double]`, `racketArm: RacketArm?`.
     - `.task(id: inputs)` runs `Task.detached(priority: .userInitiated)` calling `private nonisolated static func evaluate(track:fps:windows:side:racketArm:) -> ExerciseReport?` -> `ExerciseAdvisorKt.adviseExercisesForTrack(...)`. When `measured` lands, reset `expandedIds` to the first suggestion's `exercise.id` (if any) and set `resolved = true`.
     - Layout matches Android 1-for-1:
       - `MovementSummary` section (`ExercisesCopy.shared.SUMMARY_HEADING`, 2 `ShuttlStatTile`s in an `HStack(spacing: 8)` with `labelLineLimit: 2`, and `ExercisesCopy.shared.zoneBreakdown(...)`).
       - For `.insufficientData` / `.balanced`: `PanelMessage(text: report.message ?? ExercisesCopy.shared.NO_COUNTED_RALLIES)`.
       - For `.suggestions`: `ExercisesCopy.shared.SUGGESTIONS_HEADING` + expandable `ExerciseCard`s (`Shuttl.bgSecondary`, `RoundedRectangle(cornerRadius: ShuttlRadius.large)` + 1pt `Shuttl.border` stroke, tap header to toggle `expandedIds`, SF Symbol `chevron.down` / `chevron.up` using `.shuttlType(ShuttlType.labelSmall)` or explicit `.frame(width: 14, height: 14)` on `.resizable().scaledToFit()` so no `.font(` is used!).
       - Footer honesty caption `ExercisesCopy.shared.HONESTY_CAPTION` in `.shuttlType(ShuttlType.bodySmall)` / `Shuttl.textTertiary` when `report.status != .insufficientData`.
2. Wire `iosApp/Sources/Match/AnalysisSection.swift:118-135`:
   - Add `case .drills: ExercisesPanel(source: source, windows: windows, sideNames: sideNames)`.

- [ ] **Step 3: Regenerate Xcode project, build for testing, verify `.o` files, and run iOS tests**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd iosApp && xcodegen generate && cd ..
xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null
xcodebuild build-for-testing \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
```
Verify `ExercisesPanel.o` and `ExercisesPanelTests.o` exist in `iosApp/build/DerivedData`, then boot the iPhone 17 Pro simulator (if not booted) and run the full iOS unit test suite (including `ExercisesPanelTests`, `CloudAnalysisPanelsTests`, `TypographyAdoptionTests`):
```bash
P=$PWD/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator
PL=$DEVELOPER_DIR/Platforms/iPhoneSimulator.platform/Developer
SIMCTL_CHILD_DYLD_FRAMEWORK_PATH="$P/iosApp.app/Frameworks:$P:$PL/Library/Frameworks:$PL/usr/lib:$P/iosApp.app" \
SIMCTL_CHILD_DYLD_LIBRARY_PATH="$P/iosApp.app:$P:$PL/usr/lib:$PL/Library/Frameworks" \
xcrun simctl spawn booted "$PL/Library/Xcode/Agents/xctest" "$P/iosApp.app/PlugIns/iosAppTests.xctest"
```
Expected: `BUILD SUCCEEDED` and `Executed ... 0 failures`.

- [ ] **Step 4: Commit Task 6**

```bash
git add iosApp/Sources/LocalAnalysis/ExercisesPanel.swift \
  iosApp/Sources/Match/AnalysisSection.swift \
  iosApp/Tests/ExercisesPanelTests.swift \
  iosApp/Tests/CloudAnalysisPanelsTests.swift \
  iosApp/iosApp.xcodeproj
git commit -m "feat(ios): add Drills panel with post-analysis exercise suggestions"
```

---

## Final Cross-Platform Verification Gate

After Task 6:
1. Run `:analysis:jvmTest`, `:shared:jvmTest`, `:androidApp:testDebugUnitTest`, `:androidApp:assembleDebug`, `:androidApp:assembleRelease`, and `tools/check-container-colors.sh`.
2. Run the full iOS `iosAppTests` bundle (confirming `ExercisesPanelTests`, `CloudAnalysisPanelsTests`, `BasePositionPanelTests`, and `TypographyAdoptionTests` all pass with 0 failures).
3. Inspect `git status` and `git diff --stat origin/feat/player-profiles..HEAD` to confirm no stray/untracked files (`firebase-debug.log` stays untouched/uncommitted) and zero em dashes in added code or copy.
