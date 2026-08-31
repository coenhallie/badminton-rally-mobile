# Design: on-device analysis pipeline for iOS and Android

**Date:** 2026-08-31
**Status:** Proposal, pending approval
**Follows:** `2026-08-31-web-analysis-pipeline-reference.md`, which describes the
cloud pipeline this one mirrors. **Reference convention:** a bare `§N` means a
section of *this* document; `ref §N` means a section of that one.

Today the phone uploads a video, the cloud does the work, and the phone reads
back rally clips. This design moves the whole analysis onto the device: shuttle
tracking, rally detection, clip cutting, pose, player identity, speed, and every
analytic the web app currently computes in a browser tab. The web app
(`badminton-tracker`) is not touched. Its upload-and-process pipeline stays
exactly as it is, and it remains the fallback for devices that cannot do the
work.

The point of doing this is not to save GPU cost, although it does. It is that a
locally-computed analysis never has to serialize and download the per-frame
skeleton dump (ref §2), which is the single obstacle that has kept analytics off
mobile.

Because the cloud pipeline stays alive, both implementations exist and must
agree. Measuring that agreement is a first-class deliverable here, not an
afterthought: §5.7 specifies the A/B harness and §6 the list of places local is
allowed to differ.

---

## 1. What exists today

**Mobile drives Phase 1 already.** `AnalyzeCoordinator` runs
UPLOAD -> CREATE_ROW -> KEYPOINTS -> TRIGGER -> PROCESSING, and treats
`phase1_complete` as success (`VideosRepository.kt:41`). Phase 2 is never
invoked from the phone; the comment on that line says analytics are
desktop-only.

**Platform capabilities are injected, not abstracted.** `AnalyzeCoordinator`
takes `openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel` as a
constructor parameter (`AnalyzeCoordinator.kt:36`, used at `:124`). The shared
module never touches a file handle. This is the pattern the inference boundary
follows.

**`shared` has almost no platform-specific code.** `androidMain` contains one
file (`SyncLock.android.kt`). `iosMain` contains interop glue and test doubles.
Everything else is `commonMain`.

**Both platforms already extract frames.** `CourtFrameLoader.swift` uses
`AVAssetImageGenerator` for the court-marking still, and Android has a mirroring
`loadFirstFrame`. Single-frame extraction exists; bulk sequential decode does
not.

**Two modules.** `settings.gradle.kts` includes only `:shared` and
`:androidApp`; iOS consumes the `Shared` static framework via SKIE.

**CI is device-free for shared code.** `.github/workflows/ci.yml` runs
`:shared:jvmTest` on ubuntu, plus Android unit tests, an Android assemble, an
iOS framework link and an iOS app build.

**Clips, annotations and sharing all live in Supabase.** `ClipsRepository` reads
`rally_clips`; `AnnotationsRepository` writes `rally_annotations`;
`SharesRepository` is wired into `ClipListScreen` and `MatchScreen`. Nothing is
local-first today.

For the cloud side, see the reference doc rather than a summary here. The parts
this design leans on are cited as constraints in §3.

---

## 2. The one fact the design turns on

**The analytics are arithmetic. The perception is what needs the GPU.**

`_compute_analytics` in the Modal worker is about 140 lines and returns
per-player speed, distance, a position track, and three literal `None`s (ref §4.4).
Everything richer that the web app shows - rallies as the user sees them, shot
events, per-rally speed, shot placement, recovery, reaction time, movement
efficiency, zone coverage, heatmaps - runs in a browser tab, in JavaScript, in
well under a second, over data that has already been computed (ref §5.2).

So the expensive half and the fragile half are different halves. Turning pixels
into keypoints needs an accelerator. Turning keypoints into a rally list needs a
loop. The boundary between platform code and shared Kotlin goes exactly there,
and everything else in this design follows from that placement.

---

## 3. Constraints, verified

**3.1 The cloud already splits at this line.** Phase 2 writes per-frame results
to `/cache/{video_id}_skeleton.jsonl` mid-loop
(`modal_supabase_processor.py:2702-2703`) and reads them back for post-processing
(`:3439`). The pipeline is already "inference pass, then analytics pass over an
intermediate". This design copies that structure rather than inventing one.

**3.2 Track IDs are nearly irrelevant to player identity.**
`PlayerIdentityTracker` weights YOLO track-ID continuity at `W_TRACK_ID = 0.15`
(`modal_supabase_processor.py:1388`), the second-lowest of six, with a source
comment saying far-player IDs are unreliable. Identity is guaranteed by the hard
court-side constraint, which needs no track IDs; the weight only breaks ties
within a side (ref §4.2). This is what makes dropping BoT-SORT defensible.

**3.3 Production runs fp16, not fp32.** `yolo_half = torch.cuda.is_available()`
and `half=yolo_half` is passed to every inference call
(`modal_supabase_processor.py:2574`, `:2867`). An fp16 export on device is
therefore precision-parity with what runs today, and the ref §8.1 distance-inflation
risk is entered only by going to int8.

**3.4 Two of four weights are not in this or the other repo.**
`backend/models/badminton/weights/best.pt` (5.9 MB) is present.
`yolo26m-pose.pt` is not: it is loaded as a bare filename
(`modal_supabase_processor.py:2578`) and resolved by Ultralytics at runtime, so
it is unpinned. TrackNetV3 and InpaintNet are not present either; they live on
the Modal volume.

**3.5 The TrackNet weights are third-party but the code is recorded as MIT.**
`backend/upload_tracknet.py:1-11` documents the checkpoints as downloaded from
the TrackNetV3 authors' Google Drive release and pushed to the Modal volume.
`backend/tracknet/__init__.py:4` and `backend/tracknet/model.py:8` both record
the upstream as `github.com/qaz812345/TrackNetV3` under the **MIT License**.

MIT permits redistribution in a binary, so this is not the blocker it first
appeared to be. Three qualifications survive, and they are checks rather than
gates:

- The MIT claim is a **docstring comment, not a vendored licence file**. No
  `LICENSE` was copied into `badminton-tracker`. It should be confirmed against
  upstream once and the actual text vendored.
- **Code licence and checkpoint licence are not automatically the same.** An MIT
  repository covers the code; released weights often carry no explicit grant or
  inherit terms from the training data. The check is specifically whether
  upstream says anything about the checkpoints separately.
- MIT requires the **copyright and permission notice to travel with
  redistribution**, which means an acknowledgements entry in the app.

**Scope assumption:** this app is currently internal research use, which is the
safest posture under any licence. That assumption is load-bearing and not
obviously stable, given `SharesRepository` is wired into two screens and the
coaching work in both repos implies external users. MIT is what makes external
distribution fine; the internal framing is the fallback, not the primary
argument.

**3.6 TrackNet is trivially exportable and its input is small.**
`backend/tracknet/model.py:61` is a plain conv U-Net whose complete op inventory
is `Conv2d`, `BatchNorm2d`, `ReLU`, `MaxPool2d`, bilinear `Upsample`,
`torch.cat`, a 1x1 `Conv2d` and `sigmoid`. Input is 512 x 288 and one forward
pass covers 8 frames (`backend/tracknet/inference.py:23-24`, `:43`). No custom
ops, no dynamic control flow.

**3.7 The homography is already dependency-free in TypeScript.**
`src/utils/homography.ts` implements Hartley-normalized DLT by hand -
`calculateHomography` at `:182`, `applyHomography` at `:242` - with no OpenCV and
no math library. `speed_calc.py` uses `cv2.findHomography` and documents itself
as a port of the same TS reference. Porting the TypeScript keeps OpenCV out of
`:analysis` entirely.

**3.8 The device floor is below what Phase 2 needs.** `minSdk = 26`
(`androidApp/build.gradle.kts:38`, `shared/build.gradle.kts:66`) is Android 8.0,
2017 hardware. `iosApp/project.yml:3-4` sets iOS 17.0, so iPhone XS / A12, 2018.
A medium pose model at `imgsz=960`, run on every frame (`sample_rate = 1`,
`modal_supabase_processor.py:2650`), will not be acceptable at the bottom of that
range.

**3.9 The annotation-timestamp corruption in ref §8.5 is live.** The Phase 2 clip
re-cut moves clip bounds while preserving `rally_clips.id`, and
`AnnotationsRepository` ships in this app. A locally-produced pipeline must not
reproduce that bug, which means local clip bounds must be stable across re-runs
or annotations must be migrated with them.

---

## 4. Decisions

| Question | Chosen | Rejected, and why |
|---|---|---|
| Scope | Both phases, fully local | Phase 1 only: leaves analytics permanently cloud-bound, which is the problem being solved. Staged-and-see: same architecture, but the end state should be committed so the boundary is designed for it |
| Sync model | Local compute, cloud sync | Fully offline: breaks sharing, cross-device and `rally_annotations` unless a local-first store is built behind every repository interface. Materially larger project. User-selectable dual paths: two pipelines to reconcile forever |
| Weak devices | Cloud fallback via capability check | Feature-gate: a paying user on an older phone loses the feature outright. Raise minimum globally: sheds users of features that work fine today. Local-always: multi-hour runs on the hardware least able to absorb them |
| Platform/shared boundary | Batch handoff via an intermediate file | Platform owns the loop: duplicates every stateful filter and the identity tracker in two languages. Shared owns the loop: tens of thousands of Kotlin/Swift boundary crossings per video, and pixel handles across the bridge |
| Runtime | ONNX Runtime on both platforms | Native Core ML + LiteRT: better ceiling, but three things to compare instead of one, and two conversion pipelines. Kept as an escape hatch behind the same interface |
| BoT-SORT | Dropped | Porting it to Kotlin is real work for a 0.15 tie-break weight (§3.2). Running it natively per platform violates the boundary. Additive to restore if measurement says it matters |
| Speed aggregation | `speed_calc` only | Reproducing `_compute_analytics`'s IQR / top-5% / clamp-to-15 second pass would carry ref §8.10's divergence onto mobile. The `speed_calc` numbers are the ones users actually see |
| Zone coverage | Ported as-is, video-normalised | Fixing it to court coordinates here would make local and web disagree by design and pollute the comparison. Fix both at once, later |
| Precision | fp16 | int8 is the change that triggers ref §8.1 distance inflation. Not attempted until fp16 numbers exist to compare against |
| Producer marker | `results_meta.producer` | Adding `'local'` to `pipeline_variant` means migrating the schema the web app shares. `results_meta` is unstructured jsonb, so extra keys are free and invisible to the web app |

---

## 5. The design

### 5.1 Layer map and boundary

```
platform layer  (Swift on iOS, Kotlin in androidApp)
  VideoDecoder    AVAssetReader          / MediaCodec
  ModelRunner     ONNX Runtime           / ONNX Runtime
  ClipCutter      AVAssetWriter          / MediaCodec + MediaMuxer
        |
        |  writes  RawInference  (binary, device-local, never uploaded)
        v
:analysis  (Kotlin multiplatform, no I/O, no network, jvmTest in CI)
  ShuttleTrack       ROI + static-cluster filtering
  IdentityTracker    hard side constraint, calibration, cost matrix
  Metrics            speed/distance filter chain, aggregation
  PoseClassifier     the rule cascade
  RallyDetection     gradient + shot-gap, union, refine, padding
  Analytics          rallies, per-rally speed, placement, recovery,
                     reaction, efficiency, zones, heatmap
  Comparator         the A/B diff (§5.7)
        |
        |  produces  AnalysisResult  (mirrors the cloud results.json shape)
        v
:shared  ClipsRepository / VideosRepository -> Supabase
```

The platform layer satisfies one injected interface, following `openChannel`:

```kotlin
interface LocalInferenceEngine {
    suspend fun run(
        videoPath: String,
        onProgress: (Float) -> Unit,
    ): RawInference
}
```

It decodes frames, runs models, and emits raw model output. It never computes a
metric, never assigns a `player_id`, never decides a rally boundary. Everything
with a history of drifting between implementations lives in `:analysis`, written
once.

### 5.2 The intermediate format

`RawInference` is a compact binary record stream. Per frame:

- `frame`, `timestamp` (presentation timestamp from the sample buffer)
- shuttle: `x`, `y`, `confidence` from the TrackNet heatmap peak, unfiltered
- per detected person: bbox, and 17 x (`x`, `y`, `confidence`)
- detection boxes: class id, confidence, box

Binary rather than JSON. At roughly 5.5M floats for a 30-minute match, a
named-field JSON encoding reproduces the ref §2 problem on the phone. The file never
leaves the device, so there is no interop reason for it to be readable. A Kotlin
reader in `:analysis` serves both the pipeline and the desktop comparator.

`AnalysisResult` is JSON and deliberately mirrors the cloud's `results.json`
schema, because that is what the comparator diffs and what the sync layer
uploads.

### 5.3 The `:analysis` module

A third KMP module, not more `:shared`. The dependency sets are disjoint:
`:shared` is Supabase, Ktor, auth and repositories; `:analysis` is pure math with
no I/O. `:shared` depends on `:analysis`, never the reverse.

Ported from `badminton-tracker`, roughly 1,200 lines of Python and 2,500 of
TypeScript. Less than that sounds, because porting **collapses duplication that
already exists**: `shot_detection.py` and `utils/shotDetection.ts` are the same
algorithm maintained twice, as are `rally_detection_shot_gap.py` and the rally
grouping loop in `useAdvancedAnalytics.ts`, whose docstrings name each other as
sync targets.

| Source | Ported to |
|---|---|
| `speed_calc.py` | filter constants, `maxFrameJumpPixels`, `medianSpeedRejects`, `pixelToMeters`, aggregation |
| `shot_detection.py` + `utils/shotDetection.ts` | one shot detector |
| `rally_detection.py` | gradient detector |
| `rally_detection_shot_gap.py` + the TS grouping loop | one shot-gap detector, `refineRallies`, `unionRallies` |
| `PlayerIdentityTracker` | identity, calibration, cost matrix, net-band logic |
| `classify_pose` + `calculate_body_angles` | pose rule cascade |
| `_build_shuttle_positions_dict`, `_compute_court_polygon`, `valid_net_line`, `normalize_fps`, `pad_rally_windows` | shuttle track + geometry |
| `useAdvancedAnalytics.ts` | rallies, per-rally speed, length distribution, placement, recovery, reaction, efficiency |
| `useZoneAnalytics.ts`, `useHeatmap.ts`, `useShotSegments.ts` | zones, heatmap, movement segments, body-angle peaks |
| `utils/homography.ts`, `speedZones.ts`, `bodyAngles.ts` | geometry and zone helpers |

The homography port takes the TypeScript, not the Python, per §3.7.

### 5.4 The platform inference layer

**Conversion.** Every model exports once to ONNX at fp16, and ONNX Runtime runs
it on both platforms. One pivot format means the A/B test has one variable
rather than three, and one conversion pipeline covers all models: Ultralytics
exports ONNX natively for the two YOLO models, and TrackNet is plain PyTorch so
`torch.onnx.export` handles it with no intermediate hop (§3.6).

Conversion scripts live in **this** repo under `tools/models/`, leaving
`badminton-tracker` untouched. A committed manifest pins source-weight SHAs,
converter versions and output SHAs, so a re-conversion that changes a byte is
visible.

**One decode pass, not three.** The cloud decodes the video three times. Here the
platform does a single sequential decode and per frame feeds the pose model, the
detection model, and an 8-frame ring buffer for TrackNet, writing one
`RawInference`. This is possible only because `:analysis` now owns the
filtering; in the cloud, Phase 1's filtered shuttle output feeds Phase 2, forcing
the ordering.

Sequential decode is not optional. Seeking per frame tens of thousands of times
is pathologically slow on both platforms.

**Correction, 2026-08-31.** "One decode pass" is too strong as written. The
shuttle path needs a **bounded seek pre-pass** first: the median background in
stage 1 below is computed from up to 300 frames sampled evenly across the whole
video (`max_bg_samples: int = 300`, `inference.py:111`; `np.linspace` sampling
at `inference.py:200-201`), each reached by a seek, and that background is an
input to the very first inference of the main pass, so it cannot be computed
lazily as the main pass goes. 300 seeks is not what the paragraph above
objects to. The accurate statement is: one bounded seek pre-pass of at most 300
frames, then one sequential frame-by-frame decode pass. See Task 11 of
`2026-08-31-stage1-device-layer-ios-plan.md`.

**Shuttle postprocessing must reproduce production exactly, not approximate it.**
Added 2026-08-31 after reading `backend/tracknet/inference.py` closely. The cloud's
shuttle track is not a heatmap argmax. Three stages sit between the model and a
coordinate, and an earlier draft of this design silently omitted all three:

1. **Median background, not the first frame.** `bg_mode` is `concat`, so the model
   takes a background plane as its extra three channels, and production computes
   that as a pixel-wise **median over sampled frames**
   (`inference.py:193-220`). A median removes everything that moves; a single
   frame contains a player mid-swing. These are not interchangeable inputs.
2. **Blob detection with an area filter and a weighted centroid, not argmax.**
   `_heatmap_to_coord` (`inference.py:477-505`) thresholds at 0.5, runs connected
   components, **rejects any blob larger than `max_area = 100`**, takes the
   largest surviving blob, and returns its heatmap-weighted centroid. Three
   behaviours argmax does not have: large spurious activations are rejected
   rather than followed, the result is sub-pixel, and a frame whose only blobs
   are oversized returns **not visible**, which argmax can never do.
3. **InpaintNet trajectory gap-filling.** Production runs it over the whole
   trajectory after TrackNet (`inference.py:170-173`). It **raises** shuttle
   coverage by filling gaps, and coverage is the quantity the shot-gap detector's
   25 percent visibility gate consumes. Omitting it does not merely change
   positions, it systematically lowers the coverage that decides whether a rally
   is accepted at all.

All three are ported faithfully. InpaintNet is exported to ONNX alongside
TrackNet and runs in the same pass.

**Where they live.** Stages 1 and 2 stay in the platform layer, because they are
tightly coupled to decoding and to the model output and would otherwise force
per-frame heatmaps across the boundary. That is the one place this design accepts
a two-language implementation, so it is bought with a shared golden vector: a
committed fixture of model outputs and their expected coordinates that both the
Swift and the Kotlin implementations must reproduce, plus a Python reference run
from the production code itself. Stage 3 is trajectory-level rather than
frame-level, so InpaintNet's input is small and its invocation belongs with the
other model calls.

**Decode and preprocessing are a fidelity surface too, and an unmeasured one.**
Production decodes with OpenCV `VideoCapture`, resizes with `cv2.resize` to
512 x 288 (default `INTER_LINEAR`), converts BGR to RGB, and scales by 1/255
(`inference.py:472-474`). On device the decoder is `AVAssetReader` or
`MediaCodec`, and the resize is whatever the platform's scaler does. Different
interpolation, different colour conversion, and on some containers a different
frame count, all feeding **every** model on **every** frame. This is upstream of
the conversion question and is not covered by any gate in this plan.

Pin it before the device layer is built: decode the same video both ways, and
compare the resulting 512 x 288 RGB tensors directly. If they differ materially,
match the platform scaler to `INTER_LINEAR` or resize on the CPU, rather than
discovering the difference later as unexplained drift in shuttle positions.

**Clip cutting.** `AVAssetWriter` on iOS, `MediaCodec` plus `MediaMuxer` on
Android. Frame-accurate boundaries need a re-encode, not a stream copy, for the
same reason the cloud re-encodes. Clip bounds are computed by `:analysis` from
the same `padRallyWindows` logic, so they are deterministic given the same
`RawInference`, which is what §3.9 requires for a re-run.

A **model-version change is the case that determinism does not cover**: new
weights produce a different `RawInference`, hence different bounds, hence
annotation timestamps that point into footage that has moved. This is exactly
ref §8.5's bug, arriving by a different route. The rule: when a re-analysis moves
a clip's bounds beyond a small epsilon, every annotation on that clip has its
`timestamp_seconds` shifted by the boundary delta, in the same transaction that
writes the new bounds. Annotations that would fall outside the new clip are
flagged rather than silently clamped. This is the one place local deliberately
does something the cloud does not do at all.

**Distribution.** Phase 1 models bundle with the app if they fit comfortably; the
pose model downloads on first analysis from a Supabase `models` bucket. That
bucket is also the answer if §3.5's checkpoint question comes back badly, and it
allows shipping a model fix without an app release.

### 5.5 Cloud sync

The phone computes, then makes Supabase look as though the cloud had done it.
Clips go to the `clips` bucket, thumbnails to `thumbnails`, `rally_clips` rows
through the existing upsert shape, and a `results.json` to `results`.
`results_meta` gains `producer: "local"` and a model-version stamp. No migration.

`results.json` omits `skeleton_data` by default. Uploading the per-frame blob is
the ref §2 problem inverted: a large upload of data the phone already has. The
consequence is explicit: for locally-processed videos the web app can render the
summary but not the per-frame surfaces (skeleton overlay, speed graph), because
those need `skeleton_data`. Acceptable while the web app is the desktop tool,
and reversible by flipping one flag. A/B mode uploads the full payload.

Source-video upload becomes optional. It is mandatory today only because the
cloud needs the pixels.

### 5.6 Capability routing

A device allowlist would rot immediately. Instead, on first analysis the app runs
a calibration pass - a fixed frame count through the pose model at real
resolution - and projects full-video wall clock. Above a threshold expressed as a
multiple of video duration, that video routes to the existing `process-video` and
`start-analytics` edge functions. The result caches per device and per model
version.

The threshold cannot be set from source and comes from §7's stage 0b. Routing
also fires unconditionally when models fail to load or the runtime is
unavailable, so a conversion problem degrades to cloud rather than to a broken
screen.

### 5.7 The A/B harness

One comparator implementation in `:analysis`, two venues: a desktop CLI on the
`jvm()` target for CI, and an in-app dev screen. The in-app venue matters most,
because the device already holds the local output and can download the cloud's
`results.json` for the same video, so comparison runs in place across many real
videos without exporting anything.

**Level 1, perception.** Did the models convert faithfully?

These numbers are only interpretable *because* §5.4 reproduces production's
postprocessing rather than approximating it. Against an argmax-and-first-frame
approximation, a difference here would be dominated by the reimplementation and
would say nothing about the conversion. §5.4's fidelity is what turns Level 1
from noise into a measurement.

- shuttle visibility rate, local versus cloud, as a percentage of frames. This is
  the ref §11.5 cliff metric and the single most important number in the exercise
- shuttle position delta in pixels where both are visible
- player coverage: frames with zero, one and two detections
- keypoint displacement in pixels, mean / median / p95, overall and per keypoint

**Level 2, derived.** Rally count and per-rally time-window IoU, shot count,
per-player distance and average and max speed, zone coverage, and the
recovery / reaction / efficiency summaries.

**Level 3, the split diagnostic.** Nearly free, because `:analysis` is pure:

- run `:analysis` over **cloud's** `skeleton_data`, compare to cloud's own
  analytics. Any difference is a **porting bug**
- run `:analysis` over **local's** `RawInference`, compare to the above. Any
  difference is **model conversion**

Without this split, a rally-count difference is uninterpretable and you will tune
the wrong thing. Level 3's first half is the CI test from §10, so it is already
built.

**Alignment.** The two streams are deliberately not frame-aligned, because
ref §8.3's off-by-one is fixed locally and left alone in the cloud. The comparator
aligns on timestamp, searches a small offset window, and reports the best-fit
offset it found. An offset other than the expected one frame means something else
is wrong.

**Reporting.** A JSON report per video, shown in the dev screen and exportable.
No new tables, no aggregation backend.

---

## 6. The intentional-divergence register

Deliberate differences from the cloud. The comparator takes this list as input:
each entry is either excluded from the pass/fail judgement or annotated in the
report. **Anything not on this list that differs is a bug.**

1. **BoT-SORT dropped.** Identity runs on raw per-frame detections with the
   `W_TRACK_ID` term removed and the other five weights unchanged (§3.2). This is
   the one divergence taken on judgement rather than on evidence, so it carries an
   obligation the others do not: the harness must **quantify** what it costs, by
   comparing per-player assignment against the cloud's on the same footage. If the
   cost is non-zero, porting BoT-SORT into `:analysis` is additive, not a rewrite.
   A divergence accepted for convenience and never measured is how fidelity is
   lost quietly.
2. **`_compute_analytics` aggregation dropped.** `speed_calc` is the only
   aggregation, resolving ref §8.10 rather than porting it.
3. **Frame indexing done correctly.** No off-by-one between shuttle position and
   frame; the cloud's ref §8.3 defect is not reproduced.
4. **`runPhase1` takes the raw shuttle track as given.** In the worker the raw
   track is TrackNet output already gated by a permissive court ROI: x expanded
   1.40 about the centroid, every vertex above the centroid clamped to y = 0
   (`modal_supabase_processor.py:2193-2208`). `runPhase1` applies no such gate,
   so it is MORE permissive than the cloud, and `refineRallies` can widen a
   rally toward noise the cloud had already discarded. Cheap to close - apply
   that ROI to `rawShuttle` before the call - but not worth guessing at before
   the golden comparison says whether it moves any boundary. Revisit as soon as
   a corpus exists.

5. **`onnxconverter_common.float16` fails on the YOLO graphs but not on
   TrackNet or InpaintNet.** Recorded because the boundary is not obvious.
   On 2026-09-01 the converter produced graphs ONNX Runtime refuses to load
   for every YOLO model, failing at a Resize node, and `keep_io_types`,
   `disable_shape_infer` and an `op_block_list` all failed to avoid it;
   `export_yolo.py` uses Ultralytics' own `half=True` instead. This was
   predicted to break TrackNet too, since it upsamples. **It does not** -
   TrackNet and InpaintNet convert through the same code and both load and
   run. So `export_tracknet.py` is correct as written, and the failure is
   specific to how the YOLO exports build their Resize nodes rather than to
   the converter in general.

6. **InpaintNet fp16 has a precision-sensitive case.** On the seeded synthetic
   sweep the fp32 ONNX graph is exact at every length (0.000 px-equivalent),
   while the fp16 graph shifts 0.074 px at length 16 and **5.713 px at length
   128** - and 128 is production's chunk stride (`inference.py:368`), so that
   length really occurs. At 512x288 that is roughly 21 px once scaled to
   1080p. The input is synthetic, which the check's own docstring calls
   inconclusive, so this is a flag rather than a verdict: watch it in the 0a
   coverage numbers, where InpaintNet's real contribution is measurable, and
   fall back to the fp32 InpaintNet if it shows there. It is 2.1MB against
   1.1MB, so that fallback is nearly free.

This register lives here and grows as more are found.

### 6.2 Resolved: the apparent rally welding was a comparison error

Recorded because the investigation is worth not repeating.

**Symptom.** Comparing the port against a real capture (`2eabfc01`, 25fps,
12032 frames), the cloud's `results.json` listed 26 rallies and the port
produced 20, welding cloud rallies 2 to 5 into one spanning frames 196 to 2510.

**Root cause: the comparison target, not the port.** Running
badminton-tracker's OWN `rally_detection.py` and `rally_detection_shot_gap.py`
on the same reconstructed tracks also yields 20, welded identically. The port
was reproducing the cloud's code correctly.

`results.json`'s rally list is not reproducible from a capture. It is
`union_rallies(gradient over the filtered track, shot-gap over Phase 1's
skeleton_frames)`, and **Phase 1's skeleton_frames are never persisted**: a
`completed` capture carries Phase 2's, written by the full YOLO loop
(`modal_supabase_processor.py:4642`), and a `phase1` capture carries none at
all. One input to the stored list is gone by the time anyone reads the file.

**Verification that replaced it.** `RallyStageParityTest` checks all five
stages - gradient, raw shot-gap, filtered shot-gap, union, refine - against the
cloud's own detectors on identical inputs, over three real captures at 25,
29.736 and 50 fps. **206 rallies compared, every stage identical.** Goldens are
regenerated by `tools/corpus/make_stage_goldens.py`.

Checking stages rather than one end number is also the better test: it
localises a future divergence to a single detector instead of leaving it to be
bisected. Verified non-vacuous by mutation - moving the union's overlap
threshold from 0.5 to 0.4, or the gradient detector's landing buffer from 0.5s
to 0.4s, both fail it.

**The standing lesson.** Where a cloud artifact's inputs are not preserved, it
cannot serve as a fidelity oracle no matter how authoritative it looks. Compare
against the source implementation on inputs you control.

### 6.1 Not divergences: cloud behaviour reproduced on purpose

Found while porting, confirmed against the source, and deliberately kept. They
look like bugs, so they are recorded to stop a later reader "fixing" them into
a real divergence.

- **Refinement emits overlapping rallies.** `refine_rallies` clamps each
  boundary against the ORIGINAL filtered bounds, never the refined ones, so one
  raw rally spanning two filtered rallies widens the first forwards and the
  second backwards past it. Refining `(10, 20)` and `(23.5, 33)` against raw
  `(9.5, 25)` gives `(9.5, 23.1)` and `(20.4, 33)`.
- **Two clips can therefore cover the same footage.** `pad_rally_windows`
  documents that "no two clips duplicate rally footage", and that holds only
  for non-overlapping input, which its own upstream does not guarantee. The
  pair above pads to `[7.5, 23.1]` and `[20.4, 34.5]`. Anything downstream
  assuming disjoint clips is wrong against the cloud as well as against this
  port. Pinned by `Phase1PipelineTest.clip_windows_can_overlap_when_refinement_overlaps`.
- **The two rally detectors use different gap thresholds**, 3.0s for the
  gradient detector and 3.1s for the shot-gap one. Unifying them would change
  the union, so it is a measured change, not a tidy-up.

---

## 7. Stage 0: two gates and a check

Run before anything else is built. 0a and 0b are throwaway spikes and each can
stop or reshape the project; 0c is now a short verification task (see §3.5).

**0a. TrackNet conversion.** Pull the weights off the Modal volume, export to
ONNX fp16, run on a real device against a video with known cloud output, and
measure shuttle visibility rate against cloud's. Below roughly 25% in a rally
window the shot-gap detector rejects rallies outright, so a coverage collapse
means no rallies, not slightly worse ones. **Gate: if coverage does not survive,
stop and reconsider before anything else is built.**

**0b. Pose throughput.** Pin and fetch `yolo26m-pose.pt` (§3.4), convert, and
measure sustained ms/frame across a **10-minute** run at both 960 and 640, on the
oldest supported device and a current flagship. A 30-second benchmark does not
show throttling and is worthless here. **Output: the §5.6 threshold, and a
yes/no on whether Phase 2 local is viable.**

**0c. TrackNet licence, now a check rather than a gate.** The upstream is
recorded as MIT (§3.5), which permits redistribution, so this no longer blocks
the work. What remains is ten minutes: confirm MIT against the upstream
repository, vendor the licence text, check whether the checkpoints carry terms
separate from the code, and add the attribution entry the licence requires. Only
a surprise on the checkpoint question would force serving from a `models` bucket
instead of bundling.

---

## 8. Deliberately not in this pass

**The `gb_fusion` variant.** `yolo11s-ball.pt` and `_merge_shuttle_sources` are
an A/B experiment on the cloud side; the default variant is `legacy`. Local
implements `legacy` only.

**int8 quantization.** The change that would trigger ref §8.1's distance inflation.
Not attempted until fp16 numbers exist to compare against.

**Fixing zone coverage to court coordinates.** Real, and wrong in both
implementations today, but changing it here alone would make local and web
disagree by design. It should change in both at once.

**Native Core ML and LiteRT runtimes.** The escape hatch is designed for
(§5.1's interface) but not built. Only justified if §7's numbers demand it.

**Local-first storage.** Clips, annotations and sharing stay on Supabase. Going
fully offline would mean reimplementing every repository interface against a
local store, and is a separate project.

**Moving the web app's analytics server-side.** Ref §10 recommends it, and it remains the right call for the web app, but it is
independent of this work and touches `badminton-tracker`, which this design
leaves alone.

**Aggregate cross-video A/B statistics.** Reports export as JSON. If trends
across many videos are needed, that is a small tool over exported reports.

---

## 9. Sequencing

**Stage 0** - the three gates in §7.

**Stage 1: Phase 1 vertical slice, Android first** (revised, see below; this
paragraph originally read "iOS first"). Capture the verification corpus,
which also closes ref §8.11. Port the `:analysis` subset Phase 1 needs: geometry and
homography, shuttle track filtering, shot detection, both rally detectors,
`refineRallies`, clip padding. Build the `RawInference` format, the single decode
pass, TrackNet and the detector, device-side clip cutting, and the sync path.
Comparator levels 1 to 3 scoped to Phase 1 metrics. Ends shippable: rally clips
produced entirely on device, syncing to Supabase, with measured agreement against
cloud. The original reasoning for iOS first was that its hardware range is
narrower and the signal cleaner; that still holds in the abstract and is
outweighed by the reasons below.

**Stage 2: Phase 1 on iOS.** Only the platform layer is new. `:analysis` and the
comparator are done and tested. This is the payoff for the §5.1 boundary.

**Revised 2026-09-01: stages 1 and 2 are swapped, Android goes first.** There
is no iPhone available and there is a Galaxy S23. Beyond availability, Android
is the better first target: `androidApp` is Kotlin, so `:analysis` runs on the
device unchanged, which removes the one two-language implementation this design
accepted in §5.4 and the shared golden vector that paid for it. The
`RawInference` writer is also the reader, so its byte-order contract has one
side rather than two. iOS then inherits a device interface already exercised
against a real engine rather than only a fake.

**Stage 3: Phase 2.** `:analysis` grows the identity tracker, pose
classification, the speed and distance chain, and the ref §5.2 analytics, each with
golden-file tests. The platform layer adds the pose model to the existing decode
pass. The comparator extends to keypoint displacement and derived analytics. The
largest stage, and the one whose shape stage 0b decides.

**Stage 4: capability routing and fallback.** Required before Phase 2 reaches
users on the low end; force-enable during development means it is not needed
earlier.

**Stage 5: hardening.** Interruption and resume, thermal backoff, intermediate
storage management, and the iOS background-execution question from ref §11.6.

The two things that can kill the project, TrackNet coverage and pose throughput,
are answered before the large port begins. The port is the biggest chunk but the
least risky: pure Kotlin, no device, verified in CI.

**This design spans more than one implementation plan, deliberately.** The first
plan should cover **Stage 0 and Stage 1 only**, because Stage 0's gates can
invalidate everything after it and Stage 1 ends at a shippable, measurable state.
Stages 2 through 5 each get their own plan, written once the preceding stage's
numbers exist. Writing a single plan across all five would be committing detail
to decisions that Stage 0 has not yet informed.

---

## 10. How this is verified

**`:analysis`, unit, in CI.** Golden-file equivalence against recorded cloud
output: feed a real `results.json`'s `skeleton_data`, `manual_court_keypoints`
and `fps` into the ported analytics and assert it produces the same rallies,
speeds, placements, recovery, reaction and efficiency the browser computes from
that identical input. No models, no device, no video. Runs on the existing
`jvmTest` job.

Tolerances: exact equality for anything discrete - rally counts, frame indices,
shot counts, zone bucket assignments. Relative `1e-6` for floats. A failure at
those tolerances is a porting bug, not float noise, and gets fixed rather than
loosened.

Trimmed fixtures of two to three minutes of `skeleton_data` live in the repo so
CI stays fast; the full corpus lives outside it for the device work.

**`:analysis`, unit, the tricky ones.** The identity tracker's calibration
refusing to complete with both players on one side; the net-band hysteresis; the
`normalize_fps` substitution path; `padRallyWindows` never reaching into a
neighbour's detected window; `refineRallies` clamping; the trailing-isolated-shot
case that ref §8.6 describes. These have known-correct behaviour
documented in the audits and should be tested against it directly, not only
through golden files.

**Platform layer, on device.** That a full decode pass produces one
`RawInference` record per frame with monotonic timestamps, and that a re-run over
the same video produces a byte-identical file. Determinism here is what makes
§3.9 hold.

**A/B, on device, per video.** The three levels in §5.7 against the corpus.
Level 3 first: if the porting-bug half is non-zero, stop and fix that before
reading anything else.

**End to end, by hand, once per platform.** Import a video, mark the court, run
locally, watch clips appear, confirm they sync and are visible from the web app
and from a second device, annotate one, re-run the analysis, and confirm the
annotation still points where it should. Then the awkward one: background the app
mid-analysis and return.

**Not verified by this design.** Whether local is *accurate enough* to replace
cloud. That is what the harness measures, and the answer is an outcome of the
work, not a claim of it.
