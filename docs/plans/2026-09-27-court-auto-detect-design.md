# Design: find the court automatically, so nobody has to tap twelve points by hand

**Date:** 2026-09-27
**Status:** Approved for implementation by Coen on 2026-09-27 ("Lets implement
the feature. Only pause when there are either breaking changes, or other really
important questions. Implement it as accurate as possible, test it too"). Coen
delegated the design calls; a reviewer agent reviewed the approach in his place.
**Repo:** `badminton-rally-mobile` only. Nothing here changes the wire format of
the marks, so neither the cloud worker nor the web app changes.
**Follows:** `2026-09-27-camera-motion-design.md` (`marked_at_s`, the frame the
marks belong to) on `feat/camera-motion`; this branch stacks on it.

---

## 1. The problem

Before an analysis the coach marks the court: twelve taps on a still of the
video, in a fixed order, each on a line crossing. On a phone that is fiddly, and
the accuracy of every court-plane number (heatmap, base position, speeds,
zones) is bounded by it. Measured on `IMG_5892` against the painted lines
(2026-09-27 heatmap investigation), the near-half position error that tap noise
alone causes is:

| taps off by (σ) | player position error, median | p90 |
|---|---|---|
| 1 px (not achievable by hand) | 2.0 cm | 4.4 cm |
| 3 px (careful, zoomed in) | 6.0 cm | 13.2 cm |
| 6 px (a thumb on a phone) | 12.4 cm | 26.7 cm |

The court is made of painted lines of known geometry. A program can find them to
a fraction of a pixel, which is better than any tap, and it can do so in about a
second.

---

## 2. What exists today

- **The marking model** is shared: `CourtMarkingState` holds the twelve points
  in source-video pixels, in the order TL, TR, BR, BL, NL, NR, SNL, SNR, SFL,
  SFR, CTN, CTF (`shared/.../localvideo/court/CourtMarkingModel.kt:7-45`).
  `place` appends one point (`:71`), `undo` drops the last (`:78`), `clear`
  empties (`:80`), `restored` reopens on saved marks (`:108`),
  `toCourtKeypoints` writes the wire type (`:137`). A tap on a complete marking
  is ignored; there is no way to move one point.
- **Android** loads the still at 0.1 s with `MediaMetadataRetriever`
  (`androidApp/.../court/CourtMarkingScreen.kt:776-801`), the view model opens
  on it (`CourtMarkingViewModel.kt:65-86`), taps go through
  `detectTapGestures` with a pinch-zoom inverse (`CourtMarkingScreen.kt:443-452`),
  and the overlay draws a dashed guide, the corner rectangle and the markers
  (`:591-670`). Built in `AuthGate.kt:590`.
- **iOS** loads the still with `AVAssetImageGenerator` and records its actual
  time (`iosApp/Sources/CourtMarking/CourtFrameLoader.swift:91-101`); the view
  keeps the marking in `@State` and places points in `tapGesture`
  (`CourtMarkingView.swift:294-307`); the canvas draws the same overlay
  (`:363-430`).
- **The point labels.** The keypoint table puts the "near" service line and
  centre point on the top-of-frame half (`analysis/.../geometry/CourtGeometry.kt:78-91`).
  The app resolves a mislabelled pair by which side of the net it falls on
  (`geometry/Homography.kt:235-253`); **the cloud does not**: it reads the twelve
  keys literally against the same table (`badminton-tracker/backend/speed_calc.py:43-68`).
  So anything that places points automatically must write them exactly in the
  table's convention.
- **Court detection existed before** in the web app: a Roboflow keypoint model,
  removed from the Modal worker "for better accuracy" in favour of manual marks
  (`badminton-tracker/backend/modal_inference.py:23-25,251`). A learned keypoint
  regressor is a different method from the one below: this design fits the
  painted lines themselves, which is where sub-pixel precision comes from.
- **Pixel access from Kotlin on iOS** goes through `BulkInterop`, which reads
  Swift-owned memory by pointer (`shared/src/iosMain/.../local/BulkInterop.kt`).

---

## 3. What was measured before designing

A Python reference implementation (`tools/court/court_probe.py`, OpenCV) was
iterated on real frames until it worked, then frozen, then run on frames it had
never seen. The frames, their labels and the reference answers are in
`~/shuttl-corpus/court-detect/` (built by `tools/court/build_court_corpus.py`).

**The corpus.** 81 frames from 12 videos, labelled by eye from overlay contact
sheets: 54 show a whole court, 27 do not.

- Courts: `IMG_5892` (handheld, All England, panning), `IMG_4795` (a club
  sports hall: wooden floor, several courts, basketball lines, faint pale
  badminton lines, camera low at a corner), a dark arena (Birmingham), and
  broadcasts from Bangkok, Changzhou, Hong Kong and Paris 2024.
- Not courts, chosen to be hard: player close-ups, crowd shots, a zoomed
  mid-court replay with part of a court visible, a low shot along the net with
  most of the court out of frame, screen recordings of this app, and football
  broadcasts with a pitch-diagram graphic (a drawn rectangle of lines).
- **Tuning set:** 39 frames, used to shape the method. **Held-out set:** 42
  frames extracted and labelled after the parameters were frozen.

**Result, reference implementation:**

| | tuning | held-out |
|---|---|---|
| frames with a court, found and accepted | 29 / 29 | 25 / 25 |
| frames without one, rejected | 10 / 10 | 17 / 17 |

Every accepted fit was checked by eye against the lines. The margin of the
acceptance rule (§4.7): correct fits refine to a residual of 0.29 to 0.66 px and
converge (last step under 0.04 px); the closest wrong fits either keep a
residual of 2.2 px or more or put keypoints outside the frame.

**Accuracy, independent references** (none of these come from the method itself):

| reference | agreement |
|---|---|
| synthetic courts, exact truth (5 camera positions x 4 floor/line colours, blur, noise, distractor lines, occluders) | keypoints median 0.01 px, worst 0.32 px |
| `IMG_5892` marking frame, reference marks from per-line fits made on 2026-09-27 | near-half position median 2.3 cm, p90 3.6 cm |
| Paris 2024, the marks a user placed by hand for that video | median 4.4 px (the spread of hand placement) |
| club hall, the base of both net posts (not painted lines, so independent of the fit) | the fitted net points sit on the post bases by eye at 4x zoom |

On real frames the painted court does not match an ideal court exactly
(painting tolerance, mats, slight lens bending): independent single-line fits
disagree with any whole-court fit by 1 to 6 px on the far lines. That is the
floor for real-frame agreement, and it is well under a tap's error.

**Completing a court from four corner taps** (§5.3), 8 frames x 3 trials of
random tap error:

| tap error σ | lands within 2 px of the detected court |
|---|---|
| 8 px | 24 / 24 |
| 15 px | 24 / 24 |
| 25 px | 24 / 24 |
| 40 px | 20 / 24 (the misses are caught by §4.7) |

**Cost, reference implementation** (Python + NumPy, host, 1080p): search 0.3 to
3.8 s, refinement about 0.3 s. It tries up to ~470,000 court placements in
batches; the port does the same work in plain loops, and §4.5 removes most of
the per-placement arithmetic.

---

## 4. The detector

Pure Kotlin in `:analysis`, package `com.badmintontracker.analysis.court`, so
both phones run one implementation and it is tested on the host. No OpenCV, as
for the camera tracker. The reference implementation is the specification for
every stage; exact values below are the frozen ones.

### 4.1 Model

Court coordinates in metres, `x` across (0 to 6.1), `y` along (0 to 13.4), the
same frame as `COURT_KEYPOINT_POSITIONS`. Twelve painted segments: six across
(`y` = 0, 0.76, 4.72, 8.68, 12.64, 13.4), four along (`x` = 0, 0.46, 5.64, 6.1),
and the two halves of the centre line (`x` = 3.05, from each baseline to its
short service line).

BWF measures every dimension to the outer edge of a 40 mm line, and a line
belongs to the area it bounds. So the **search** uses the segments above and the
**refinement** fits line *centres*: baselines and sidelines 0.02 m inside the
outer edge, the long service line 0.78 m from the baseline, the short service
line 2.00 m from the net, the singles sideline 0.48 m in. The twelve keypoints
are then projected from the table's own positions, so they mean exactly what
the table says (the outer corner is the outer corner), not "the middle of the
crossing a person would tap", which is 2 cm off.

### 4.2 Input

An `RgbImage(width, height, argb: IntArray)`. Luma is
`0.299 R + 0.587 G + 0.114 B`. Two images are derived:

- **work image:** area-averaged so its long side is 960 (the search runs here);
- **fit image:** the luma at source resolution, area-averaged down to a long
  side of 1920 when the source is larger (a 4K frame as floats would be 33 MB
  for no accuracy that matters at heatmap scale). Keypoints are scaled back.

### 4.3 Line pixels (work image)

1. Hessian ridge response at three scales, σ = 1.0, 1.8, 3.0: Gaussian blur,
   then 3x3 Sobel second derivatives `dxx`, `dyy`, `dxy`. Eigenvalues
   `l1 >= l2`. Bright-line response `(-l2 - |l1|)·σ²` where `l2 < 0`; dark-line
   response `(l1 - |l2|)·σ²` where `l1 > 0`. Both polarities, so a blue line on
   a pale floor is found as well as a white one. Per pixel keep the strongest
   scale and its line-normal direction.
2. Non-maximum suppression across the line (bilinear samples one pixel either
   side along the normal).
3. Contrast normalisation: response divided by (local standard deviation of luma
   over a 41x41 box + 6), kept above 1.2, and raw response above 4. This is what
   finds faint lines on a bright wooden floor.
4. Connected components (8-connected); keep those whose bounding box diagonal is
   at least 3% of the long side. Crowds, text and wood grain fall out here.

### 4.4 Candidate lines and their two directions

1. Oriented Hough: each line pixel votes only for normal angles within ±2.5° of
   its own (11 votes, 0.25° bins over 0 to π, 1 px distance bins). Peaks taken
   greedily, suppressing ±8 bins either way, until 40 lines or a peak under
   `0.025 × long side` votes. Each line keeps the extent of the pixels within
   1.5 px of it (2nd to 98th percentile along it).
2. **Vanishing points.** A court's lines run in two directions, and in the image
   each direction meets at a vanishing point. RANSAC over line pairs: the point
   whose inliers (lines within 1.5° of pointing at it) carry the most votes is
   the first direction; repeat on the rest for the second.
3. **Horizon.** The line through the two vanishing points is the horizon, and a
   court is on the floor, below it. Lines whose extent lies above the horizon
   (ceiling beams, wall edges, balcony rails, which in a sports hall run parallel
   to the court and point at the same vanishing point) are dropped. This was the
   difference between 6 and 8 of 8 club-hall frames.

### 4.5 Search

For the strongest 9 lines of each direction, every pair from one and pair from
the other forms a quadrilateral (rejected unless strictly convex and within two
image sizes of the centre). Each is tried as every rectangle the court model
contains: 15 pairs of across-lines x 6 pairs of along-lines x 4 corner orders
(the other 4 orders are the court's own symmetry and score identically).

A rectangle maps to the unit square by an affine map, so each quadrilateral
needs one homography solve (unit square to quad) and each of its 360 placements
is that matrix times a constant: no 8x8 solve per placement.

A placement survives if all four court corners are in front of the camera, the
court's image area is between 2% and 6000% of the frame, and its net line is at
least 8% of the frame long. It is scored against a distance transform of the
line pixels: sample the model every 0.5 m, weight each sample by its image
length (capped at 40 px), +1 if within 1.5 px of a line pixel, -0.5 if in frame
and not, 0 out of frame. The best 60 are rescored at 0.1 m samples and the best
one is kept. It is then relabelled so model `(0, 0)` is the top-left of the
image: mirror `x` if it projects right of `(6.1, 0)`, then mirror `y` if it
projects below `(0, 13.4)`. That is the table's convention (§2).

### 4.6 Refinement (fit image)

Alternate *measure* and *solve* until the keypoints stop moving:

- **Measure.** Every 0.05 m along the line-centre model (2% trimmed from each
  end), project the point and read a luma profile across the projected line at
  0.5 px steps, over `1.5 × expected width + tolerance + 3` px either side,
  where the expected width is the 40 mm line projected there. Baseline is the
  median of the outer 20% of the profile. The peak must be within
  `tolerance + width` of the prediction and at least 8 grey levels high; the
  line's centre is the midpoint of the two half-maximum crossings (linear
  interpolation), which is immune to a brightness slope across the line.
  Rejected if the crossings touch the window or the width is under 0.3x or over
  2.5x (+2 px) the expected one. Polarity is decided per sample on the first
  pass and fixed to the majority after it.
- **Solve.** Gauss-Newton on the eight homography parameters, minimising each
  observed centre's signed distance to its projected model line, Huber-weighted
  (scale `1.4826 × median |r| + 0.1`, knee at 1.5 scales), ten iterations with
  the observations fixed.
- **Loop.** Tolerance starts at 12 px and halves each round to a floor of 2.5
  px; stop when the in-frame keypoints moved under 0.02 px on a round at the
  floor, or after 8 rounds. Fewer than 30 observations is a failure.

The earlier single-loop version wandered (its window was narrower than a near
line is wide); this one converges in 3 rounds on synthetic courts and to a
residual of 0.3 to 0.7 px on real ones.

### 4.7 Acceptance

A detection is returned only if all of these hold, else "not found" with the
reason (for the log, not the user):

1. the refinement converged: last round's keypoint motion ≤ 0.1 px;
2. residual ≤ 1.5 px, scaled to a 1920 px long side;
3. all twelve keypoints are inside the frame (a point the screen cannot show
   cannot be checked or moved, and a manual marking could not place it either);
4. at least two across-lines and two along-lines each have 40% of their
   in-frame length on line pixels;
5. the twelve points, read back through the app's own `homography()` and
   `maxResidualM()`, fit to 5 cm: the same gate a hand marking meets, and a
   check that `courtPositions()` resolves them exactly as the table does.

### 4.8 From four corners

`fromCorners(image, corners)`: the search of §4.5 with 60 candidate lines and
the 14 strongest per direction, keeping only placements whose four corners are
each within 5% of the work image's long side of the four taps (compared as a
set, any order), best score wins. If none is near, the four taps themselves
seed the refinement with a 30 px starting tolerance. Then §4.6 and §4.7 as
above.

---

## 5. The marking screen

### 5.1 What the coach sees

- **Opening a video with no saved marks:** the frame appears as today, and the
  instruction line reads "Finding the court..." while the detector runs off the
  main thread. A tap before it finishes places a point as today and discards the
  detection when it arrives: the coach's hand wins.
- **Found:** all twelve points appear at once and the court's lines are drawn
  over the frame in the accent colour. The note under the instruction says:
  "Found the court automatically. Check the lines sit on the court's lines; tap
  a point to move it." Continue is enabled.
- **Not found:** "Couldn't find the court. Tap the four corners and the rest
  will be placed for you." The coach taps TL, TR, BR, BL as today. On the fourth,
  §4.8 runs ("Lining the points up with the court..."). Success places all
  twelve (the four corners snapped onto the lines) with "Lined up with the
  court's lines. Check them; tap a point to move it." Failure keeps the four
  taps and continues the manual flow at Net-Left with "Couldn't line the points
  up. Tap the rest."
- **Opening on saved marks:** unchanged: no detection runs, the saved-marks
  note shows.
- **Find court** (a third button beside Undo and Clear) runs the detector on
  demand, for a coach who cleared saved marks or wants the automatic result
  back. It replaces the marking as one step, which Undo reverses.

### 5.2 Checking and moving points

Whenever the marking is complete, however it was made, the court model is drawn
through the twelve points (the homography the analysis will use, projected
back). A wrong mark shows as a line that misses its paint, which is easier to
see than a dot that is slightly off.

On a complete marking a tap on a point (within 22 dp of it on screen, at any
zoom) selects it: it gets a ring, and the instruction reads "Tap the new spot
for Service Near-Left" in that point's colour. The next tap moves it there; a
tap on the same point cancels. Tap-to-move rather than drag: a finger dragging a
point covers it, the frame already pinch-zooms for precision, and every gesture
stays a tap, so nothing competes with pan and zoom.

### 5.3 Undo

Undo reverses the last *edit*: a placed point, a moved point, an applied
detection, or a corner completion (which returns to the four raw taps). The
marking keeps a history of point lists for this. With no history (saved marks
just restored) it drops the last point, as today.

A completion runs only when a *tap* brings the marking to four points, so Undo
back to four and tapping on does not re-run it: Undo is how a coach declines it.

### 5.4 Labels

The detector writes the table's convention (SNL, SNR and CTN on the top-of-frame
half), which the schematic guide beside the frame already shows. The labels are
not changed: the cloud reads them literally.

---

## 6. Platform plumbing

- **`:analysis`:** `RgbImage`, `CourtFinder.find(image)` and
  `CourtFinder.fromCorners(image, corners)`, returning a `CourtFinding`: the
  twelve points in table order (or none), the homography, and the numbers of
  §4.7 for the log.
- **`:shared`:** the marking model gains history, `move`, `selectAt`, and
  `withDetected`; `CourtMarkingSpec` gains the strings of §5 and a
  `courtLines()` helper that returns the model's segments in source pixels for
  the overlay, so both screens draw the same thing. The decision "a tap made it
  four points, so complete from the corners" is a shared function, not two.
- **Android:** the view model gets a `findCourt: suspend (Bitmap) -> CourtFinding`
  and the corner equivalent, run on `Dispatchers.Default`. The bitmap's pixels
  come from `Bitmap.getPixels`.
- **iOS:** the view draws the `CGImage` into an RGBA buffer and hands the
  pointer to a new `BulkInterop.rgbImage(...)`, then calls the finder in a
  detached task.
- **The frame.** Detection runs on exactly the frame on screen, so the marks and
  `marked_at_s` stay consistent.

---

## 6a. Decisions

| decision | chosen | rejected, and why |
|---|---|---|
| method | fit the painted lines to the court model | a learned keypoint regressor: no labelled training set, a model to ship per platform, and regressed keypoints are pixels off where line fitting is sub-pixel; the web app's Roboflow model was removed for accuracy |
| where it runs | shared Kotlin in `:analysis` | per-platform ports (the camera design's reasoning: one implementation, host-testable); OpenCV (a 20+ MB dependency per platform for a few routines) |
| unsure result | say so and fall back to manual, with the four-corner completion | placing a best guess silently: the acceptance numbers have a margin on 81 frames, not a guarantee, and a wrong court poisons every metric |
| moving a point | select, then tap the new spot | dragging: the finger hides the point, and a drag fights pan |
| output labels | the table's convention exactly | "near = near the camera": the cloud reads the keys literally |
| keypoints | projected from the table positions through a line-centre fit | the line crossings themselves: 2 cm off the model the metrics assume |
| partial courts | not accepted (§4.7.3) | extrapolating off-frame keypoints: the screen could not show them to be checked |

---

## 7. Deliberately not in this pass

- **The web app's marking screen** (`badminton-tracker/src/components/CourtSetup.vue`).
  It could call the same algorithm through the Python reference, but that is a
  separate product change.
- **Detection in the cloud.** Marks still come from the phone.
- **Lens distortion.** Ultra-wide (0.5x) recordings bend lines; the fit is a
  homography, like everything downstream.
- **Trying other frames** when the frame on screen fails (a player covering a
  line, a black first frame). The camera design already lets any frame be the
  marking frame; choosing one is a UI change of its own.
- **Courts partly out of frame.** Refused, as above.
- **Untested:** portrait video, 4K sources, a dark or fading-in frame at 0.1 s
  on Android, outdoor courts. The design handles them, but no corpus frame
  covers them.

---

## 8. Verification

1. **Synthetic, every build** (`commonTest`): a Kotlin renderer of the same
   scenes as `tools/court/synth.py` (5 cameras x 4 colour schemes, blur, noise,
   distractor lines, occluders). Keypoints within 0.1 px median and 0.5 px
   worst of the truth; blank, noise-only and random-line images return not
   found.
2. **Corpus, host** (`jvmTest`, runs when `~/shuttl-corpus/court-detect` exists):
   - every one of the 81 labels reproduced (found and accepted exactly on the 54
     courts);
   - a port check, against the reference implementation's keypoints: median
     ≤ 1 px, worst ≤ 3 px per frame, reported per stage when it fails;
   - accuracy, against independent references: `IMG_5892` near-half error
     median ≤ 3 cm; Paris hand marks median ≤ 6 px;
   - four-corner completion with 15 px tap error: every trial within 2 px.
3. **Units:** the marking model (history, move, select, detection, the
   completion trigger), the Android view model (detection applied only to an
   empty marking, first tap wins, completion after the fourth tap), the iOS
   bridging.
4. **On the emulator and the simulator**, `IMG_5892` and a club-hall clip:
   screenshots of the found court, a moved point, a four-corner completion, and
   the time the detector took. Target: under 2 s on the debuggable emulator
   (where the camera tracker ran 10x slower than a phone), well under 1 s on a
   phone.
5. **Not verifiable here:** a real phone's timing (the S23 and an iPhone), and
   club halls beyond the one recorded.
