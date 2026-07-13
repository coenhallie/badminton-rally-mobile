# iOS Analyze Pipeline — Court Marking, Upload, Processing (Design)

Date: 2026-07-13
Status: Approved

## Goal

Bring the analyze pipeline to iOS with full Android parity — 12-point court marking,
resumable foreground upload, `process-video` orchestration, progress/result UI — by
promoting the orchestration (`AnalyzeCoordinator`) and the pure court-marking geometry
to `shared/commonMain`. This completes feature parity between the two apps.

## Decisions

1. **Approach A: promote `AnalyzeCoordinator` to shared.** It is near-pure (single
   JVM-ism: `java.util.Collections.synchronizedSet`, replaced by the existing shared
   `SyncLock`); its platform seams (`openChannel`, `log`, `scope`) are already
   injected. Android keeps constructing it exactly as today.
2. **Promote `CourtMarkingModel` too** (`CourtPoint`, `CourtMarkingSpec`,
   `CourtMarkingState`) — verified 100% pure Kotlin; moves verbatim with its tests.
   ViewModels and all UI stay native per platform.
3. **Foreground-only uploads** (user decision): keep-awake while uploading, resume
   from the last TUS offset after interruption. No background-URLSession path.
4. **Version bump to 0.4.0 (12)** as the final task (feature-parity milestone).

## 1. Shared promotions

- `AnalyzeCoordinator` + `AnalyzeProgress` move to
  `com.badmintontracker.shared.localvideo`. The one JVM line (`synchronizedSet`)
  becomes a plain `MutableSet` guarded by the shared `SyncLock`. Constructor,
  step logic (`UPLOAD → CREATE_ROW → KEYPOINTS → TRIGGER → PROCESSING` with ordinal
  `startFrom` comparison), retry-from-failed-step, reattach, duplicate-key tolerance,
  annotation-aware completion (ANALYZED vs remove), `resultSeen` reset on failure,
  progress map, `hasActiveUpload` — all byte-identical behavior. Error copy unchanged
  ("No court points saved", "Couldn't register video", "Couldn't save court points",
  "Couldn't start analysis", "Analysis failed",
  "Analysis finished but found no rallies in this video.").
- `CourtMarkingModel.kt` (`CourtPoint`, `CourtMarkingSpec` with the 12 labels/colors/
  order, `CourtMarkingState` with `place`/`undo`/`clear`/`toCourtKeypoints`) moves to
  `com.badmintontracker.shared.localvideo.court` verbatim.
- Tests: `AnalyzeCoordinatorTest` (10 behaviors incl. resume/reattach/no-rallies/
  annotations-kept) and `CourtMarkingModelTest` move to `commonTest`, along with
  `FakeVideosRepository`/`FakeClipsRepository` (to a shared `testing` package in
  commonTest). Android's `CourtMarkingViewModelTest` stays (its VM stays Android).
- Android refactor: imports only, plus `RallyAndroidApp` constructing the shared
  coordinator (same injected lambdas). Zero behavior change.

## 2. iOS platform glue (iosMain + Swift)

- `iosMain` factory `createIosAnalyzeCoordinator(rally: RallyApp, documentsPath: String): AnalyzeCoordinator`
  (the established `createRallyApp` pattern) — it wires the coordinator with an
  internal channel opener that resolves the entry's Documents-relative `uri` against
  `documentsPath`, implemented with `NSFileHandle` positioned at `offset`, streaming 8 KiB-order chunks
  through a writer `ByteChannel` (never loads the file into memory). Missing file →
  throws with the Android-parity message "Video file is missing or access was
  revoked" (surfaces as FAILED at UPLOAD).
- The iOS coordinator instance lives for the app session (created in the App init
  alongside `RallyApp`), scope = `SupervisorJob() + Dispatchers.Default` equivalent;
  `reattachToProcessing()` called at startup.
- Keep-awake: observe `hasActiveUpload` and set
  `UIApplication.shared.isIdleTimerDisabled` accordingly (Android's
  FLAG_KEEP_SCREEN_ON analog).

## 3. Court marking screen (SwiftUI)

Mirrors Android's tap-in-order flow exactly, backed by the shared model:

- Title "COURT MAPPING"; first frame extracted at t = 0.1 s via
  `AVAssetImageGenerator` at source resolution (failure copy: "Couldn't extract video
  frame" / "Couldn't load frame" states as on Android).
- Tap to place points in the fixed order (indices 0-11: TL, TR, BR, BL, NL, NR, SNL,
  SNR, SFL, SFR, CTN, CTF) with Android's exact colors and short labels; taps beyond
  12 ignored (model behavior).
- Instruction row: "Tap: <full label>" colored per point / "All points placed";
  "<n> / 12 points" counter.
- Pinch-zoom 1–6× with pan, transform-origin top-left; tap coordinates are
  inverse-transformed before hitting the shared `place()` (display-only affordance —
  output coordinates are unaffected). Point markers keep constant on-screen size
  (radius scaled by 1/zoom).
- Overlays: dashed court guide (15% margins, net at h/2, service lines at the 0.6
  factor, center line), dashed corner rectangle once 4+ points placed.
- Schematic mini-court (court meters: 6.1 × 13.4, service line 1.98) showing
  placed/next/unplaced landmarks, with Android's copy: "Tap each court landmark in
  the order shown." and "12 points give precise homography for player tracking,
  speeds and zones. Pinch to zoom for accuracy."
- Buttons: Undo / Clear (enabled when points exist); "Start Analysis" when complete →
  `coordinator.startAnalysis(entryId, marking.toCourtKeypoints())` → pop back to the
  clip list.

## 4. Analyze UI wiring (iOS)

- Local rows: "Analyze" button when `canAnalyze` (stage LOCAL or FAILED); otherwise a
  small progress spinner while the pipeline runs. Status line from
  progress + entries: "Uploading N%…" / "Uploading…", "Analyzing N%…" / "Analyzing…",
  "Analyzed" (verbatim Android mapping; LOCAL and FAILED show none).
- Local player: "Analyze" toolbar button when `canAnalyze` → court marking (no
  retry-skip branch, matching Android's player).
- Routing rule (list): FAILED entry with saved keypoints → `coordinator.retry(id)`
  directly; otherwise → court marking screen.
- Auto result dialog: first FAILED entry with `resultSeen == false` triggers an alert;
  title "No rallies found" when the message contains "no rallies" (case-insensitive),
  else "Analysis failed"; body = failureMessage (fallback "Unknown error"); buttons
  Retry (acknowledges + re-routes through the analyze action) and Close
  (acknowledges only). Acknowledge = persist `resultSeen = true`.
- Completion behavior comes from the shared coordinator: clips refreshed (new match
  appears), entry removed — or kept as "Analyzed" when it has local annotations.

## 5. Versioning

Final task: `Config/Version.xcconfig` → `MARKETING_VERSION = 0.4.0`,
`CURRENT_PROJECT_VERSION = 12`; CHANGELOG entry. Tag `v0.4.0` at release; CI verifies.

## 6. Testing & verification

- Shared: the moved `AnalyzeCoordinatorTest` (10 cases) and `CourtMarkingModelTest`
  suites in `commonTest` with the promoted fakes; existing CI jobs run them and
  compile them for iOS.
- Android: full suite green; import-only diffs (plus the fakes' package move in
  tests).
- iOS: XCTest for the status-text mapping and court-marking view-state glue where it
  goes beyond binding; suite + build per task.
- Live acceptance (user walkthrough at milestone end): analyze a short real video
  end-to-end on the simulator/device — court marking taps, upload progress, processing
  progress, resulting rally clips in the match list; a failure path (e.g. no-rallies
  video) shows the result dialog with Retry.

## Out of scope

- Background (URLSession) uploads — foreground-only per decision.
- Custom fullscreen/orientation player work (still deferred).
- Phase-2 analytics surfaces (desktop-only, as on Android).
- Promoting `CourtMarkingViewModel` (stays a thin native layer on both platforms).
