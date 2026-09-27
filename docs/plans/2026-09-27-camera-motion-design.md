# Design: follow the camera, so a handheld recording still maps onto the court

**Date:** 2026-09-27
**Status:** Approved for implementation by Coen on 2026-09-27
("implement it ... make sure it works for both the local pipeline and the
cloud"). Coen delegated the design calls; a reviewer agent reviewed this spec in
his place.
**Spans two repos:** `badminton-rally-mobile` (this one) and `badminton-tracker`
(the Modal worker). Deploying the worker is the owner's step.
**Follows:** `2026-09-12-cloud-pose-artifacts-design.md` (the RawInference
artifact this extends), `2026-09-07-pose-metrics-plan.md` (the skeleton store
this extends), and the 2026-09-27 heatmap accuracy investigation summarised
in §1.

---

## 1. The problem, measured

A coach filmed an All England practice from a seat behind the near baseline
(`IMG_5892.MOV`, iPhone 16 Pro, 1920x1080, 72.7 s, no rotation). The heatmap
disagreed with what he saw on the video. The cause is that the phone moved.

- The court is marked once, on the still at 0.1 s. Every frame of the video is
  then mapped to the court through that one marking.
- The phone tilted and panned while recording. Measured against the stands
  (ORB features, RANSAC homography, every frame), the court moved a median of
  61 px from where it was marked, p90 118 px, worst 139 px. The scale stayed
  at 1.00: the phone rotated in place; it did not zoom or walk.
- Run through the app's own selection rules with near-perfect marks (the
  painted-line crossings, fitted to 3 cm), the fixed marking put the player
  metres from where she stood:

| | fixed marks (today) | camera followed |
|---|---|---|
| frames with a position | 69% | 99% |
| same frames, position difference | median 60 cm, p90 2.4 m, worst 4.3 m | reference |

- For 22 seconds of the 72 (0:11 to 0:23 and 0:28 to 0:37) the camera had
  tilted far enough that her feet sat above the old net line. The selector read
  her as the far player and dropped her.
- The Base panel reads the same track, so it inherits the same error, and the
  Skeleton panel's two court-plane tiles (stance, distance behind the service
  line) project her ankles through the same fixed fit.

A measured comparison of ways to follow the camera, on the same video, host,
one thread, scored against the painted lines and against the every-frame ORB
reference:

| method | host ms/frame | line error px, median / p95 | position vs reference, median / p95 |
|---|---|---|---|
| ORB + RANSAC vs the marking frame, 960 wide, every frame | 172 | 0.87 / 2.11 | reference |
| chained KLT (frame to frame), 480 wide, every frame | 1.1 | 0.99 / 2.94 | 4.1 / 14.6 cm |
| translation-only phase correlation, 480 wide | 1.4 | 5.5 / 12 | **154 / 266 cm (fails)** |
| **anchored LK (marking frame to this frame), 480 wide, §4's exact settings: 150 corners, 3 pyramid levels, 10 Hz** | **0.29** | **0.82 / 2.00** | **1.6 / 4.8 cm** |

(`/tmp/hm/cost/spec_cfg.py`; the reference and anchored tracks are kept in
`~/shuttl-corpus/IMG_5892/` for §8.)

Anchored LK tracks the marking frame's own features into each frame, starting
from the previous estimate, so errors do not accumulate however long the video
is. It never lost the camera on this video at any sampling interval tried
(every frame up to every 30th).

On the phone the pass already costs about 465 ms per frame on an S23
(`tools/models/reports/phase1-throughput-s23.md` plus the pose seed). The
tracker's share is estimated at 1 to 4 ms per frame, under 1%. §8 replaces the
estimate with a device number.

---

## 2. What exists today

Verified by reading the code; file references are the evidence.

### 2.1 One court fit for the whole video

- `selectPlayers` builds one `NearPlayerSelector` for the run
  (`analysis/.../player/PlayerTrack.kt:97-151`), and the selector computes one
  homography from the marks (`NearPlayer.kt:116`) and one net line
  (`NearPlayer.kt:280-290`). Every frame goes through both.
- `PlayerPose` (`PlayerTrack.kt:32-43`) carries the chosen person's keypoints
  in the frame's own pixels. The overlay draws them over the video; that stays.
- The Skeleton panel computes one homography from the stored marks and feeds
  it to `poseMetrics` for every pose (Android `SkeletonPanel.kt:118-143, 271`;
  iOS `SkeletonPanel.swift:114-162, 194-214`).

### 2.2 The marking frame is known only by its time

- Android: `getFrameAtTime(100_000L, OPTION_CLOSEST)`
  (`CourtMarkingScreen.kt:781`). iOS: `CMTime(value: 1, timescale: 10)` with
  zero tolerance before and unlimited after (`CourtFrameLoader.swift:22-25`).
  The web app: `video.currentTime = 0.1` (`CourtSetup.vue:116-117`).
- Nothing stores which frame that was. The marks are 12 pixel pairs
  (`shared/.../model/CourtKeypoints.kt:12-25`).

### 2.3 The per-frame loop and the pose file

- Android decodes with MediaCodec into YUV_420_888 `Image`s and hands each to
  `onFrame(index, seconds, image)` before the TrackNet resize
  (`TrackNetRunner.kt:117-120`); the engine's only `onFrame` runs the detector
  and pose (`AndroidLocalInferenceEngine.kt:80-107`). iOS is the same shape
  with a 420v `CVPixelBuffer` (`TrackNetRunner.swift:115-118`,
  `IosLocalInferenceEngine.swift:141-165`).
- Neither platform has a greyscale downsample today; the luma plane is read
  only inside the RGB samplers (`FramePreprocessor.kt:112, 212`;
  `FramePreprocessor.swift:86-112`).
- The phone never persists a `RawInference`; it is assembled in memory after
  the pass (`AndroidLocalInferenceEngine.kt:118-149`,
  `IosLocalInferenceEngine.swift:173-203`) and handed to
  `LocalAnalysisCoordinator` (`:148`).
- `RawInferenceCodec` is version 1 and `decode` rejects every other version
  (`RawInferenceCodec.kt:27, 69-72`). The cloud's Python writer is also version
  1 (`badminton-tracker/backend/raw_inference_codec.py:40-41`).
- Only the unmerged mobile branches read the cloud artifact
  (`CloudPoseCoordinator.kt:64`, present on `feat/match-page-hub` and
  `feat/sign-in-design` only; no tag or `main` build has it). A format bump
  therefore breaks no shipped build.

### 2.4 The cloud applies the marks to every frame too

In `backend/modal_supabase_processor.py` (branch `feat/cloud-pose-artifacts`,
deployed as Modal app version 24 on 2026-09-16):

- The court area filter keeps a skeleton only if its box bottom lies inside the
  four marked corners scaled 1.02 about their centroid (`:3022-3043`,
  `:3437-3450`). A pan moves the court out from under the fixed polygon and
  drops the player before the phone ever sees them.
- `PlayerIdentityTracker` assigns at most one player per side of a fixed net
  line (`:1606-1612`, `:1726-1772`, `:1812-1817`), fed once at `:2971-2977`.
- The Phase 2 shuttle court area (`:3063-3080`) and the court-to-metres
  transform for speed (`:711-725`, `:3051-3053`, `:3617-3628`) are fixed too.
- Frames are read sequentially and numbered from 1 (`:3132-3169`); the
  timestamp is `CAP_PROP_POS_MSEC` read before each `read()`.

---

## 3. The model

**The camera rotates in place.** A handheld phone filming from a seat turns
and tilts; it does not travel metres. Under pure rotation (and zoom) every
point in the image, the stands and the court floor alike, moves by one
homography. So one homography per frame, estimated from whatever texture is
in view, maps the marking frame onto that frame exactly.

**`M(t)` maps marking-frame pixels to the pixels of the frame at time `t`,**
both in the decoded frame's own pixel space (the space pose keypoints are in).
The court fit for that frame is then `H · M(t)⁻¹`, where `H` is today's fit
from the marks. The net line for that frame is the marked net line mapped by
`M(t)`.

**The marking frame is the first decoded frame whose timestamp is at least the
marking time `T`,** defined by time, not by index, because Android numbers
frames from 0 and the cloud from 1.

**`T` travels with the marks.** The wire `CourtKeypoints` gains an optional
`marked_at_s` (seconds), stored inside `videos.manual_court_keypoints` and the
local entry like the 12 points. `T = marked_at_s ?: 0.1`.

- Why it is needed: iOS's `CourtFrameLoader` allows unlimited tolerance after
  0.1 s, so AVFoundation hands back a later sync frame. On `IMG_5892.MOV` it
  returned the frame at **1.0 s**, measured by the spec review. Marks drawn
  there and anchored at 0.1 s would be off by whatever the camera did in that
  0.9 s.
- New iOS marks: the loader keeps its tolerances and records the generator's
  `actualTime` as `marked_at_s`. (Zero tolerance after would force a 0.1 s
  frame, but recording the truth is the same fix without changing which frame
  a coach sees.)
- New Android marks: `getFrameAtTime(100_000, OPTION_CLOSEST)` returns the
  frame nearest 0.1 s and does not say which; `marked_at_s = 0.1`, within one
  frame.
- The web app writes no `marked_at_s`; 0.1 is what `CourtSetup.vue` seeks to.
- Legacy iOS marks on a local entry (no `marked_at_s`): the iOS run asks the
  same generator, with the same tolerances the marking screen used, for its
  `actualTime`, and anchors there. Legacy iOS marks that reached the cloud
  cannot be told apart from web marks and anchor at 0.1 s; re-marking fixes
  them (§7).
- Nothing reads `manual_court_keypoints` key by key in a way an extra number
  would break: the backend and web read named keys, and the only app decoder
  of the row (`CloudPoseRepository.kt:61`) is unshipped and gets the field.
  Shipped builds only ever write the column (`VideosRepository.kt:147`).
  Local entries decode with `ignoreUnknownKeys` (`LocalVideoRepository.kt:65`).

The cloud's frame clock lags by one frame (`CAP_PROP_POS_MSEC` read before
`read()`: frames 1 and 2 both read 0.0 s, measured by the spec review on
OpenCV 4.13). Its marking frame is therefore one frame later than the
phone's. That is inside the one-frame tolerance, and the join between frames
and camera samples is still exact because both come from the same clock.

A camera that walks (translates by metres) breaks the model, because the
stands and the floor then move differently. That is not handheld-from-a-seat
filming and is out of scope (§7).

---

## 4. The tracker

One implementation for both phones, in `:analysis` (`commonMain`, package
`com.badmintontracker.analysis.camera`), pure Kotlin with no dependencies,
tested on the JVM like the rest of the module. The cloud runs the same
algorithm through OpenCV (§6). The two are not bit-identical; both are held to
the painted lines on the same video (§8).

### 4.1 Input

- A greyscale image, the luma plane area-averaged down so its long side is 480
  px (480x270 for 16:9). Luma, not RGB: the tracker needs texture, not colour,
  and the Y plane is already there. A source already 480 or smaller on its
  long side is used as is.
- Area average for any ratio: output pixel `(ox, oy)` averages source columns
  `floor(ox * W / w) until max(that + 1, floor((ox + 1) * W / w))`, rows
  likewise. Exact for 1920 to 480, approximate for 1280 to 480; the tracker
  does not care.
- Tracker pixels map to source pixels by pixel centres:
  `x_src = (x_t + 0.5) * sx - 0.5` with `sx = W / w`, so
  `S = [[sx, 0, 0.5 sx - 0.5], [0, sy, 0.5 sy - 0.5], [0, 0, 1]]`.
- `Luma.downsample(plane, width, height, rowStride, out)` in `commonMain` does
  the area average. Android copies plane 0 of the `Image` into a reused
  `ByteArray` and calls it. iOS calls a new `BulkInterop` function that reads
  the locked `CVPixelBuffer`'s plane 0 through a pointer, the way
  `medianBackgroundInto` already does (`shared/src/iosMain/.../BulkInterop.kt:73-89`),
  so no 2 MB array crosses the Swift bridge element by element.

### 4.2 Sampling

- The tracker asks for a frame each time the timestamp crosses a 0.1 s
  boundary (10 Hz, whatever the frame rate, and correct on variable frame
  rate), and for the marking frame (the first at or after `T`) whether or not
  it crosses one.
- Frames sampled before the marking frame are buffered and tracked once it
  arrives: 130 KB each, ten per second of `T` (one for the usual `T = 0.1`).
- The tracker is constructed with `T` and the source size; a pass whose marks
  are unknown runs no tracker.
- The anchored tracker does not accumulate error, so a lower rate costs only
  temporal resolution. 10 Hz keeps within 1.6 cm of every-frame accuracy on the
  measured video (§1) and follows a hand's slow drift; it would miss a
  deliberate fast whip, which is §7's problem, not this one's.

### 4.3 One sample

The OpenCV call each step corresponds to is given, so the cloud's
implementation (§6) is the same algorithm by construction.

1. **Reference features, once.** Shi-Tomasi corners on the marking frame:
   up to 150, quality level 0.01 of the strongest response, at least 8 px
   apart, 3x3 structure-tensor window over Sobel gradients, at least 10 px from
   the border. (`goodFeaturesToTrack(img, 150, 0.01, 8, blockSize=3)`, border
   filter applied after.) Fewer than 40 corners means the marking frame has no
   texture to follow: the tracker gives up and the run uses the fixed marks.
2. **Pyramids.** Three levels (full, half, quarter): each level is the one
   below blurred with the 5-tap `[1 4 6 4 1] / 16` kernel in both directions
   (border replicated) and every second pixel taken. Gradients at each level
   are Scharr, scaled by 1/32. The marking frame's pyramid is built once.
   (`calcOpticalFlowPyrLK` with `maxLevel = 2`.)
3. **Anchored pyramidal Lucas-Kanade.** Each reference corner is tracked from
   the marking frame into this frame, starting at the previous accepted `M`
   applied to the corner (the identity before the first). 21x21 window,
   bilinear sampling with the border replicated, up to 20 iterations per
   level, stopping when the update's length is at most 0.03 px. A corner fails
   if its structure tensor's smaller eigenvalue, divided by the window area,
   is under 1e-4, or if it ends outside the image.
   (`winSize = (21, 21)`, `OPTFLOW_USE_INITIAL_FLOW`,
   `criteria = (COUNT | EPS, 20, 0.03)`, `minEigThreshold = 1e-4`.)
4. **RANSAC homography** over the surviving pairs: 4-point minimal fits
   through the existing DLT (`calculateHomography`), inlier threshold 1.0 px at
   tracker scale, 300 iterations from `kotlin.random.Random(seed = 0x5EED)` so a
   run is repeatable on every platform, then a least-squares refit on the
   inliers. (`findHomography(src, dst, RANSAC, 1.0, maxIters = 300)`.)
5. **Accept** when there are at least 40 inliers and the result is plausible:
   the four image corners of the marking frame each land within 25% of the
   image width of where they started, and the determinant of the upper-left
   2x2 lies in 0.5..2. Otherwise the sample is dropped and the next one starts
   from the last accepted `M`.
6. Scale the accepted homography from tracker pixels to source pixels
   (`S · M · S⁻¹`).

### 4.4 Reading `M` at a frame

`CameraMotion(referenceTimestamp, samples)` answers `at(timestamp): Matrix3x3?`,
with samples sorted by timestamp and, where two share a timestamp, the first
kept. In order:

1. A sample whose timestamp equals `t` to within 1e-6 s: its `M`.
2. `t` between two neighbouring samples no more than 0.5 s apart: the
   element-wise linear blend, renormalised so `h22 = 1`. The motion between
   10 Hz samples is a fraction of a pixel of rotation; a blend is exact to well
   under a pixel.
3. Otherwise, the nearest sample if it is within 0.15 s of `t`.
4. Otherwise `null`, meaning **the camera is not known here.**

Every stored `M` has `h22` exactly 1.0, and `CameraMotion`, `CameraSample`
and `M` are plain values (lists, not arrays) so two equal motions compare
equal. Inside the tracker the arithmetic uses `DoubleArray`s; the list form is
only the boundary.

A frame with no known camera produces no position. It is not drawn with the
fixed marks, because §1 shows that is exactly the wrong answer. It is counted
as a new rejection, `CAMERA_LOST`, so a thin track explains itself.

`RawInference.camera == null` means no tracking was done (an older file, a
rallies-only pass): selection uses the fixed marks, as today.

---

## 5. Where `M` is used on the phone

### 5.1 The pose file (RawInference version 2)

- `RawInference` gains `camera: CameraMotion?`.
- Version 2 is version 1's layout plus a camera section after the frames:
  `hasCamera int8`; if 1: `referenceTimestamp float64`, `sampleCount int32`,
  then per sample `timestamp float64` and the eight entries `h00 h01 h02 h10
  h11 h12 h20 h21` as `float64` (`h22 = 1`), in source pixels.
- `decode` reads versions 1 (camera `null`) and 2, and rejects anything left
  over after the camera section. `encode` writes version 2. The Python writer
  writes version 2.
- Samples join to frames by timestamp, which both writers take from the same
  clock as each frame's own `timestamp`, so the phone's 0-based and the cloud's
  1-based frame numbers never meet.

### 5.2 Selection

- `selectPlayers(raw, keypoints)` reads `raw.camera`. When it is present, for
  each frame with people: `M = camera.at(frame.timestamp)`. If `M` is null the
  frame is a `CAMERA_LOST` rejection on both sides. Otherwise every person's
  keypoints are mapped by `M⁻¹` into marking-frame pixels and the existing
  selector runs unchanged: ground point, side of the net, on-court margin,
  torso scale, highest box confidence.
- The returned `PlayerPose` keeps the person's original frame-pixel keypoints
  (the overlay draws those) and gains `camera: Matrix3x3?`, that frame's `M`.
- `CAMERA_LOST` goes after `BAD_COURT` in `RejectionReason` so the ordinal of
  every existing reason is unchanged. The stores do not persist rejections
  (`PlayerTrackStore.kt:150-153`), so no track format changes.

### 5.3 The skeleton store and the Skeleton panel

- One new store version, **4**, written by both `save` (device) and `saveAll`
  (cloud) on both platforms: the version 3 multi-track layout, plus
  `hasCamera int32` after the marks; when it is 1 every pose record is followed
  by its eight `float64` camera entries. A device run writes one NEAR track.
  Versions 1 to 3 still load, with no camera.
- The panel computes each pose's court fit as `H · pose.camera⁻¹` when the
  pose has a camera, and `H` otherwise. `H`'s residual gate is unchanged.
- `Homography.kt` makes today's private `multiply3x3` / `invert3x3` public
  under those names (top-level functions, not extensions on the
  `List<List<Double>>` typealias, which would attach to every nested list),
  and adds `courtFitAt(h: Matrix3x3, camera: Matrix3x3?): Matrix3x3?`
  (`h · camera⁻¹`, or `h` when `camera` is null) so neither platform composes
  matrices itself.
- Within one skeleton file every pose has a camera or none does: selection
  only returns a pose for a frame whose camera it knew.

### 5.4 The heatmap and Base panels

They read court-metre samples from the track store, which are now right. No
change. A run in which the camera could not be followed at all leaves an empty
track, and an empty track is never offered to these panels
(`HeatmapPanel.kt:59-76`, `AnalysisSection.swift:295-301`), so no empty-state
wording can reach a coach. Instead `LocalAnalysisCoordinator` and
`CloudPoseCoordinator` log the `CAMERA_LOST` count beside the existing
`BAD_COURT` log (`LocalAnalysisCoordinator.kt:150`). That an empty track shows
nothing at all is pre-existing (the `BAD_COURT` message has the same
unreachable path) and is reported, not fixed here.

### 5.5 The platform engines

- The tracker runs only when pose runs (a rallies-only pass has nothing to map).
  The engine owns one `CameraMotionTracker` for the pass, created with `T` and
  the source size, used on the decode thread only.
- The engine learns `T` the way it learns the marks: `LocalAnalysisCoordinator`
  already hands the engine a request per run; it gains `markingTimeS`. The
  coordinator computes it as `keypoints.markedAtSeconds ?: legacyMarkingTime`,
  where `legacyMarkingTime` is 0.1 on Android and, on iOS, the generator's
  `actualTime` for 0.1 s under the marking screen's tolerances.
- Android: in the engine's `onFrame`, if `tracker.wants(seconds)`, copy the Y
  plane (plane 0, honouring its row stride) into a reused `ByteArray`,
  `Luma.downsample` it, and `tracker.offer(seconds, image)`. After the pass,
  `tracker.finish()` goes into `RawInference.camera`.
- iOS: the same, with the downsample done in Kotlin/Native from the locked
  plane's base address through a new `BulkInterop.lumaDownsample(...)`, so the
  2 MB plane never crosses the bridge.
- A tracker failure never fails the run: `finish()` returns what it has, and
  frames it could not follow become `CAMERA_LOST`.

---

## 6. The cloud

In `badminton-tracker`, branch `feat/camera-motion` from
`feat/cloud-pose-artifacts` (what is deployed).

- `backend/camera_motion.py`: the §4 algorithm through the OpenCV calls §4
  names, the same parameters, the same sampling and the same `at(timestamp)`
  rule, from the full-resolution frame the loop already holds (`:3163`),
  converted to grey and area-resized (`INTER_AREA`) to the §4.1 size. `T` is
  `manual_court_keypoints["marked_at_s"]` or 0.1. `cv2` is imported inside
  its functions, as the processor already does, so the offline verify scripts
  that import the processor still run without OpenCV.
- The Modal image mounts each backend module by name
  (`modal_supabase_processor.py:3928`): `camera_motion.py` gets its own
  `add_local_file` line, or every Phase 2 run fails at import after deploy.
- **In the loop the camera is causal.** Frames are read in batches of 8, so a
  frame's later sample may not exist yet. The tracker is fed each sampled
  frame as it is read, and in-loop code uses the latest accepted `M`
  (identity before the first, or when the tracker gave up). Only the section
  written to the file uses §4.4's blending, on the phone.
- **Stabilise early, emit late.** Each frame's detected skeletons are mapped
  by `M⁻¹` into marking-frame pixels as shadow copies (centre, box, keypoints)
  that keep a reference to the original. Everything the loop decides runs on
  the shadows with the fixed geometry unchanged: the court area filter, the
  sitting filter, cumulative movement, the jump gate, the identity tracker
  (its net line, its calibration, its velocities) and speed. What is emitted
  (`skeleton_frames`, `results.json`, the pose file) is the originals, in
  frame pixels. This keeps the tracker's own `net_line` rewriting
  (`:1942`, `:1964`) and every pixel threshold meaning what it means today,
  and it is the phone's approach (§5.2) in the cloud. The Phase 2 shuttle
  court area is mapped by `M` instead, because shuttle candidates are points,
  not skeletons.
- **Plumbing.** `_run_full_yolo_loop` returns the motion in `tracking_state`
  under `"camera_motion"`; `raw_inference_from_skeleton_frames(...)` and
  `encode_raw_inference(...)` gain a `camera` argument.
- `raw_inference_codec.py` writes version 2 with the camera section;
  `verify_raw_inference_codec.py` checks the new layout and writes a version 2
  fixture that the phone's codec test decodes, next to the existing version 1
  fixture (kept, to prove version 1 still reads).
- `backend/scripts/verify_camera_motion.py`, offline like the other verify
  scripts: synthetic warps with a known homography, and the painted-line check
  on `IMG_5892` when the video is present.

---

## 6a. Decisions

| decision | chosen | rejected, and why |
|---|---|---|
| how to follow the camera | anchored LK against the marking frame | chained KLT: error accumulates (4 cm median after 72 s, unbounded on a match). Translation-only phase correlation: 1.5 m off on the measured video, and cannot express perspective. ORB matching every frame: accurate but 17 to 172 ms per frame on the host, 40 to 400x the cost |
| where the phone's tracker lives | one pure-Kotlin tracker in `:analysis` | Apple Vision on iOS plus something else on Android: two behaviours to keep in step. OpenCV for Android: 10 MB or more per ABI on a 121 MB APK, for four functions |
| how selection uses `M` | map each person's keypoints into marking-frame pixels, run the selector unchanged | a per-frame selector with its own homography and net line: the same result through more code, and every gate would need re-verifying |
| what the skeleton store keeps | per-pose `M`, composed with `H` at read time | per-pose court fit: bakes today's `H` into the file, so a better court fit later could not reach stored skeletons |
| format change | RawInference version 2 | an unversioned trailer after version 1 frames: old readers would skip it, but no shipped build reads the file (§2.3), so compatibility buys nothing and a trailer is a second, hidden format |
| sampling | 10 Hz by timestamp | every frame: 3x the cost for no measured accuracy. Every Nth frame: wrong on variable frame rate |
| a frame the tracker lost | no position (`CAMERA_LOST`) | fall back to the fixed marks: §1 is the measurement of what that draws |
| which frame the marks belong to | `marked_at_s` stored with the marks; iOS records the generator's `actualTime` | assume 0.1 s: iOS marks came from a frame up to a GOP later (1.0 s on `IMG_5892`). Force iOS to 0.1 s with zero tolerance: fixes new marks only, and still leaves nothing recording which frame was used |
| cloud in-loop camera | causal (latest accepted `M`), shadows stabilised by `M⁻¹` | the full `at()` blend in-loop: needs samples from batches not yet read. Mapping the fixed geometry forward by `M`: fights the identity tracker's own net-line calibration and leaves every pixel-space motion threshold polluted by the pan |

---

## 7. Deliberately not in this pass

- **Rotated recordings** (portrait, or landscape upside down). The marks are in
  display orientation and the decoded frames are not, on iOS certainly and on
  Android probably. That is a separate, pre-existing defect; this design
  neither fixes nor worsens it, because `M` lives in decoded space like the
  keypoints.
- **Phase 1** (TrackNet's median background, rally detection, the Phase 1
  shuttle court area). A moving camera probably hurts shuttle detection too.
  Unmeasured, and a different pipeline.
- **The web app** (`useZoneAnalytics.ts`, `speed_calc.recalculate_speeds`),
  which read positions from `results.json` through the fixed marks. This pass
  changes what they see: the cloud now keeps players on frames where the
  camera had moved, which it used to drop, and `results.json` stores them in
  frame pixels as before, so the web maps those frames to wrong places instead
  of leaving them out. Fixing it means writing `M` into `results.json` too.
- **Legacy iOS marks in the cloud** anchor at 0.1 s while they were drawn on a
  later frame (§3). Marking again fixes a video.
- **An empty track explains nothing to the coach** (§5.4), today and after.
- **Tracks already stored.** Cloud tracks are computed once at install and the
  raw file is not kept; a video must be analysed again to benefit.
- **A camera that walks**, a fast deliberate pan that outruns 10 Hz, and a
  view that turns away from every marking-frame feature. All three become
  `CAMERA_LOST` rather than wrong positions.
- The ankle-height correction and a rallies-only heatmap from the same
  investigation: separate changes.

---

## 8. Verification

1. **Unit tests, `:analysis`, JVM and iOS simulator:** the downsample against
   a hand-computed average at an integer and a non-integer ratio; corners,
   pyramid and LK on synthetic textures shifted and warped by known
   homographies (recovered to within 0.1 px at tracker scale); RANSAC with 30%
   planted outliers; the sampler's 0.1 s boundaries and the marking frame on
   constant and variable frame rates and with `T = 1.0`; `at()`'s exact,
   blend, hold, gap and duplicate-timestamp rules; the codec's version 1 and 2
   round trips and truncation; selection with a camera (a panned person is
   found where they stand, a frame with no known camera is `CAMERA_LOST`, a
   `null` `camera` field is today's behaviour exactly).
2. **The real video, corpus-gated JVM test:** `tools/models/camera_motion_probe.py`
   writes `IMG_5892`'s raw Y planes (video range, as the phones decode them,
   via ffmpeg `extractplanes=y`) at each 10 Hz sample, their timestamps, the
   painted-line check points and the reference tracks to
   `~/shuttl-corpus/IMG_5892/`. Through `Luma.downsample` and the tracker, the
   Kotlin result must keep the near service line, near baseline and near long
   service line within 2 px p95 of the paint, and agree with OpenCV's anchored
   tracker to within 1 px median at source scale.
3. **Store and panel tests** on both platforms for version 4 and the per-pose
   court fit; the iOS layout test pinned to Android's bytes, as today.
4. **Cost on a device:** tracker milliseconds per sample and per frame on the
   S23 if it is reachable, otherwise on the emulator and the iOS simulator,
   with the S23 figure stated as an estimate.
5. **End to end on both phones:** `IMG_5892` analysed on the device path on
   Android and on iOS (the two luma paths differ), the stored track selected
   against the ideal marks. Pass: at least 95% of pose frames produce a
   position, and against the reference track (every-frame ORB, §1) the median
   is at most 5 cm and p95 at most 15 cm. The heatmap, Base and Skeleton panels
   drawn and screenshotted.
6. **End to end in the cloud, locally:** the Phase 2 loop run on `IMG_5892`
   on this Mac (not on Modal) with the medium pose model, its `poses.raw`
   decoded by the phone's codec and selected. Pass: at least 95% of pose
   frames produce a position, and against the reference track the median is at
   most 8 cm and p95 at most 25 cm (a different model, and a clock one frame
   late). `verify_raw_inference_codec.py`, `verify_camera_motion.py`,
   `verify_tracker_invariants.py` and `verify_speed_filters.py` all pass.
7. **In production, after the owner deploys** (`python3 -m modal deploy
   backend/modal_supabase_processor.py`): one Phase 2 run of `IMG_5892`,
   installed on a phone through `CloudPoseCoordinator`, with its heatmap,
   Base and Skeleton panels checked. Until then §8.6 is the cloud's evidence.
   Once the worker writes version 2, the older unmerged branches'
   dev builds can no longer install cloud poses; no shipped build is affected.
