# Design: Animated Pose & Court Visuals on Drill Cards

**Date:** 2026-11-23
**Status:** Approved by Coen
**Branch:** `feat/exercise-suggestions` (small follow-on to `2026-11-22-exercise-suggestions-design.md`)
**Asked for:** Coen, 2026-11-23: add a small image or animation on each drill card so the player sees how to pose and move, built with Option A (native Canvas visuals driven by `:shared`) to evaluate how it looks in the simulator.

---

## 1. Intent & Scope

When a coach or player expands a suggested exercise on the **Drills** panel, show a compact, looping visual right above `Setup` so the movement pattern and body posture are clear at a glance without reading a wall of text.

### Goals
- Define all 13 exercises' visuals once in `:shared` (`ExerciseVisuals.kt`): half-court paths + articulated pose keyframes + pure interpolation helpers.
- Render natively on Android Compose `Canvas` and iOS SwiftUI `Canvas` using Shuttl's Light/Dark design tokens (`ShuttlTheme` / `Shuttl`) with zero new dependencies and zero image assets.
- Mirror poses and asymmetric drills automatically when the player profile's `racketArm` is `LEFT`.
- Respect system Reduce Motion (show a clean static key pose and step through keyframes on tap when Reduce Motion is enabled) and animate **only while a card is expanded**.

### Non-Goals
- No Lottie/Rive/video dependencies or bundled raster images.
- No 3D meshes or fine grip/finger detail (sticks to gross body posture, racket carriage, lunge depth, torso alignment, and court paths).
- No changes to `:analysis` or rule logic.

---

## 2. Approaches Considered

- **Option A: Shared Kotlin keyframes + native Compose/SwiftUI Canvas *(Chosen)*** - 100% offline, ~5 KB code, automatic Light/Dark & left-hand mirroring, matches Shuttl's `CourtCard` and `SkeletonOverlay` visual language, zero new deps.
- **Option B: Custom 2-frame vector/PNG illustrations *(Rejected for now)*** - clearer foot/grip detail, but requires authoring/QA across 13 dual-theme illustrations and doesn't animate multi-corner footwork.
- **Option C: Lottie/Rive or short MP4 videos *(Rejected for now)*** - adds SDK dependencies or 10-20 MB of filmed clips before the drill catalog is finalized.

---

## 3. Design Sketch

### Inside an Expanded On-Court Card (11 drills)
```text
┌──────────────────────────────────────────────┐
│  ┌──────────────────┐  ┌──────────────────┐  │
│  │  ═══ net ═══     │  │        o         │  │
│  │  · · · · · ·     │  │       /|\   (o)  │  │  <- articulated pose + racket
│  │       ○ base     │  │      / | \  /    │  │     looping 2-3 keyframes
│  │     ↗   ↘        │  │       / \        │  │
│  │    ●     ·       │  │     _/   \_      │  │
│  │  (half-court)    │  │  ─── ground ───  │  │
│  └──────────────────┘  └──────────────────┘  │
│  Court movement        Pose: Lunge           │
└──────────────────────────────────────────────┘
```
- **Left tile (~140dp/pt tall):** Top-down player half-court (net, short service line, centre line, singles/doubles lines), base/cone ring in accent colour, subtle dashed path of corners, and an animated dot tracing the drill loop (base -> corner -> recover to base).
- **Right tile (~140dp/pt tall):** Articulated figure on a ground line with a subtle racket shaft + head on the racket hand, smoothly interpolating between 2-3 keyframes (e.g., `Ready` -> `Lunge` -> `Recover`) with the active phase label underneath.

### Inside an Expanded Off-Court Card (2 exercises)
```text
┌──────────────────────────────────────────────┐
│  ┌────────────────────────────────────────┐  │
│  │                 o                      │  │
│  │                /|\   (vertical cue)    │  │  <- wider pose stage (no court)
│  │               / | \                    │  │     looping 2-3 form keyframes
│  │                / \                     │  │
│  │              _/   \_                   │  │
│  │          ───── ground ─────            │  │
│  └────────────────────────────────────────┘  │
│  Pose: Lower under control                   │
└──────────────────────────────────────────────┘
```

### Shared Data & Math (`shared/exercises/ExerciseVisuals.kt`)
- `VisualPoint(x: Float, y: Float)`
- `PoseFigure`: 14 normalized body joints (`head`, `neck`, `shoulderL/R`, `elbowL/R`, `wristL/R`, `hipL/R`, `kneeL/R`, `ankleL/R`) in `[0, 1]` plus optional `racketTip: VisualPoint?` (drawn from `wristR`, mirrored across `x = 0.5` when `racketArm == LEFT`).
- `PoseKeyframe(label: String, figure: PoseFigure)` and `PoseSequence(keyframes: List<PoseKeyframe>, cycleSeconds: Double)`.
- `CourtDrillPath(baseMarker: VisualPoint, waypoints: List<VisualPoint>, shuttleStart: VisualPoint?, shuttleEnd: VisualPoint?, cycleSeconds: Double)` in player-relative half-court metres (`x ∈ [0, 6.1]`, `depthFromNet ∈ [0, 6.7]`).
- `ExerciseVisual(exerciseId: String, courtPath: CourtDrillPath?, pose: PoseSequence)` - 1 entry for every exercise in `ExerciseCatalog.all` (pinned by `ExerciseVisualsTest`).
- Pure `:shared` sampling helpers:
  - `samplePose(sequence, phase01, mirrorX): SampledPose` (smoothstep interpolation + active keyframe label)
  - `sampleCourtPoint(path, phase01, mirrorX): VisualPoint` (smoothstep segment travel along waypoints)

### Platform Views (`ExerciseVisualView.kt` / `ExerciseVisualView.swift`)
- Rendered inside `ExerciseCard`'s `if (expanded)` block right above `Setup`, receiving `exerciseId` and `racketArm: RacketArm?`.
- Android: `rememberInfiniteTransition` driving `phase01` (`0f..1f`) when expanded and animations are enabled; tap toggles pause / steps keyframes under Reduce Motion.
- iOS: `TimelineView(.animation(paused: reduceMotion))` driving `phase01` cleanly with zero timer leaks; tapping steps keyframes when Reduce Motion is enabled. No `.font(` calls (uses `.shuttlType(...)`).

---

## 4. Calls Made (For Coen to Check)
1. **Side-by-side Court + Pose** for the 11 on-court drills, and **full-width Pose only** for the 2 off-court exercises (since off-court moves have no court path).
2. **Smooth 2-3 keyframe looping animation** only while a card is expanded (collapsed cards stay quiet and fast to scroll), with a phase caption underneath.
3. **Automatic left-hand mirroring** via the shown side's `RacketArm` (`profileArm(shownSide, sideNames)`).
4. **Pure `:shared` coordinate math** so Android and iOS draw the exact same figure and path, verified by unit tests in `:shared` plus Android/iOS builds and simulator screenshot check.
