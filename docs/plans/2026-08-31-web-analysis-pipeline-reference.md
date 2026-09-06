# Reference: the web app's Modal analysis pipeline and every metric it produces

**Date:** 2026-08-31
**Status:** Reference, not a proposal. Describes `badminton-tracker` as it stands
at commit `da46dbd`.
**Audience:** whoever implements analytics in this KMP app.

The mobile app already drives the first half of this pipeline: it uploads a
video, writes court keypoints, calls the `process-video` edge function and reads
back `rally_clips`. What it does not do is show a single analytic number. This
document is the map of what exists on the other side of that line: how each
number is produced, where it lives, in what unit and coordinate frame, and which
of them exist only in a browser tab and would have to be ported or moved.

**The headline finding, because it reframes the port.** The Modal worker
persists almost nothing. Its analytics aggregation step, `_compute_analytics`,
returns per-player average speed, max speed, total distance, a position list,
frame/dimension metadata, and then literally `None` for shuttle metrics, court
detection and zone analytics. Everything else the web app shows - rallies as the
user sees them, shot events, per-rally speed, shot placement, recovery, reaction
time, movement efficiency, heatmaps, zone coverage - is computed **in the
browser, at view time, from a per-frame skeleton dump**. That dump is the whole
analysis: a JSON blob holding 17 keypoints per player per frame for the entire
match. Mobile cannot naively load it. §10 lays out what follows from that.

### How this was verified

Everything below was read from source in `badminton-tracker` at `da46dbd`. Where
a claim comes from a design or audit document rather than code, it says so.

Two things in that repo are stale and must not be used as reference:
`README.md` describes a FastAPI-plus-WebSocket architecture with backend modules
(`main.py`, `court_detection.py`, `shuttle_analytics.py`, `detection_smoothing.py`)
that **do not exist**, and `docs/MODAL_DEPLOYMENT_GUIDE.md` documents the older
`modal_inference.py` frame-by-frame service, not the pipeline that actually runs.
Their feature lists (Roboflow court detection, Kalman smoothing, temporal
interpolation, shot type classification) are aspirational. §9 separates them out.

---

## 1. The pipeline end to end

Three parties: the client (Vue web app, or this KMP app), Supabase (Postgres,
Storage, Edge Functions, Realtime), and Modal (GPU workers). There is no other
backend. Modal writes to Supabase directly with the service role.

```
client                supabase edge fn            modal                      supabase
------                ----------------            -----                      --------
upload mp4 ─────────────────────────────────────────────────────────────────> storage: videos/
insert videos row ──────────────────────────────────────────────────────────> videos (status=uploaded)
write keypoints ────────────────────────────────────────────────────────────> videos.manual_court_keypoints

POST process-video ──> validate JWT + owner
                       require status=uploaded
                       require manual_court_keypoints
                       sign videos/ URL (1 h)
                       flip status=processing_phase1
                       HMAC-SHA256 sign body
                       POST  ────────────────────> process_video (web endpoint)
                                                   verify HMAC
                                                   .spawn(_process_video_worker)
                                                   returns 202 immediately
                                                        │
                                                   PHASE 1 (A10G, up to 2 h)
                                                   TrackNet shuttle pass
                                                   detection-only YOLO loop
                                                   rally detection x2
                                                   ffmpeg clip cut + thumbs
                                                        └──────────────────>  storage: results/, clips/, thumbnails/
                                                                              rally_clips rows
                                                                              videos.status=phase1_complete
     <── realtime postgres_changes on videos + processing_logs ───────────────┘

POST start-analytics ─> require status in
                        (phase1_complete, failed_phase2)
                        flip status=processing_phase2
                        HMAC sign
                        POST  ───────────────────> process_analytics
                                                   .spawn(_process_analytics_worker)
                                                        │
                                                   PHASE 2 (A10G, up to 2 h)
                                                   full pose + BoT-SORT loop
                                                   player identity tracking
                                                   speed / distance
                                                   re-cut clips at browser parity
                                                        └──────────────────>  storage: results/ (overwritten)
                                                                              rally_clips (re-cut)
                                                                              videos.player_labels (thumbs)
                                                                              videos.status=completed
```

Two more edge functions exist, both synchronous request/response, neither of
which writes to the database:

- **`recalculate-speeds`** re-runs `speed_calc.calculate_speeds_from_skeleton`
  over the stored `results.json` and returns the numbers to the caller. The web
  app calls this on every results view to drive its speed graph and timeline.
- **`export-pdf`** calls `modal_pdf_export.generate_pdf`, which renders a
  report with a heatmap, a court movement minimap, player statistics and shuttle
  statistics, and returns a signed URL.

And **`duplicate-video`**, which clones a `videos` row with
`source_video_id` set so the same footage can be re-run under the other
`pipeline_variant` for A/B comparison.

### Authentication

Client to edge function: Supabase JWT in `Authorization`. Each function
re-validates the JWT with the service-role client, then re-reads the `videos`
row through a **user-scoped** client so RLS proves ownership.

Edge function to Modal: HMAC-SHA256 of the raw request body with a shared
secret, hex-encoded in `X-Signature` (`supabase/functions/_shared/hmac.ts`,
verified by `supabase_helpers.verify_hmac`). Modal rejects unsigned calls with
401. There is no other authentication on the Modal endpoints.

Modal to Supabase: the service role key, which bypasses RLS entirely.

### Progress reporting

There is no callback and no websocket. The worker writes `videos.progress`
(0-100 as a **percentage**, not a fraction), `videos.current_frame`,
`videos.total_frames` every 2 seconds, and appends rows to `processing_logs`
(`message`, `level`, `category`, `phase`). Both tables are in the
`supabase_realtime` publication (migration `0009`), so clients subscribe with
`postgres_changes`. `rally_clips` is deliberately **not** in the publication:
this app fetches clips on appearance instead.

DELETE events are not delivered on either table. Replica identity is left at the
default, so a DELETE carries only the primary key, RLS cannot be evaluated
against it, and Realtime drops it. Migration `0009` documents this as a
deliberate trade against the WAL cost of `REPLICA IDENTITY FULL`.

### Status machine

`pending` → `uploaded` → `processing_phase1` → `phase1_complete` →
`processing_phase2` → `completed`, with `failed_phase1` / `failed_phase2` as the
terminal error states. `processing` and `failed` remain in the CHECK constraint
for historical rows only and are not emitted by any current code path.

Note the divergence in where the status flip happens. `start-analytics` flips to
`processing_phase2` **before** calling Modal, so a double-click cannot start two
workers; if the function then dies between the flip and the call, the row is
orphaned in `processing_phase2` with no worker and no watchdog. `process-video`
does the same and rolls back to `failed_phase1` if Modal rejects.

---

## 2. Storage and the data contract

### Buckets

Four private buckets, all with identical path-prefix RLS: the first folder
segment of the object key must equal `auth.uid()`.

| Bucket | Key layout | Written by |
|---|---|---|
| `videos` | `{owner}/{video_id}.mp4` (client-chosen) | client upload |
| `results` | `{owner}/{video_id}/results.json` | Modal, both phases |
| `clips` | `{owner}/{video_id}/rally_{n}.mp4` | Modal clip cutter |
| `thumbnails` | `{owner}/{video_id}/rally_{n}.jpg` and `player_{0,1}.jpg` | Modal |

### Tables

`videos` carries the pipeline state plus `results_meta` (a small jsonb summary),
`results_storage_path`, `manual_court_keypoints` (jsonb, 12 points),
`player_labels` (jsonb, user names and thumbnail paths), `pipeline_variant`
(`legacy` or `gb_fusion`), `source_video_id`, and `title`.

`rally_clips` is the one table this app already reads in full. Its
`start_timestamp` / `end_timestamp` / `duration_seconds` describe the **padded**
clip file, not the analytical rally window (§4.4).

`processing_logs` and `rally_annotations` complete the set.

### `videos.results_meta`

Small, cheap to read, and the right thing for a list screen. After Phase 1:
`phase`, `duration`, `fps`, `total_frames`, `has_rally_detection`,
`rally_count`, `tracknet_used`. After Phase 2 it is rewritten with `phase:
"completed"`, `processed_frames`, `player_count: 2`, `has_court_detection:
false` (hardcoded), `has_rally_detection`, `rally_count`.

### `results.json` after Phase 1

```jsonc
{
  "phase": "phase1",
  "pipeline_variant": "legacy" | "gb_fusion",
  "rallies": [ { "id", "start_frame", "end_frame",
                 "start_timestamp", "end_timestamp", "duration_seconds" } ],
  "shuttle_positions": { "<frame>": { "x", "y", "visible" } },   // every frame
  "fps": 30.0,
  "total_frames": 54000,
  "video_metadata": { "duration_seconds", "filename" }
}
```

### `results.json` after Phase 2

Phase 2 downloads the above, adds to it, and overwrites the same object key.
The merged payload keeps Phase 1's keys and adds:

```jsonc
{
  "phase": "completed",
  "video_id", "duration", "fps", "total_frames",
  "skeleton_data":   [ SkeletonFrame, ... ],   // legacy key, what the web app reads
  "skeleton_frames": [ same list by reference ],
  "analytics": { ...the analytics dict, nested... },
  // ...and the same analytics dict spread at the top level:
  "players": [ { "player_id", "avg_speed", "max_speed",
                 "total_distance", "positions", "keypoints_history": [] } ],
  "processed_frames": 54000,
  "video_width": 1920, "video_height": 1080,
  "shuttle": null, "court_detection": null, "player_zone_analytics": null
}
```

A `SkeletonFrame` is one per video frame:

```jsonc
{
  "frame": 1, "timestamp": 0.0333,
  "players": [ { "player_id": 0,
                 "keypoints": [ { "name", "x", "y", "confidence" } x17 ],
                 "center": { "x", "y" },      // ankle midpoint, video pixels
                 "position": { "x", "y" },    // same value
                 "current_speed": 12.4,       // km/h, 0 when filtered out
                 "pose": { "pose_type", "confidence", "body_angles" } } ],
  "badminton_detections": { "frame", "players", "shuttlecocks", "rackets", "other" },
  "shuttle_position": { "x", "y", "source": "tracknet"|"gb_yolo"|"yolo" } | null
}
```

**Size.** This is the constraint that decides the mobile design. Seventeen
keypoints with names and confidences, plus nine body angles, per player per
frame, for a whole match. The worker itself is provisioned at 8 GB RAM
specifically for "skeleton data + JSON serialization for long videos", it writes
frames to a JSONL file on disk during the loop rather than holding them in
memory, and it frees the list before uploading. The web app downloads this whole
file over a signed URL and holds it in a Vue ref. No saved example exists in the
repo to quote an exact figure, but the shape makes tens to hundreds of megabytes
for a full match the realistic range. **Measure one before designing around it.**

---

## 3. Phase 1: shuttle tracking and rally segmentation

`_process_video_worker`, A10G, `timeout=7200`, `memory=8192`, cache volume at
`/cache`, models volume at `/models`.

### 3.1 Video probe and the fps guard

OpenCV `CAP_PROP_FPS` returns 0 for some containers and for variable-frame-rate
sources. `normalize_fps` coerces anything non-finite or `<= 0` to a default of
30 and returns a "was substituted" flag, which is logged as a warning. This
matters because every rally detector bails on `fps <= 0` and the Phase 2 speed
loop divides by it. Duration is computed as `total_frames / fps`.

### 3.2 Shuttle tracking

**TrackNetV3** (`/models/tracknet/TrackNet_best.pt`, optionally
`InpaintNet_best.pt`) runs a full-video pass at batch size 32, sequence length
8, and returns `{frame: {x, y, visible}}`. If the weight is absent the pass is
skipped and the pipeline falls back to YOLO shuttle detections. Reported
coverage is roughly 40-50% of frames visible, against 10-40% for per-frame YOLO.

**Good-Badminton fusion** (`pipeline_variant == "gb_fusion"` only) additionally
runs `yolo11s-ball.pt` over the whole video at `conf=0.18`, rejecting boxes
larger than 0.4% of frame area or with aspect ratio above 4.0, keeping the
highest-confidence survivor per frame. `_merge_shuttle_sources` then fuses:
TrackNet wins wherever it is visible, GB fills the gaps, and each surviving
position is tagged with its `source`. A missing GB weight raises rather than
silently degrading to legacy, so the A/B stays honest.

### 3.3 The detection-only loop

A stripped YOLO pass at batch size 16 with **no pose model, no tracking, no
skeleton accumulation**. It uses `/models/badminton/best.pt` when present and
falls back to stock `yolo11n.pt`. Per frame it emits only `frame`, `timestamp`
(from `CAP_PROP_POS_MSEC`, read before the decode), `badminton_detections` and
`shuttle_position`.

Shuttle selection per frame: TrackNet position first if visible, in-court and
not static, otherwise the highest-confidence YOLO shuttlecock detection that
passes the same gates.

Two spatial filters apply:

- **Court ROI.** The four corner keypoints, expanded 2% about their centroid,
  then for shuttle purposes expanded a further 1.40x horizontally with the top
  edge clamped to `y = 0` (a high clear leaves the court footprint).
- **Static-cluster rejection**, intended to kill fixed false positives such as
  a logo or a white object on the ground. Thresholds scale with resolution and
  frame rate: `SHUTTLE_STATIC_DIST = max(4, 0.013 * long_edge * 30/fps)`,
  `SHUTTLE_MIN_MOVE = max(2, 0.007 * long_edge * 30/fps)`, confirmation at 3
  observations. See §8.2: **this mechanism is dead in this loop.**

### 3.4 Rally detection, run twice, over two different shuttle tracks

This is the part most worth understanding, because the rally list the user
finally sees is not the one stored in `results.json`.

**Track A, "raw":** the per-frame `shuttle_position` on the slim frames above.

**Track B, "filtered":** `_build_shuttle_positions_dict` re-filters TrackNet's
output independently, with a **different** ROI (1.15x uniform, versus track A's
1.40x horizontal) and a static-cluster prune that, unlike track A's, is placed
inside the accepted-position branch and therefore actually works.

**Detector 1, gradient** (`rally_detection.detect_rallies`), over track B.
Subsamples visible positions at a stride of `max(3, 0.3 * fps)` frames, then
declares a shot wherever consecutive velocity vectors have a negative dot
product (any reversal beyond 90 degrees), provided at least one leg exceeds 15
px of displacement (`min_speed_sq = 225`) and at least `max(3, 0.6 * fps)`
frames have passed since the last shot. Shots are then grouped into rallies on a
3.0 s gap, requiring at least 2 shots and at least 0.8 s of duration, with
`0.5 * fps` frames of tail added for the shuttle to land.

**Detector 2, shot-gap** (`rally_detection_shot_gap.detect_rallies_from_shots`
over `shot_detection.detect_all_shots`), run over **both** tracks. This is a
line-by-line Python port of the browser's detector in
`src/utils/shotDetection.ts`, and the docstrings on both sides name each other
as the sync target. It adds, over detector 1:

- outlier rejection, dropping any position more than 400 px from **both**
  neighbours;
- a 2.5 s maximum gap between sampled positions, so velocity is never built
  across inter-rally dead air;
- a shuttle-activity gate: at least 25% of frames inside a candidate rally
  window must carry a shuttle position, which is what rejects replays and
  crowd cutaways;
- a 3.1 s rally gap rather than 3.0 s (the constants are not shared, and the
  clipping audit flags this as harmless but wrong);
- optionally `require_players`, exact browser parity, which is off in Phase 1
  because Phase 1 frames carry no player data at all.

Two rally sets leave Phase 1:

- **`detected_rallies`** = gradient ∪ raw shot-gap, deduplicated by
  `union_rallies` on 50% overlap of the shorter rally. This is what goes into
  `results.json`, and it is what the web app draws as the "backend" timeline.
- **`clip_rallies`** = `refine_rallies(filtered, raw)`: keep the filtered
  track's rally list and splits, widen each boundary toward any overlapping raw
  bound by at most 3.1 s per side, clamp to neighbours. **This** is what gets
  cut into clip files.

A pose-classification fallback detector exists in the port
(`detect_pose_shots`, triggered when fewer than 4 shuttle shots are found) but
the pipeline never populates `pose_classifications`, so it always yields
nothing. Its own docstring says so.

### 3.5 Clip cutting

`pad_rally_windows` widens each rally by `CLIP_PRE_ROLL_S = 2.0` before and
`CLIP_POST_ROLL_S = 1.5` after. The reasoning is written into the source and is
worth repeating: a serve is not a direction reversal, so the first shot the
detector fires on is the *return* of serve and the serve is always outside the
detected window; and the window ends at the last racket contact, so the outcome
is never on screen. Padding may only consume dead air between rallies, never
reaches into a neighbour's detected window, never shrinks a window, and is
clamped to `[0, ffprobe duration]`.

Each clip is a frame-accurate `libx264 -preset ultrafast -crf 23` re-encode with
`-ss`/`-to` as **input** options (both absolute source positions), roughly
10-15 s per clip on A10G, about 50x slower than a stream copy but with exact
boundaries instead of keyframe-aligned ones. A thumbnail is grabbed at
`max(0.5, detected_start - clip_start)` seconds into the clip, so it shows play
rather than the pre-serve pause the pre-roll adds, scaled to 480 px wide.

The `rally_clips` upsert keys on `(video_id, rally_index)` and deliberately
omits `title` from the payload, because the phone can rename a clip and 0004
grants `authenticated` UPDATE on that column; the match title is instead
stamped in a follow-up UPDATE filtered on `title IS NULL`.

---

## 4. Phase 2: pose, identity and per-frame speed

`_process_analytics_worker`. Re-downloads the source video (Phase 1's signed URL
has expired), re-downloads Phase 1's `results.json`, and prefers Phase 1's
`fps`/`total_frames` over a fresh probe.

### 4.1 Models and tracking

- **`yolo26m-pose.pt`** at `imgsz=960`, batch size 8, fp16 on GPU. 17 COCO
  keypoints.
- The badminton detection model again for shuttlecock/racket boxes.
- **BoT-SORT** with a config written per video: `track_high_thresh: 0.3`,
  `track_low_thresh: 0.1`, `new_track_thresh: 0.4`,
  `track_buffer: max(3.0 * fps, 30)` frames, `match_thresh: 0.9`,
  `fuse_score: true`, `gmc_method: sparseOptFlow`, `with_reid: false`.
- Phase 1's filtered `shuttle_positions` are passed in as the shuttle track;
  TrackNet is not re-run.

### 4.2 Player identity

`PlayerIdentityTracker` maps raw tracks to a stable `player_id` of 0 (top of
frame, far side) or 1 (bottom, near side). It rests on an invariant confirmed
with the project owner and recorded in the metric audit:

> **Fixed-sides invariant.** Within any uploaded video, each player stays on one
> side of the net for its entire duration. A video is always exactly one game.

Under that invariant court side is ground truth, not a hint, and the tracker was
rewritten (2026-07-25) to treat it as a **hard constraint**: a skeleton may only
be assigned to the player who owns its side of the net, so the two candidate
sets are disjoint and an identity swap is structurally impossible rather than
something to detect and repair. 145 lines of swap detection and majority-vote
smoothing were deleted in that change.

Mechanics that matter if this is ever ported or debugged:

- The net line comes from the `net_left` / `net_right` manual keypoints, and
  side is decided by the net's y **interpolated at that x**, not by a pixel
  midline. `valid_net_line` rejects degenerate endpoints (both at origin,
  insufficient horizontal separation, out of frame) and falls back to the pixel
  midline **with a warning**, because a zero-length net line silently classifies
  every point in the frame as "bottom" and switches identity off entirely.
- `NET_BAND_PX = max(8, 0.01 * long_edge)`, about 19 px at 1080p, is a
  hysteresis band where the hard constraint relaxes so a lunging front foot
  crossing the net line does not cost a frame.
- Calibration runs over the first 15 frames, refines the midline **before**
  deriving sides from it, and refuses to complete with both players on one
  side, extending up to `CALIBRATION_MAX_FRAMES = 300` (10 s at 30 fps) before
  dropping the net line and splitting by relative position.
- Within a side, a composite cost breaks ties: distance 1.0, velocity-predicted
  distance 0.6, court side 0.8 (now only discriminating inside the net band),
  bbox area 0.2, YOLO track-id continuity 0.15, previous-frame stickiness 0.5.
  Velocity is an EMA with alpha 0.3, clamped to `max(40, 0.035 * long_edge)`
  px/frame.
- When no disjoint pair exists, the better-supported player is placed and the
  other is **left unassigned**. A missing frame costs a longer `dt`; a wrong
  frame corrupts both players' statistics.
- Two health counters are logged and kept deliberately separate:
  `frames_unsplittable` (two skeletons, not splittable one per side - the
  constraint's own failure mode, warned above 5%) and `frames_single_skeleton`
  (only one player detected at all - normal on real footage).

A `TrackerMetricsAccumulator` runs alongside, recording unsupervised
tracking-quality diagnostics: id switches per minute, one- and two-player
coverage, unique track ids, teleport events, court-side flips, untracked
detection percentage, per-track median step and keypoint jitter. It writes a
summary JSON and a per-frame JSONL to `/cache/tracker_metrics/` and echoes a
single-line record to stdout. **None of this reaches Supabase**; it exists to be
scraped from Modal logs.

### 4.3 Player position, speed and distance

Position is the **ankle midpoint** (`(left_ankle + right_ankle) / 2`), falling
back to the hip midpoint. Ankles put the position on the court plane, which is
what makes the homography correct.

The homography is `cv2.getPerspectiveTransform` over the four court corners onto
a 6.1 x 13.4 m rectangle. With no keypoints the loop falls back to a crude
scalar, `13.4 / (long_edge * 0.8)` meters per pixel, which is a rough guess and
should be treated as such.

Per frame, per player, the filter chain is:

1. **Pixel-jump gate.** Reject if displacement exceeds
   `max_frame_jump_pixels = max(80, 0.07 * long_edge * 30/fps)`. This is the
   coarse ID-swap detector, and it scales with both resolution and frame rate.
2. Project both endpoints through the homography, take the metric distance,
   divide by `dt = frames_elapsed / fps`.
3. **Hard speed cap.** Reject above `MAX_REALISTIC_SPEED_KMH = 25.0`.
4. **Running-median outlier filter.** Over a window of 5 accepted samples,
   reject anything more than 3.0x the running median, provided that median is
   itself above 2.0 km/h.
5. Only if valid: add the metric distance to the player's total, append the
   speed to their sample list, and advance the tracking position.

`speed_calc.py` is the single source of truth for these constants and the Phase
2 loop imports them, so a processing run and a later `recalculate-speeds` cannot
disagree. They used to, and the metric audit documents the divergence in detail.
There is deliberately **no** meters-per-frame gate: `speed_kmh == (d / frames) *
fps * 3.6`, so a per-frame distance limit is a per-second speed limit divided by
fps, and the old `0.25 m/frame` constant silently became binding at 25 fps and
inert at 60 fps.

### 4.4 Aggregation, and how little of it there is

`_compute_analytics` takes the accumulated positions, distances and speeds and
applies a **second, separate** filtering pass to the per-player speed samples:

1. hard filter at 25 km/h;
2. IQR outlier removal, upper bound `min(Q3 + 1.5*IQR, 20 km/h)`, applied only
   with 5 or more samples;
3. discard the top 5%;
4. clamp the resulting average to 15 km/h and the max to 25 km/h.

It returns exactly this and nothing else:

```python
{ "players": [ {player_id, avg_speed, max_speed, total_distance,
                positions, keypoints_history: []} ],
  "processed_frames": int, "video_width": int, "video_height": int,
  "shuttle": None, "court_detection": None, "player_zone_analytics": None }
```

The three `None`s are not a bug or a regression; the comment says the
pre-refactor worker also produced `None` there. There is no server-side shuttle
speed, no shot type classification, no court detection, no zone analytics.

### 4.5 Player thumbnails, and the clip re-cut

Best-effort and non-blocking: `_pick_best_two_player_frame` searches the first
10 s for a frame with two players separated by at least 25% of the video height,
crops each, uploads to `thumbnails/{owner}/{video}/player_{0,1}.jpg`, and merges
the paths into `videos.player_labels` **without clobbering** user-set names.

Then Phase 2 re-cuts every rally clip. It runs `detect_rallies_from_shots` over
the full skeleton stream with `require_players=True`, which is byte-for-byte the
rally set the browser computes in-page, upserts the clips in place, and calls
`delete_stale_rally_clips` to remove any with `rally_index` above the new count.
The clipping audit verified this parity claim holds. See §8.5 for what it does
to annotations.

---

## 5. The metric catalogue

Every user-visible number, and where it is actually produced. This is the table
to read before deciding what mobile does.

### 5.1 Produced by Modal and persisted (mobile can just read these)

| Metric | Where | Unit / frame | Notes |
|---|---|---|---|
| Rally list (`rallies`) | Phase 1, `results.json` | seconds and frame indices | gradient ∪ raw shot-gap union, **not** what the web UI draws by default |
| Rally clips + bounds | Phase 1, re-cut Phase 2, `rally_clips` | seconds | **padded** bounds, describes the file |
| Clip thumbnails | Phase 1 | jpeg 480 px | grabbed at first detected shot |
| Shuttle positions per frame | Phase 1, `results.json` | video pixels | every frame, `visible` flag |
| Per-player total distance | Phase 2, `results.json` `players[]` | meters | unsmoothed integral, see §8.1. **The web UI does not show this number**, see §8.10 |
| Per-player avg speed | Phase 2 | km/h | after 4 filters plus IQR plus top-5% trim, clamped to 15. **Not the number the web UI shows**, see §8.10 |
| Per-player max speed | Phase 2 | km/h | clamped to 25. Same caveat |
| Per-player position track | Phase 2 | video pixels, per frame | ankle midpoint |
| Per-frame `current_speed` | Phase 2, inside `skeleton_data` | km/h, 0 when filtered | ~50% are 0 by design |
| 17 keypoints per player per frame | Phase 2 | video pixels + confidence | the bulk of the payload |
| Nine body angles per player per frame | Phase 2 | degrees, plus `arm_raise` in pixels | elbows, shoulders, knees, hips, torso lean |
| Heuristic pose type per player per frame | Phase 2 | enum | see 5.4 |
| Player identity thumbnails | Phase 2, `videos.player_labels` | jpeg | |
| Duration, fps, frame counts | both, `results_meta` | | cheap to read without the blob |

### 5.2 Computed in the browser at view time (mobile must port or move)

All from `skeleton_data` plus the manual court keypoints. All in
`src/composables/useAdvancedAnalytics.ts` unless noted.

| Metric | Method | Unit / frame |
|---|---|---|
| **Client rallies** | `detectAllShots` then gap grouping. This is the timeline the UI presents, and Phase 2's clip re-cut matches it exactly | seconds |
| **Shot events** | shuttle direction reversal, with outlier rejection and stride subsampling | frame + timestamp + nearest player |
| Rally length distribution | bins 1-3 / 4-6 / 7-10 / 11-15 / 16-20 / 21+ by shot count, plus mean shots and mean duration | counts, seconds |
| **Per-rally speed stats** | mean and max over non-zero `current_speed` in the rally's frame range; distance re-integrated as `Σ (speed/3.6) * dt`; flagged `reliable` only when at least 30% of a player's frames carry a non-zero speed | km/h, meters |
| **Shot placement heatmap** | shuttle position at each shot, through the homography, binned into a 6 x 8 court grid (about 1.0 x 1.675 m per cell), one grid per shot type plus an `all` grid | court meters |
| **Recovery analysis** | after each shot, scan 2 s for 3 consecutive frames of sub-noise-floor movement; noise floor is `max(2, 2 x median inter-frame displacement)` per player. Quality is 60% time score (thresholds 0.8 / 1.2 / 1.8 s) and 40% position score (1.0 / 2.0 / 3.0 m from the player's half-court centre) | seconds, meters |
| **Reaction time** | for each pair of consecutive shots by different players, the delay until the responder displaces more than `max(5, 0.025 x mean inter-player distance)` px; anticipatory movement filtered by checking 5 frames before the opponent's shot; accepted only in 50-1500 ms | milliseconds |
| **Movement efficiency** | over 30-frame segments, `displacement / path length`, averaged, scaled to 0-100 | unitless score |
| Zone coverage | `useZoneAnalytics.ts`: **video-pixel** thirds, `ny < 0.33` front / `< 0.67` mid / back, `nx` likewise left / centre / right, as percentages | percent, video-normalised |
| Position heatmap | `useHeatmap.ts`: 100 x 100 grid over normalised video pixels, Gaussian splat radius 2, normalised to 0-255, per player and combined | video-normalised |
| Shot movement segments | `useShotSegments.ts`: between consecutive shots, the responding player's max and mean speed, speed zone, and a speed profile array | km/h |
| Peak body mechanics per segment | `useShotSegments.ts`: peak leg stretch (ankle-to-ankle through the homography), deepest knee flex, max absolute torso lean | meters, degrees |
| Speed zone classification | `speedZones.ts` over `SPEED_ZONE_THRESHOLDS` | see 5.3 |

### 5.3 Speed zones

Defined in `src/types/analysis.ts` in **m/s**, while every speed the pipeline
produces is in **km/h**. `getSpeedZone` divides by 3.6 before comparing. This is
the single most likely unit bug in a port.

| Zone | m/s | km/h equivalent |
|---|---|---|
| standing | 0.0 - 0.5 | 0 - 1.8 |
| walking | 0.5 - 1.5 | 1.8 - 5.4 |
| jogging | 1.5 - 3.5 | 5.4 - 12.6 |
| running | 3.5 - 5.5 | 12.6 - 19.8 |
| sprinting | 5.5 - 7.5 | 19.8 - 27.0 |
| explosive | 7.5+ | 27.0+ |

Note that `explosive` and most of `sprinting` are unreachable: the pipeline caps
player speed at 25 km/h and the aggregate average at 15 km/h.

### 5.4 Pose classification

A hand-written rule cascade in `classify_pose`, evaluated per player per frame
from the keypoints, with fixed confidences:

| Pose | Condition | Confidence |
|---|---|---|
| smash | wrist above head, `arm_raise > 30 px`, `torso_lean < -5°` | 0.85 |
| overhead | wrist above head | 0.80 |
| serving | wrist above shoulder, `abs(torso_lean) < 20°`, arm not wide | 0.75 |
| lunge | knee angle difference above 25° and a knee below 120° | 0.80 |
| forehand / backhand | a wrist more than 80 px lateral from the nose, not above shoulder; side chosen by which wrist is further out | 0.70 |
| ready | both knees below 150°, difference under 20°, lean under 25° | 0.75 |
| recovery | `15° < abs(torso_lean) < 35°` without a deep knee bend | 0.60 |
| standing | both knees above 155°, lean under 15° | 0.70 |

The 80 px lateral threshold is an absolute pixel constant with no resolution
scaling, so this classifier behaves differently at 720p and 4K. The web UI draws
these as a coloured overlay; nothing aggregates them into a statistic.

Separately, `TrainedPoseClass` (`backhand-general`, `defense`, `lift`,
`offense`, `serve`, `smash`) and the whole `pose_classifications` frame field
exist in the type layer for a custom trained model that the pipeline never runs.
Always null. `useAdvancedAnalytics` says so in capitals.

---

## 6. Units and coordinate frames

The one table worth keeping open during a port.

| Quantity | Unit | Frame | Produced by |
|---|---|---|---|
| `player.center`, `player.position` | pixels | video pixels, origin top-left | Phase 2 |
| `keypoints[].x/y` | pixels | video pixels | Phase 2 |
| `shuttle_position` | pixels | video pixels | Phase 1 |
| `manual_court_keypoints` | pixels | video pixels | client |
| `current_speed` | **km/h** | scalar | Phase 2 |
| `players[].avg_speed`, `max_speed` | **km/h** | scalar | Phase 2 |
| `players[].total_distance` | meters | court plane | Phase 2 |
| `SPEED_ZONE_THRESHOLDS` | **m/s** | scalar | client constant |
| Homography output | meters | court plane, `(0,0)` at `top_left`, x across the 6.1 m width, y along the 13.4 m length | both |
| Shot placement grid | court meters | 6 cols x 8 rows | client |
| Zone coverage | percent | **video-normalised, not court** | client |
| Heatmap | 0-255 | video-normalised 100 x 100 | client |
| Rally timestamps in `results.json` | seconds | source PTS (shot-gap) or `frame/fps` (gradient); see §8.4 | Phase 1 |
| `rally_clips` timestamps | seconds | source timeline, **padded** | Phase 1 / 2 |
| `videos.progress` | **percent 0-100** | | both workers |
| `rally_clips.rally_index` | 1-based | | Phase 1 / 2 |
| Frame numbering in `skeleton_data` | **1-based** | | both loops |
| TrackNet frame indices | **0-based** | | TrackNet; see §8.3 |
| Body angles | degrees | | Phase 2 |
| `arm_raise` | **pixels**, not degrees, despite living in the angles dict | video pixels | Phase 2 |

Court geometry constants, consistent across `speed_calc.py` and
`types/analysis.ts`: length 13.4 m, doubles width 6.1 m, singles width 5.18 m,
short service line 1.98 m from the net, net height 1.524 m centre / 1.55 m
posts. The homography maps to the **doubles** rectangle.

The 12 manual keypoints, in the order `speed_calc` expects: `top_left`,
`top_right`, `bottom_right`, `bottom_left`, `net_left`, `net_right`,
`service_line_near_left`, `service_line_near_right`, `service_line_far_left`,
`service_line_far_right`, `center_near`, `center_far`. The four corners drive
the homography; the two net points drive player identity; the rest improve the
least-squares fit when `speed_calc.build_homography` runs.

---

## 7. Client-side detail worth carrying over

Two behaviours are easy to miss and expensive to rediscover.

**Roughly half of all `current_speed` values are zero.** That is the filter
chain rejecting a frame, not the player standing still. Every client aggregate
that touches speed averages **only the non-zero samples**, and
`rallySpeedStats` gates on at least 30% non-zero coverage before calling the
result reliable. An implementation that averages including zeros will report
roughly half the true speed.

**Client-side re-capping was removed and must not come back.** `App.vue` used to
re-apply a 25 km/h cap over `skeleton_data[].players[].current_speed`, zeroing
anything above it. Because the downstream aggregates skip zeros, that silently
deleted each player's fastest movements from the per-rally statistics. The
metric audit removed it and made `speed_calc.py` the single source of truth;
`backend/scripts/verify_speed_filters.py` scans the worker source to assert the
old constants are not reintroduced.

---

## 8. Known accuracy caveats

Every item here was cross-checked against current source, not just read from the
audits. Items the audits mark fixed and that the code confirms are omitted.

### 8.1 Distance is an unsmoothed integral (open)

Both implementations accumulate `distance += hypot(dx, dy)` per frame from the
ankle midpoint, with no smoothing and no minimum-movement floor. Pose jitter
therefore **adds** to total distance rather than averaging out, because every
displacement contributes its absolute magnitude regardless of direction. The
inflation is unquantified and the audit is explicit that it should not be tuned
blind: the measurement that settles it is to take a segment where a player is
standing still and read off the distance accumulated across it, all of which is
noise. Positions are on the court plane and the homography is applied correctly,
so this is a noise problem, not a projection problem.

### 8.2 The static-cluster shuttle filter is dead in both per-frame loops (open)

Both `_run_detection_only_loop` and `_run_full_yolo_loop` prune clusters to
`count >= 3` **unconditionally every frame**. A cluster is created with
`count = 1` and pruned in the same iteration, so it can never reach the
threshold. Only `_build_shuttle_positions_dict` places the prune inside the
accepted-position branch, which is why the filtered track's filter works and the
raw track's does not.

**Do not apply the obvious one-line fix.** The audit replays it: clusters are
never aged out, so once one survives it becomes a permanent 24 px blind spot for
the rest of the match, and any location where the shuttle appears slow for 3
frames (a clear's apex, a slow drop, a net exchange) creates one. That is the
actual cause of the "over-filtering trims rally tails" symptom. Activating the
prune without cluster ageing would propagate that failure from the filtered
track into `skeleton_data` and the client timeline.

### 8.3 TrackNet indices are 0-based, the loops index 1-based (open)

`tracknet/inference.py` numbers the first decoded frame 0. Both worker loops do
`frame_count += 1` before use and then index `tracknet_positions[frame_count]`.
Every shuttle position therefore sits one frame ahead of the image and timestamp
it is attached to; `tracknet[0]` is never read and the last frame gets no
shuttle. Impact on rally bounds is about 33 ms and uniform. It matters for
anything correlating shuttle position with the frame image or with player
positions: shot attribution, shuttle speed, overlays.

### 8.4 Two time bases are unioned (open)

`rally_detection.detect_rallies` computes timestamps as `frame / fps`, synthetic
and uniform. The shot-gap detector carries `CAP_PROP_POS_MSEC` PTS values.
`union_rallies` merges the two directly, so for VFR sources the overlap test
compares different time bases. Related: the two detectors use 3.0 s and 3.1 s
rally gaps from unshared constants.

### 8.5 The Phase 2 re-cut rewrites annotated clips (**now live for this app**)

The re-cut upserts on `(video_id, rally_index)`, so `rally_clips.id` is
preserved while `start_timestamp`, `end_timestamp` and the stored MP4 all
change. `rally_annotations.clip_id` keeps pointing at the same row and its
`timestamp_seconds` becomes an offset into footage that no longer matches. The
audit filed this as latent because no annotation writer existed.

**It is no longer latent.** This app ships
`shared/.../repo/AnnotationsRepository.kt`, which inserts and updates
`rally_annotations`. Any video annotated after Phase 1 and then run through
Phase 2 will have its annotation timestamps silently desynchronised, and
`delete_stale_rally_clips` will cascade-delete annotations on any clip the
re-cut drops. The audit's suggested fix is to shift `timestamp_seconds` by the
boundary delta when a re-cut moves a clip beyond a small epsilon, or to flag the
annotation stale. Nothing has been done.

### 8.6 `refine_rallies` still drags neighbours (open)

Two defects the audit verified and the current code still has: it clamps rally
*i*'s start against `filtered[i-1]["end_timestamp"]`, the **un-refined**
neighbour, rather than the already-widened one; and its `overlapping` set
collects any raw rally that intersects, so a welded or long raw rally can drag a
neighbour's bounds far past its own detection. The audit's replay produces a
2.7 s overlap between adjacent clips from realistic inputs. Separately,
`max_extension_sec` defaults to `RALLY_GAP_SECONDS = 3.1` per side, so an
isolated rally can gain 6.2 s; the audit argues a bound tied to shuttle flight
time (0.5 to 1.0 s) is defensible and 3.1 s is not.

The audit's own sequencing advice: `refine_rallies` exists **because** the raw
track is unfiltered and the filtered track over-filters, which is §8.2 plus the
ROI divergence below. Fix those and the two tracks converge, at which point
`refine_rallies` is close to a no-op. Do not polish it first.

### 8.7 ROI divergence between the two shuttle tracks (open)

The raw track uses a court polygon expanded 1.40x horizontally with the top
clamped to `y = 0`; the filtered track uses 1.15x uniform. The filtered track
therefore discards high clears and deep lifts near the baseline. This is the
other half of the trimmed-tails problem.

### 8.8 Phase 1 and Phase 2 clip boundaries differ

Phase 1's clips come from `refine_rallies` over detection-only data with no
player gate; Phase 2's come from the shot-gap detector at strict browser parity.
Clip **boundaries**, not just clip count, move under the user between the two
phases. The web app labels Phase 1 clips "preliminary"; the audit notes that
label should also say the boundaries will change.

### 8.9 Three rally lists exist for one video, and they disagree (open)

The pieces are in §3.4, §4.5 and §8.8; the consequence deserves stating once.
There are three rally lists for a single video:

1. `results.json.rallies` and `results_meta.rally_count` - the gradient ∪ raw
   shot-gap **union**;
2. `rally_clips` after Phase 1 - `refine_rallies(filtered, raw)`, padded;
3. `rally_clips` after Phase 2 - browser-parity shot-gap, padded, re-cut in
   place over the same rows.

They are produced by different detectors over different shuttle tracks and they
routinely differ in count as well as in bounds. This is live today: a mobile
screen showing "12 rallies" from `rally_clips` and a summary showing
`results_meta.rally_count` can legitimately disagree on the same video, and both
the count and the bounds change under the user if Phase 2 ever runs. Pick one
source per surface and never mix them.

### 8.10 The stored player statistics are not the ones the web app shows (open)

The metric audit unified the **per-frame** speed filters, so the Phase 2 loop
and `speed_calc.py` now reject the same frames. It did not unify the
**aggregate** step, and the two still differ:

| | `_compute_analytics` (writes `results.json`) | `speed_calc.calculate_speeds_from_skeleton` (returned by `recalculate-speeds`) |
|---|---|---|
| Sample set | speeds accepted by the in-loop filters | recomputed from `skeleton_data`, **additionally** dropping any position that maps more than 2 m outside the court |
| Aggregate filter | hard cap 25, then IQR with upper bound `min(Q3+1.5·IQR, 20)`, then drop the top 5% | hard cap 25 only |
| Average | clamped to 15 km/h | unclamped |
| Max | clamped to 25 km/h | clamped to 25 km/h |

`App.vue:646-649` then **overwrites** `player.avg_speed`, `player.max_speed` and
`player.total_distance` in memory with the `recalculate-speeds` values before
the dashboard renders. The audit removed the client-side re-*capping* from that
block; it did not remove the overwrite.

So the web app's Player Statistics cards, and the PDF (which is handed
`config.players` from the same overwritten array), show the `speed_calc`
numbers. `results.json` stores the more aggressively trimmed ones, and nothing
in the web app ever displays them. A mobile app reading `results.json` directly
will show systematically **lower** average speeds than the web app for the same
video. Decide deliberately which of the two is the product's number.

### 8.11 No verification corpus exists

No saved `results.json` or TrackNet dump exists anywhere in the repo. Every
threshold in this document is currently unfalsifiable against real footage. If
mobile work starts here, persisting one full `skeleton_frames` plus
`tracknet_positions` dump is the cheapest thing that makes everything above
testable.

---

## 9. Documented but not real

Claims in `README.md`, `docs/MODAL_DEPLOYMENT_GUIDE.md` or the type layer with
no live producer. Listed so nobody plans around them.

| Claim | Reality |
|---|---|
| Roboflow court keypoint detection, 22-keypoint model, court confidence | No call site. `court_detection` is hardcoded `None`; `results_meta.has_court_detection` is hardcoded `false`. Court geometry comes only from the user's 12 manual clicks. |
| Kalman filtering, temporal interpolation, motion prediction, video deblur | No such module exists. `detection_smoothing.py` is not in the repo. |
| Shot type classification (smash / clear / drop / drive / net shot / lob) | The `ShotType` union exists; every shot the pipeline emits is `"unknown"`. Nothing classifies shot type. |
| Shuttle speed, fastest shot, shuttle statistics | `ShuttleMetrics` type and PDF section exist; `_compute_analytics` returns `shuttle: None`. `shuttle_speed_kmh` on a frame is documented as "rarely populated" and is in practice never set. |
| Server-side `player_zone_analytics` | Returns `None`. Zone coverage is client-side, and in **video-normalised** coordinates rather than court coordinates, with `avg_distance_to_net_m` hardcoded to 0 (the UI hides the row). |
| Trained pose/action model (`pose_classifications`) | Never populated. The pose-shot fallback detector that depends on it therefore never fires. |
| Fatigue detection, pressure index | Types remain in `analysis.ts`. `App.vue`'s own changelog: "Removed Pressure Index and Fatigue Detection due to insufficient data accuracy". No producer. |
| `MovementEfficiency.totalDistance` / `usefulDistance` / `wastedDistance` | Present in the type, always written as 0. Only `efficiencyScore` and `avgDirectness` are real. |
| FastAPI backend, WebSocket progress, `/api/upload`, `/api/analyze` | Gone. Supabase Edge Functions plus Realtime. |
| `Analytics` aggregate type | `export type Analytics = any`, with a TODO saying no aggregate shape exists. |

---

## 10. What this means for this app

**Everything above §10 is a description of the system. This section is an
opinion about what to do with it, and should be read as one.**

Four facts decide the design.

**One, and it bites first.** There is no single rally list (§8.9). The count in
`results_meta`, the rows in `rally_clips` after Phase 1, and the rows in
`rally_clips` after Phase 2 are three different answers, and the last two share
the same primary keys. Any mobile surface that shows a rally count or a rally
boundary has to name which of the three it is reading, and two surfaces reading
different ones will visibly disagree.

**Two.** The persisted analytics are thin: per-player distance, average speed,
max speed, a position track, and the rally list. Those are readable today with
one signed-URL fetch and would populate a respectable match summary screen
without porting anything.

**Three.** Everything richer lives in about 1500 lines of TypeScript that run
against a full per-frame skeleton dump. There are three ways to get it on a
phone, and they are genuinely different products:

- *Port the TypeScript to Kotlin.* Faithful to what the web app shows, no
  backend change, but it means downloading and parsing the entire skeleton blob
  on a phone, and it creates a second implementation of a dozen algorithms that
  already have a documented history of drifting from their Python twins.
- *Move the computation into Phase 2.* The Python ports already exist for the
  hard part (`shot_detection.py` and `rally_detection_shot_gap.py` are
  line-by-line ports of the browser detectors, and Phase 2 already runs the
  browser-parity rally detector for its clip re-cut). Extending
  `_compute_analytics` to emit rallies, shots, per-rally speeds, placement
  grids, recovery, reaction and efficiency into `results.json` would let both
  clients read one small analytics object instead of computing over the blob.
  This also fixes the standing oddity that `results.json` stores player numbers
  no user ever sees (§8.10).

  This is not a mechanical lift, and pretending otherwise would misprice it.
  Three costs are real. **Zone coverage is a product decision, not a port**: it
  is currently computed in video-normalised thirds rather than court
  coordinates, so moving it means either reproducing that (wrong, but matching
  the web app) or fixing it to court coordinates (right, but then web and
  mobile disagree until the web app is changed too). **Recovery and reaction
  carry the zero-speed discipline from §7** - both depend on per-player noise
  floors and inter-player distance statistics over the whole frame set, and the
  aggregates skip zeros; this is exactly the kind of detail the TS and Python
  twins have drifted on before. And **it only removes divergence if the web app
  then reads those fields instead of computing them**. Ship the server-side
  version while `useAdvancedAnalytics` keeps computing its own, and the result
  is a fourth implementation, not one fewer.
- *A third edge function* that computes analytics on demand from the stored blob
  and returns a small payload. Cheapest to build, but adds a per-view latency
  and a third place the algorithms live.

The second option is the one that leaves the codebase smaller than it found it,
and it is the only one that puts a single implementation behind both clients.

There is a fourth option, running the perception itself on the phone so no
cloud pass is needed at all. It is a different axis from these three, which are
all about where the *arithmetic* runs, and it gets its own section: §11.

**Four.** Phase 2 is currently desktop-only by policy, not by capability. This
app's `ProcessingUpdate.isSuccess` treats `phase1_complete` as done and the
comment says so outright. Nothing prevents mobile from calling
`start-analytics`; it needs the status machine extended past
`phase1_complete` and a UI for a second, longer wait.

Before any of that, three things are worth doing because they are cheap and they
unblock judgement: capture one real `results.json` and measure it (§2), decide
which player-statistics number is the product's (§8.10), and resolve §8.5, which
is a live data-corruption path in code this app already ships.

---

## 11. On-device feasibility

**Fact and opinion are separated inside this section.** §11.1 to §11.4 are read
from source and stated as fact. §11.5 is an accuracy argument derived from those
facts plus §8.1. §11.6 lists what is genuinely unresolved and refuses to
estimate it. §11.7 is opinion.

### 11.1 What the cloud is actually paying for

Both workers run on `gpu="A10G"` (`modal_supabase_processor.py:3801` and
`:4399`), a 24 GB datacenter GPU. Not CPU. That is worth stating because it
changes the arithmetic of any comparison.

What that GPU buys is almost entirely **perception**: turning pixels into
keypoints and shuttle positions. The analytics themselves are arithmetic over
data that has already been computed. Rally grouping, per-rally speed, shot
placement binning, recovery, reaction time and movement efficiency all run in
well under a second, which is precisely why they currently run in a browser tab
with no backend call at all (§5.2).

So "can the phone do the analytics" and "can the phone do the inference" are two
different questions with two different answers:

- **The analytics: yes, today, with no ML on device at all.** The phone's
  problem is not compute, it is that the *input* to those analytics is a
  hundred-megabyte skeleton dump (§2). That is a data-placement problem, and
  §10 is about solving it.
- **The inference: technically yes, with real caveats.** The rest of this
  section.

### 11.2 Model-by-model export outlook

Four models run across the two phases. Three are Ultralytics YOLO, which has
first-class export to Core ML, TFLite, ONNX and NCNN as a documented one-liner
(`model.export(format=...)`).

| Model | Where | What it is | Export outlook |
|---|---|---|---|
| `yolo26m-pose.pt` @ `imgsz=960` | Phase 2 | Ultralytics pose, medium | Supported path. The heavy one, and the input size is large for mobile. **Not pinned in the repo**, see §11.6 |
| `/models/badminton/best.pt` | both phases | Ultralytics detect (loaded via `YOLO(path)`) | Supported path, small model |
| `yolo11s-ball.pt` | Phase 1, `gb_fusion` only | Ultralytics detect, small | Supported path |
| TrackNetV3 + InpaintNet | Phase 1 | plain PyTorch, **not** YOLO | Better than expected, see below |

**TrackNetV3 is a plain convolutional U-Net** (`backend/tracknet/model.py:61`).
The complete op inventory is `Conv2d` 3x3, `BatchNorm2d`, `ReLU`, `MaxPool2d`,
`Upsample(mode="bilinear", align_corners=True)`, `torch.cat` skip connections, a
final `Conv2d` 1x1 and `sigmoid`. No custom ops, no dynamic control flow, no
attention, no deformable convolutions. Every one of those is a core
ONNX / Core ML / TFLite op. `InpaintNet` is the same story in 1D (`Conv1d`,
`LeakyReLU`, `MaxPool1d`, `Upsample(mode="linear")`).

Its shape is also favourable (`backend/tracknet/inference.py:23-24, 43, 96-105`):
input is **512 x 288**, and one forward pass covers **8 frames** at once
(`seq_len=8`, `in_dim=27` for the background-concat mode, `out_dim=8` heatmaps).
The decoder blocks running at full 512 x 288 dominate the cost, but that cost
amortizes across 8 frames, and the input is far smaller than the pose model's
960.

The one thing to validate numerically rather than assume is
`align_corners=True` on the bilinear upsamples. Its behaviour has historically
differed subtly between runtimes. That is a checkable discrepancy, not a
blocker.

**On YOLO26 specifically** (lower confidence, check current Ultralytics docs
before relying on it): the headline changes in YOLO26 are end-to-end NMS-free
inference and removal of the DFL module, and both were motivated by edge
deployment. Those are exactly the parts that historically made YOLO exports
awkward, since NMS often cannot fuse and falls back to CPU. If that holds,
YOLO26 is a *better* mobile target than YOLO11 or v8, not a worse one. Pose
export is somewhat less battle-tested than detect export and should be verified
first.

### 11.3 Production already runs fp16

`yolo_half = torch.cuda.is_available()`, and `half=yolo_half` is passed to every
inference call in both loops (`:2153`, `:2245` for Phase 1; `:2574`, `:2867`,
`:2870` for Phase 2). The worker even logs which precision it chose (`:2640`).

Production is therefore **not** fp32. That has a direct consequence for the
accuracy question: an **fp16 export on device is precision-parity with what runs
today**, and Core ML's Neural Engine prefers fp16 natively. The accuracy risks in
§11.5 are entered only by quantizing to **int8** to chase throughput.

### 11.4 What is not a model and has to be rewritten

**BoT-SORT is not a model.** It is Ultralytics' Python tracker plus OpenCV
global motion compensation (`gmc_method: sparseOptFlow`, `with_reid: False`,
`:2607` and `:2611`). There is nothing to export. It has to be reimplemented
natively, or OpenCV shipped on-device (official iOS and Android builds exist and
carry `goodFeaturesToTrack` / `calcOpticalFlowPyrLK`). `PlayerIdentityTracker`
consumes BoT-SORT's track IDs directly, so this sits on the critical path for
Phase 2 rather than being optional.

Everything *after* inference ports with essentially zero accuracy risk: the
identity tracker, `speed_calc`, both rally detectors, and the whole of §5.2 are
deterministic arithmetic with coarse thresholds, and they have already survived
one port (the Python and TypeScript twins).

### 11.5 Where accuracy would actually degrade

Only if something is given up to buy throughput. Three specific mechanisms, all
grounded in this pipeline rather than generic:

**int8 quantization would systematically inflate distance.** §8.1: total
distance is an unsmoothed integral of frame-to-frame ankle displacement, with no
smoothing and no minimum-movement floor, so jitter *adds* rather than averaging
out. int8 adds exactly that kind of coordinate noise and nothing downstream
absorbs it. The reported number would rise, look plausible, and be wrong. fp16
avoids this entirely (§11.3).

**Dropping `imgsz` from 960 cascades.** The obvious move to make pose affordable
is 640, which hits the smallest person in the frame first. The far player is
already the weak one: `useAdvancedAnalytics` notes pose "frequently drops the
far player", and the worker logs `frames_single_skeleton` as common on real
footage (§4.2). Fewer two-player frames means fewer speed samples, and it feeds
the `require_players` gate in `detect_all_shots`, which drops shots on frames
carrying no player data. Fewer shots, fewer rallies.

**Degrading TrackNet is a cliff, not a gradient.** The shot-gap detector rejects
a candidate rally outright when fewer than 25% of frames in the window carry a
shuttle position (`SHUTTLE_VISIBILITY_THRESHOLD`). Current coverage is roughly
40-50%. Lose enough of that in conversion and whole rallies disappear rather
than boundaries getting looser.

### 11.6 What is unresolved, and the measurement that settles it

Deliberately no wall-clock estimate appears here. Producing one would mean
chaining model FLOPs, an assumed NPU utilization and an assumed thermal derating
into a single number that would read as a measurement while being a product of
guesses. What can be stated as fact:

- **The load.** A medium pose model at `imgsz=960`, on **every** frame
  (`sample_rate = 1`, hardcoded at `:2650`), for the whole match, plus TrackNet,
  plus a detection model, plus BoT-SORT's optical flow on CPU.
- **Thermals move in one direction.** Phones throttle under sustained
  NPU-plus-decode load in a way a datacenter GPU does not. Direction certain,
  magnitude unknown.
- **Batching does not transfer.** Modal batches 8 frames for pose and 16 for
  detection (`:2573`, `:2152`) and 32 sequences for TrackNet (`:1973`),
  specifically to amortize kernel-launch overhead. Mobile NPUs generally want
  batch=1 with pipelining, so that multiplier is not inherited.
- **iOS will not grant multi-hour background compute.** `BGProcessingTask` is
  system-scheduled and killable. This may be dispositive on its own, regardless
  of how fast the NPU turns out to be.
- **Decode plumbing is real work.** Feeding tens of thousands of frames from a
  hardware decoder into the NPU without a CPU round-trip is
  `AVAssetReader` -> `CVPixelBuffer` -> Vision/Core ML on iOS (well supported)
  and `MediaCodec` -> `ImageReader` -> LiteRT/NNAPI on Android (messier, but
  routine).
- **`yolo26m-pose.pt` is not pinned.** It is loaded as
  `YOLO("yolo26m-pose.pt")` with no `MODELS_PATH` prefix (`:2578`), unlike the
  badminton model on the very next line (`:2582`), so Ultralytics resolves it at
  runtime. That exact weight has to be pinned before any conversion is
  reproducible.

**The benchmark that answers this**, in rough order of value per day spent:

1. Export TrackNetV3 to Core ML and TFLite. Run it against the PyTorch output on
   identical frames and measure per-frame shuttle-visibility rate, not just
   tensor deltas. If coverage does not survive (§11.5), the rest is moot.
2. Export `yolo26m-pose` at fp16 and run it over ~1000 frames of real footage at
   both 960 and 640. Measure two things: **sustained ms/frame across a 10-minute
   run** (the thermal answer, which a 30-second benchmark will not show), and
   **mean keypoint displacement against the Modal fp16 output on the same
   frames** (the accuracy answer, which directly predicts the distance
   inflation).
3. Only then decide about int8.

### 11.7 Opinion

The coherent split is **Phase 1 on device, Phase 2 in the cloud**. Phase 1 needs
only TrackNet at 512 x 288 and a nano-class detector, both small, and its
output (rally clips) is already the mobile product today. Phase 2 is where the
medium pose model at 960 lives, and it is the part that both costs the most and
degrades the most under any compromise.

That split also happens to be the cheapest thing to validate, because exporting
the two Phase 1 models is step 1 and 2 of the benchmark above.

**The one genuine architectural argument for going on-device** is that it makes
the §2 size problem vanish. Computing in-process means the skeleton dump is
never serialized to JSON and never downloaded, so the single largest obstacle to
mobile analytics disappears as a side effect rather than being engineered
around. That is real, and it is why this deserves a measurement rather than a
dismissal.

Against it: §10's option two (move analytics into Phase 2) delivers every metric
in §5.2 to the phone with no on-device inference, no accuracy risk, no
conversion work, no thermal question and no iOS background-execution problem.
On-device perception should be justified by something option two cannot provide
- offline capability, privacy, or per-video GPU cost - and not by the analytics
themselves, which were never the expensive part.
