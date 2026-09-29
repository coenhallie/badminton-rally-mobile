# Design: find the far player, and keep both players the right people

Date: 2026-09-29. Status: approved for build (approach B, singles only).
Spans two repos: badminton-tracker (the cloud worker, stages 0 and 1) and this
one (the phone's selection and the corpus scorer, stages 0 and 2).

## 1. The problem, measured

A cloud analysis of the club-hall match "aSASa" (video `d71b0798`, 1920x1080,
603 frames, fixed camera behind one baseline) installs a pose file whose Far
side is empty. The emulator's stored cloud track reads `NEAR 603 603` and
`FAR 603 0`: 603 pose frames on each side, 603 near positions, no far ones.
Heatmap and Skeleton both hide a side with no samples, so the coach sees Near
only. Another cloud match on the same emulator has 158-159 positions on both
sides, so this is the footage, not the install path.

Traced on the same video, off-device (scripts and numbers in section 9):

- **The pose model never finds the far player at the worker's input size.**
  `yolo26m-pose` at `imgsz=960`, conf 0.15 (the worker's settings) detects him
  in 0 of 603 frames. He is about 115 px tall in the 1920 px frame, so about
  57 px at the model's input. Model size does not help at that input:
  large and extra-large at 960 also find him in 0 of 61 sampled frames, and
  extra-large at 1280 in 4 of 61. Full resolution (1920) finds him in 38 of 61.
- **Zooming in on him does.** A 1.5x crop of the far half finds him in 61 of
  61 sampled frames. A window that follows him at about 2x, re-found through
  the half-court crop when lost, keeps him in 467 of 603 frames.
- **The worker's filters are not the bottleneck.** Feeding those detections
  through the worker's own filter chain (its real `court_area_admits`,
  `distinct_people`, `PlayerIdentityTracker`, and a copy of the in-loop
  sitting and top-two logic) sends him in 455 frames. The chain loses 12 to
  the sitting filter (wide lunges) and none to the umpire or the bystander
  behind the far-left corner: the court-area filter keeps both out.
- **Without the worker's filters, the phone's own pick is not safe.** Given
  every detection, the phone's per-frame "most confident person on this side
  who passes the gates" hands Far to the umpire by the right net post in 527
  of 603 frames. He stands about 1.7 m outside the sideline, inside the
  phone's 2 m margin, and outscores a small far player.

## 2. What exists today

### 2.1 The worker's pose pass

- One `pose_model.track(...)` call per batch of 8 frames, BoT-SORT tracking,
  `conf=0.15, iou=0.5, imgsz=960` (badminton-tracker
  `backend/modal_supabase_processor.py:3404`, batch size at `:3091`).
- Per frame: `distinct_people` removes ultralytics' double reports (`:3650`),
  each skeleton becomes a camera-stabilised shadow, then:
  - the court-area filter: box bottom inside the four marked corners grown by
    2% (`:3290`, `:3755`), or, while the camera is followed, the ankle midpoint
    inside it (`court_area_admits`, `:2974`);
  - the sitting filter: box height under 1.2x its width, or hips close to
    knees or ankles in the vertical (`is_sitting_pose`, `:3704`). A wide
    lunge's box trips the first clause;
  - with more than two candidates after 45 frames, the top two by cumulative
    movement in pixels, with a 100 px floor (`:3272`, `:3802`). A far player
    moves roughly a quarter of the pixels a near one does for the same metres;
  - `PlayerIdentityTracker.match_skeletons` (`:3835`): player 0 is the top
    (far) side, player 1 the bottom, and after calibration each may only take
    a skeleton on its own side of the net.
- The pose file carries only the players the identity tracker kept, as
  `RawPerson`s with a derived box and no identity
  (`raw_inference_from_skeleton_frames`, `:217`, rationale at `:237`).

### 2.2 The phone's selection

- `selectPlayers` (`analysis/.../player/PlayerTrack.kt:114`) runs one
  `NearPlayerSelector.select(frame, side)` per frame and side (`:165`).
- `select` (`NearPlayer.kt:167`) keeps, among the people who pass the four
  gates (ankles, side of the net, on court within `OUT_OF_COURT_MARGIN_M =
  2.0`, torso scale), the one with the highest box confidence (`:194`). Each
  frame is decided alone; nothing remembers who Far was a frame ago.
- `CourtOccupancy.addAll` (`CourtOccupancy.kt:52`) credits each sample with
  the time to the next one, capped at `MAX_GAP_S = 0.5` (`:134`). Short gaps
  (a jump, a brief occlusion) are therefore already held at the last position
  on the heatmap.

### 2.3 The local harness

- `backend/scripts/run_phase2_locally.py` (badminton-tracker) runs the
  worker's real `_run_full_yolo_loop` on a local video and writes the pose
  file the worker would upload.
- `CloudLocalRunCorpusTest` (`analysis/src/jvmTest/.../camera/`) reads that
  file through `RawInferenceCodec` and `selectPlayers` and scores Near against
  `IMG_5892`'s reference track.

## 3. Stage 0: measure both players, on three videos

### 3.1 Corpus

| video | what it tests |
|---|---|
| `d71b0798` "aSASa" (new to the corpus, `~/shuttl-corpus/d71b0798-.../`) | club hall, fixed camera, small far player, umpire and bystanders just off the court |
| `743d7fb1` (Paris 2024 broadcast) | far player about as small, seated line judges, umpire's chair, photographers, cuts to other cameras |
| `IMG_5892` (handheld) | camera followed while it pans; a small far player at the net, an umpire in a high chair and a seated line judge |

aSASa's source and marks (with `marked_at_s`) are copied off the emulator
into the corpus layout (`source.mov`, `video.json`).

### 3.2 References

Per video, `players_reference.json`: for each side, per frame (0-based
decode order), the true player's ankle ground point in court metres, or
absent where the oracle could not place them. A side can instead be declared
`empty` (nobody plays there); no corpus video has one today, so the scorer's
false-positive count is kept for such a video and no gate reads it.
`IMG_5892`'s existing `ref_track` stays the yardstick of its own Near gate
(`CloudLocalRunCorpusTest`); its two-player reference comes from the oracle
like the others'.

Built by an oracle much stronger than anything under test (`yolo26x-pose` on
the full frame at 1920 plus 2x crops of each half, the phone's gates, and
identity by a shortest path over the whole video with a court-speed limit).
The oracle shares ideas with the system it scores (crops, gates, a path), so
agreement alone proves little, and the audit is aimed where a wrong reference
would change a gate: montages, with both the oracle's and the system's pick
drawn, of every frame where the two disagree by more than 1 m or where
exactly one of them has somebody, plus 20 evenly spaced frames per side as a
control. Each correction is made by hand and recorded in the file's `audit`
block, with the frame and what was wrong.

Frames the phone rejects as `CAMERA_LOST` (the cut and replay stretches of
`743d7fb1`) describe a view the marks do not, so they carry no reference and
are left out of every denominator; the scorer reports how many were excluded.

### 3.3 The scorer

`TwoPlayerCorpusTest` (JVM, next to `CloudLocalRunCorpusTest`) reads each
video's local-run pose file through `RawInferenceCodec` and `selectPlayers`,
exactly as a phone installs it, and prints per side:

- **coverage**: samples over pose frames, the app's own
  `PlayerTrack.coverage`;
- **right person**: samples within 1.0 m of the reference in the same frame;
- **wrong person**: samples more than 1.0 m from the reference;
- **unknown**: samples in frames where the reference has nobody, counted and
  otherwise ignored, so an oracle miss is never scored as a system error;
- **false positives**: any sample on a side declared `empty`;
- **position error**: median and p95 over right-person samples.

**One frame join, in the scorer only.** The worker numbers frames from 1 and
its timestamps run one frame late (`CloudLocalRunCorpusTest`'s KDoc), while
references count decoded frames from 0, so pose-file frame `k` is reference
frame `k - 1` for every video. Joining by timestamp, or by `k`, would pair
each sample with a neighbouring frame and put one frame of motion into every
error figure.

The baseline (today's worker) is recorded before stage 1 changes anything.
Once stage 1 lands, the test asserts the section 7 gates. It skips loudly
without the corpus, and `ANALYSIS_REQUIRE_CORPUS=1` makes the skip a failure,
as the existing corpus tests do.

`run_phase2_locally.py` gains `--video-id` and a per-video corpus layout
(`<corpus>/<id>/cloud-local/poses.raw`), so one command produces each pose
file. It keeps running on the CPU, which is what it measures timing on.

A **device-style file** per video, for stage 2's trigger: every person the
device's nano graph finds (`tools/models/pose_dump.py`, the deployed
`posen.960.fp16.onnx`), encoded through badminton-tracker's
`raw_inference_codec`, unfiltered, as the device path hands them to
`selectPlayers`.

## 4. Stage 1: the worker finds small players

### 4.1 The zoom pass

A new module, `backend/player_zoom.py`, pure functions plus a small state
object, so its geometry and rules are tested without a GPU. The loop calls it
once per frame, after the full-frame track result and before
`distinct_people`.

Per identity slot (player 0 far, player 1 near), the zoom state holds the
slot's last emitted box in frame pixels (the `_orig` box the loop emits), a
running mean of its height, and how many frames the slot has been empty.

- **Follow.** When the slot's mean height, scaled to the pose model's input
  (`height * 960 / max(frame width, frame height)`), is under
  `ZOOM_TRIGGER_MODEL_PX = 120` (80 as first planned; 120 as built, see
  section 10), and the slot was filled within the last `LOST_FRAMES`: crop a
  window centred on the last box, `2.6 h` wide and `1.8 h` tall (at least
  200 x 160 px), clipped to the frame, and run pose on it at
  `ZOOM_IMGSZ = 640`, about 2x for a 115 px player. Keep the one detection
  whose centre lies within `0.6 h` of the last box's centre and whose ankles
  are confident and on the slot's side of the net; nothing else from the
  window is used.
- **Find.** When the slot has been empty for `LOST_FRAMES = 3` frames (or
  was never filled), search its half of the court instead: the half plus a
  2 m margin, projected from court metres through the marks into the marking
  frame and through the camera into this frame, extended upward by 2.5 m of
  player height at the far edge, run at 1.5x its width (the aSASa far half
  runs at `imgsz=1600`). Keep the most confident detection whose ankles lie
  inside the court lines of that half, or within `ACQUIRE_MARGIN_M = 0.5` of
  them (added as built: strictly inside, a far player serving from behind the
  baseline was not re-found for seconds; the umpire and bystanders on the
  corpus stand a metre and more out), not in the wider search margin: this is
  what keeps the umpire and bystanders from being adopted. After `SEARCH_BACKOFF_S = 1.0` of
  continuous failure, search every fifth frame only, so an empty half (the
  `IMG_5892` far side) does not pay a half-court pass on every frame.
- **Merge.** A kept detection is mapped back to frame pixels. The full-frame
  pass's double reports (`distinct_people`) are removed FIRST
  (`merge_zoom_picks`, found as built): merging first replaced one copy's
  keypoints, the copies stopped matching, and the stale one outranked the far
  player in the top-two movement cut. If it overlaps
  a full-frame detection (IoU over 0.3), it replaces that detection's
  keypoints and box, keeping its BoT-SORT track id: the zoomed keypoints are
  the better measurement. Otherwise it is appended with a zoom-session track
  id (`ZOOM_TRACK_ID_BASE + session`), so cumulative movement and the
  identity tracker's track-id term keep working. Everything downstream (court
  area, sitting, top two, identity, the pose file) is unchanged and still
  decides.
- **The camera.** Follow windows come from emitted boxes, which are already
  frame pixels, so they need no mapping. Every test against the marks does:
  "on the slot's side of the net" and "inside the court lines" map the
  detection's ankles into the marking frame through `frame_to_marking` first,
  as every other decision in the loop does, and the Find region goes the other
  way through `camera_now` (marking frame to this frame). A frame whose camera
  is lost gets no Find search: the marks do not describe that view.
- **Batching.** The full-frame pass stays batched. Zoom calls are per frame,
  because a window depends on the previous frame's result; when both slots
  need one, their crops share one call. A second `YOLO` instance runs them, so
  `predict` never touches the tracker state `track(persist=True)` keeps on the
  first.

### 4.2 Filter changes, adopted only on evidence

Each was to be kept only if the stage 0 scorer showed a gain with no
regression on any video (section 7). As run (section 10): none is adopted.
The first was built and measured and gave +3 right-person samples net with a
Near regression; the other two were not built, because the worker-chain
replay (section 1) lost the far player to neither. The three as designed:

1. **Sitting from the joints.** When hips, knees and ankles are all confident,
   decide sitting from them alone (hips within a fraction of the torso length
   of the knees' height) and ignore the box's aspect ratio, which a racket arm
   and a lunging leg inflate. The aspect-ratio clause stays for skeletons
   whose legs are not visible, which is how a seated judge behind a table
   usually appears.
2. **Movement in metres.** Cumulative movement measured on the ground point
   in court metres, through the marks, with the floor restated in metres.
3. **Court margin in metres.** The court-area polygon grown by a fixed margin
   in court metres instead of 2% in pixels.

## 5. Stage 2: the phone keeps each side's player over time

Built if the stage 0 and 1 measurements show any wrong-person samples under
today's per-frame pick: on either side of a cloud file (after stage 1), or on
the Near side of a device-style file. Device runs only ever keep Near, so a
device-style file's Far side (where the umpire is known to win) is not a
reason to build it. If none show, this stage is recorded as measured and not
needed, and not built.

`selectPlayers` keeps its gates and replaces the per-frame argmax with a
shortest path per side over the whole run (`SidePath.kt`, as built):

- nodes are (frame, person) pairs that pass the side's gates;
- a node earns `REWARD = 5`, less `-ln(box confidence)`, plus
  `OUTSIDE_PER_M = 3` for each metre it stands further outside the half's
  lines than the frame's least-outside candidate. Relative, as built, rather
  than the absolute cost first planned: an absolute cost steep enough to beat
  an umpire detected in every frame also dropped lone candidates the gates
  accept, and a mild one let the umpire's unbroken path out-earn a player who
  drops out now and then. Relative, a lone candidate pays nothing and is kept
  exactly as the per-frame pick kept it, and an umpire beside a player pays
  for his metre on every frame;
- an edge joins two nodes up to `MAX_SKIP_S = 0.5` apart and costs
  `0.5 x frames skipped + 1.0 x metres`; it does not exist beyond
  `MAX_SPEED_MPS = 8.0 x dt + NOISE_M = 1.0`. The allowance was found while
  planning: a far player's ground point moves a metre between frames when a
  foot lifts, while a jump to another person is several metres;
- a path starts free, and picking up again after an earlier piece costs
  `RESTART = 10`, so the path can be several stretches;
- a frame the path skips produces no sample and counts as
  `RejectionReason.NOT_ON_PATH`, appended last so every stored ordinal stays.

**The other player reaching across the net** (`crossedFrom`, found on the
corpus). On `IMG_5892` the near player walks to the net with her ankles
across it while the far player is hidden behind her, so she is the ONLY
person on the far side for a few frames and no path can refuse her. A piece
of one side's track that lasts at most `MAX_CROSSING_S = 1.0`, overlaps no
sample of the other side, and starts or ends within reach of the other
side's track is that player, and is dropped (`NOT_ON_PATH`). The length
bound keeps a genuine player re-found at the net beside the other one.

With one candidate per frame and no implausible jumps the path is exactly
today's selection; the 326 pre-existing `:analysis` JVM tests pass unchanged.
The same function serves device and cloud runs, as today.

## 6. Decisions

| decision | chosen | rejected, and why |
|---|---|---|
| Where to fix detection | Zoom pass in the worker | Bigger model: 0 of 61 at 960 for large and extra-large. Full-frame `imgsz=1920`: 38 of 61 at 4x the pixels, against 61 of 61 for a 1.5x half-court crop at 2.4x. |
| Zoom shape | Follow the player, re-find by half court | Half court every frame: more pixels per frame, and it hands the phone the umpire whenever the worker's filters would let him through (527 of 603 frames given every detection). |
| Who decides identity | The worker's identity tracker, as today; the phone keeps a per-side path as a second line (stage 2) | Phone decides alone, worker sends every candidate (approach C): one implementation, but a larger pose file and the biggest change. Revisit only if the identity tracker is measured going wrong. |
| Gap filling on the phone | Not built | `CourtOccupancy` already holds a position for up to 0.5 s (section 2.2), which covers jumps and brief occlusions. |
| Smoothing positions | Not built | The heatmap's cells are coarser than the 8 cm median frame-to-frame step, and nothing on the phone computes speed from positions. |
| Filter changes | Each behind its own constant, kept on evidence | Changing them on reasoning alone: the court-area filter is what keeps the umpire and bystanders out today. |
| Pose file format | Unchanged | Nothing here needs a new field, so no app ordering constraint on the deploy. |

## 7. Verification

- **Gates, on the stage 0 scorer (local runs of the worker's real loop):**
  - aSASa Far: coverage at least 0.70 (from 0), wrong person 0, median error
    at most 15 cm.
  - aSASa Near and `743d7fb1` Near: coverage and right-person counts no more
    than 1 percentage point below baseline, wrong person no higher.
  - `743d7fb1` Far: coverage above baseline, wrong person no higher.
  - `IMG_5892`: Near still passes §8.6's gates (coverage 0.95, median 8 cm,
    p95 25 cm); Far coverage above baseline, wrong person no higher.
  - Cost, local only: Phase 2 wall time per frame on this machine's CPU at
    most 2.5x baseline on each video. This is not a GPU claim. On the A10G
    the full-frame pass is batched eight frames at a time and the zoom calls
    are one per frame, so per-call overhead weighs more there than here; the
    A10G's number comes from the first deployed run and is part of the
    owner's deploy check.
- **Order.** `IMG_5892` is the canary, run first after the zoom is wired in
  and before anything is tuned for aSASa. Its player measures 96 to 161 px at
  the model's input (median 134, 22 sampled frames), above the 80 px trigger,
  so its existing Near gate (0.974 / 6.8 cm today) checks that the zoom leaves
  a large player alone, while its small far player, whom the camera pans
  away from and back to, exercises Follow and Find under a moving camera.
  `743d7fb1` iterates on `--max-frames` runs; its full length is run for the
  final numbers.
- **Unit tests.** badminton-tracker's `scripts/verify_*.py` convention for
  `player_zoom` (window geometry, clipping, the Find region through a camera,
  the side and inside-the-lines tests through a non-identity camera,
  continuation and acquisition picks, merge and track ids, search backoff) and
  for each adopted filter change. Kotlin tests for stage 2 if built, including
  the equivalence with today's pick.
- **On a device.** Before the deploy, the emulator's aSASa entry gets a track
  from the local run of the new worker (planted with `run-as`, as the
  camera-motion work did), and its Near/Far toggle, Far heatmap and Base panel
  are checked in light and dark. After the owner deploys, aSASa is re-analysed
  on the emulator and the installed track is compared with the local run's.

## 8. Deliberately not in this pass

- Doubles: the identity tracker and the Near/Far panels hold one player per
  side.
- Fine-tuning the pose model on small players: needs labelled data first.
- A larger pose model anywhere in the pipeline: measured as no help at the
  worker's input size.
- Identity in the pose file, or the phone deciding identity alone.
- Re-analysing stored videos: an existing match benefits only when re-analysed.
- Deploying the worker: the owner's step
  (`python3 -m modal deploy backend/modal_supabase_processor.py`).

## 9. The experiments behind section 1

Scripts in `/tmp/asasa` (throwaway; the durable versions are the harness,
the references and the scorer above):

| script | question | result |
|---|---|---|
| `dump.py` + `gates.py` | does the worker's model find the far player at 960? | 0 of 603 frames |
| `exp.py` | model size and crop zoom, 61 sampled frames | m/l/x at 960: 0; x at 1280: 4; m at 1920: 38; m + half crop at 1.0x/1.5x/2.0x: 19/61/61 |
| `full.py` | half crop at 1.5x, all frames, phone gates | 491 of 603 detected and passing, positions all inside the far half |
| `track2.py` | follow window vs half crop, phone's own pick | follow: 467 far player, 0 wrong; half crop: 76 far player, 527 umpire |
| `half_inside.py` | half crop with the pick limited to near the lines | 0 m: 338; 0.5 m: 398; 1.0 m: 454; 0 wrong at each |
| `record.py` + `chain.py` | the worker's own filter chain on today's and the zoomed detections | today: 0 sent; zoomed: 455 sent, 12 lost to the sitting filter, 0 wrong on the phone; Near 602 either way |

## 10. Verification, as run

Every table below is `TwoPlayerCorpusTest` (JVM, `--rerun`) over pose files
from badminton-tracker's `run_phase2_locally.py`, the worker's own loop run
on this machine's CPU. Columns: samples, coverage (samples over pose frames,
camera-lost frames excluded), right person (within 1 m of the reference),
wrong person, unknown (the reference has nobody there), median and p95
position error over right-person samples.

### 10.1 The references

- `IMG_5892`'s oracle Near path against its independent `ref_track` (ORB
  camera, app rules): 2044 common frames, median 8.4 cm, p95 27.1 cm, none
  over 1 m.
- The audit (`tools/models/players_reference_audit.py`) against the zoomed
  runs, every flagged crop viewed:
  - aSASa: no disagreements. Every Far sample off the reference path is the
    far player serving or standing deep behind the baseline, which the
    oracle's per-metre outside cost left out. No corrections.
  - `IMG_5892`: 12 Far disagreements. Frames 368-373 and 1745 are the far
    player with a foot lifted (both picks on him, a metre apart at the far
    baseline): cleared as having no reliable ground point. Frames 1375-1378
    and 1394 are the near player walking to the net with her ankles across
    it: the reference (the far player) is right, the system wrong. Every
    off-path sample viewed is the right person (jumps, lunges, occlusion).

### 10.2 The phone's selection, per-frame pick against the per-side path

| file | side | per-frame pick | per-side path + crossing rule |
|---|---|---|---|
| `IMG_5892` baseline | Far | 1597 right, 5 wrong | 1597 right, 0 wrong |
| `IMG_5892` zoomed | Far | 1617 right, 4 wrong | 1617 right, 0 wrong |

Every other row was unchanged by the path: aSASa Near 601 right, 0 wrong;
`IMG_5892` Near 2047 (baseline) and 2049 (zoomed) right, 0 wrong; device-style
Near on aSASa 589 right, 0 wrong. The five `IMG_5892` frames were the near
player as the ONLY far candidate (the far player hidden behind her, never in
the file), which is what the crossing rule is for; the per-side path alone
did not remove them.
