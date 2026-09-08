# Design: the on-device analysis pipeline on iOS

**Date:** 2026-09-09
**Status:** Proposal, pending approval
**Implements:** Stage 2 of `2026-08-31-on-device-analysis-pipeline-design.md`
("Phase 1 on iOS"), plus the Phase 2 platform work Android has since shipped.
**Reference convention:** a bare `§N` means a section of *this* document;
`pipeline §N` means a section of the 2026-08-31 design; `stage1 Task N` means a
task of `2026-08-31-stage1-device-layer-plan.md`.

Android analyses a match on the phone today: it cuts rallies into clips, tracks
where the near player stood, keeps the skeleton, and draws all three on an
Analytics detail screen. iPhone has the Analytics *list* and nothing behind it.
Every row on that list is `ANALYSABLE` forever, because `hasStoredTrack` is
hardcoded `false` (`AnalyticsRows.swift`), and the only thing an "Analyze"
button can do is send the video to the cloud.

This design closes that. It is a **platform-layer port**, not a second
implementation of the analysis: `:analysis` is already a Kotlin Multiplatform
module with `iosArm64`, `iosX64` and `iosSimulatorArm64` targets, and
`LocalAnalysisCoordinator` is already in `commonMain` behind a one-method
injected seam. What iOS is missing is the four things the seam does not cover -
decode, inference, storage, and the screens.

---

## 1. What exists today, on each side

| | Android | iOS |
|---|---|---|
| `:analysis` (geometry, shuttle track, rally detection, near-player selection) | used directly | **compiles for iOS already**, not yet reachable from Swift (§3.1) |
| `LocalAnalysisCoordinator`, `AnalysisPlan`, `DeviceThroughputRepository` | used | in the framework, unused |
| `LocalInferenceEngine` implementation | `AndroidLocalInferenceEngine` | **missing** |
| decode | `VideoFrameSource` (MediaExtractor + MediaCodec) | **missing** |
| preprocessing | `FramePreprocessor` | **missing** |
| model runners | TrackNet, detector, pose | **missing** |
| ONNX Runtime | `onnxruntime-android` 1.20.0 | **missing** |
| track / skeleton / clip stores | `PlayerTrackStore`, `SkeletonStore` | **missing** |
| clip cutting | `ClipCutter` (MediaCodec + MediaMuxer) | **missing** |
| run orchestration | `LocalAnalysisRunner` + a foreground service | **missing** (§4) |
| Analytics list | `AnalyticsScreen` | ported, in sync |
| Analytics detail (heatmap / base / skeleton) | `AnalyticsDetailScreen` and five panels | **missing** |
| cloud-or-device choice at court marking | `CourtMarkingScreen` offers both | cloud only |

The list line is the one that decides the shape of the work: **everything above
the line is shared code iOS can call, and everything below it is Swift that has
to be written.** The 2026-08-31 design bought that split deliberately
(pipeline §5.1), and this is the stage that collects on it.

---

## 2. What "in sync with Android" means here, exactly

Three different things, and they are worth separating because only two of them
are achievable.

**Numerically in sync.** The same video, marked the same way, must produce the
same rallies, the same clip bounds and the same player track on both phones.
This is achievable and it is the point: everything downstream of `RawInference`
is one Kotlin implementation running on both platforms, so parity reduces to
parity of `RawInference` itself. §3 is about protecting that.

**In sync in what the coach can do and see.** The same screens, the same
controls, the same words. Achievable, and §5 covers it.

**In sync in when the work can run.** *Not* achievable, and §4 says so rather
than pretending otherwise.

---

## 3. The engine

### 3.1 `:analysis` becomes visible to Swift

`shared/build.gradle.kts` declares `api(project(":analysis"))`, which is what
puts `RawInference`, `PlayerTrack` and `AnalysisResult` in the generated
`Shared.h` - they are reachable from `:shared`'s own public API, so the
compiler drags them along. Free-standing functions are not: `heatmapToCoord`,
`medianBackground` and `selectNearPlayer` appear nowhere in the header, because
no exported signature mentions them.

Add `export(project(":analysis"))` to the framework block. One line, and it is
the line that keeps this port from growing a second implementation of the
shuttle heatmap postprocessing.

That matters more than it looks. Pipeline §5.4 conceded exactly one
two-language implementation - the heatmap blob detection - and paid for it with
a committed golden vector, on the assumption that the iOS side would be Swift.
Stage 1 deleted that concession by going Android-first, where `:analysis` runs
unchanged. **This design keeps it deleted.** `heatmapToCoord`'s threshold, its
`max_area = 100` blob rejection and its weighted centroid are subtle enough
that two implementations would drift, and there is no reason to have two.

### 3.2 ONNX Runtime, same version, same graphs

`microsoft/onnxruntime-swift-package-manager` at tag **1.20.0** - the same
version `gradle/libs.versions.toml` pins for Android. Added through XcodeGen's
`packages:`, not CocoaPods: this project has no workspace, and introducing one
would collide with the `embedAndSignAppleFrameworkForXcode` pre-build script
that produces `Shared.framework`.

The same `.onnx` files run on both platforms. **Not CoreML.** Converting would
invalidate every parity oracle this repo has built - `check_tracknet_parity.py`,
`pose_parity.py`, `:analysis:compareResults` - and would do it in exchange for
speed that has not been shown to be needed on an iPhone. If the Neural Engine
turns out to be worth it later, it arrives as ONNX Runtime's CoreML execution
provider behind the same `OnnxSession` seam, with its own parity gate.

Checked before committing to this: the three graphs are fp16 *internally* and
`float32` at the boundary (`elem_type 1` on every input and output), with fully
static shapes. So the Objective-C binding's plain `float` tensors are enough,
and no fp16 plumbing is needed.

**InpaintNet is not ported, because Android never implemented it.**
`TrackNetRunner`'s KDoc names an `InpaintNetRunner` that does not exist, and
pipeline §6.3 records why: on both measured videos it filled zero frames, and
production's own PyTorch path fills zero too. Porting a stage Android does not
run would be a divergence, not parity. The stale Android KDoc is a real defect
and is listed in §7.

### 3.3 Decode: `AVAssetReader`, sequentially

Three walks of the file, two of which decode. The two that decode match
`VideoFrameSource` exactly:

1. **A background pass.** Production computes the median background from up to
   300 frames sampled evenly across the whole video, and that background is an
   input to the *first* inference of the main pass, so it cannot be computed
   lazily.
2. **The main pass**, one frame at a time, no seeking.

Android reaches the 300 sampled frames with `MediaMetadataRetriever.getFrameAtIndex`,
which seeks. **iOS does not seek at all: it reads the whole file sequentially
and keeps the frames whose index is in the sampled set.** `AVAssetImageGenerator`
is the only frame-indexed-looking API on this platform and it is not
frame-indexed at all - it seeks by *time*, and on the variable-frame-rate corpus
a time-derived index lands on a different frame than production's
`cap.set(CAP_PROP_POS_FRAMES, idx)`. A sequential pass gets the exact frames by
construction. It costs one extra decode of the file with no inference attached,
which is small beside a pass that runs three models per frame, and it is
*more* faithful than Android's, not less.

**The third walk counts frames and decodes nothing.**
`AVAssetReaderTrackOutput` with `outputSettings: nil` vends samples in the
format they are stored in, which makes counting container parsing rather than a
decode - the direct analogue of Android's `MediaExtractor` sample walk. It
matters more than it sounds: `nominalFrameRate` times duration is an estimate,
on this corpus it is the wrong estimate, and the count sets every frame index
and therefore every rally boundary.

**The background pass gets a tenth of the progress bar.** It is minutes of work
on a long match, and a bar that only starts moving once the models do reads as a
run that never started. Android does not need this - its background pass is 300
seeks - and it is the reason the two platforms' bars cannot be compared tick for
tick.

The sample indices themselves come from `backgroundSampleIndices`, which
reproduces `np.linspace(..., dtype=int)`'s truncation. That belongs in
`:analysis` so both platforms read one implementation; today it sits in
Android's `VideoFrameSource`. Moving it is §7's work, not a blocker.

Timestamps come from `CMSampleBufferGetPresentationTimeStamp`, never `i / fps`,
for the reason pipeline §5.2 gives and the reason the repo has already been
bitten by: a pose drawn on a seeked frame looks like a phantom.

Pixel format is `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange` - the YUV the
decoder actually produces, not BGRA. Letting AVFoundation convert would hand
the colour decision to a framework whose matrix choice is undocumented and
version-dependent, and §3.4 is precisely about not doing that.

### 3.4 Preprocessing is where parity is won or lost

`FramePreprocessor` is ported line for line, including the two findings that
took measurement to establish and that no reimplementation would rediscover:

- **BT.601 limited range**, on footage that declares BT.709. Production decodes
  with OpenCV's `VideoCapture`, which does not pass the container's colour space
  to swscale, so swscale falls back to BT.601. Measured across four candidate
  matrices, BT.601-limited is off by a mean of 1.56/255 and the file's own
  declared BT.709-limited by 3.45 (pipeline §6.4). Matching the cloud's
  implementation beats matching the standard it claims to follow.
- **OpenCV's pixel-centre resize mapping**, `(dst + 0.5) * scale - 0.5`. The
  naive `dst * scale` is off by half a source pixel on every frame feeding every
  model.

The one structural difference from Android is the chroma layout: MediaCodec's
`YUV_420_888` gives three planes with per-plane strides, and iOS's biplanar
format gives Y in plane 0 and interleaved CbCr in plane 1. That is a different
*address* for the same sample, not a different sample, so the arithmetic is
identical and only the indexing changes.

This is verified, not assumed. §6 says how.

### 3.5 Clip cutting

`AVAssetReader` plus `AVAssetWriter`, re-encoding rather than stream-copying,
for the reason Android and the cloud both re-encode: rally bounds are
frame-accurate and a stream copy snaps to the nearest keyframe.

`AVAssetExportSession` is the shorter route and is rejected: its
`timeRange` honours `AVAssetExportPresetPassthrough`-style keyframe snapping in
practice, and it gives no control over whether audio is carried, which the
clips do not need.

### 3.6 Model distribution

The three graphs used - `tracknet.fp16.onnx` (22.7MB), `badminton.fp16.onnx`
(6.2MB), `posen.fp16.onnx` (6.3MB) - total **35.2MB**, staged into the app
bundle by a build script that copies them out of `tools/models/onnx`, exactly as
`androidApp/build.gradle.kts` stages them into assets.

They are not committed: `tools/models/onnx/` is gitignored derived output. So
the script needs the same escape hatch Android's task has - Android takes
`-PonnxModelsOptional=true` and CI passes it; iOS takes `ONNX_MODELS_OPTIONAL=YES`
and the `ios-app` CI job sets it. A build without models compiles, packages and
cannot analyse a video, and says so.

`inpaintnet.onnx` is bundled on Android and used by nothing (§3.2). It is not
bundled here.

**Attribution travels with the weights.** The MIT notice obligation
stage1 Task 10 Step 5 records applies to this bundle too; the iOS licences
screen gets the same `TrackNetV3-LICENSE.txt` entry Android's does.

---

## 4. Background execution: the one thing that cannot be in sync

Android holds a foreground service and a wake lock for the whole run
(`LocalAnalysisService`), so a coach can lock the phone and come back to
finished clips. **iOS has no equivalent and will not get one.** What it has:

- `beginBackgroundTask` - seconds to a low minute, intended for finishing a
  request, not for running a model over 6,000 frames.
- `BGProcessingTaskRequest` - runs when the system chooses, typically
  overnight on charge, and can be terminated at any moment. It cannot be made
  to start when the coach presses a button.

So the honest options are: run only while the app is in front, or promise
background analysis and deliver a run that dies silently at 4%.

**Decision: foreground-only, stated in the UI, with the idle timer disabled
while a run is in flight.**

- `UIApplication.shared.isIdleTimerDisabled = true` for the duration, so the
  screen does not lock under a running analysis. This is the direct analogue of
  Android's wake lock and it is what makes a 20-minute run survivable.
- On `scenePhase` leaving `.active`, the run is **suspended and reported as
  suspended**, not cancelled: the coach comes back to a run that resumes, or to
  an honest "Analysis paused - keep Shuttl open" line. Partial work already
  written (the track, the skeleton) is kept, because `LocalAnalysisRunner`
  already writes those before clip cutting for exactly this class of reason.
- The metric picker's time estimate says "keep Shuttl open" on iOS. It is the
  same `AnalysisEstimate.describe()` string with one platform sentence after it,
  not a different estimate.

This is a real divergence from Android and it goes in pipeline §6's
intentional-divergence register (§7).

**Not decided here:** whether long videos should route to the cloud on iOS more
readily than on Android because of this. That is a capability-routing question,
pipeline §5.6 and Stage 4, and it needs measurements from an actual iPhone that
do not exist yet.

---

## 5. The screens

Everything on this list already exists on Android and is ported, not designed.
The point of listing them is that "the pipeline works" is not the deliverable -
the deliverable is that a coach cannot tell which phone they are holding.

**Court marking gains the target choice.** `CourtMarkingScreen` on Android ends
in a picker: analyse in the cloud, or on device, and for the device path a
metric selector (rally clips / player movement / skeleton) with a time estimate.
`CourtMarkingView` ends in a single "Start Analysis" button. It gains the same
picker and the same `MetricSelector`, reading the same
`estimateAnalysis` and `DeviceThroughputRepository` from `shared`.

**The Analytics list stops being a dead end.** Four things change in
`AnalyticsRows.swift` and `AnalyticsListView.swift`, and all four are comments
today that become false the moment a track can exist:

1. `hasStoredTrack: false` becomes a real lookup against the track store.
2. `buildAnalyticsRows` takes the device runner's liveness map as well as the
   cloud's, and `analyseAffordance` composes both, as Android's `affordanceFor`
   does. Its "ONE liveness source, not two" KDoc is rewritten, not left to rot.
3. A `READY` row becomes tappable and opens the detail screen.
4. The `.dot` legend regains "- tap to view", which was dropped only because
   there was nothing to tap.

**The detail screen** is `AnalyticsDetailScreen`'s port: a `ShuttlPillTabs` row
(already ported) over Heatmap / Base / Skeleton, each tab offered only when it
has content.

**The panels**, in the order they are worth building:

| panel | what it draws | Android source |
|---|---|---|
| Heatmap | where the near player stood, on a court | `HeatmapPanel`, `CourtHeatmapView` |
| Base | per-rally base position | `BasePositionPanel`, `BasePositionFormat` |
| Skeleton | joints over playback, with the metrics strip and graph | `SkeletonPanel`, `SkeletonOverlay`, `MetricsStrip`, `MetricGraph` |

**The chrome.** `BackgroundWorkAction` and `LocalAnalysisBanner` have shared
state models (`BackgroundWork`, `DeviceWork`, `deviceWorkLabel`) already in
`shared`, so the port is the SwiftUI, not the logic.

---

## 6. How this is verified

The whole argument of §2 is that parity reduces to `RawInference` parity, so
that is where the evidence has to be.

**Throughput, before the picker quotes anything.** Measured 2026-09-09 and
recorded in `tools/models/reports/ios-throughput-simulator.md`. Two findings:
build configuration moves the per-frame cost by 3.8x, because the preprocessing
is a per-pixel loop that `-Onone` does not optimise; and the S23 seeds
`DeviceThroughput` ships are not defensible on this platform. What is still
missing is a real iPhone, and until it exists the picker's first estimate on iOS
is a reference Android device's.

**Throughput, before the picker quotes anything.** Measured 2026-09-09 and
recorded in `tools/models/reports/ios-throughput-simulator.md`. Two findings:
build configuration moves the per-frame cost by 3.8x, because the preprocessing
is a per-pixel loop that `-Onone` does not optimise; and the S23 seeds
`DeviceThroughput` ships are not the right shape for this platform. What is
still missing is a real iPhone, and until one exists the picker's first estimate
on iOS is a reference Android device's.

**Preprocessing, on the simulator, against the committed fixture.** stage1
Task 12 produced reference tensors from production's OpenCV. The Swift
`FramePreprocessor` is held to the same threshold the Kotlin one is, on the same
frames. This runs in the existing `iosAppTests` target and needs no models.

**`heatmapToCoord` needs no iOS test at all** - it is the Kotlin one, called
from Swift. That is the payoff of §3.1 and it is worth stating so nobody adds a
Swift copy "for symmetry".

**Decode, on the simulator.** The corpus video decodes to exactly 5,972 frames
at 29.73572449542545 fps, a number stage1 Task 11 pinned against both the
container and `results.json`. A different count on iOS shifts every frame index
and therefore every rally boundary, and is a finding rather than a rounding
difference.

**`RawInference`, device against device.** The real gate. Run the same corpus
video through both engines and diff the emitted `RawInference`. Everything
downstream is one implementation, so this single comparison covers rallies,
clip bounds and the player track at once. `RawInferenceCodec` already gives both
sides a byte format to write.

Tolerances follow pipeline §10: exact for anything discrete - frame count,
frame indices, visibility flags - and relative `1e-6` for floats is *too tight*
here and deliberately not used, because two different decoders are involved.
The shuttle-position threshold is the one number this design cannot set from a
desk; it is set from the first paired run and recorded in the plan.

**End to end, by hand.** Import a video on an iPhone, mark the court, analyse on
device, watch clips appear, open the heatmap, scrub the skeleton. Then the
awkward one §4 exists for: background the app mid-run and come back.

---

## 7. Register: what this changes elsewhere, and what it leaves behind

**New intentional divergence, iOS from Android.** Foreground-only analysis (§4).
Belongs in pipeline §6.

**Android defects found while reading, not fixed by this design.**

- `TrackNetRunner`'s KDoc names an `InpaintNetRunner` that does not exist, and
  `ModelCatalog.INPAINTNET` carries a long comment about a graph nothing loads.
  `inpaintnet.onnx` is bundled into the APK and read by nothing. Small, and it
  is exactly the kind of stale comment this codebase treats as a defect.
- `backgroundSampleIndices` was production-parity arithmetic living in an
  Android UI-layer file. **Moved to `:analysis` in this pass**, since iOS needs
  it and a second copy was the alternative; its tests moved from `androidTest`
  to `commonTest` with it, where CI runs them.
- `TrackNetRunner` reports progress of exactly 1.0 on the last full sequence,
  which `LocalInferenceEngine`'s own contract forbids: completion is the
  coordinator's to report after the analysis that FOLLOWS inference. The
  coordinator's `coerceAtMost(0.999f)` hides it, so the visible symptom is only
  that Android's bar sits full through rally detection and clip cutting. iOS
  clamps in the engine; Android still does not.
- `DeviceThroughputRepository` records what a run achieved without knowing which
  build produced it. A Debug build measures 3.8x slower here than a Release one,
  and a developer running Debug teaches the estimator that this phone is four
  times slower than it is - and the estimate persists. **Fixed in this
  pass**, since iOS needs it and a second copy was the alternative.
- `TrackNetRunner` reports progress of exactly 1.0 on the last full sequence,
  which `LocalInferenceEngine`'s own contract forbids - completion is the
  coordinator's to report after the analysis that follows inference. The
  coordinator's `coerceAtMost(0.999f)` hides it today, so the visible symptom is
  only that Android's bar sits full through rally detection and clip cutting.
  iOS clamps in the engine; Android still does not.

**Deliberately not in this pass.**

- Cloud sync of locally-produced results (pipeline §5.5). Android does not do it
  either; adding it on iOS first would be a divergence in the wrong direction.
- Capability routing (pipeline §5.6, Stage 4). Needs iPhone throughput numbers
  that do not exist.
- The in-app A/B comparator (pipeline §5.7). The desktop CLI covers the
  measurement this stage needs.
- CoreML execution (§3.2).
