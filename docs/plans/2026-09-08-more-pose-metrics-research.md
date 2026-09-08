# More measurements from the stored skeleton and shuttle track: what else is honest

Status: research, 2026-09-08. Nothing here is built. Follows
`2026-09-07-pose-metrics-research.md`, whose tiers A, B and D shipped as the
metrics strip, and uses the same corpus, the same deployed graph and the same
resolved homography.

## 1. The question and the short answer

The strip shows nine per-frame numbers under the video. The ask is what else
can be measured for a coach, with the hard constraint that a number the app
shows must be one it can stand behind. So every candidate below was measured
on the corpus before it got a verdict, and "helpful" was weighed against
"honest" rather than the other way round.

Short answer: three additions are honest today, one of them opens a whole
class of shot-by-shot numbers, and a longer list of things coaches ask for
cannot be measured from this data and should not be faked.

| candidate | verdict | rests on | measured |
|---|---|---|---|
| shoulder tilt, hip tilt | **add to the strip** | 2D joints, frontal plane faces the camera | jitter 1.5° median, 8° p90 |
| hip angle | add, same caveat as the knee | 2D joints, sagittal plane | jitter 3.3° median, 12° p90 |
| base position per rally | **build: rally card** | ankles + homography | two pose models agree to 1-2 cm |
| distance covered per rally | build, with the method in the label | ankles + homography | models agree within 4%; method changes it by up to 1.8x |
| top speed per rally, 0.5 s window | build, window in the label | ankles + homography | 4-6 m/s on the corpus, in the elite range |
| contact moments for the near player's own shots | **build: the anchor for shot-by-shot numbers** | shuttle reversals + nearest wrist | within 2 frames of the reversal on 16 of 17 after a distance gate; 4 of 4 checked by eye |
| stance, position, elbow, shoulder tilt at contact | build, on top of contact moments | the strip's metrics, sampled at the contact frame | same accuracy as the strip |
| recovery time to base after own shot | build, on top of contact moments | ankle midpoint vs the rally's median position | 0.5-1.5 s on the corpus, plausible, not validated against a reference |
| racket arm, inferred | suggest, coach confirms | wrist nearest the shuttle at contact, majority over the match | 6 of 7 and 9 of 15 right per shot; correct as a majority on both videos |
| contact height class (overhead / mid / underarm) | build as a coarse class with an "unsure" band | shuttle pixel row vs the player's own shoulder and hip rows | correct on the 4 frames checked; camera tilt biases the middle band |
| stroke count per player, per rally | **not honest** | shuttle reversals | 40-63% of consecutive shots land on the same side |
| split step | not measurable | ankle pixel rows | a 5 cm hop is 7 px; per-frame ankle jitter p90 is 11.5 px |
| jump height, hip drop, contact height in metres | not honest without the net-top mark (tier C, unchanged) | vertical scale | see the earlier doc §5 |
| shuttle speed, smash speed | not measurable | a 2D pixel track of an object in the air | no depth |
| shoulder or hip rotation | not measurable from behind | 3D | the earlier doc §1, tier D |

## 2. What was measured, and how

Four sources, all reproducible from `tools/models/`:

- **The deployed graph**, `posen.960.fp16.onnx`, decoded the way `PoseRunner`
  does, run over frames 100-1259 of 743d7fb1 (rallies 1-3, 1160 frames) and
  frames 0-1299 of 2eabfc01 (rallies 1-3, 52 s), every person kept
  (`pose_dump.py`). The near player is picked offline by
  `NearPlayerSelector`'s rule including the torso scale gate.
- **The cloud's skeletons** in the corpus `results.json` (`skeleton_frames`,
  every person, all 5972 frames of 743d7fb1) as a second pose model, so that
  a per-rally number could be checked for model dependence across all 11
  rallies rather than 3.
- **The tracker's shot detector**, `badminton-tracker/backend/shot_detection.py`,
  run over the cloud's filtered shuttle track with `require_players=False`,
  exactly as the Kotlin port does. It is the oracle the port is checked
  against, so its shot list is the one the app would produce.
- **Frames by eye**, extracted sequentially (a seek lands on the wrong frame
  on this container; see §8), with the stored pose and shuttle drawn on them.

Scripts: `pose_dump.py` (inference), `pose_events_probe.py` (shots, contact
frames, contact class, racket arm), `pose_rally_report.py` (per-rally movement
aggregates, recovery). The events probe runs under the tracker's backend venv.

## 3. Two more angles for the strip

The strip has elbow, arm elevation, knee and lateral trunk lean. Two lines
were left out of the first pass that a coach reads from behind the court:

| angle | median | jitter median | jitter p90 | plane |
|---|---|---|---|---|
| shoulder line tilt (right shoulder above left) | 1.9° | 1.5° | 7.9° | frontal, faces the camera |
| hip line tilt | -1.6° | 1.7° | 8.1° | frontal, faces the camera |
| hip angle (shoulder-hip-knee), each side | 145-149° | 3.3° | 11.7° | sagittal, edge-on |

Shoulder tilt is the honest one. The line between the two shoulders lies in
the plane the camera is looking at, so its image angle is close to the real
angle, and it is the cue coaches use for overhead technique: non-racket
shoulder up and racket shoulder down before contact, then the reverse. The
same for the hips, which shows whether the tilt comes from the trunk or from
the legs. Hip angle is the knee's problem repeated: bending toward or away
from the camera is invisible, so it belongs with the knee tiles and the same
"as seen by the camera" caption.

Two ratios were measured and rejected for the strip:

- Wrist height above the shoulders in torso units (jitter 0.04 median, 0.19
  p90): stable, dimensionless, and tilt-resistant, but it is a number a coach
  has no intuition for. Its use is §5's contact class, not a tile.
- Shoulder width over hip width as a rotation proxy: the apparent widths
  depend on the pose model's shoulder placement more than on rotation.
  Rotation stays in tier D.

## 4. Per-rally movement: what is stable and what is a definition

Everything here is on the court plane from the ankles, the heatmap's footing.
The corpus has 11 rallies on 743d7fb1; the deployed graph covers the first
three and the cloud skeletons all of them, so the first two rallies are the
model-dependence check.

| rally | model | coverage | path, every frame | path, 0.25 s | path, 0.5 s | top speed 0.5 s | wide stances | median position x, y |
|---|---|---|---|---|---|---|---|---|
| 1 | deployed nano | 1.00 | 30.8 m | 22.9 m | 17.4 m | 4.16 m/s | 10 | 3.22, 9.85 |
| 1 | cloud | 0.99 | 29.7 m | 23.1 m | 17.7 m | 4.07 m/s | 8 | 3.21, 9.86 |
| 2 | deployed nano | 1.00 | 17.7 m | 13.6 m | 12.0 m | 4.95 m/s | 4 | 3.35, 10.50 |
| 2 | cloud | 1.00 | 17.3 m | 13.7 m | 11.5 m | 4.69 m/s | 4 | 3.33, 10.59 |

Cloud only, rallies 4-11 with coverage above 0.8: paths of 13-34 m at the
0.25 s resampling, top speeds 2.4-4.3 m/s, median positions within 0.3 m of
x = 3.05 and 0.6 m of y = 10.0. On 2eabfc01 the deployed graph gives 24 m,
38 m and top speeds of 3.0-5.9 m/s for its two fully covered rallies.

What this says:

- **Base position is the most trustworthy number in this document.** The
  median ankle-midpoint position of a rally agrees between two different
  pose models to 1-2 cm, and the noise floor measured on a standing player
  is a position standard deviation of 1.7 cm. Where the player stands, and
  how that drifts between rallies or between games, is a coaching fact the
  app can state without a caveat.
- **Distance covered is a definition, not a measurement.** The same track
  gives 31 m, 23 m or 17 m for rally 1 depending on whether the path is
  summed every frame, every 0.25 s or every 0.5 s. The every-frame sum adds
  jitter (a standing player accrues 0.28 m of "path" per 3 s) and the coarse
  resamplings drop real lunges. The models agree within 4% at every
  definition, so the number is reproducible, but it is only comparable to
  itself. Ship it with the resampling in the label and never against a
  number from another product. For reference, a markerless YOLO-plus-homography
  study of elite players reports 25 m per rally and a court-position error of
  45 mm ([Indicator analysis of footwork, 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC13083079/)),
  and rally 1 here reads 23 m at 0.25 s.
- **Speed needs a window.** Per-frame speed is junk: the corpus' maximum is
  225 m/s, from a broadcast cut the scale gate now rejects, and the p90 of
  per-frame speed on ordinary frames is 8 m/s, which is noise. Over 0.5 s the
  top speeds are 4-6 m/s, which is where the literature puts a lunge or a
  push-off (6-7 m/s peak, [speed_calc.py](../../../badminton-tracker/backend/speed_calc.py)
  caps at 25 km/h for the same reason). Show top speed over 0.5 s, labelled,
  and no instantaneous speed tile on the strip.
- **Counting lunges is not honest to ±1.** A threshold crossing on stance
  width (1.2 m) counts 10 or 8 for the same rally depending on the model. A
  "wide stances" number can appear in a card as a rough indicator; it must
  not be presented as a count of lunges.
- **The cloud's own analytics on this video are wrong** and should not be
  surfaced: `analytics.players[0]` reports 430 m and a 16.9 km/h maximum, on
  the mislabelled homography the 2026-09-07 heatmap report describes and on
  per-frame speeds. Not fixed here; the tracker is read-only for this work.

### Recovery to base

With a contact moment (§5) and a base position, "how long until back at
base" is one subtraction. On rallies 1-2 of 743d7fb1, six of the near
player's seven shots were struck 0.7-2.0 m from the rally's median position
and the player was back within 0.75 m of it 0.5-1.1 s later. On 2eabfc01,
0.9-1.5 s. Plausible, and the inputs are the two most stable quantities
here, but no reference measurement exists to validate it against, so the
card should show it as a time with the base radius stated, not as a score.

### Split step

Not detectable. The near player's ankle row jitters 3 px per frame (11.5 px
at p90) and a split-step hop of 5 cm is about 7 px at the near baseline of a
1080p broadcast. An event-triggered average of the ankle and hip rows around
eight opponent contacts shows no rise before contact, only the descent that
is the player moving toward the camera afterwards. Coaches time the split
step to the opponent's contact within about 100 ms
([Shuttle Lab](https://www.joinshuttlelab.com/learn/split-step-badminton),
[Biomechanical effects of the split-step, 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC11117488/)),
which would need a vertical resolution this camera does not have. A phone
closer to the court might; it is a spike for later, not a feature.

## 5. Contact moments: the anchor that turns per-frame numbers into per-shot numbers

The strip answers "what was the elbow doing in this frame". A coach's
question is "what was the elbow doing when she hit it", and for that the
app needs to know when she hit it. The data to find out is already produced:
the shuttle track and every person's wrists.

**Method.** The shot detector finds shuttle direction reversals. For each
reversal, look ±10 frames for the wrist, of any detected person, that comes
nearest the shuttle. If that wrist belongs to the near player, this is one
of the near player's shots and the frame of nearest approach is the contact
frame.

**Measured**, deployed graph, both videos, 22 near-player shots:

| | 743d7fb1 (7 shots) | 2eabfc01 (15 shots) |
|---|---|---|
| contact frame within 2 frames of the reversal | 7 | 11 |
| wrist-to-shuttle distance at contact | 41-83 px | 47-131 px on 10; 148-258 px on 5 |
| nearest other person's wrist | 81-248 px | 79-964 px |
| wrist chosen | right 6, left 1 | right 9, left 6 |

The five far ones on 2eabfc01 are reversals with no wrist near the shuttle
(a bounce, a net cord, tracking noise): the nearest anything was 148-258 px
away and the other player's wrist up to 960 px. A gate at about 1 m of
court scale at the player's depth (roughly 140 px here) removes them and
leaves the contact frame within 2 frames of the reversal on 16 of the 17
that remain; the one exception sits 10 frames before its reversal with the
wrist 83 px from the shuttle, which is the strided detector registering a
reversal late rather than a wrong contact. Two contacts on 2eabfc01
resolved 2 frames apart from two separate reversals, so contacts closer
than 0.3 s must be merged.

Four contact frames on 743d7fb1 were checked by eye with the stored pose
and shuttle drawn on the sequentially decoded frame: each shows the near
player's right wrist beside the shuttle at the moment of a forehand drive
or lunge at chest height. The elbow angles at those frames (132-149°) are
the near-straight arm of a drive, as expected.

**What this enables**, each at the accuracy the strip already has for its
per-frame value:

- A per-shot table for the near player: time, stance, distance behind the
  service line, elbow and arm angle, shoulder tilt, all sampled at the
  contact frame. The graph already exists; contact markers on it make a
  serve or a smash findable without scrubbing.
- Recovery time to base after each shot (§4).
- Shot tempo: the median gap between detected reversals is 0.9-1.4 s per
  rally on the corpus, where elite rallies run about one stroke per second.
  This is a property of the shuttle track alone and does not depend on the
  near player being found.

**Racket arm, inferred.** The near player's wrist nearest the shuttle is the
right one on 6 of 7 shots on 743d7fb1 and 9 of 15 on 2eabfc01; the player
in both is right-handed. The left cases are frames where both hands are
close together (a two-handed ready position, a backhand reach), so the
inference is right as a majority over a match and wrong often enough per
shot that it must not decide silently. The racket-arm preference the strip
already stores is the right home: pre-fill it from the majority and let the
coach confirm or change it.

**Contact height class.** Comparing the shuttle's pixel row with the same
player's shoulder and hip rows at contact needs no vertical scale, so it
side-steps the tier C problem. On 743d7fb1 the four frames checked by eye
were classed "mid" and were chest-height drives. On 2eabfc01, 12 of 15 came
out "overhead" and were not checked by eye; that camera is steep, and from
a steep camera a shuttle in front of the player at chest height projects
above the shoulder row, so the middle band is biased upward. The honest
shape is three classes with a wide "unsure" band: clearly above the head
(more than one torso length above the shoulders), clearly below the hips,
and everything else unlabelled. That coarse split is close to the
attacking / neutral / defensive division the 2026-08-28 capability review
suggested carries most of the coaching value of shot classification, and it
costs no model.

**Stroke count per player is not honest.** If the reversal list were
complete, shots would alternate near, far, near. They do not: 6 of 15
consecutive pairs on 743d7fb1 and 17 of 27 on 2eabfc01 are the same side
twice (deployed graph); 27 of 60 over all 11 rallies with the cloud
skeletons. The cause is in the shuttle track: only 68 of 1044 visible
shuttle samples on 743d7fb1 lie below the net line in the image, so the
shuttle is mostly lost near the camera and reversals there go undetected,
while the 0.3 s sampling stride the detector uses on dense tracks merges
fast exchanges. So the reversals that are found are good anchors (precision),
and the list is far from complete (recall). A card must never say "you hit
7 shots in this rally"; it can say "7 of your shots were found".

## 6. Not measurable, and why the app should say so

- **Shuttle speed and smash speed.** The shuttle track is 2D pixels of an
  object in the air; without depth there is no speed. The one mobile system
  that does this uses a fixed side-on camera and a known racket-to-camera
  distance ([arXiv 2509.05334](https://arxiv.org/pdf/2509.05334)). Not with
  this camera position.
- **Heights** (contact height in metres, jump height, hip drop): tier C,
  unchanged from the earlier doc. Needs the net-top marks first.
- **Split step** (§4), **rotation** (tier D), **anything about the far
  player**: the pipeline tracks the near player only, for the coverage
  reasons in the phase 2 design.
- **Technique scores.** A number like "smash technique 7/10" would be a
  weighted sum of the angles above, each with the projection caveat. The
  2026-08-28 review declined per-shot form critique for the same reason.

## 7. What it would take

Contact moments need the shuttle track and the wrists of every detected
person at view time, and the skeleton file carries only the near player.
Two ways, and the second is the right one:

1. Read the shuttle track back from the analysis output and re-run the
   nearest-wrist search at view time. Cheap to reach, but the far players'
   wrists are gone from the skeleton file, so the near-versus-other decision
   cannot be made.
2. Decide contacts at analysis time, where every person is in hand, and
   write a small event list (contact frame, wrist, wrist-to-shuttle distance,
   contact class) into the skeleton file as a v3 section. The strip, the
   graph markers, the per-shot table and the rally card all read from that.
   Old files open with no events.

The per-rally card (base position, path at 0.25 s, top speed at 0.5 s, wide
stances, recovery times) is a pure function of the stored track plus the
event list and the rally windows the clips already have.

## 8. What was not asked for but was found

- **Seeking the corpus mp4 by frame index lands on the wrong frame.**
  `cv2.CAP_PROP_POS_FRAMES` on this variable-frame-rate container returned
  frames several positions off, and three correct contact poses looked like
  phantom detections scattered on the court logo until the frames were
  decoded sequentially. Any by-eye check must decode from frame 0.
- **The cloud's per-player distance and speed** in `results.json` rest on
  the mislabelled homography and per-frame speeds (§4). Nothing in the app
  reads them; nothing should.
- **Rally 3 of 743d7fb1 has no near-player frames** under the scale gate:
  the whole rally is a broadcast close-up. The card must show "player not
  found" for such a rally rather than zeros.

## 9. Recommendation

In order, each independently shippable:

1. **Shoulder tilt and hip tilt tiles**, plus hip angle beside the knee, on
   the existing strip and graph. Same code paths as the current metrics.
2. **A per-rally card**: base position drawn on the court schematic, path
   length labelled with its resampling, top speed labelled with its window,
   wide-stance count as an indicator. Base position is the headline.
3. **Contact events** written at analysis time (§7, option 2), then contact
   markers on the graph, a per-shot table, recovery times, the racket-arm
   suggestion, and the three-way contact height class with an "unsure"
   band. Copy must say "N of your shots were found", never a total.
4. Leave stroke counts, split step, shuttle speed and every height where
   they are until the shuttle track sees the near half of the court and the
   net-top marks exist.

Sources: [Indicator analysis of the footwork in the world's top badminton athletes based on markerless motion measurement, 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC13083079/);
[Biomechanical effects of the badminton split-step on forecourt lunging footwork, 2024](https://pmc.ncbi.nlm.nih.gov/articles/PMC11117488/);
[Shuttle Lab, split step timing](https://www.joinshuttlelab.com/learn/split-step-badminton);
[Football-specific validity of TRACAB optical tracking, distance vs speed error](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7064167/);
[Validation of a 2D video-based pose estimation app for gait against markerless capture](https://pmc.ncbi.nlm.nih.gov/articles/PMC13454463/);
[A real-time vision-based system for badminton smash speed estimation on mobile devices](https://arxiv.org/pdf/2509.05334);
`docs/plans/2026-09-07-pose-metrics-research.md`;
`docs/plans/2026-08-28-coaching-capability-review-design.md`;
`tools/models/reports/heatmap-accuracy-2026-09-07.md`.
