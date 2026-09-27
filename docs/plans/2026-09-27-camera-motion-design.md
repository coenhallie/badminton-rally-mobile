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
| **anchored LK (marking frame to this frame), 480 wide, every 3rd frame** | **0.4** | **0.60 / 1.93** | **1.6 / 4.9 cm** |

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

**The marking frame is the first decoded frame whose timestamp is at least
0.1 s.** The Android, web and iOS stills are all within one frame of it; the
camera does not move measurably in one frame. It is defined by time, not by
index, because Android numbers frames from 0 and the cloud from 1.

One change keeps that true on iOS: `CourtFrameLoader` allows unlimited
tolerance after 0.1 s today, which lets AVFoundation hand back a later sync
frame, possibly a second or more in. With the camera now known to move, the
marks must come from the frame the tracker anchors to, so the tolerance after
becomes `.zero` as well.

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
  and the Y plane is already there.
- `Luma.downsample(plane, width, height, rowStride, out)` in `commonMain` does
  the area average. Android copies plane 0 of the `Image` into a reused
  `ByteArray` and calls it. iOS calls a new `BulkInterop` function that reads
  the locked `CVPixelBuffer`'s plane 0 through a pointer, the way
  `medianBackgroundInto` already does (`shared/src/iosMain/.../BulkInterop.kt:73-89`),
  so no 2 MB array crosses the Swift bridge element by element.

### 4.2 Sampling

- The tracker asks for a frame each time the timestamp crosses a 0.1 s
  boundary (10 Hz, whatever the frame rate, and correct on variable frame
  rate). The marking frame is always one of them.
- Frames before the marking frame (the first 0.1 s) are buffered and tracked
  once it arrives: at most a handful of 130 KB images.
- The anchored tracker does not accumulate error, so a lower rate costs only
  temporal resolution. 10 Hz keeps within 1.6 cm of every-frame accuracy on the
  measured video (§1) and follows a hand's slow drift; it would miss a
  deliberate fast whip, which is §7's problem, not this one's.

### 4.3 One sample

1. **Reference features, once.** Shi-Tomasi corners on the marking frame: up
   to 150, quality level 0.01 of the strongest, at least 8 px apart, 3x3
   structure-tensor window, at least 10 px from the border.
2. **Pyramids.** Three levels (full, half, quarter), each level a 5-tap
   Gaussian then a 2x decimation, the marking frame's built once.
3. **Anchored pyramidal Lucas-Kanade.** Each reference corner is tracked from
   the marking frame into this frame, starting at the previous accepted
   `M` applied to the corner (the identity before the first). 21x21 window,
   up to 20 iterations per level, stop when the update is under 0.03 px. A
   corner fails if its window leaves the image or its system is singular.
4. **RANSAC homography** over the surviving pairs: 4-point minimal fits
   through the existing DLT (`calculateHomography`), inlier threshold 1.0 px at
   tracker scale, 300 iterations from a fixed-seed generator so a run is
   repeatable, then a least-squares refit on the inliers.
5. **Accept** when there are at least 40 inliers and the result is plausible:
   the four image corners of the marking frame each land within 25% of the
   image width of where they started, and the determinant of the upper-left
   2x2 lies in 0.5..2. Otherwise the sample is dropped and the next one starts
   from the last accepted `M`.
6. Scale the accepted homography from tracker pixels to source pixels
   (`S · M · S⁻¹`).

### 4.4 Reading `M` at a frame

`CameraMotion(referenceTimestamp, samples)` answers `at(timestamp): Matrix3x3?`:

- A sample at that timestamp: its `M`.
- Between two samples no more than 0.5 s apart: the element-wise linear blend,
  renormalised so `h22 = 1`. The motion between 10 Hz samples is a fraction
  of a pixel of rotation; a blend is exact to well under a pixel.
- Within 0.15 s before the first sample or after the last: the nearest sample.
- Anywhere else: `null`, meaning **the camera is not known here.**

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
- `Matrix3x3` gains public `times` and `inverse()` in `Homography.kt` (today's
  private `multiply3x3` / `invert3x3`), and a helper
  `courtFitAt(h, camera)` so neither platform composes matrices itself.

### 5.4 The heatmap and Base panels

They read court-metre samples from the track store, which are now right. No
change, except the empty-state line: when a pass found no position because the
camera could not be followed, the heatmap says so instead of "the player was
not found" (`whyEmpty`, Android `CourtHeatmapView.kt:262-270` and iOS
`CourtHeatmapView.swift:219`).

### 5.5 The platform engines

- The tracker runs only when pose runs (a rallies-only pass has nothing to map).
- Android: in the engine's `onFrame`, if `tracker.wants(seconds)`, copy the Y
  plane, downsample, `tracker.offer(index, seconds, luma)`. After the pass,
  `tracker.finish()` goes into `RawInference.camera`.
- iOS: the same, with the downsample done in Kotlin/Native from the plane's
  base address through `BulkInterop`.
- A tracker failure never fails the run: `finish()` returns what it has, and
  frames it could not follow become `CAMERA_LOST`.

---

## 6. The cloud

In `badminton-tracker`, branch `feat/camera-motion` from
`feat/cloud-pose-artifacts` (what is deployed).

- `backend/camera_motion.py`: the §4 algorithm through OpenCV
  (`goodFeaturesToTrack`, `calcOpticalFlowPyrLK` with
  `OPTFLOW_USE_INITIAL_FLOW`, `findHomography` RANSAC), the same parameters,
  the same 10 Hz sampling and the same `at(timestamp)` rule, from the
  full-resolution frame the loop already holds (`:3163`).
- In `_run_full_yolo_loop`, per frame, with `M = motion.at(timestamp)`:
  - the court area polygon's four corners are mapped by `M` before the
    point-in-polygon test;
  - the identity tracker's net line is mapped by `M` for that frame;
  - the Phase 2 shuttle court area is mapped by `M`;
  - speed's court position is `H_court · M⁻¹ · p`, with the previous point
    mapped by its own frame's `M`.
  Where `M` is unknown the fixed geometry is used, as today: the cloud's job
  here is to keep the right people and write `M`, and the phone makes the
  final call through `CAMERA_LOST`.
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
  which read positions from `results.json` through the fixed marks.
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
   a hand-computed average; corners, pyramid and LK on synthetic textures
   shifted and warped by known homographies (recovered to within 0.1 px);
   RANSAC with 30% planted outliers; the sampler's 0.1 s boundaries on constant
   and variable frame rates; `at()`'s blend, hold and gap rules; the codec's
   version 1 and 2 round trips and truncation; selection with a camera
   (a panned person is found where they stand, a `null` camera frame is
   `CAMERA_LOST`, a `null` `camera` field is today's behaviour).
2. **The real video, corpus-gated JVM test:** `tools/models/camera_motion_probe.py`
   writes `IMG_5892`'s 10 Hz luma frames and the painted-line check points to
   `~/shuttl-corpus/IMG_5892/`; the Kotlin tracker must keep the near service
   line, near baseline and near long service line within 2 px p95 of the paint,
   and agree with OpenCV's anchored tracker to within 1 px median at source
   scale.
3. **Store and panel tests** on both platforms for version 4 and the per-pose
   court fit; the iOS layout test pinned to Android's bytes, as today.
4. **Cost on a device:** tracker milliseconds per frame on the S23 if it is
   reachable, otherwise on the emulator and the iOS simulator with the S23
   estimate stated as an estimate.
5. **End to end on a phone:** `IMG_5892` analysed on the device path, the
   heatmap drawn, compared against §1's corrected map.
6. **End to end in the cloud:** the Phase 2 loop run locally on `IMG_5892`
   (not on Modal), its `poses.raw` decoded by the phone's codec and selected
   with coverage and positions matching the device run's.
7. Deploying the worker is the owner's step: `python3 -m modal deploy
   backend/modal_supabase_processor.py`.
