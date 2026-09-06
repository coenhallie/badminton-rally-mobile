# Design: Phase 2 on device, near player only

**Date:** 2026-09-01
**Status:** Proposal, in implementation
**Implements:** Stage 3 of `2026-08-31-on-device-analysis-pipeline-design.md`
**Reference:** `2026-08-31-web-analysis-pipeline-reference.md`

Phase 1 produces rally clips. This adds the two analytics a coach asked for: a
**court heatmap** showing where the player spent the match, and a **skeleton
view**. Both need per-frame pose, which is the thing the cloud never gave mobile.

Stage 3 was described as "the one whose shape stage 0b decides". Those numbers
now exist (§2), and they decide three things: which model, at what input size,
and how many players.

---

## 1. What exists today

Verified by reading the code and by measurement, not assumed.

- The detector already finds people. Classes are `0: person, 1: racket,
  2: shuttle` (`DetectorRunner.kt:18`), it runs at 640 on every frame in the
  existing decode pass, and only class 2 is read. **The person boxes are already
  paid for.**
- `Homography.calculateHomography` and `courtHomography` are ported with parity
  tests (`Homography.kt:16,70`), against `src/utils/homography.ts`.
- `validNetLine` is ported (`Polygon.kt:61`).
- `CourtKeypoints` carries `netLeft` / `netRight` and all twelve marked points
  (`CourtGeometry.kt:20-31`), collected before an analysis can start.
- `Court.LENGTH = 13.4`, `WIDTH_DOUBLES = 6.1` (`CourtGeometry.kt:7-8`).
- Pose is deliberately not bundled: 43.5MB against the whole Phase 1 set's
  28.5MB (`ModelCatalog.kt:15`).
- Phase 1 costs 235 ms/frame on an S23.

---

## 2. The measurements that decide the shape

On the S23, and against `yolo26l-pose@960` as reference, near player, 120 frames:

| config | ms/frame | coverage | ankle error | all joints | worst joint |
|---|---|---|---|---|---|
| nano@640 | 110 | 95% | 10.2 cm | 6.4 px | wrist 11.4 px |
| **nano@960** | **230** | **93%** | **8.9 cm** | **4.7 px** | **elbow 6.9 px** |
| small@960 | 551 | 94% | 4.4 cm | 3.8 px | hip 5.3 px |
| medium@960 (cloud's) | 1567 | 93% | 2.8 cm | | |

Three facts follow, and each kills an option:

**2.1 The cloud's configuration is not viable.** Medium at 960 on every frame is
1567 ms cold and about 2200 ms thermally settled. With Phase 1 on top that is
eight hours for an eight-minute video. Acceleration does not rescue it: CPU
595ms, NNAPI 629ms, XNNPACK 1803ms.

**2.2 Nano's weakness is the far player, not precision.** Nano finds the far
player in 44% of frames against small's 68%. On the near player every model
lands between 93 and 95%, nano at 640 included. Tracking one player removes
nano's only real defect rather than tolerating it.

**2.3 The far player is the untrustworthy half regardless of model.** Even the
largest model finds it in about 70% of frames against 93% near. Dropping it
loses less than it appears to.

---

## 3. Decisions

| Question | Chosen | Rejected, and why |
|---|---|---|
| Model | `yolo26n-pose` | medium: 8 hours for an 8-minute video (§2.1). small: 2.4x the cost of nano for 4px of joint accuracy that no view resolves |
| Input size | 960 | 640 is 2x faster and immaterial for the heatmap, but its worst joint is the **wrist** at 11.4px, which is the fastest-moving joint on a racket arm and the one a coach looks at |
| Players | Near only | Both: the far player costs the same inference and returns 70%-coverage data. Revisit as a second pass if relational analytics are ever wanted |
| Frame rate | Every frame | Sampling at 5fps: unnecessary once nano is chosen, and it would make the skeleton view choppy. Full rate is the accuracy-maximal option and it is affordable |
| Heatmap space | Court metres | Video-normalised pixels, as `useHeatmap.ts` does: camera-dependent, not comparable between matches, and a fixed pixel blur is a spatially varying court blur |
| Position point | Ankle midpoint, hip fallback | Box centre or hip: the homography maps the ground plane, and a hip is a metre above it |
| Off-court detections | Rejected by projected position | Trusting the net line alone: the far side of it contains the crowd, and the measurement rejected more detections than it kept |

---

## 4. `:analysis`, the pure layer

New package `com.badmintontracker.analysis.player`.

### 4.1 `PoseFrame`, the platform boundary

The platform layer decodes ONNX output; `:analysis` never sees a tensor.

```kotlin
/** One detected person in one frame, in video pixels. */
data class PosePerson(
    val boxConfidence: Float,
    val keypoints: List<Point>,      // COCO-17 order
    val keypointConfidence: List<Float>,
)

data class PoseFrame(val frame: Int, val people: List<PosePerson>)
```

### 4.2 Selecting the near player

Three gates, in order, because each is cheaper than the next and rejects
different things:

1. **Ground point.** Ankle midpoint if both ankles clear `MIN_KEYPOINT_CONFIDENCE`,
   else hip midpoint, else the person is skipped. Which one was used is carried,
   because a hip-derived point is about a metre off the plane and downstream
   quality reporting needs to know.
2. **Side of the net.** The net's y interpolated at the point's x, never a pixel
   midline. `validNetLine` guards it; without that guard a degenerate line
   classifies every point as one side.
3. **On court.** Project through the homography and reject outside the court
   plus `OUT_OF_COURT_MARGIN_M = 2.0`. This is what removes the crowd.

Of what survives, the highest box confidence wins. One player per frame.

### 4.3 `CourtOccupancy`, the heatmap

A grid over the court in metres, accumulating **time**, not sample counts:

```kotlin
class CourtOccupancy(val cellSizeM: Double = 0.25) {
    fun add(position: Point, seconds: Double)
    fun grid(): List<List<Double>>
}
```

Time-weighted so the map does not change meaning when frames are dropped or the
view is filtered to rallies. Samples carry `dt` from their own frame spacing.

Smoothing is a Gaussian over the metre grid, so the kernel is the same physical
size everywhere on the court. This is the concrete accuracy argument for court
space over the web app's pixel space.

### 4.4 `PlayerTrack`, what gets persisted

```kotlin
data class PlayerSample(
    val frame: Int,
    val courtPosition: Point,        // metres
    val onAnkles: Boolean,
    val inRally: Boolean,
)
```

Small, and separate from the skeleton dump. The heatmap needs only this; the
skeleton view needs the keypoints. Keeping them apart means the heatmap does not
pay the skeleton's storage, and `inRally` makes whole-match versus rallies-only a
view toggle rather than a re-analysis.

---

## 5. Android

### 5.1 `PoseRunner`

Mirrors `DetectorRunner`: letterbox to 960, CHW tensor, one forward pass,
decode `(1, 56, anchors)` into 17 keypoints plus a box, NMS, and hand
`PoseFrame` to `:analysis`. It joins the existing single decode pass rather
than adding a second one.

### 5.2 The model ships side-loaded first

nano at 960 fp16 is 6.3MB, against medium's 43.5MB, so bundling is defensible
where it was not before. Until the pipeline is proven end to end it is read from
`/data/local/tmp`, as the throughput tests do, so APK size does not move on an
unproven path.

---

## 6. Deliberately not in this pass

- **The far player.** §2.3.
- **Speed, distance, reaction time, shot placement.** They need the far player
  or a smoothing decision the reference says must not be tuned blind (ref §8.1).
- **iOS.** Platform layer only, after this is proven.
- **Bundling the model in the APK.** §5.2.
- **The hip-fallback problem.** About a quarter of positions fall back to the
  hip, which is a systematic court error larger than any model-size difference.
  The fix is cropping the person box and running pose on the crop, which the
  detector's boxes already make possible, and it deserves its own measurement.

---

## 7. How this is verified

- `commonTest`: near-player selection against hand-built frames, including a
  crowd detection outside the court, a person on the far side, and a degenerate
  net line. Occupancy accumulates time rather than counts, and is invariant to
  sampling rate for the same path.
- Device: `PoseRunner` decodes a real frame into 17 plausible keypoints.
- End to end: heatmap of a real match concentrates where a player actually
  stands, which is a shape check no unit test makes.
