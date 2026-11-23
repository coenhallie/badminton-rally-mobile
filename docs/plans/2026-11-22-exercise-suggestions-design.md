# Design: Post-Analysis Exercise Suggestions

**Date:** 2026-11-22
**Status:** Draft — awaiting builder check and approval by Coen
**Asked for:** Coen, 2026-11-22: after a complete video analysis finishes, suggest exercises to improve the player's measured datapoints, leaving out the coach confirm/ignore approval flow for now.
**Follows:** `2026-10-31-player-profiles-design.md` (Step 1 shipped; this prototypes the metric-to-exercise engine for Step 5 on single-video data, without blocking on Steps 2–4), `2026-09-07-pose-metrics-research.md`, and `2026-09-08-more-pose-metrics-research.md`.

---

## 1. Intent & Why

Video analysis already produces a court-plane movement track and per-rally base positions for a match, and shows them as a heatmap, rally base dots, and per-frame skeleton angles. Today, turning those numbers into training work is left entirely to the coach's eye.

The goal of this pass is to prove whether the **honest datapoints already produced by a completed video analysis** can automatically generate useful, grounded exercise suggestions right inside the Match Page's Analysis section — without changing the ML pipeline, without requiring videos to be re-analysed, and without building the coach approval / player login workflow yet.

Long-term, the coach will review and approve suggested exercises before players see them (Steps 3–5 of `2026-10-31-player-profiles-design.md`). This pass builds the pure measurement, rule, and catalog foundation so both single-match inspection now and coach-reviewed workflows later share one engine.

### Goals
- Derive honest, verified movement and positioning summaries from any completed video analysis (`PlayerTrack` + bounded `RallyWindow`s), working for both **on-device runs** (Near player) and **cloud runs** (Near and Far players), including already-analysed matches.
- Evaluate those summaries against transparent, quality-gated singles badminton rules and suggest **1–4 ranked exercises**, each showing the exact measured datapoint(s) that triggered it.
- Keep all measurement math in `:analysis` and all rule evaluation, catalog entries, and copy in `:shared` so Android and iOS produce identical suggestions.
- Surface suggestions as a new panel inside the existing Match Page **Analysis** section on both Android and iOS, respecting the existing `Near | Far` / named-player toggle and playing hand (`RacketArm`).

### Non-Goals (This Pass)
- **Coach confirm / ignore / assign flow** (explicitly deferred by Coen). No persistence of accepted/dismissed exercises, no new Supabase tables or migrations.
- **Cross-match aggregation** on the Player Profile tab (Step 2 of `2026-10-31-player-profiles-design.md`). Suggestions in this pass evaluate one analysed video at a time.
- **Player logins** (Step 4).
- **Biomechanical form critique from raw 2D pose angles** (`elbow*`, `arm*`, `knee*`). Per `2026-09-07-pose-metrics-research.md` and `2026-09-08-more-pose-metrics-research.md`, knee flexion is edge-on from behind the baseline (deep lunges read ~157–173°), elbow angles have 19–44° p90 jitter / 21–36° projection error, and per-frame streams lack contact anchors.
- **Pipeline / ML model / cloud worker changes** (no contact-moment extraction, no shot classification, no re-analysis required).
- **Doubles support** or **within-video end-swap detection.** Singles tracking (`NearPlayerSelector` tracks 1 player per court half) and static court ends remain unchanged. Matching `Heatmap` and `Base`, `AnalysisSection` and `availablePanels(...)` do **not** read `MatchFormat` or hide the tab for doubles matches; the panel's honesty caption explicitly notes that suggestions assume singles rally movement. *(My call — see Section 8.)*
- **Score-log or annotation-label rules.** Video-only matches are common, and `AnalysisSection` currently reads only the video analysis artifacts (`PlayerTrack` + `RallyWindow`s + `CourtSideNames`). Keeping this pass purely track-driven avoids coupling `AnalysisSection` to `ScoreLog` / clip-annotation loading and keeps every suggestion tied directly to the video analysis that just finished. *(My call — see Section 8.)*

### Anti-Goals
- **Inventing advice on thin or noisy data.** A video with 2 tracked rallies or 30% tracking coverage must say honestly why there are no suggestions rather than guessing.
- **Inventing flaws when movement looks balanced.** If no rule triggers, say movement looked balanced rather than forcing a filler drill.
- **Presenting clip-padded window length as rally duration.** Stored `RallyWindow`s include 2.0 s pre-roll and 1.5 s post-roll (`ClipWindows.kt:15-16`), so raw window lengths must not be used to claim rallies were short or long.
- **Duplicating rules, thresholds, or drill copy across Android and iOS.** Everything except view rendering lives in `:analysis` and `:shared`.

---

## 2. Approaches Considered

1. **Approach 1: Shared Kotlin Rule Engine + Curated Catalog on Existing Tracks *(Chosen)***
   - Pure Kotlin in `:analysis` + `:shared` computes court-plane positioning, zone balance, base drift/consistency, and windowed burst speed/path directly from stored `PlayerTrack` + `RallyWindow`s. Maps triggered findings to an owned catalog of concise drills and surfaces them as a 4th panel in `AnalysisSection`.
   - *Trade-offs:* 100% offline, instant, works retroactively on already-analysed matches, zero API cost, zero hallucination risk, zero DB/worker changes. Limited to movement and positioning signals (no contact-moment biomechanics yet).
2. **Approach 2: Add Contact Moments to the Pipeline First, Then Rules *(Rejected for now)***
   - Persist shuttle-reversal + nearest-wrist contact events in `SkeletonStore` v5 (device + cloud) to unlock recovery-time-to-base, contact stance, and frontal-plane shoulder/hip tilt at contact.
   - *Trade-offs:* Richer shot-anchored signals, but 3–4× larger, touches binary storage + both inference runners + cloud ingestion, and only works after re-analysing videos. Can plug into Approach 1's rule engine later.
3. **Approach 3: Metric Summary + Server-Side LLM Synthesis *(Rejected for now)***
   - Send structured metric summaries + coach notes to a Supabase Edge Function calling an LLM to generate exercises.
   - *Trade-offs:* Flexible phrasing and reads free-text notes, but requires backend/API keys/cost, fails offline in sports halls, adds latency, and risks over-interpreting noisy single-video data or drill footage.

---

## 3. What Exists Today (Verified)

- **Analysis panels:** `AnalyticsPanel` (`shared/.../analytics/AnalyticsPanel.kt:4-39`) defines `Heatmap`, `Base`, `Skeleton`. `availablePanels(hasTrack, hasBoundedClips, hasSkeleton, hasVideo)` controls which pills appear on both platforms. `panelCapabilities` (`shared/.../analytics/AnalysisCapability.kt:34-54`) is pinned to `AnalyticsPanel.entries` by `AnalysisCapabilityTest.kt:21`.
- **Panel hosts:** `AnalysisSection.kt` (Android) and `AnalysisSection.swift` (iOS) load `PlayerTrackStore.Stored` (`NEAR` for device runs, `NEAR` + `FAR` for cloud runs, with `fps`), resolve `List<RallyWindow>` via `rallyWindowsFor()`, and receive `sideNames: CourtSideNames?` (resolved player names + `racketArm` via `profileArm(side, sideNames)`).
- **Existing per-rally computation pattern:** `BasePositionPanel.kt:88-103` (Android) runs `basePositions()` on `Dispatchers.Default` for all sides at once; `BasePositionPanel.swift:71-80` (iOS) runs `Task.detached(priority: .userInitiated)` keyed on flattened `Inputs`. Both show `SideToggle` above the empty/content ladder when `tracks.size > 1`.
- **Player-relative coordinates:** `BasePositionFormat.kt:27-36` defines player-relative depth behind own short service line and lateral offset from centre line facing the net (`NEAR`: `behind = y - 8.68`, `across = x - 3.05`; `FAR`: `behind = 4.72 - y`, `across = 3.05 - x`).
- **Validated movement primitives:**
  - `basePositions()` (`analysis/.../player/BasePosition.kt:59-99`): 1–2 cm model agreement, 1.7 cm standing SD, `MIN_RALLY_SECONDS = 1.0`.
  - `CourtOccupancy` (`analysis/.../player/CourtOccupancy.kt:60-78`): time-weighted dwell capped at `MAX_GAP_S = 0.5` s inside `RallyFrames`.
  - `tools/models/pose_rally_report.py:71-96`: empirically validated `0.25 s` resampled path length (4% model agreement) and `0.5 s` windowed peak speed (4–6 m/s corpus range, matching elite 6–7 m/s literature).
- **Pill tab constraints:** `ShuttlPillTabs` on both platforms wraps between words and shrinks down to 0.75× if a word is too wide for equal-width pills. Current 3-pill Analysis row has widest word `Skeleton` (8 chars). A 4-pill row with `"Drills"` (6 chars) keeps `Skeleton` as the widest word, avoiding word breaks at large text on 320pt/360dp phones.

---

## 4. Layer 1: `:analysis` — `MovementProfile`

New file: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/MovementProfile.kt`.

Pure Kotlin function:
```kotlin
fun movementProfile(
    track: PlayerTrack,
    fps: Double,
    windows: List<RallyWindow>,
    side: CourtSide,
    minSeconds: Double = MIN_RALLY_SECONDS,
): MovementProfile
```

### 4.1 Coordinate System (Player-Relative, Facing Net)
For every sample `p = sample.courtPosition` (court metres: `x ∈ [0, 6.1]`, `y ∈ [0, 13.4]`, net at `y = 6.7`, `SERVICE_LINE = 1.98`):
- **Depth from net (`depthFromNetM`):**
  - `NEAR`: `p.y - Court.LENGTH / 2.0` (`p.y - 6.7`)
  - `FAR`: `Court.LENGTH / 2.0 - p.y` (`6.7 - p.y`)
- **Behind own service line (`behindServiceLineM`):** `depthFromNetM - Court.SERVICE_LINE` (positive toward own baseline, negative inside own service box).
- **Lateral offset (`lateralFromCentreM`):**
  - `NEAR`: `p.x - Court.WIDTH_DOUBLES / 2.0` (`p.x - 3.05`)
  - `FAR`: `Court.WIDTH_DOUBLES / 2.0 - p.x` (`3.05 - p.x`)
  - Negative = player's own left; positive = player's own right.

### 4.2 Output Model (`MovementProfile`)
```kotlin
data class MovementProfile(
    /** Number of bounded rallies meeting [MIN_RALLY_SECONDS] of tracked samples. */
    val countedRallies: Int,
    /** Share of all frames inside bounded rally windows that had a valid ankle sample (0..1). */
    val rallyCoverage: Double,
    /** Total credited seconds inside bounded rallies (time-weighted, capped at 0.5 s per gap). */
    val totalRallySeconds: Double,
    /** Overall median position behind own service line across counted rallies (metres), null if none. */
    val overallBehindServiceLineM: Double?,
    /** Overall median lateral offset from centre line (metres; + player right, - player left), null if none. */
    val overallLateralFromCentreM: Double?,
    /** Median Euclidean distance of per-rally bases from the overall base (metres), null if < 2 counted rallies. */
    val baseSpreadM: Double?,
    /**
     * Last-third median base depth minus first-third median base depth (metres).
     * Positive means the player's base retreated deeper later in the video.
     * Null when [countedRallies] < [MIN_TREND_RALLIES] (6).
     */
    val baseDepthDriftM: Double?,
    /** Fraction of credited rally time in front court (depthFromNetM < 2.2 m), 0..1. */
    val frontShare: Double,
    /** Fraction of credited rally time in mid court (2.2 m <= depthFromNetM <= 4.5 m), 0..1. */
    val midShare: Double,
    /** Fraction of credited rally time in rear court (depthFromNetM > 4.5 m), 0..1. */
    val rearShare: Double,
    /** Fraction of credited rally time on player's own left half (lateralFromCentreM < 0), 0..1. */
    val leftShare: Double,
    /** Fraction of credited rally time on player's own right half (lateralFromCentreM >= 0), 0..1. */
    val rightShare: Double,
    /** Median per-rally path length resampled at 0.25 s (metres), null if no counted rallies. */
    val medianRallyPathM: Double?,
    /** Median across counted rallies of each rally's peak 0.5 s windowed speed (m/s), null if none. */
    val medianPeakSpeedMps: Double?,
    /**
     * First-third median peak speed (m/s) across counted rallies, null if < 6 counted rallies
     * or if either split has no valid 0.5 s speed windows.
     */
    val earlyPeakSpeedMps: Double?,
    /** Last-third median peak speed (m/s), paired with [earlyPeakSpeedMps]. */
    val latePeakSpeedMps: Double?,
    /**
     * Fractional drop in 0.5 s peak speed from first third to last third:
     * `(early - late) / early`. Positive means slower later. Null when either split is unavailable
     * or `early <= 0`.
     */
    val speedDropFraction: Double?,
)
```

### 4.3 Exact Computation Rules & Edge Cases
1. **Base & rally selection:** Calls `basePositions(track, fps, windows, minSeconds)` directly so `countedRallies` and overall/per-rally bases match the Base panel identically (`BasePosition.kt:59-99`).
   - `rallyCoverage`: computed via `RallyFrames.of(windows, fps)?.coverage(track) ?: 0.0` (matches the Heatmap panel's Rallies coverage stat identically!).
   - `baseSpreadM`: if `bases.rallies.size >= 2` and `bases.overall != null`, median of `hypot(r.position.x - overall.x, r.position.y - overall.y)`.
   - **First-third vs. last-third split (for `baseDepthDriftM` and speed drop):** Requires `countedRallies >= 6` (`MIN_TREND_RALLIES = 6`). Split size `k = countedRallies / 3` (integer division, so `k >= 2` for 6–8 rallies, `k = 3` for 9–11, etc.). First third = `rallies.take(k)`, last third = `rallies.takeLast(k)`. Rallies are already in chronological `startSeconds` order.
2. **Zone shares (`frontShare`, `midShare`, `rearShare`, `leftShare`, `rightShare`):**
   - Computed over `track.samples` (sorted by frame) filtered to `RallyFrames.of(windows, fps)` using the **exact same `dt` capping rule** as `CourtOccupancy.addAll` (`MAX_GAP_S = 0.5`, clamped to `rallyEnd`).
   - Zone cutoffs (my call, standard singles geometry on a 6.7 m half-court):
     - Front: `depthFromNetM < 2.2` m (net to just behind the 1.98 m short service line)
     - Mid: `2.2 <= depthFromNetM <= 4.5` m (core singles base & mid-court band)
     - Rear: `depthFromNetM > 4.5` m (rear third to and beyond the 6.7 m baseline)
   - If `totalRallySeconds == 0.0`, all five shares are `0.0`.
3. **Per-rally path length (0.25 s resampling, `PATH_STEP_S = 0.25`):**
   - For each qualifying rally's sorted samples: anchor at the first sample `anchor`. Walk forward; whenever `(cur.frame - prev.frame) / fps > MAX_GAP_S (0.5)`, reset `anchor = cur` (do not bridge tracking dropouts). Otherwise, when `(cur.frame - anchor.frame) / fps >= 0.25`, accumulate `hypot(cur - anchor)` and set `anchor = cur`.
   - Store median across qualifying rallies as `medianRallyPathM`.
4. **Per-rally peak speed (0.5 s window, `SPEED_WINDOW_S = 0.5`):**
   - Matches `tools/models/pose_rally_report.py:78-96` and `SidePath.kt`'s physical speed ceiling:
   - First split each qualifying rally's sorted samples into contiguous runs separated by gaps `> MAX_GAP_S (0.5 s)` so speed is never measured across a tracking dropout.
   - Within each contiguous run, for each index `i`, advance pointer `j` to the first sample with `dt = (s[j].frame - s[i].frame) / fps >= 0.45` s. If `dt <= 0.65` s, compute `v = hypot(s[j] - s[i]) / dt`. Ignore implausible glitches where `v > MAX_PLAUSIBLE_SPEED_MPS` (`8.0 m/s`, matching `SidePath.kt:220`).
   - A rally's peak speed is the max surviving `v` in that rally (null if no valid 0.5 s window existed in that rally).
   - `medianPeakSpeedMps` is the median of non-null rally peak speeds.
   - For `earlyPeakSpeedMps` / `latePeakSpeedMps`, require at least 2 rallies with valid peak speeds in both the first third and the last third.

---

## 5. Layer 2: `:shared` — Rule Engine & Exercise Catalog

New package: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/`.

Files:
- `ExerciseModels.kt` — public data classes & enums (Swift/Obj-C clean: flat primitives, lists, enums, sealed hierarchy avoided where Swift switch ergonomics suffer — use a plain `ExerciseReport` data class with a `status: ExerciseReportStatus` enum!).
- `ExerciseCatalog.kt` — curated catalog of original, legally clean exercises (on-court drills + targeted off-court conditioning).
- `ExerciseAdvisor.kt` — quality gates, 7 rules, handedness-aware evidence copy, ranking & deduplication.
- `ExercisesCopy.kt` — panel headings, empty/insufficient/balanced copy, summary labels, honesty caption.

> **Swift/Obj-C Interop Note (My call):** Follow `shared`'s existing pattern (`BasePositionFormat.kt`, `MatchListItem.kt`): prefer plain data classes + enums over deeply nested sealed classes so Swift code reads cleanly without awkward Kotlin sealed casting.

### 5.1 Quality Gates (Honest-Over-Helpful)
- `MIN_RALLIES = 4` (for base/zone rules)
- `MIN_TREND_RALLIES = 6` (for early-vs-late drift and speed-drop rules)
- `MIN_COVERAGE = 0.50` (`rallyCoverage >= 0.50`)
- If `profile.countedRallies < MIN_RALLIES` or `profile.rallyCoverage < MIN_COVERAGE`:
  - Return `status = ExerciseReportStatus.INSUFFICIENT_DATA` with a plain message naming what was measured vs. what is needed (e.g., *"Need at least 4 tracked rallies with 50% coverage to suggest exercises. This video had 2 rallies (71% coverage)."*).
- If data passes gates and **zero rules trigger**:
  - Return `status = ExerciseReportStatus.BALANCED` with a plain message (e.g., *"Positioning and movement looked balanced across 9 rallies. Nothing here calls for a corrective drill."*).

### 5.2 The 7 Trigger Rules & Ranking

All rules run independently on `MovementProfile` + `racketArm: RacketArm?`. Each triggered finding produces a normalized `severity: Double` (`>= 1.0` at threshold, growing smoothly with excess) so findings rank deterministically from strongest signal to weakest.

| # | `FindingKind` | Trigger Condition | Severity Formula | Mapped Exercises (Primary / Secondary) |
|---|---|---|---|---|
| 1 | `DEEP_BASE` | `overallBehindServiceLineM > 2.00` m | `(behind - 2.00) / 0.50 + 1.0` | 1. **Base-Mark 6-Corner Shadow** *(On-court)*<br>2. **Clear-to-Base Multi-Shuttle** *(On-court)* |
| 2 | `SHALLOW_BASE` | `overallBehindServiceLineM < 0.75` m | `(0.75 - behind) / 0.40 + 1.0` | 1. **Net-to-Rear Recovery Drill** *(On-court)*<br>2. **Front-Back 2-Corner Feeding** *(On-court)* |
| 3 | `LATERAL_BIAS` | `|overallLateralFromCentreM| > 0.40` m **OR** `max(leftShare, rightShare) >= 0.65` | `max((|lat| - 0.40) / 0.25, (skew - 0.65) / 0.12) + 1.0` | 1. **Opposite-Side Corner Recovery** *(On-court — adapts copy to forehand/backhand when `racketArm` is set, or left/right when unset)*<br>2. **Centre-Line Split-Step Reset** *(On-court)* |
| 4 | `BASE_DRIFT_LATE` | `countedRallies >= 6` **AND** `baseDepthDriftM > 0.45` m | `(drift - 0.45) / 0.30 + 1.0` | 1. **Fatigue-State 6-Corner Shadow** *(On-court)*<br>2. **Split-Squat Endurance Sets** *(Off-court)* |
| 5 | `INCONSISTENT_BASE` | `baseSpreadM > 0.85` m | `(spread - 0.85) / 0.35 + 1.0` | 1. **Cone-Touch Base Discipline** *(On-court)* |
| 6 | `LOW_FRONT_PRESENCE` | `rearShare > 0.55` **AND** `frontShare < 0.12` | `max((rear - 0.55) / 0.15, (0.12 - front) / 0.08) + 1.0` | 1. **Drop-and-Follow Net Attack** *(On-court)*<br>2. **Forecourt Lunge & Net-Kill Feed** *(On-court)* |
| 7 | `LATE_SPEED_DROP` | `countedRallies >= 6` **AND** `earlyPeakSpeedMps >= 2.5` **AND** `speedDropFraction >= 0.20` | `(drop - 0.20) / 0.12 + 1.0` | 1. **Repeat-Burst Multi-Shuttle** *(On-court)*<br>2. **Lateral Bounds & Split-Jumps** *(Off-court)* |

**Selection & Cap Rules (Deterministic):**
1. Sort triggered findings by `severity` descending, breaking ties by `FindingKind.ordinal` ascending.
2. Cap output at **`MAX_SUGGESTIONS = 4`** exercise cards so a match with several flags gives a focused training session rather than a wall of text.
3. Allocation strategy across triggered findings:
   - First pass: add the **primary** exercise of each triggered finding (up to 4, deduplicating by `exercise.id`).
   - Second pass: if fewer than **2** suggestions exist (i.e. only 1 finding triggered) and that finding has a secondary exercise, add its **secondary** exercise (so a single strong finding shows 1 on-court + 1 complementary/off-court option where available, 2 cards total). If 2+ findings triggered, 1 primary exercise per finding (up to 4) keeps every card tied to a distinct finding.

**Handedness-Aware Copy (`LATERAL_BIAS`):**
- Right-handed (`RacketArm.RIGHT`): positive `lateralFromCentreM` = `"forehand side"`, negative = `"backhand side"`.
- Left-handed (`RacketArm.LEFT`): positive `lateralFromCentreM` = `"backhand side"`, negative = `"forehand side"`.
- Unset (`null`): positive = `"right of centre"`, negative = `"left of centre"`.
- Example evidence line:
  - With arm set: *"Base sat 0.52 m toward the forehand side (67% of rally time there), leaving the backhand side open."*
  - Without arm: *"Base sat 0.52 m right of centre (67% of rally time on the right)."*

### 5.3 Curated Catalog (13 Original Exercises — 11 On-Court, 2 Off-Court)
*(My call: mix of primarily on-court badminton drills plus 2 targeted off-court conditioning exercises for late-match endurance/burst drop, written cleanly in our own concise coaching voice with zero third-party copyright encumbrances.)*

Every `Exercise` carries:
- `id: String` (stable slug, e.g. `"base-mark-6-corner-shadow"`, ready for future Step 3 coach assignment)
- `title: String`
- `category: ExerciseCategory` (`ON_COURT` / `OFF_COURT`)
- `focus: FocusArea` (`BASE_RECOVERY`, `COURT_COVERAGE`, `LATERAL_BALANCE`, `ENDURANCE`, `EXPLOSIVE_SPEED`)
- `prescription: String` (e.g. `"4 × 45 s · 20 s rest"` or `"3 × 15 shuttles"`)
- `setup: String` (1–2 lines: solo or feeder, equipment needed)
- `steps: List<String>` (3–4 short imperative steps)
- `coachingCue: String` (single crisp cue for the coach/player to watch)

Full exercise list:
1. `base-mark-6-corner-shadow` — **Base-Mark 6-Corner Shadow** (On-court · Base recovery)
2. `clear-to-base-multi-shuttle` — **Clear-to-Base Multi-Shuttle** (On-court · Base recovery)
3. `net-to-rear-recovery` — **Net-to-Rear Recovery Drill** (On-court · Court coverage)
4. `front-back-2-corner-feed` — **Front-Back 2-Corner Feeding** (On-court · Court coverage)
5. `opposite-side-corner-recovery` — **Wide-Corner Balance Feeding** (On-court · Lateral balance)
6. `centre-line-split-reset` — **Centre-Line Split-Step Reset** (On-court · Lateral balance)
7. `fatigue-6-corner-shadow` — **Fatigue-State 6-Corner Shadow** (On-court · Endurance)
8. `split-squat-endurance` — **Split-Squat Endurance Sets** (Off-court · Endurance)
9. `cone-touch-base-discipline` — **Cone-Touch Base Discipline** (On-court · Base recovery)
10. `drop-and-follow-net-attack` — **Drop-and-Follow Net Attack** (On-court · Court coverage)
11. `forecourt-lunge-net-kill` — **Forecourt Lunge & Net-Kill Feed** (On-court · Court coverage)
12. `repeat-burst-multi-shuttle` — **Repeat-Burst Multi-Shuttle** (On-court · Explosive speed)
13. `lateral-bounds-split-jumps` — **Lateral Bounds & Split-Jumps** (Off-court · Explosive speed)

---

## 6. Layer 3: UI Integration (Android & iOS Parity)

### 6.1 Navigation & Availability (`AnalyticsPanel.kt`, `AnalysisCapability.kt`)
- Add `AnalyticsPanel.Drills` with `label = "Drills"` *(my call for 4-pill large-text fit — `"Drills"` is 6 chars vs `"Exercises"` 9 chars, keeping `"Skeleton"` as the widest pill word on 320pt/360dp screens; section copy inside the panel clearly presents suggested exercises & drills)*.
- `availablePanels(hasTrack, hasBoundedClips, hasSkeleton, hasVideo)`:
  ```kotlin
  add(AnalyticsPanel.Heatmap)
  if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Base)
  if (hasSkeleton && hasVideo) add(AnalyticsPanel.Skeleton)
  if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Drills)
  ```
- Update `panelCapabilities` in `AnalysisCapability.kt`:
  - `AnalyticsPanel.Drills`: `cloud = "Both players"`, `device = "The near player"`.

### 6.2 Panel Layout (`ExercisesPanel.kt` / `ExercisesPanel.swift`)
Follows `BasePositionPanel` verbatim in structure, threading, gutter (`24dp` / `ShuttlGutter.page`), and empty-message ladder (`source == null`, `samples.isEmpty()`, `windows.none { it.isBounded }`):

```text
┌─────────────────────────────────────────────┐
│  [ Marco  |  Jip ]            (SideToggle)  │  <- shown when 2 tracks exist
├─────────────────────────────────────────────┤
│  MOVEMENT SUMMARY                           │
│  ┌──────────────┐ ┌──────────────┐          │
│  │ 11 rallies   │ │ 4.6 m/s      │          │  <- 2–3 compact stat tiles
│  │ 84% tracked  │ │ peak (0.5 s) │          │     (rallies/coverage, peak speed,
│  └──────────────┘ └──────────────┘          │      front / mid / rear % bar)
│  Front 14% · Mid 54% · Rear 32%             │
├─────────────────────────────────────────────┤
│  SUGGESTED EXERCISES                        │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │ ON-COURT           4 × 45 s · 20 s   ▾│  │
│  │ Base-Mark 6-Corner Shadow             │  │
│  │ Base averaged 2.24 m behind the       │  │  <- exact measured evidence
│  │ service line across 11 rallies.       │  │
│  │ ───────────────────────────────────── │  │  <- expanded (1st card open
│  │ Setup: Place a flat marker 1.4 m...   │  │     by default; tap to toggle)
│  │ 1. Start balanced over the marker...  │  │
│  │ 2. Split-step and lunge to a corner…  │  │
│  │ 3. Push off hard back to the marker…  │  │
│  │ Cue: Land on the mark before looking  │  │
│  │ for the next corner.                  │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │ OFF-COURT             3 × 12 / leg   ▸│  │  <- collapsed by default
│  │ Split-Squat Endurance Sets            │  │
│  │ Base drifted 0.58 m deeper between    │  │
│  │ early and late rallies.               │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  Based on singles rally movement from this  │  <- honesty caveat
│  video. Doubles play and isolated feeding   │
│  drills may skew positioning.               │
└─────────────────────────────────────────────┘
```

- **State & Threading:**
  - Android (`ExercisesPanel.kt`): `produceState` on `Dispatchers.Default` computing `ExerciseReport` per available `CourtSide` (passing `profileArm(side, sideNames)`), so switching `Near | Far` is instant and never blocks Main.
  - iOS (`ExercisesPanel.swift`): `.task(id: inputs)` with `Task.detached(priority: .userInitiated)` mirroring `BasePositionPanel.swift:71-80`. Key `Inputs` on `(samples, side, fps, bounds, racketArm)` so changing player hand or side recomputes cleanly.
- **Card expansion:** First suggested card starts expanded so the coach immediately sees one complete actionable exercise without an extra tap; subsequent cards start collapsed and toggle smoothly on tap (with `accessibilityLabel` / `Role.Button` + expanded state).
- **Insufficient data / Balanced states:** Still show the compact **Movement Summary** (when at least 1 rally was tracked) above the honest `PanelMessage` so the coach can see *why* (e.g., `2 rallies · 71% tracked` → *"Need at least 4 tracked rallies..."*, or `9 rallies · Front 22% · Mid 52% · Rear 26%` → *"Positioning and movement looked balanced..."*).
- **Racket arm fallback:** If `profileArm(shownSide, sideNames)` is `null`, fall back to `racketArmPrefs.get(entryId)` on single-player / near-side views? **No — keep it clean and honest:** `racketArmPrefs` is a single per-video preference shared by both ends (`2026-10-31-player-profiles-design.md:129-132`), so only use `profileArm(shownSide, sideNames)` when a player profile has a hand set, and fall back to `"left of centre"` / `"right of centre"` otherwise so a far opponent is never mis-handed! *(My call.)*

---

## 7. Verification Plan

1. **`:analysis:jvmTest` (`MovementProfileTest.kt`):**
   - Synthetic tracks for both `CourtSide.NEAR` and `CourtSide.FAR` verifying mirrored depth & lateral signs.
   - Base spread, first-third vs. last-third base depth drift (`< 6` rallies → `null`, `>= 6` rallies → exact delta).
   - Zone shares (`front + mid + rear == 1.0`, `left + right == 1.0`) with `MAX_GAP_S = 0.5` gap capping and rally clamping.
   - 0.25 s path length and 0.5 s peak speed (rejecting gaps `> 0.5 s` and speeds `> 8.0 m/s`), plus early-vs-late speed drop.
   - Empty track, unbounded windows, sub-1 s rallies.
2. **`:shared:jvmTest` (`ExerciseAdvisorTest.kt`, `AvailablePanelsTest.kt`, `AnalysisCapabilityTest.kt`):**
   - Quality gates: `< 4` rallies → `INSUFFICIENT_DATA`, `< 50%` coverage → `INSUFFICIENT_DATA`.
   - Balanced profile → `BALANCED` with 0 suggestions.
   - Each of the 7 rules triggers above threshold and does not trigger below threshold.
   - Handedness copy: `RIGHT`, `LEFT`, and `null` produce correct forehand/backhand vs. left/right evidence text.
   - Ranking order, deduplication, `MAX_SUGGESTIONS = 4` cap, and single-finding secondary inclusion.
   - Every `Exercise.id` in `ExerciseCatalog` is unique and every exercise has non-empty title, prescription, setup, `>= 3` steps, and coaching cue.
   - Updated `AvailablePanelsTest` and `AnalysisCapabilityTest` (`panelCapabilities` matches `AnalyticsPanel.entries`).
3. **Android tests (`./gradlew :androidApp:testDebugUnitTest`):**
   - Update `CloudAnalysisPanelsTest.kt` for the new `Drills` panel.
   - Unit test `exercisesMessage` empty/loading ladder matching `BasePositionPanel`.
4. **iOS tests (`xcodebuild test` on Simulator):**
   - Update `CloudAnalysisPanelsTests.swift` for `.drills`.
   - Unit test `exercisesMessage` ladder mirroring `BasePositionPanelTests.swift`.
5. **Visual / layout check (Android + iOS):**
   - Verify 4-pill `Heatmap | Base | Skeleton | Drills` row and `ExercisesPanel` cards in light mode, dark mode, and large text (ensuring no clipped words on narrow screens).

---

## 8. Calls Made (For Coen to Check)

These decisions were not specified in the prompt and are proposed here — any of them can be overridden before planning:

1. **Pill label `"Drills"`** (6 chars) instead of `"Exercises"` (9 chars) so the 4-pill `Heatmap | Base | Skeleton | Drills` row stays within `"Skeleton"`'s width at large text on narrow phones; the panel heading inside reads `"Suggested exercises"`.
2. **On-court drills + 2 off-court conditioning exercises** (11 on-court, 2 off-court for late-match endurance/speed drop), written cleanly as original catalog entries rather than bundling external files.
3. **Track-driven only in this pass** (does not pull `ScoreLog` or clip annotations into `AnalysisSection`), so video-only analyses and scored matches behave identically and `AnalysisSection` keeps its clean data boundary.
4. **Profile `racketArm` only** for forehand/backhand lateral wording (ignores the legacy single-video `racketArmPrefs` fallback so the far opponent is never labelled with the near player's hand).
5. **Expandable cards with the 1st suggestion expanded by default** and up to 4 suggestions shown, plus a compact Movement Summary strip above them.
6. **Thresholds** (`DEEP_BASE > 2.00 m`, `SHALLOW_BASE < 0.75 m`, `LATERAL_BIAS > 0.40 m` or `>= 65%`, `BASE_DRIFT_LATE > 0.45 m`, `INCONSISTENT_BASE > 0.85 m`, `LOW_FRONT_PRESENCE: rear > 55% & front < 12%`, `LATE_SPEED_DROP >= 20%` with `early >= 2.5 m/s`, `MIN_RALLIES = 4`, `MIN_COVERAGE = 50%`) — all isolated as named constants in `ExerciseAdvisor.kt` for easy tuning after real match testing.
7. **No `MatchFormat` gating on `Drills`:** `availablePanels(...)` and `AnalysisSection` keep their existing signatures (matching `Heatmap` and `Base`), and the footer caption notes plainly that doubles play and isolated feeding drills may skew positioning.

## 9. Left to the Builder

- Exact Compose / SwiftUI card styling tokens (surface tint, border stroke, badge pill styling, chevron icon) following existing `ShuttlTheme` / `Shuttl` design tokens.
- Exact wording polish of the 13 exercise descriptions, as long as each stays concise (1–2 line setup, 3–4 steps, 1 coaching cue) and matches the repo's plain, direct tone.
- Whether `ExercisesPanel` shares a small `MovementSummaryCard` helper with tests or keeps it file-private per platform.
