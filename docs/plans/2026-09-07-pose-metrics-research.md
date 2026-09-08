# Body measurements from the stored skeleton: what can be measured, and how well

Status: implemented 2026-09-08 on Android; see
docs/screenshots/android-analytics-metrics-*.png. Tier C heights (§5) are not
built.

## 1. The question and the short answer

The skeleton view draws joints over the video and nothing else. The ask is to
measure things from it, frame by frame, and show them under the video: the
angle of the arm, the space between the feet, and so on, so a coach can look
at a serve and read the numbers.

Short answer: yes, a useful strip of per-frame measurements can be added under
the video container from what is already stored, with no new model, no new
analysis run, and no change to the analysis pipeline. What is measurable
splits into three tiers, and the tiers matter more than the list:

| tier | examples | rests on | verdict |
|---|---|---|---|
| A. metric, on the court plane | stance width in metres; distance behind the service line; lateral offset from the centre line | ankles + court homography (both already stored or reachable) | trustworthy; the same footing as the heatmap |
| B. angles in the image plane | elbow, knee, hip, shoulder (arm to torso), trunk lean, arm elevation | 2D joints only | useful and stable when the joint's plane faces the camera; systematically wrong when it does not, and the app cannot tell which |
| C. heights above the court | wrist (contact) height, hip height, airborne | needs a vertical scale the court marks do not give | not honest from today's marks; needs one more mark (the net top) and still never a service-fault call |
| D. not measurable | hand or wrist angle, racket angle, racket head position, shoulder or hip rotation, true 3D angles, which arm is the racket arm | keypoints the model does not have | needs a different model, or a coach's input |

The user's two named examples land in A (feet) and B (arm). "Angle of the
hand" specifically is D: the COCO-17 skeleton ends at the wrist. There is no
hand keypoint, so wrist flexion and racket angle do not exist in the data.

## 2. What exists to measure from

- `SkeletonStore` v1 keeps, per analysed video, the near player's 17 COCO
  joints with a confidence each, at every frame where the player was found,
  with the container timestamp (`androidApp/.../localanalysis/SkeletonStore.kt:34`).
  Nothing about the far player is kept.
- The court marks are persisted on the local entry for device runs too
  (`androidApp/.../AuthGate.kt:644`), so the same homography the heatmap
  used (`analysis/.../geometry/Homography.kt`, resolved by net side) is
  reachable at view time. It is not in the skeleton file; see §7.
- The skeleton panel already runs a per-display-frame loop that resolves the
  pose for the player's current position (`SkeletonPanel.kt:161-162`), and it
  is a `Column` with the video box, the transport bars and a one-line
  summary under it. The strip goes into that column; nothing structural.
- The joints are 2D, from a single camera, at 960 px letterbox. Ankles,
  knees, hips and shoulders are confident in 100% of player frames on both
  corpus videos; elbows 94-98%; wrists 79-95%; the face is mostly absent
  because the near player has their back to the camera (nose confident in
  16-22% of frames). Anything built on the nose or eyes is not available.

## 3. Tier A: metric measurements on the court plane

Both ankles are on the floor, and the homography maps the floor. So
anything built from ankle positions comes out in real metres, exactly as the
heatmap's positions do, with the same ~9 cm ankle precision the phase 2 work
measured and the 0.37 m worst-case court fit.

- **Stance width**: distance between the two ankles' court positions. On
  743d7fb1 the near player's median is 0.82 m, p10 0.37 m, p90 1.40 m;
  during the serve at 122 s (frames 3609-3633, confirmed by eye: feet
  together at the service line, racket low) it reads 0.19-0.43 m, then
  0.8-1.0 m in the step after contact.
- **Distance behind the service line** and **lateral offset from the centre
  line**: the ankle midpoint's court y minus 8.68 m and court x minus 3.05 m.
  Cheap, and the thing a serve coach actually asks about ("how far back do
  you stand").
- **Movement**: displacement of the ankle midpoint between frames gives step
  length and speed, though the heatmap track already carries this and the
  strip should not duplicate it.

Jitter frame to frame (743d7fb1, non-duplicate frames): stance median 7 cm,
p90 29 cm. The p90 is the lunges, where one ankle is genuinely moving fast,
not noise.

Two conditions break tier A silently, both found in the measurement:

- **Feet off the floor.** A jump projects the ankles further away than the
  player is. In 743d7fb1 the frames 1081-1086 show an estimated shoulder
  height of 1.9 m, and 1094-1097 a stance of 3.7-4.0 m. Those frames were a
  broadcast close-up from a different camera (checked by extracting the
  frames), so the marks did not apply at all; a real jump does the same in
  a milder form.
- **The camera is not the marked camera.** Broadcast cuts and replays (the
  corpus) or a phone that was moved (a coach's own recording) leave the
  marks describing a court that is no longer in the frame. Nothing in the
  pipeline notices. 2eabfc01 opens with an animated intro figure that the
  pose model detected and the selector accepted as an on-court near player
  (frame 82, checked by eye).

Both cases are already polluting the heatmap, not only a future metrics
strip. A plausibility gate is cheap: the pixel shoulder width against the
local px-per-metre (a shoulder is 0.35-0.5 m wide) rejects a figure that is
not at the depth the homography says it is. Listed in §8 as an issue outside
this task.

## 4. Tier B: angles in the image plane

An angle between three 2D joints is the true angle projected onto the image
plane. It is exact when the three joints lie in a plane facing the camera,
and increasingly wrong as that plane turns edge-on. This is not a model
accuracy question; it is geometry, and it applies to every 2D pose product.

What it means for the camera position the app's marking flow assumes
(behind or above a baseline, the whole court in view, the near player's
back to the camera):

- **Seen well (frontal plane)**: stance width, lateral trunk lean, arm
  elevation to the side (shoulder abduction), elbow angle when the arm is
  out to the side, knee valgus in a landing.
- **Seen badly (sagittal plane)**: knee flexion in a lunge toward or away
  from the camera, hip flexion, forward trunk lean, elbow angle when the arm
  points at the camera. The lunge at rally start 1 (frames 105-112, stance
  1.4 m) reads knee angles of 157-173°, which is a nearly straight leg; the
  frame shows a low, wide ready stance with both knees clearly bent. The
  knee is bending in the plane the camera cannot see.

The literature agrees on the size of this. On a dataset of 2.2M frames of
exercise against marker-based capture, eleven open 2D estimators had a mean
absolute error on the projected knee flexion angle between 9.3° and about 31°
and on projected elbow flexion between 21.5° and about 36°, with 2D joint
position errors of 72-122 mm ([Scientific Reports 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12589393/)).
Where the joint plane does face the camera, a 2D smartphone estimator agreed
with a multi-camera reference to a mean absolute error of 1.4° (pelvis) to
6.5° (shoulder) ([JMIR mHealth 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7781802/)).
Those two numbers are the honest range for this strip: a few degrees when
the view is right, tens of degrees when it is not.

Measured stability on the corpus (per-frame, deployed nano model at 960,
743d7fb1 / 2eabfc01, non-duplicate consecutive frames):

| angle | available | median value | jitter median | jitter p90 |
|---|---|---|---|---|
| elbow, right | 92% / 79% | 154° / 158° | 3.7° | 19° / 26° |
| elbow, left | 95% / 87% | 143° / 142° | 5.3° | 27° / 44° |
| knee (each) | 100% | 153-161° | 2.3-4.8° | 12-16° |
| shoulder (arm to torso) | 94-98% | 28-42° | 1.7-4.3° | 12-20° |
| hip | 100% | 149-154° | 2.2-4.2° | 11-12° |
| trunk lateral lean | 100% | -5° | 0.8-1.7° | 4-5° |

A centred 5-frame median cuts the p90 jitter by a third to a half, but a
serve flick is about 100 ms, three frames at 30 fps, and a 5-frame median
flattens exactly that. Recommendation: show the raw per-frame value, do not
smooth, and put a small time-series graph under the tiles so the eye does the
smoothing and the flick stays visible (§7).

The elbow is the noisiest joint, and the racket-arm elbow is the one a
serve coach wants. Its p90 jitter is where the wrist confidence drops during
the fast part of the stroke (wrist confident in 79-95% of frames). The strip
must show the angle as absent when either end of a limb is below the 0.5
confidence the overlay already uses (`Skeleton.edgeVisible`), not draw a
number from a guessed wrist.

Which arm holds the racket is unknown (`Skeleton.kt:26-32` says why the
overlay draws both alike). The strip shows both arms, labelled left and
right; a per-video "racket arm" choice a coach sets once is the honest way
to get a single "arm" readout, and cheap.

## 5. Tier C: heights above the court

The serve rule everyone wants a number for: the whole shuttle below 1.15 m at
the instant of contact ([BWF, experimental law 2018](https://bwfbadminton.com/news-single/2017/11/29/experimental-service-law-from-march-2018)).
Wrist height at contact, hip height (how deep the knees are bent, seen from
behind), and "is the player airborne" all need pixels-per-metre in the
vertical direction at the player's depth.

The court marks give the ground plane only. The horizontal scale at any
depth is known from the homography, and the naive move is to use it as the
vertical scale. Measured, that is wrong by the cosine of the camera's tilt:

| video | estimated standing shoulder height | plausible real value | camera |
|---|---|---|---|
| 743d7fb1 | 1.30 m (p10 1.09, p90 1.44) | about 1.6 m for a 1.94 m player | broadcast, moderately high |
| 2eabfc01 | 0.75 m (p10 0.63, p90 1.30) | about 1.4 m | broadcast, steep, high |

A steep camera compresses vertical distances by half. The estimate is very
stable frame to frame (jitter 1-2 cm), which is the dangerous kind of wrong:
it looks like a measurement.

Two honest ways to get a vertical scale:

1. **Mark the net top.** The net is 1.55 m at the posts and 1.524 m at the
   centre, a known vertical reference already in every marked frame. Two
   more marks on the marking screen (net top at each post, above the
   existing net-line marks) give the vertical px-per-metre at the net's
   depth, and the homography's depth scaling carries it along the court.
   Small change to marking, no model, and it can be validated against the
   player's known height.
2. **Decompose the homography** into camera rotation and translation
   assuming square pixels and a centred principal point. This also gives
   the tilt with no extra marks, but rests on an assumption a phone's
   cropped or stabilised video can violate, and needs validation the
   net-top mark does not.

Even with a correct vertical scale, wrist height is not contact height (the
racket head is 30-40 cm from the wrist, in a direction the data does not
have), and 2D joint error is 7-12 cm against a rule that turns on 5 mm. The
published fault-detection system that does judge the rule uses two cameras
at 70 fps mounted at exactly 1.150 m and pointing along the 1.15 m plane, and
reaches 58% on serves within 5 mm of the line ([Sensors 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC10747833/)).
So the app can show "wrist about 0.95 m" as a coaching cue, never a fault
call, and the UI copy has to say which.

Hip height as a knee-bend proxy is the better use of a vertical scale from a
behind-the-court camera: knee flexion itself is invisible from behind (§4),
but a hip that drops 20 cm is not.

## 6. How this was measured

Two host scripts in `tools/models/`, so the numbers stay reproducible:

- `pose_metrics_probe.py`: decodes the corpus video with OpenCV, letterboxes
  and runs `posen.960.fp16.onnx` the way `PoseRunner` does (the decode was
  shown to match the device to 0.00 px median in the heatmap report), picks
  the near player by `NearPlayerSelector`'s rule, and writes every candidate
  metric per frame. 1300 frames of each corpus video (44 s), plus windows
  around three rally starts on 743d7fb1 to find a near-player serve.
- `pose_metrics_report.py`: availability, value distribution, and frame-to-frame
  jitter raw and after a 5-frame median.

Two things the probe exposed about the data itself:

- 9% of consecutive player frames in 743d7fb1 are exact duplicates: the
  variable-frame-rate container repeats frames, so the pose repeats. The
  strip will show two identical frames in a row there; that is the source,
  not a bug, but the jitter figures above exclude those pairs.
- The near player was found in 70% of frames on both videos. The other 30%
  is mostly broadcast cuts, the far player's rallies at the net, and frames
  with the ankles out of the frame. A phone on a tripod behind the court
  will do better than a broadcast.

## 7. Adding it under the video container

Everything below is computable at view time from the stored pose and the
court marks. No new analysis run, no new model, no change to the pipeline.

**Where.** `SkeletonPlayer` is a `Column`: video box, `PlaybackControlBar`,
`FrameStepBar`, summary line. The measurements strip sits directly under the
video box, above the transport bars, so the frame and its numbers are read
together; a metric graph goes under the bars.

**What the strip shows, per frame.** A row of compact tiles, each one label
and one value, absent shown as a dash rather than a stale number:

- Stance (m), and behind service line (m) when the player is on the near
  half. Tier A, from the ankles and the homography.
- Elbow L / Elbow R (°), Knee L / Knee R (°), Arm L / Arm R (° from torso),
  Lean (°). Tier B, image plane, with a one-line caption that the angles
  are as seen by the camera. A tile dims when a joint at either end is
  below 0.5 confidence.
- A "racket arm" choice (left, right, unset), stored per video, that
  promotes one arm's tiles to the front and labels them "Arm" instead of
  "Left arm"; unset shows both.

**Angle arcs on the overlay.** The selected metric's angle drawn as a small
arc at the joint on `SkeletonOverlay`, so the number and the geometry it
came from are on the same pixels. This is the single most useful part for
teaching, and it costs one arc.

**A graph.** One selected metric over ±2 s around the playhead, raw values,
gaps where the joint was absent, a playhead line. Tapping the graph seeks.
This is what makes the serve analysis work: the elbow angle through a serve
is a curve, and a coach comparing two serves compares curves, not a frame.

**Not smoothing** the displayed values (§4). The graph carries the context;
the tile shows the frame.

**Data dependencies.** The homography is needed for tier A. It comes from
the local entry's persisted marks today, which are deleted with the entry
while the skeleton file can outlive it (`SkeletonPanel` already handles the
"video no longer on this phone" case). Better: bump `SkeletonStore` to v2
and write the twelve marks into the header (96 bytes), so the strip depends
on nothing but the file. v1 files still open, with tier A tiles absent.

**Effort and risk.** Pure-Kotlin angle and court-plane functions in
`:analysis` with tests against the corpus numbers above; a `MetricsStrip`
composable; the arc on the overlay; the graph. The risk is not the code, it
is the honesty of the copy: the tiles must say "as seen by the camera", and
the height tiles do not ship until §5's net-top mark exists.

## 8. What was not asked for but was found

- **Camera cuts and airborne frames pollute the heatmap** (§3). The selector
  accepts a figure at any pixel scale as long as its ankle midpoint lands on
  the court. A shoulder-width-to-scale plausibility gate would reject the
  close-ups and the intro animation. Small, in `NearPlayerSelector`, owed.
- **Duplicate frames** in VFR sources double-count a pose in the heatmap's
  time weighting by exactly the duplicated interval. Correct by
  construction (the frame really was shown for that long), noted so nobody
  chases it as jitter.

## 9. Beyond the current model, if the tiers above are not enough

- **Hands and racket.** COCO-WholeBody models (RTMW, 133 keypoints
  including 21 per hand) exist as ONNX and run on phones ([RTMPose project](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose));
  they would give wrist flexion. Racket head position needs a racket
  detector; public datasets exist ([RacketDB](https://www.scitepress.org/Papers/2025/131597/131597.pdf)).
  Both roughly double the per-frame cost on a pipeline that already needs
  more than 2 GB of RAM, and a hand at 960 px on a player 25 m from the
  camera is a few pixels wide. Not recommended until the 2D strip has been
  used.
- **3D angles.** 2D-to-3D lifting models (MotionBERT class) take the stored
  2D sequence and return 3D joints, which would fix the projection problem
  of §4 in principle. The same Scientific Reports study found their 3D
  errors larger than the 2D ones (146-249 mm), and sports-specific
  evaluations show them capturing individual differences in joint angles
  but not fast-motion velocities ([AthleticsPose 2025](https://arxiv.org/html/2507.12905)).
  Worth a measured spike on the corpus before believing it.

## 10. Recommendation

Build the strip in §7 with tiers A and B, the racket-arm choice, the arc,
and the graph, and bump the skeleton file to carry the marks. Add the
net-top marks and the tier C heights as a second step, validated against the
net height and a known player height before they show. Fix the plausibility
gate in §8 on the way, since the same measurement exposed it in the
heatmap. Leave hands, rackets and 3D until a coach has used the 2D strip
and says what is missing.

Sources: [Scientific Reports 2025, monocular pose vs marker capture](https://pmc.ncbi.nlm.nih.gov/articles/PMC12589393/);
[JMIR mHealth 2020, 2D smartphone pose vs multiview](https://pmc.ncbi.nlm.nih.gov/articles/PMC7781802/);
[Sensors 2023, automated service height fault detection](https://pmc.ncbi.nlm.nih.gov/articles/PMC10747833/);
[BWF experimental service law](https://bwfbadminton.com/news-single/2017/11/29/experimental-service-law-from-march-2018);
[Potential and limitations of the short backhand serve, kinematics](https://www.researchgate.net/publication/366804224_Potential_and_limitations_of_short_backhand_serve_in_badminton_Kinematics_analysis);
[RTMPose / RTMW](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose);
[RacketDB](https://www.scitepress.org/Papers/2025/131597/131597.pdf);
[AthleticsPose](https://arxiv.org/html/2507.12905).
