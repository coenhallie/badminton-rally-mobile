# iOS Local Video — Capture, Library, Player (Design)

Date: 2026-07-11
Status: Approved

## Goal

Bring Android's local-video feature set to iOS: record/import videos, a local library
("On this phone") in the match list, and local playback with on-device annotations and
frame-accurate stepping. Keep both apps on the shared version source and bump to 0.3.0
at milestone end.

## Scope decisions

1. **In scope:** capture + import, local library rows, local player with local
   annotations and frame stepping (FrameStepBar parity).
2. **Out of scope (milestone 3 — the analyze seam):** court marking, resumable upload,
   `process-video` invocation, processing progress. Concretely excluded on iOS: Analyze
   buttons (row + player), stage badges/status text, `AnalyzeResultDialog`, retry/
   acknowledge flows. Every iOS entry stays at `AnalyzeStage.LOCAL`.
3. **Architecture: Approach A — promote persistence to shared.** Models and
   repositories move to `shared/commonMain`; ViewModels/UI stay native per platform.
4. **Recordings are also saved to the Photos library** (user choice), mirroring
   Android's gallery-visible `Movies/Shuttl` recordings.
5. **Version bump at milestone end:** `Config/Version.xcconfig` → `MARKETING_VERSION =
   0.3.0`, `CURRENT_PROJECT_VERSION = 10`.

## 1. Shared-module promotion

Move from `androidApp` (`com.badmintontracker.android.localvideo`) to a new
`shared/commonMain` package `com.badmintontracker.shared.localvideo`:

- `LocalVideoEntry` with `AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }`
  and `AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }` (enum order is
  load-bearing for the analyze retry logic — preserve it).
- `LocalAnnotation`.
- `LocalVideoRepository` — Settings key `"local_videos"`, JSON array via
  `ListSerializer(LocalVideoEntry.serializer())`, `ignoreUnknownKeys`, newest-first
  sorting on every mutate/load, corrupt JSON → empty list, mutations serialized under a
  lock. API unchanged: `entries: StateFlow`, `add`, `update(id, transform)`, `remove`,
  `get`.
- `LocalAnnotationsRepository` — Settings key `"local_annotations"`, JSON map
  `videoId -> [LocalAnnotation]`, timestamp-sorted lists, corrupt JSON → empty map,
  same lock discipline. API unchanged: `byVideoId: StateFlow`, `annotationsFor`,
  `hasAnnotations`, `add`, `delete`, `removeAllFor`.

**Persistence compatibility is a hard requirement:** identical Settings keys, field
names, and JSON shapes, so existing Android installs keep their data across the app
update.

Platform gaps get shared helpers:
- `expect`/`actual` internal lock (`SyncLock` with `withLock`): actuals in `androidMain`
  and `jvmMain` use `synchronized`; the `iosMain` actual uses `NSRecursiveLock`.
- `randomUuid(): String` via `kotlin.uuid.Uuid.random()` in commonMain (stable enough on
  Kotlin 2.3; no expect/actual needed).
- Timestamps (`addedAtEpochMs`, `createdAtEpochMs`) are passed in by callers (as today);
  no clock abstraction needed.

`RallyApp` gains two properties constructed from its existing `Settings`:
`val localVideos: LocalVideoRepository` and `val localAnnotations:
LocalAnnotationsRepository`. Android's `RallyAndroidApp` stops constructing its own
instances and passes `rally.localVideos` / `rally.localAnnotations` through (same
`Settings` instance as before, so stored data is unaffected). All Android imports
update; Android ViewModels, `AnalyzeCoordinator`, and court marking are otherwise
untouched.

The Android tests `LocalVideoRepositoryTest` and `LocalAnnotationsRepositoryTest` move
to `shared/commonTest` (using `multiplatform-settings-test` `MapSettings`, already a
commonTest dependency), preserving all pinned behaviors: CRUD roundtrip, newest-first
ordering, cross-instance persistence, 100-concurrent-writes safety, corrupt-JSON
fallback, per-video isolation, delete/removeAllFor semantics.

## 2. iOS storage & intake

- **File store:** `Documents/LocalVideos/<uuid>.mp4`. The entry's `uri` field stores the
  path **relative to Documents** (`LocalVideos/<uuid>.mp4`); a resolver produces the
  absolute `URL` at use time (the container path changes across app updates, so absolute
  paths must never be persisted).
- **Import:** `PHPickerViewController` (SwiftUI representable), `filter = .videos`,
  no permission prompt. The provider's file is copied into the store; `displayName`
  comes from the provider's suggested name (fallback `"video.mp4"`).
- **Record:** system camera via `UIImagePickerController` representable
  (`sourceType = .camera`, movie capture). Requires `NSCameraUsageDescription` and
  `NSMicrophoneUsageDescription`. The temp movie is copied into the store with
  `displayName = "shuttl_<epochMillis>.mp4"` (Android's pattern) and additionally saved
  to the Photos library (`NSPhotoLibraryAddUsageDescription`); a Photos-save failure is
  non-fatal (the in-app copy is authoritative).
- **Metadata:** duration via `AVURLAsset.load(.duration)` (0 on failure, like Android's
  best-effort retriever); size via `FileManager` attributes.
- **Cap:** 1 GB (1_073_741_824 bytes), checked before the entry is created; oversize
  files are rejected with Android's exact copy: **"Video is larger than 1GB. Please use
  a shorter recording."** — shown as an error banner on the clip list (Android uses a
  snackbar). The copied file is deleted on rejection.
- **Remove:** "Remove from app" deletes the entry, its annotations
  (`removeAllFor(id)`), and the stored file. Videos saved to Photos survive (that copy
  belongs to the user).

## 3. iOS UI

**Clip list additions (`ClipListView`):**
- Toolbar **+** button (accessibility label "Add video") with menu items **"Record
  video"** and **"Import video"**.
- New **"On this phone"** section above "My matches", rendered from
  `rally.localVideos.entries` (SKIE AsyncSequence). Row: 96×54 thumbnail via
  `AVAssetImageGenerator` (cached per entry id), `displayName` (single line,
  truncated), subtitle `"M:SS · <MMM d, yyyy>"` uppercased (duration format m:ss —
  Android's `formatDuration`; date via the existing pinned-locale formatter), overflow
  menu (label "Local video menu") with **"Remove from app"**. Tap → local player.
- Empty state (no local videos AND no matches, not refreshing) becomes Android's full
  copy: **"No matches yet. Record one with the + button above."**
- Intake errors (cap, copy failures) render in the existing error banner slot.

**Local player (`LocalPlayerView` + model):**
- AVPlayer over the resolved file URL, paused initially, zero-tolerance seeking.
- Portrait layout: 16:9 player, **FrameStepBar**, annotation list below; storage note
  shown in both empty and non-empty annotation states: **"Annotations are saved on this
  phone and are removed if you remove the video from the app."**
- **FrameStepBar** (Swift port): previous-frame tap = step −1, hold = step −3 every
  100 ms; next-frame tap = step +1, hold = play, pause on release; hold activation
  400 ms. The stepping math is extracted as a pure `FrameStepMath` function mirroring
  Android exactly: fps from the video track's `nominalFrameRate` (fallback 30), frame
  index computed with a half-frame offset to survive position truncation, target =
  `frameIndex * frameDuration + 1ms`, clamped to `[0, duration]`, sought with zero
  tolerance.
- Annotations: reuse milestone 1's `AddAnnotationSheet`, kind badges, and row layout;
  add button captures the player's current position (coerced ≥ 0); tap-to-seek; delete
  confirm dialog titled **"Delete annotation?"** with text = the quoted body (or "This
  annotation" when body is blank), Delete/Cancel buttons. Backed by
  `rally.localAnnotations` (add trims body; blank body + nil kind is a no-op).
- Playback error overlay: **"Couldn't play this video. The file may have been moved or
  deleted."** with a Retry button that re-creates the player item.
- Fullscreen: rely on the system player controls' fullscreen affordance
  (`AVPlayerViewController` when needed); no custom orientation locking this milestone.

## 4. Versioning

Final task: `Config/Version.xcconfig` → `MARKETING_VERSION = 0.3.0`,
`CURRENT_PROJECT_VERSION = 10`; CHANGELOG entry under Unreleased. Both apps read the
file at build time — no other change needed. Tag `v0.3.0` at release; existing CI
verifies the tag.

## 5. Testing & error handling

- **Shared:** the two moved repository test suites in `commonTest` (run by the existing
  `shared-tests` job; also compiled for iOS by the `ios-framework` job).
- **iOS unit tests (XCTest):** `FrameStepMath` (step from truncated positions, half-frame
  slack, clamping, fps fallback), duration formatting (`65_000 ms → "1:05"`), and the
  relative-path resolver.
- **iOS integration:** build + simulator walkthrough (import a video, see the row +
  thumbnail, play, frame-step, annotate, remove) — camera capture requires a physical
  device and is user-verified.
- **Android regression:** full existing suite must stay green after the import
  refactor; no Android behavior change is expected.
- Error copy matches Android verbatim wherever a counterpart exists (cap message,
  playback error, storage note, dialog copy).

## Out of scope (future)

- Analyze pipeline on iOS (court marking, upload with resume, processing progress) —
  milestone 3; the promoted `AnalyzeStage`/`AnalyzeStep`/`keypoints` fields land now so
  milestone 3 needs no model migration.
- Promoting `AnalyzeCoordinator` to shared (decide at milestone 3).
- Custom fullscreen/orientation handling in the local player.
