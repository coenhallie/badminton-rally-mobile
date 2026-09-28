# Design: find the court automatically, so nobody has to tap twelve points by hand

**Date:** 2026-09-27
**Status:** Approved for implementation by Coen on 2026-09-27 ("Lets implement
the feature. Only pause when there are either breaking changes, or other really
important questions. Implement it as accurate as possible, test it too"). Coen
delegated the design calls; a reviewer agent reviewed the approach in his place.
Implemented on 2026-09-28 on `feat/court-auto-detect` (not pushed). What
changed on the way is in §9; what was run, and what was not, is in §10.
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
| 40 px | 20 / 24 (see below: §4.7 does not catch every miss) |

Correction (2026-09-28, Task 4): the original note here said the 40 px misses
"are caught by §4.7". That is not true in general. On a synthetic court
(`cams[2]`, three taps exact, one moved), the Kotlin port *accepted a wrong
court* with the top-left tap 90 px off: the corner-guided search picked a wrong
placement whose corners all lay within the tap radius, refinement settled on the
wrong lines, and every §4.7 check passed (the far corners wrong, the centre-far
point 51 px from the truth). At 70 px off it picked the right court. The
reference implementation accepted a wrong court at both 70 and 90 px, with
corners up to 37 px off. Line support and the model residual cannot tell a
wrong line assignment from the right one. Whether the 4 misses at 40 px above
were refused or accepted was not recorded. What protects the coach is the
screen, not the acceptance rule: the court lines are drawn through all twelve
points, so a wrong court shows as lines that miss the paint, and Undo returns
to the four raw taps (§5.3). Ruling: accepted as a known limit (§9).

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

---

## 9. What shipped differently

Deviations from this design and from `2026-09-27-court-auto-detect-plan.md`.
Source of truth: the SDD ledger,
`.superpowers/sdd/2026-09-27-court-auto-detect-plan/progress.md` (every
`Ruling:` line and every deferred minor recorded there), and the per-task
reports beside it. Each entry names the task and, where the ledger records
one, the ruling.

### The detector port (Tasks 1-5)

- **The work image is never upscaled.** §4.2 says the work image is
  area-averaged to a long side of 960; `longSideSize` caps the long side and
  never grows a frame, where the reference implementation always resizes to
  exactly 960, upscaling smaller frames. A deliberate choice of the port
  (Task 1).
- **Four numeric choices differ from the reference implementation on
  purpose, and are documented in the KDoc as "close to", not "same as",
  OpenCV:** luma is unrounded float BT.601 (OpenCV rounds to `uint8`); the
  Gaussian kernel radius is `ceil(3 σ)` (OpenCV's float path is about 4 σ);
  the distance transform is the exact Euclidean one (the probe's
  `DIST_L2, 3` is a 3x3 chamfer, 0.955 for a unit step); bilinear samples are
  exact (the probe's `cv2.remap` is 1/32 px fixed point). Refinement solves
  with an analytic Jacobian (checked against central differences to 1e-5)
  and Householder QR, where the probe uses forward differences and `lstsq`.
  Effect, measured in Task 5: the intermediate stages do not match the
  probe's exactly (hall_5: 296 quads against the probe's 208; ridge masks
  0.93 to 0.96 Jaccard), but the final keypoints do (median of per-frame
  medians 0.0028 px, worst 0.13 px, §10), and 5 of the 27 no-court frames
  are refused for a different reason than the probe gives (all still
  refused).
- **Area downsampling is exact-overlap averaging (`cv2.INTER_AREA`'s rule),
  not the whole-pixel box rule the plan specified.** Ruling (Task 5 fix,
  `b6092b1`): the box rule broke the pixel-centre mapping at non-integer
  ratios (at 4/3 it shifted the image by 1/3 source px toward the origin, and
  the 1280x720 frames' work images differed from the probe's by up to 108
  grey levels). A second ruling: the ramp test checks OpenCV's exact values
  (per-pixel error at most 1/12 px, zero net shift) instead of the plan's
  1e-4, which exact box means cannot meet. After the fix the cz_3 work image
  is within 1 grey level of the probe's (mask Jaccard 0.396 -> 0.913); every
  corpus number the test prints was unchanged, and 1080p and 4K sources give
  bit-identical images to before (integer ratios).
- **`searchCourtNear` skips the horizon filter** (`lineFamilies(horizon =
  false)`), as the probe's `search_near` does. Ruling: the corner-completion
  numbers in §3 were measured that way; the cost is a possible ceiling-line
  candidate near a tap, bounded by the tap radius.
- **`fromCorners` can accept a wrong court when one tap is 70-90 px off**
  (Task 4, found by scratch runs; the probe does the same, worse). Ruling:
  accepted as a known limit; the overlay shows the fit and Undo returns to
  the raw taps. §3's table note is corrected accordingly. The fallback branch
  (four taps seeding refinement at a 30 px tolerance) is not exercised by any
  test.
- **Line support (§4.7.4) counts the two centre-line halves as along-lines**,
  as the probe does, and reads its visibility from each piece's first point.
- **Two no-court frames are refused by one gate where the probe used two.**
  `foot_2400` and `foot_4000` (football broadcasts with a drawn pitch
  diagram) refine cleanly onto a wrong placement (residual 0.19 and 0.10 px,
  last step under 0.002 px) and are refused only because keypoints fall
  outside the frame (§4.7.3); the probe's refinement failed its gates first.
  It fits §3's stated margin (wrong fits either keep a residual of 2.2 px or
  more *or* put keypoints outside the frame) but is thinner than the probe's
  on these two frames. Recorded as acceptable: the marking frame is from a
  badminton video.
- **The synthetic accuracy test uses 3 camera positions, not §8.1's 5.**
  `CourtFinderTest` renders 3 cameras x 4 floor/line colours with blur,
  noise, distractor lines and occluders; one camera (`cams[1]`) puts two
  keypoints outside 1280x720 and is not scored, so the numbers in §10 are
  over 8 scenes (96 keypoints), plus one portrait scene.
- **The four-corner test uses `cams[2]`, not the plan's `cams[1]`.** Ruling
  (Task 4): `cams[1]`'s near corners lie outside the frame, which §4.7.3
  refuses by design, so no correct implementation could pass the plan's test.
- **The ridge map is built once per call** and shared by the search and the
  line-support check (Task 4); Task 10 made the distance transform shared
  too, which resolved the deferred "line support recomputes the EDT" minor.

### Performance (Task 10, not in the plan)

- Added by ruling after Task 9a measured `find` at 2.1 s on the emulator's
  non-debuggable ART and 10-42 s in the debuggable app. Ten commits
  (`117168c6..3cde6a71`), all inside `analysis/.../court/`: Hough peaks from
  per-row maxima; border handling out of the filters' inner loops; one
  `atan2` per ridge pixel, only where it is read; one shared distance
  transform; a branch-and-bound coarse score (exact top 60, same tie order);
  fine scores bounded by the best so far; tap corners checked before the
  gates in the corner-guided search; blur taps across the row;
  segment-by-segment scoring that skips segments wholly out of frame;
  per-line trigonometry computed once; profile samples without border clamps
  inside the image.
- **Results are bit-identical**: a dump of every corpus frame's reason,
  residual and the raw bits of all nine homography entries (plus `fromCorners`
  with fixed jittered taps, 8 odd-sized frames and intermediate-stage hashes)
  compared equal with `cmp` before and after; the synthetic tests' printed
  lines are identical on JVM and iOS; the corpus test's printed numbers are
  unchanged. Timings are in §10.
- **The cold first call stays slow on Android.** Ruling: the 1.76 s cold call
  on the emulator is accepted, because detection runs in the background under
  "Finding the court..." and the coach can tap meanwhile; a baseline profile
  (ahead-of-time compilation) is a follow-up.

### The marking model (Task 6)

- **History is `List<CourtMarkingStep>` (points and `markedAtSeconds`), not
  `List<List<CourtPoint>>`.** Ruling: Undo must restore the marks' own time.
  Clear and Find court are undoable and can replace restored marks drawn on
  another frame, and legacy marks have no time at all; restoring them with
  the on-screen frame's time would be a guess recorded as a measurement.
- **`withDetected` always stamps `markedAtSeconds = frameSeconds`**, not only
  over an empty marking. Ruling: detection runs on the frame on screen,
  including Find court over existing marks; Undo restores the previous time.
- Every edit (place, move, Clear, a detection) is one history step and clears
  the selection; Clear on an empty marking is not a step.
- `COURT_LINE_SEGMENTS` (a public addition to `:analysis`, by ruling) uses the
  BWF dimension segments, not the line-centre ones, because the keypoints sit
  at the dimension positions.

### The screens (Tasks 7, 8)

- **Find court is on its own full-width row under Undo | Clear**, not
  "beside" them (§5.1). Ruling: at 360 dp (the Galaxy S23's class) a third of
  the row leaves the label 50.7 dp where "Find court" needs 74.3 dp, and
  `ShuttlButton` clips silently. iOS mirrors it.
- **An edit cancels an in-flight finder call** and resets the status. Ruling
  (parity for iOS): an edit is a tap that changes the points, Undo or Clear; a
  tap that only selects is not. Without it, a tap during "Finding the
  court..." left that line in place of "Tap: Top-Right Corner" until the
  finder answered. The final fix wave widened this to any tap (below).
- **New status `FIND_FAILED`, "Couldn't find the court. Your marks are
  unchanged."**, when Find court finds nothing over at least one point.
  Ruling: the `NOT_FOUND` note told the coach to tap corners he had already
  placed. Over no points it is still `NOT_FOUND`.
- **The finder's note wins over the saved-marks note** (`screenNote`).
  Ruling: with the saved-marks note winning, a failed Find court over saved
  marks was silent. In every reachable state this means `FIND_FAILED` wins.
- **The rules for what a finder answer does are shared functions**, not
  duplicated in the Android view model and the Swift view (Task 8 review,
  fix round 1): `afterOpeningFind`, `afterFindCourt` and `afterCorners`
  return a `CourtFinderOutcome(marking, status, edited)`, and a throw reads
  as not found. `isBusy` is a member of the shared `CourtFinderStatus`, by
  ruling. What stays per platform is the plumbing: cancellation, the
  `Task.isCancelled` drop on iOS, refusing Find court while busy.
- The court-line and ring strokes are divided by the zoom, so 2 dp stays 2 dp
  on screen at 6x (Task 7).
- Each answer is logged, tag/category `CourtFinder`, one line with the call
  (`find`, `find on demand`, `fromCorners`), reason, residual and
  milliseconds: logcat on Android, `os.Logger` on iOS.
- iOS reads the frame through a new `BulkInterop.rgbImage` (RGBA rows,
  `bytesPerRow` apart) from `CourtFrame.cgImage`, and runs the finder in a
  detached task. The finder is not cooperatively cancellable on either
  platform: a cancelled call keeps its thread and buffers (about 16 MB at
  1080p) until it finishes, and its answer is dropped.

### Marking-screen fixes from the device run (Task 11, not in the plan)

Added by ruling after Task 9a's screenshots showed these defects.

- **Instructions are in the text colour with a colour swatch.** "Tap: X" and
  "Tap the new spot for X" were drawn in the point's own colour, and yellow
  (Bottom-Left) was unreadable on the light theme (pre-existing for "Tap:").
  Both are now the heading text colour, preceded by a 10 dp/pt circle in the
  point's colour, centred on the first line's capitals (measured on device:
  0.5 px off on both platforms).
- **A complete marking draws small dots**: radius 5 dp/pt (divided by the
  zoom) with a 1.5 dp ring and no label, except the selected point, which
  keeps the full marker and its label. The full labelled markers buried each
  other and the lines the coach is meant to check (TL under SNL, NL under
  SNL/SFL).
- **A tap within 6 dp/pt of the selected point cancels; any other tap moves
  it.** §5.2's rule (a tap within 22 dp cancels) made a move of under 22 dp,
  about 105 source px at phone width, impossible without zooming. Selecting
  still uses 22 dp. Both radii are shared constants,
  `CourtMarkingSpec.SELECT_RADIUS = 22f` and `CANCEL_RADIUS = 6f`, and the
  cancel radius is a required parameter of `tap`. Measured on device: a
  10 dp/pt move lands (9.9-10.25 dp/pt moved); a tap 3 dp/pt away cancels.
- **The court lines are magenta, `CourtMarkingSpec.COURT_LINE_COLOR =
  0xFFFF2D95`**, on both platforms, for the lines and the selection ring, not
  the theme accent (§5.1). Ruling from the screenshot review: the theme green
  vanished on green courts.
- **The guide's first sentence ("Tap each court landmark in the order
  shown.") shows only while points remain** (`guideLines(placedCount)`, shared
  copy); beside a complete marking it contradicted the found note.
- **iOS layout.** The frame is pinned to the top with an aspect-derived,
  capped height (`min(width, height x 0.62 x aspect)`), so it no longer moves
  when the text below it changes height (in 9a it moved between 170 and
  239.7 pt, including under the finger after selecting a point). The text and
  buttons scroll below it; Continue is pinned and always drawn, disabled until
  complete. Undo | Clear | Find court use the app's secondary pill style, like
  Android. The schematic guide is inset so its corner dots are no longer
  clipped. A landmark's name is drawn with non-breaking hyphens so the move
  instruction never breaks at "Bottom-".

### Final fix wave (from the whole-branch review)

Brief: `.superpowers/sdd/2026-09-27-court-auto-detect-plan/final-fix-brief.md`;
report beside it (`final-fix-report.md`).

- **Continue waits for the finder.** Over a complete marking, Find court left
  Continue enabled; its answer could then replace the marks under the options
  step, and the analysis ran on marks the coach never saw. Continue is now
  enabled only for a complete marking and a status whose shared
  `CourtFinderStatus.allowsContinue` holds (false while `FINDING` or
  `ALIGNING`), on both screens (Android `CourtMarkingUiState.canContinue`).
  Continue also cancels any call in flight (Android `onContinue`, iOS the
  button's action), so no answer can land on marks the coach has left. §5.1's
  "Continue is enabled" holds once the finder has answered.
- **Android: process death on the options step.** The step survives in the
  saved-state bundle and the marking does not, so the screen came back on an
  empty marking with step two saved; the opening find's answer, or a twelfth
  tap, then jumped to step two unseen. The saved step is now put back to court
  mapping whenever the marking is missing or incomplete.
- **Any tap while the finder works cancels it**, on both platforms, a tap that
  only selects a point or lands on none included: a late answer would
  otherwise replace the points and drop a selection just made.
- **Stale failure notes end with an edit** (shared
  `CourtFinderStatus.afterEdit(marking)`, applied after a tap that changes the
  points): `FIND_FAILED` ("Your marks are unchanged") ends with any edit,
  `ALIGN_FAILED` ("Tap the rest") once the marking is complete; the others
  stay. Undo and Clear still end every note.
- **Find court that finds the marks already there adds no Undo step**
  (`afterFindCourt`): same points on the frame on screen means the same
  marking; the answer still says `FOUND` and puts a selection down. The same
  points saved with another frame's time are still a step.
- **Android `onTap` has no default radii**: `hitRadius` and `cancelRadius`
  are required, as they are in the shared `tap`.
- **§4.7.5's label check is now made.** The design said acceptance checks
  that `courtPositions()` resolves the found points exactly as the table does;
  the code ran only the residual check. `accept` now also refuses keypoints
  whose `courtPositions()` differs from `COURT_KEYPOINT_POSITIONS`, reason
  "labels resolve differently", as the last check so no frame refused before
  changes its reason. No synthetic scene or corpus frame reaches it: every
  corpus line printed is identical before and after (§10).
- **The portrait scene is asserted found** (`portrait_frame_is_handled`), not
  "found or refused", as §10 reports it.
- **iOS fits the court lines once per marking**, not on every body evaluation
  (a pinch frame is one), like Android's `remember(marking)`.
- **The out-of-memory claim is qualified**: a thrown `OutOfMemoryError` reads
  as not found on the JVM and Android; on Kotlin/Native a failed allocation
  ends the process, and nothing can catch it.
- **The Task 10 pruning has a committed test**
  (`analysis/src/jvmTest/.../SearchPruningTest.kt`): `searchCourt` and
  `searchCourtNear` take an internal `pruning` flag (default on) that turns
  off the early score stop and the corner-guided search's two corner
  shortcuts; on six synthetic scenes and nine tap sets the homography, score
  and counts are bit-identical either way. A deliberately broken bound fails
  it.
- **Pixel conventions, documented and not converted** (ruling). The finder's
  marks use pixel centres: `x = i` is the centre of pixel column `i`, the
  convention of the camera tracker (`Luma.trackerToSource`). A tap uses the
  display edge mapping inherited from the desktop app (`x = displayX x
  videoWidth / displayWidth`, so a tap on the centre of pixel `i` reads
  `i + 0.5`). The two differ by 0.5 source px, about 1 cm at the far baseline
  at 1080p, below what a mark can be measured to, so neither is converted.

### Left open (deferred in the ledger)

- The `fromCorners` KDoc does not state the 70-90 px tap-error limit.
- History is unbounded (12 points a step, at tapping rate).
- The iOS cancel and arrival-guard plumbing has no iOS unit test (it lives in
  `@State`); the rules themselves are shared and tested, including from Swift
  (`allowsContinue`, `afterEdit`). On Android, taps, Undo and Continue during
  an in-flight call are tested; the arrival guards behind the cancel are
  reached by the shared tests only.
- A 4K source's full-resolution luma (33 MB) is built before downsampling.
- The selected marker and its ring are clipped at the frame edge (hall
  Bottom-Right, both platforms). The small dots are not.
- The found and lined-up notes stay after the coach moves a point; they
  remain true. (The two failure notes no longer stay, above.)
- Outside this work: a pre-existing compiler warning,
  `PoseMetricsTest.kt:250` (unnecessary `!!`).

---

## 10. Verification, as run

Numbers below are from the per-task reports in
`.superpowers/sdd/2026-09-27-court-auto-detect-plan/` (`task-4-report.md`,
`task-5-report.md`, `task-9a-report.md`, `task-10-report.md`,
`task-11-report.md`, `task-9b-report.md`) and the ledger, except the gates,
which were run for this record on `c530a424`.

### Gates

Run on 2026-09-28 at `c530a424`, `JAVA_HOME` the Android Studio JBR,
`DEVELOPER_DIR` Xcode. Counts are from the test-result XMLs (timestamped
from this run) and the xcodebuild log, not exit codes. The first Gradle
invocation reported the test tasks up to date, so they were rerun with
`--rerun` on each task.

| gate | result |
|---|---|
| `:analysis:jvmTest` | 298 tests, 0 failures, 0 skipped (includes `CourtCorpusTest`, the corpus being present) |
| `:analysis:iosSimulatorArm64Test` | 286 tests, 0 failures, 0 skipped |
| `:shared:jvmTest` | 842 tests, 0 failures, 0 skipped |
| `:shared:iosSimulatorArm64Test` | 850 tests, 0 failures, 0 skipped |
| `:androidApp:testDebugUnitTest` | 368 tests, 0 failures, 0 skipped |
| `:androidApp:lintDebug` | passed: 0 errors, 22 warnings, 3 hints; none in a file this work added, and the one in a touched file (`AuthGate.kt:594`, `UseKtx` on `Uri.parse`) is on a pre-existing line |
| `ANALYSIS_REQUIRE_CORPUS=1 ... --tests '*CourtCorpusTest*' -i --rerun` | 5 / 5 passed; numbers below, identical to Task 10's |
| iOS `iosAppTests` (iPhone 17 Pro simulator, Debug) | 343 tests, 0 failures, 2 skipped |
| iOS `iosAppUITests` | 7 tests, 0 failures, 1 skipped; `** TEST SUCCEEDED **` |

Rerun after the final fix wave (§9), 2026-09-28, on the tree of its commit,
the same way (`--rerun` on each test task):

| gate | result |
|---|---|
| `:analysis:jvmTest` | 302 tests, 0 failures, 0 skipped (+2 label-resolution tests, +2 in `SearchPruningTest`) |
| `:analysis:iosSimulatorArm64Test` | 288 tests, 0 failures, 0 skipped |
| `:shared:jvmTest` | 848 tests, 0 failures, 0 skipped |
| `:shared:iosSimulatorArm64Test` | 856 tests, 0 failures, 0 skipped |
| `:androidApp:testDebugUnitTest` | 377 tests, 0 failures, 0 skipped |
| `:androidApp:assembleDebug` | built |
| `:androidApp:lintDebug` | passed: 0 errors, 22 warnings, 3 hints (unchanged) |
| `ANALYSIS_REQUIRE_CORPUS=1 ... --tests '*CourtCorpusTest*' -i --rerun` | 5 / 5 passed; all 177 printed lines other than timings identical to the run before the wave |
| iOS `iosAppTests` (iPhone 17 Pro simulator, Debug) | 345 tests, 0 failures, 2 skipped (the same two); `** TEST SUCCEEDED **` |

`iosAppUITests` was not rerun: the wave's only visible change is Continue
disabled while the finder works.

The three skips, all opt-in or account-dependent and none in court code:
`LocalInferenceEngineTests.testAHandheldRecordingPutsTheNearPlayerWhereTheReferenceHasHer`
(needs `-D RUN_HANDHELD_CORPUS_TEST`),
`ThroughputMeasurementTests.testMeasurePerFrameCost` (needs
`-D MEASURE_THROUGHPUT`), and
`VideoMatchDetailsUITests.testAVideoBornMatchOffersTheDetailsEditorWithADescription`
("the cloudia match is not on this account"). The log's one
`unable to find utility "simctl"` line is xcodebuild's diagnostics
collection after the tests, not a test.

### Synthetic accuracy (`CourtFinderTest`, JVM and iOS)

8 scenes (2 cameras fully in frame x 4 floor/line colours, blur, noise,
distractor lines, occluders), 1280x720, 96 keypoints against the renderer's
exact truth: **median 0.034 px, max 0.170 px** (gate < 0.1 / < 0.5 px). The
reference implementation on the same scenes, with its own renderer: median
0.031, max 0.169 px. The centred camera carries a 0.15 px bias in both,
at least partly the renderer's 3x supersampling (0.158 px at 3x, 0.091 px at
9x in the probe). Portrait 720x1280: found, top-left 0.094 px from the truth.
Four tapped corners 10 px off (`cams[2]`): within 0.5 px. JVM and iOS agree
to about 1e-12.

### Corpus test (host JVM), `~/shuttl-corpus/court-detect`

`CourtCorpusTest`, `ANALYSIS_REQUIRE_CORPUS=1`, 81 frames from 12 videos:

| check | gate | Task 5 (first run) | Task 10 (after the performance pass) | this record (2026-09-28, `c530a424`) |
|---|---|---|---|---|
| labels reproduced | all 81 | 81 / 81 | 81 / 81 | **81 / 81**: tune 32/32 courts found, 7/7 rejected; held-out 22/22, 20/20 |
| accepted residual (1920-scaled) | <= 1.5 px | 0.290-0.660 px | same | 0.290-0.660 px |
| port check vs `court_probe.py`, 54 frames | median <= 1 px, max <= 3 px per frame | median of medians 0.0028 px, worst max 0.1265 px (hk1_30) | same | **0.0028 / 0.1265 px** |
| `IMG_5892` near half vs the per-line reference | median <= 3 cm | 2.28 cm median, 3.58 cm p90, 6.60 cm max | same | **2.28 / 3.58 / 6.60 cm** |
| `IMG_5892` keypoints vs the reference marks | none | median 2.74 px, max 9.22 px | same | 2.74 / 9.22 px |
| Paris 2024 vs the hand marks, 60 distances | median <= 6 px | 4.43 px median, 6.77 px p90 | same | **4.43 / 6.77 px** |
| four corners, 15 px tap error, 8 frames x 3 | every trial within 2 px | 24 / 24 | 24 / 24 | **24 / 24**, every trial 0.0000 px from `find` (worst single tap 58.5 px off) |
| 4K (`m5892_2` nearest-upscaled 2x) vs 1080p x 2 + 0.5 | <= 1.5 px | 0.000000 px | same | **0.000000 px** |
| `find` time over 81 frames | none | median 200 ms | median 129 ms, 10.1 s total | median 129 ms, max 230 ms, 10.1 s total |
| `fromCorners` time | none | median 190 ms | median 88 ms | median 88 ms, max 98 ms |

Every gated number is identical across the three runs. The rejection reasons
that differ from the probe's (5 of 27 no-court frames, §9) are the same in
all three.

### Detection time, by where it was measured

`millis` is the finder only, not the frame decode. The table's "before" is
the code before Task 10's performance work, "after" is after it.

| where | build | clip | `find` before -> after | `fromCorners` before -> after |
|---|---|---|---|---|
| host JVM, corpus test, 81 frames | warm | all | median 218 -> 129 ms (4K frame 249 -> 149 ms) | median 223 -> 88 ms |
| host JVM, `Bench.java` | warm | IMG_5892 | 269 -> 164 ms (cold 474 -> 392 ms) | 234 -> 105 ms |
| host JVM, `Bench.java` | warm | hall | 143 -> 83 ms | 162 -> 72 ms |
| Android emulator (Pixel_9a), `app_process`, non-debuggable ART | warm, median of 9 | IMG_5892 | **2146 -> 933 ms** (range 891-1010) | 2179 -> 691 ms |
| same | cold (first call) | IMG_5892 | 3257 -> 1756 ms | 2322 -> 824 ms |
| same | warm | hall | 1419 -> 580 ms | 1687 -> 521 ms |
| Android emulator, the debuggable app, as seen in the UI (log) | debug | IMG_5892 | 27.4 s -> 14.0 s | 41.8 s -> 8.1 s |
| same | debug | hall | 10.1 s -> 6.3 s | 14.3 s -> 5.2 s |
| iOS simulator (iPhone 17 Pro), the app, Release | release | IMG_5892 | 291 ms (Find court on demand 286 ms), before only | 270 ms, before only |
| same | release | hall | 186 ms, before only | 199 ms, before only |
| iOS simulator, Kotlin/Native debug test binary | debug | synthetic 1280x720 | about 2.7 s, before only | - |

The emulator's "before" warm figures for `find` are from the controller's
measurement in Task 9a (2.1 s IMG_5892, 1.4 s hall) and the base runs of
Task 10's interleaved benchmark (3 invocations each, spread about +-8%). No
iOS figure was taken after Task 10. §8.4's target, under 2 s on the
debuggable emulator, was **not met**: the debuggable app still takes 5-14 s.
It is met only by the non-debuggable runtime (0.58-0.93 s warm, 1.76 s
cold), which is the closer model of a release build; the host runs about 7x
faster than the emulator's ART for the same code. Where the rest goes on the
emulator (IMG_5892, warm, after): coarse scoring about 300 ms, ridges about
260 ms, placements and gates about 60 ms, refinement 69 ms.

### Screenshots, emulator and simulator

Android: Pixel_9a emulator, debuggable build. iOS: iPhone 17 Pro simulator,
Release build. Both signed in; each clip imported through the app's own Add
match flow and the marking screen reached from it. Retaken after Task 11's fixes
(`a93bd27a`, iOS again in `c530a424`), downscaled to 1080 px tall. The
`-selected` shots are beyond the plan's three per clip. In every shot the
found court was checked at full resolution: the magenta lines sit on the
paint, and on the hall clip no other sport's line was picked and the net
line meets both post bases.

- `docs/screenshots/2026-09-27-court-detect-android-5892-found.png`: IMG_5892
  (All England, handheld), court found on opening, small dots, found note.
- `docs/screenshots/2026-09-27-court-detect-android-5892-selected.png`:
  Bottom-Left selected, yellow swatch, full marker in a magenta ring.
- `docs/screenshots/2026-09-27-court-detect-android-5892-moved.png`:
  Bottom-Left moved about 10 dp, back to a small dot.
- `docs/screenshots/2026-09-27-court-detect-android-5892-corners.png`: after
  Clear and four corner taps, the court lined up ("Lined up with the court's
  lines...").
- `docs/screenshots/2026-09-27-court-detect-android-hall-found.png`: the
  club-hall clip (`IMG_4795`, wood floor, multi-sport lines, low corner
  camera), court found on opening.
- `docs/screenshots/2026-09-27-court-detect-android-hall-selected.png`:
  Bottom-Right selected, the instruction wrapping to two lines; the ring is
  clipped at the frame edge.
- `docs/screenshots/2026-09-27-court-detect-android-hall-moved.png`:
  Bottom-Right moved about 10 dp.
- `docs/screenshots/2026-09-27-court-detect-android-hall-corners.png`: the
  hall court completed from four corner taps.
- `docs/screenshots/2026-09-27-court-detect-ios-5892-found.png`,
  `-ios-5892-selected.png`, `-ios-5892-moved.png`, `-ios-5892-corners.png`:
  the same four states for IMG_5892 on iOS (a fresh import, entry "IMG_5892
  court"). The frame's top edge measured 116 pt in every state checked
  (found, a two-line move instruction, cleared, three corners, lined up), on
  both clips.
- `docs/screenshots/2026-09-27-court-detect-ios-hall-found.png`,
  `-ios-hall-selected.png`, `-ios-hall-moved.png`, `-ios-hall-corners.png`:
  the same four states for the hall clip on iOS.

The busy states ("Finding the court...", "Lining the points up with the
court...") were seen on both platforms but only kept in scratch; on the
simulator detection takes 0.2-0.3 s. The corner completion converged to
exactly the court `find` found on every device run.

### Not verified

- **No real phone.** Every device number above is from the Pixel_9a emulator
  or the iPhone 17 Pro simulator. The Galaxy S23's and a real iPhone's
  timing, and how the screen feels under a thumb (the 22 dp select and 6 dp
  cancel reach, the dot size), are unmeasured. The emulator is software
  emulated and the simulator runs natively on the Mac, so neither is a phone
  number.
- **No iOS timing after the performance pass** (Task 10 measured the JVM and
  the Android emulator only).
- **Footage the corpus does not cover:** HDR frames through the iOS bridge
  (the IMG_5892 run found the court, but the import went through the photo
  picker and whether the frame the finder saw was still HDR was not checked;
  no test covers a wide-gamut or 16-bit `CGImage`), portrait video (one
  synthetic portrait scene only), real 4K sources (the corpus's 4K frame is a
  nearest 2x upscale of a 1080p one), a source above 1920 px at a non-integer
  ratio (e.g. 2560x1440; the downsample fix is shown by unit test only), a
  dark or fading-in frame at 0.1 s on Android, and outdoor courts.
- **Club halls beyond one.** The hall clip is `IMG_4795`; 15 of the corpus's
  81 frames come from it.
- **Android cold start.** The first call on the emulator's non-debuggable
  runtime is 1.76 s, mostly interpretation and JIT; no baseline profile was
  built or measured.
- **Lens distortion** (ultra-wide 0.5x recordings): the fit is a homography,
  and no corpus frame is from an ultra-wide lens.
