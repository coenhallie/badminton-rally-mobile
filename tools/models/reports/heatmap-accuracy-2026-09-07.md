# Heatmap accuracy, 2026-09-07

What the near-player heatmap's positions actually rest on, measured on the host
against the two corpus videos that have a source file, with the deployed
`yolo26n-pose` at 960. Three defects were found, all in the pure analysis
layer, and all fixed in the same change as this report. The device decode is
checked separately by `pose_parity.py` against `PoseParityDumpTest`.

## 1. The court homography was fitted to mislabelled points

The 12-point court position table (`COURT_KEYPOINT_POSITIONS`, a verbatim port
of `homography.ts`) puts the "near" service line and centre point on the top
half of the frame, 1.98 m short of the net, and the "far" ones on the bottom
half. The marking screen on the web app and on this app labels those points
only "Service Near-Left", "Center-Near", and so on. Whoever marks a court reads
"near" as near the camera at least as often as near the top.

The cloud's own stored marks for the three corpus videos, fitted to the table
as written (`cv2.findHomography`, plain least squares, reprojection residual in
metres):

| video | how "near" was read | mean residual | worst point | corners |
|---|---|---|---|---|
| 0a654e34 | top of frame | 0.16 m | 0.33 m | 0.04 to 0.15 m |
| 2eabfc01 | at the camera | **2.31 m** | 3.56 m | **1.53 to 1.67 m** |
| 743d7fb1 | service lines at the camera, centre points at the top | **1.66 m** | 3.73 m | **1.03 to 1.15 m** |

A least-squares fit does not confine a mislabelled point's error to that point.
Two of three videos had every corner more than a metre off, so every projected
position on them was more than a metre off, from marks that were placed
perfectly well.

**Fix.** `CourtKeypoints.courtPositions()` resolves each service-line pair and
the centre pair by which side of the marked net line the pixels fall on, and
`homography()` fits to that. Labels decide nothing. Marks that agree with the
table produce exactly the table, so parity with the TypeScript reference is
unchanged for correctly labelled input. All three corpus markings now fit to
0.37 m or better at the worst point under the Kotlin DLT (0.33 m under OpenCV).

The cloud has the same defect: `speed_calc.py` fits the same 12 points to the
same table. Not fixed here; `badminton-tracker` is read-only for this work.

**Gate.** `CourtKeypoints.maxResidualM` reports the worst reprojection
residual, and `NearPlayerSelector` refuses a court whose residual exceeds
1.0 m (`MAX_COURT_RESIDUAL_M`). A corner clicked in the wrong order fits to
about 3.5 m; well-placed marks fit to 0.37 m; the gate sits between with room
on both sides. A refused court produces no track with `BAD_COURT` counted,
and the heatmap view says the marks do not fit rather than drawing.

## 2. The hip fallback was two to three metres off, on a fifth of samples

When both ankles were not confident, the selector fell back to the hip
midpoint, flagged `onAnkles = false` on the belief that a hip sits "about a
metre" above the court and projects a little long. Measured against confident
ankles (150 frames each, every 20th, on-court people only, court centimetres,
using the resolved homography):

| candidate ground point | 743d7fb1 near | 743d7fb1 far | 2eabfc01 near | 2eabfc01 far |
|---|---|---|---|---|
| hip midpoint, median | **291 cm** | 371 cm | **173 cm** | 309 cm |
| hip midpoint, p90 | 346 cm | 437 cm | 343 cm | 512 cm |
| box bottom centre, median | 81 cm | 97 cm | 53 cm | 77 cm |
| box bottom centre, p90 | 107 cm | 119 cm | 92 cm | 127 cm |
| hip + 1.3 x torso vector, median | 37 cm | 47 cm | 24 cm | 56 cm |
| hip + 1.3 x torso vector, p90 | 102 cm | 78 cm | 58 cm | 121 cm |

against an ankle precision of about 9 cm (nano vs the largest model, from the
phase 2 measurement). Of 275 and 256 on-court detections, 60 and 58 had no
confident ankles: 22%. So a fifth of the map was being drawn about three
metres from where the player stood, biased away from the camera.

The two better candidates are still an order of magnitude worse than an
ankle, and they were measured on frames where the ankles were visible, which
are exactly not the lunges and net dives where a fallback would be used. The
torso estimate's error was still falling at the largest ratio tried, which
says the ratio is pose-dependent, not a constant.

**Fix.** Ankles only. A frame without confident ankles contributes nothing.
`PlayerSample.onAnkles` and `PlayerTrack.ankleFraction` are gone; coverage is
the whole quality story and the heatmap's summary line reports it. The stored
track format moved from v1 to v2 and v1 files are refused rather than
migrated, because a v1 track was built on both of the defects above.

## 3. A lost player was credited to the last place they were seen

`CourtOccupancy.addAll` credited each sample with the whole gap to the next
sample, and the test named `a_gap_in_the_track_is_not_credited_to_the_last_known_position`
asserted three seconds were. A player who walks off the near court for ten
seconds banked ten seconds at the spot they left from, which is a bright
patch exactly where tracking failed.

**Fix.** The credited gap is capped at `MAX_GAP_S = 0.5`. Below the cap the map
stays independent of sampling rate, which is the property the time weighting
exists for; above it the player was lost, not still.

## 4. The device decode agrees with the host

`PoseParityDumpTest` ran `PoseRunner` over the first 40 frames of 743d7fb1 on
an arm64 emulator (API 36) and dumped every person it found in source pixels.
`pose_parity.py` decoded the same frames with OpenCV, letterboxed them the
way the runner does, ran the same `posen.960.fp16.onnx` through onnxruntime,
and matched people by keypoint centroid:

| | |
|---|---|
| people found, device / host | 80 / 80 |
| frames where the person count differs | 0 |
| keypoint displacement, both sides confident, n=1221 | median 0.00 px, p90 1.00 px, max 2.69 px |
| keypoint confidence delta | median 0.000, max 0.059 |
| box confidence delta | median 0.004, max 0.015 |

So the letterbox, the tensor layout and the row decode on the device are
right, and what remains is the MediaCodec-versus-OpenCV decode difference
already measured at 1.56/255. The heatmap's positions therefore rest on: the
model (unchanged from the cloud's family), a device decode that matches the
host to a pixel, a homography that fits the marks to 0.37 m, and ankle
samples at about 9 cm. The first two are verified here; the third is gated;
the fourth is the phase 2 measurement.

## What was not changed

- The smoothing kernel, cell size and colour ramp: presentation, not position.
- The far player: still not tracked, for the reasons in the phase 2 design.
- Validation at marking time. The gate in §1 refuses a bad court after the
  analysis has run; refusing it on the marking screen, before an hour of
  inference, is the better place and is owed.
