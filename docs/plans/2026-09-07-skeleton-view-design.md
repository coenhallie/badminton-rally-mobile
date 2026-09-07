# Design: the skeleton view

**Date:** 2026-09-07
**Status:** Approved 2026-09-07; plan at `2026-09-07-skeleton-view-plan.md`
**Follows:** `2026-09-01-phase2-near-player-design.md` (which built the pose
pass and `SkeletonOverlay` but gave the overlay no host),
`2026-09-04-analytics-plan.md` (which cancelled the tab row until "the second
renderer" existed), `tools/models/reports/heatmap-accuracy-2026-09-07.md`
(the accuracy work this sits on)

The court heatmap answers where the player was. The skeleton view answers what
they were doing there: the video, with the near player's pose drawn over it,
stepped frame by frame to the moment of contact. It is the second renderer the
Analytics detail was waiting for, and it is Android only, because iOS has no
on-device analysis at all.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The metric exists and keeps nothing

- `AnalysisMetric.SKELETON_PLAYBACK` (`AnalysisPlan.kt:22`) is selectable on
  the court-marking screen, priced ("Keep every joint for overlay playback.
  Needs pose, and far more storage", `MetricSelector.kt:92,99`), and makes
  `needsPose` true (`AnalysisPlan.kt:25`), so the run loads the pose model
  (`LocalAnalysisRunner.kt:131-142`).
- `LocalAnalysisOutcome` carries `result`, `clipWindows`, `playerTrack` and no
  raw output (`LocalAnalysisCoordinator.kt:19-32`). The `RawInference` goes out
  of scope at `:67`. Nothing writes it to disk: `RawInferenceCodec.encode` has
  no production caller.
- `PlayerTrackStore` persists `frame,courtX,courtY` per sample and nothing
  else (`PlayerTrackStore.kt:26-32`).
- So today `SKELETON_PLAYBACK` is behaviourally identical to
  `PLAYER_MOVEMENT`: the same pass, the same artefact.

### 1.2 The selector discards the person it chose

`NearPlayerSelector.select` has the winning `PosePerson` in hand when it
assigns `best` (`NearPlayer.kt:121-124`) and returns only
`Result(sample, rejection)` (`:189`). `buildNearPlayerTrack`
(`PlayerTrack.kt:44-73`) is the one loop that sees every frame's result.

### 1.3 The renderer exists and matches the player's fit

`SkeletonOverlay(keypoints, confidence, videoWidth, videoHeight)`
(`SkeletonOverlay.kt:28-33`) draws `Skeleton.EDGES` in source-video pixels,
letterboxing with `min(size.width / videoWidth, size.height / videoHeight)`
and centred offsets (`:41-43`), which is what `RESIZE_MODE_FIT` does. It hides
joints under `MIN_KEYPOINT_CONFIDENCE`, the same threshold the selector gates
ankles on.

### 1.4 Playback has no position source

- Every player is Media3 `ExoPlayer` with `SeekParameters.EXACT`
  (`LocalPlayerScreen.kt:94-96`, `ClipDetailScreen.kt:75-79`) in a
  `PlayerView` with a texture surface (`res/layout/clip_player_view.xml`).
- The only position reads are imperative, at a tap (`LocalPlayerScreen.kt:237`,
  `FrameStepBar.kt:29`). There is no listener, no polling loop, no
  `withFrameNanos` anywhere.
- `FrameStepBar.kt:14-34` documents the millisecond truncation: once a frame
  renders, `currentPosition` is its presentation time rounded down, so a naive
  floor maps it to the previous frame. It adds half a frame before dividing.
- Both real players draw a sibling over the `AndroidView` inside one `Box`
  for the error surface (`LocalPlayerScreen.kt:170-197`); `CourtMarkingScreen`
  constrains its container to the video's aspect ratio instead of computing
  letterbox offsets (`CourtMarkingScreen.kt:259-263`).

### 1.5 Timestamps: the decode pass throws away the one it is handed

- `VideoFrameSource.forEachFrame` passes the container presentation time to
  its callback (`VideoFrameSource.kt:154`) and its KDoc says why: on a
  variable-frame-rate source, `frame / fps` disagrees with it (`:99-103`).
- `TrackNetRunner.track` drops it: `onFrame: (Int, Image) -> Unit`
  (`TrackNetRunner.kt:56`, called at `:100` with `index, image`).
- `AndroidLocalInferenceEngine` then writes `timestamp = i / meta.fps`
  (`AndroidLocalInferenceEngine.kt:116`).
- Phone recordings are commonly variable frame rate. A frame-synchronised
  overlay is exactly where that drift would show.

### 1.6 The emulator cannot start a run

`VideoFrameSource.metadata()` errors when the retriever has no
`METADATA_KEY_VIDEO_FRAME_COUNT` (`VideoFrameSource.kt:34-35`). The arm64
emulator's retriever reports none for the corpus video, so `PoseRunnerTest`
and the whole local pipeline fail there before decoding a frame, while
`PoseParityDumpTest`, which never calls `metadata()`, runs. The S23 reports
the key. A device whose retriever does not is a real-device risk, not only a
test-bench one.

### 1.7 The detail screen and its missing tab row

- `AnalyticsDetailScreen(entryId, localAnalysis, onBack)`
  (`AnalyticsDetailScreen.kt:41-45`) shows a "HEATMAP" heading over
  `HeatmapPanel`. Its KDoc (`:22-38`) records why there is no tab row: a
  single pill read as a dead button, and "the tab row arrives with the second
  renderer".
- The house segmented control is M3 `SingleChoiceSegmentedButtonRow` as the
  match page builds it (`MatchScreen.kt:345-357`), drawn only when both
  facets have content (`:179-180`).
- The route passes only `entryId` and the runner (`AuthGate.kt:608-614`);
  `localVideos` and `rally.playbackPrefs` are in scope there (`AuthGate.kt:90`,
  `:489`).
- The file the pose actually ran over is `filesDir/local-sources/$entryId.mp4`
  when the entry came from a `content://` URI, or the file itself when the
  URI was a path (`LocalAnalysisRunner.kt:224-234`). It is referenced nowhere
  else.

### 1.8 `HeatmapPanel`'s resolution discipline

`heatmapSource` prefers the in-memory run only when it has samples, else the
disk (`HeatmapPanel.kt:41-48`), and returns the track and its fps together
because "picking each independently paired them by coincidence" (`:37-39`).

---

## 2. The fact the design turns on

**The skeleton is a storage and timing problem, not a rendering one.** The
pose pass already runs, the renderer already draws, and the selector already
picks the player. What is missing is keeping the chosen person's joints with
the time they were seen at, and asking the player what time it is showing.
Everything below is those two things, done so the frame under the skeleton is
the frame the joints came from: container timestamps stored per pose, and a
nearest-timestamp lookup at playback with a tolerance of half a frame.

---

## 3. Decisions

| Decision | Chosen | Rejected, and why |
| --- | --- | --- |
| What is stored | The near player only, per frame: 17 joints with confidence, plus the frame's container timestamp | Every person, as `RawInference`: twice the size for a second player this pipeline does not track, and the far player's joints are the untrustworthy half |
| Where the selection comes from | `NearPlayerSelector.Result` carries the chosen person; one pure function returns the track and the poses together | A second selection pass for poses: two calls that could drift apart on any gate change |
| Time base | The container presentation time of each frame, carried through the decode pass and stored per pose | `frame / fps` at playback, as `FrameStepBar` does for stepping: wrong on variable-frame-rate sources, which phone recordings usually are |
| Playback lookup | Nearest stored timestamp within half a frame; nothing drawn when none is within it | Hold the last skeleton: draws a player where they are not, which is the one thing the overlay must not do |
| When it is written | Only when `SKELETON_PLAYBACK` was requested, before clips are cut | Always: the metric selector prices it as storage, and a coach who declined it should not pay it |
| Storage format | Binary, little-endian, fixed width, versioned header with fps and video size | The track store's text: 216 bytes per pose is 11 MB for a 30-minute match, and a person does not read a joint list |
| Video source | The file the pose ran over (`local-sources/$entryId.mp4`, or the path itself) | `entry.uri`: a content grant can be revoked, and a re-encoded or rotated copy would not share the analysed frames |
| Container sizing | A `Box` constrained to the video's aspect ratio, as court marking does | The players' 60%-height black box with letterbox arithmetic: works, but the overlay then depends on two sizing computations agreeing |
| Position source | A `withFrameNanos` loop reading `currentPosition` while the panel is composed | A `Player.Listener`: Media3 has no per-frame position callback |
| Tab row | `SingleChoiceSegmentedButtonRow` as the match page builds it, shown only when a skeleton is stored | A custom pill: the cancelled one; and a control shown with nothing to switch to, which the KDoc rightly refused |
| Frame count fallback | Count samples with `MediaExtractor` when the retriever has no frame count | Fail as today: it stops the pipeline on any device whose retriever lacks the key, and on the emulator this work has to be verified on |

---

## 4. `:analysis`, the pure layer

Package `com.badmintontracker.analysis.player`.

### 4.1 `PlayerPose`

```kotlin
/** The near player's joints in one frame, in source-video pixels. */
data class PlayerPose(
    val frame: Int,
    /** Container presentation time in seconds; what playback is matched on. */
    val timestamp: Double,
    val keypoints: List<Point>,        // COCO-17 order
    val confidence: List<Float>,
)
```

### 4.2 The selection returns both

```kotlin
data class NearPlayerSelection(val track: PlayerTrack, val poses: List<PlayerPose>)

fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection
fun buildNearPlayerTrack(raw: RawInference, keypoints: CourtKeypoints): PlayerTrack =
    selectNearPlayer(raw, keypoints).track
```

`NearPlayerSelector.Result` gains `person: PosePerson?`, set beside `best`.
`poses` has exactly one entry per sample, at the same frame, by construction.
`PlayerSample` and the v2 track format do not change.

### 4.3 The lookup

```kotlin
/**
 * The pose nearest [seconds], or null when none is within [toleranceS].
 * Binary search over poses sorted by timestamp.
 */
fun nearestPose(poses: List<PlayerPose>, seconds: Double, toleranceS: Double): PlayerPose?
```

The tolerance is half a frame period at the stored fps, plus one millisecond
for the player's truncation (§1.4).

---

## 5. The decode pass carries its timestamps

- `TrackNetRunner.track`'s `onFrame` becomes `(Int, Double, Image) -> Unit`,
  passing the presentation time it already receives.
- `AndroidLocalInferenceEngine` records it per frame and writes it to
  `RawFrame.timestamp`, falling back to `i / fps` only for a frame the decoder
  never reported, which does not happen on the corpus.
- `VideoFrameSource.metadata()` falls back to counting samples with
  `MediaExtractor` when the retriever has no frame count. The scan reads no
  pixels; it is a few hundred milliseconds on a 30-minute file.

Both are contained changes that fix documented inconsistencies, and both are
prerequisites: the first for the time base, the second for verifying any of
this on the bench available.

---

## 6. Storage

`SkeletonStore` in `androidApp/.../localanalysis/`, at
`filesDir/skeletons/$entryId.skel`, little-endian:

| field | bytes |
| --- | --- |
| magic `SKEL` | 4 |
| version i32 = 1 | 4 |
| fps f64 | 8 |
| videoWidth i32, videoHeight i32 | 8 |
| poseCount i32 | 4 |
| per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32) | 216 |

`save(entryId, poses, fps, videoWidth, videoHeight)`, `has(entryId)` (header
read only, refuses another version, mirroring `PlayerTrackStore.has`), and
`load(entryId): Stored?` returning poses, fps and video size together, for
the §1.8 reason.

`LocalAnalysisOutcome` gains `poses`, `videoWidth` and `videoHeight`.
`LocalAnalysisRunner` writes the skeleton right after the track, when
`AnalysisMetric.SKELETON_PLAYBACK in metrics && poses.isNotEmpty()`, and
exposes `hasStoredSkeleton`, `storedSkeleton` and `analysedSource(entryId, uri)`.

---

## 7. The screen

### 7.1 The detail gains its second renderer

`AnalyticsDetailScreen` takes the entry and the playback preferences as well.
When `hasStoredSkeleton(entryId)` is true it draws a two-segment
`SingleChoiceSegmentedButtonRow` ("Heatmap", "Skeleton") in place of the
"HEATMAP" heading, built exactly as `MatchScreen.kt:345-357` builds its facet
selector; otherwise the heading and `HeatmapPanel` exactly as today. The
choice survives rotation through `rememberSaveable`.

### 7.2 `SkeletonPanel`

Resolves the stored skeleton and the analysed source file. If either is
missing it says which, in one line, instead of drawing. Otherwise
`SkeletonPlayer(file, stored, prefs)`:

- An `ExoPlayer` with `SeekParameters.EXACT`, released on dispose, the media
  item prepared once, `playWhenReady = false`.
- A `Box` with `.aspectRatio(videoWidth / videoHeight)`, black, holding the
  `PlayerView` (controller off; the bars below own transport) and
  `SkeletonOverlay(fillMaxSize)` as its sibling.
- A `LaunchedEffect(player)` loop: `withFrameNanos` then read
  `currentPosition`, into a `mutableLongStateOf`. The overlay's pose is
  `nearestPose(poses, positionMs / 1000.0, tolerance)`; null draws nothing.
- Below the box: `PlaybackControlBar(player, prefs)` and `FrameStepBar(player)`,
  reused as they are. Stepping is the coaching gesture: the wrist at contact.
- A one-line summary under the bars: "Skeleton in N frames", with the frame
  under the overlay, or "no skeleton at this frame" when nothing is drawn, so
  an empty overlay reads as absence rather than as a broken view.

### 7.3 Not on the list rows

The Analytics list's READY affordance does not change. A row that is READY
opens the detail, and the detail decides what it can show.

---

## 8. Deliberately not in this pass

- **iOS.** No on-device analysis exists there (`AnalyticsRows.swift:108-116`).
- **The far player, both players, handedness.** Phase 2 design §6.
- **Skeletons over rally clips.** Clips are re-encoded with rebased
  timestamps (`ClipCutter.kt:129-130`); the source file is the frame-exact
  one. A clip-scoped view is a follow-up that needs the clip's start offset.
- **Migrating older runs.** A run before this change stored no joints; it
  needs re-running with the metric ticked, and the panel says so.
- **Compressing the store.** 11 MB per 30-minute match is acceptable and the
  metric selector already prices it.
- **A scrubber over the poses or a timeline of contacts.** Product work with
  its own design.
- **Rotated (portrait) sources.** The pipeline has no rotation concept:
  keypoints and the stored width and height are in coded (unrotated) space,
  while the player applies the container's own rotation, so a 90 or 270
  degree source would draw the skeleton off the body. Court footage is
  landscape, so this has not come up. A guard would be to read the
  container's rotation and not offer the Skeleton segment when it is
  non-zero.

---

## 9. How this is verified

### 9.1 `commonTest`

- `selectNearPlayer` yields poses and samples at the same frames, one to one,
  and the pose keypoints are the chosen person's, on hand-built frames with
  two people where the more confident is on the far side.
- `DevicePoseDumpTest` extended: the device dump yields a pose for every
  frame that yielded a sample, with 17 joints each.
- `nearestPose`: exact hit, within tolerance either side, outside tolerance
  returns null, empty list returns null, a position between two poses picks
  the nearer, and the player-truncation case (a timestamp of 0.033333 asked
  at 0.033) resolves to that pose.
- `LocalAnalysisCoordinator` with the fake engine: poses and video size come
  through the outcome; a pose-less run yields an empty list.

### 9.2 Android unit tests

- `SkeletonStore` round trip; `has` false for a missing file and for a v0
  header; `load` null for a truncated file; fps and video size come back
  with the poses.

### 9.3 On the emulator

- `VideoFrameSourceTest`: `metadata()` succeeds on the corpus video and its
  frame count equals a sample scan.
- `Phase2EndToEndTest` extended to assert every raw frame's timestamp is the
  container's (monotonic, first frame at 0, and not `i / fps` to the
  microsecond).
- The app: import the corpus video, mark the court, tick Skeleton playback,
  run, open Analytics, switch to Skeleton, step to a frame of contact.
  Screenshots into `docs/screenshots/`, light and dark: the segmented control,
  the overlay on a standing frame, the overlay at contact, and the panel's
  line when no skeleton is stored.
