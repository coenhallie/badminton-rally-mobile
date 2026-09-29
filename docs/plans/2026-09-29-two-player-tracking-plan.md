# Two-player tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cloud analysis finds the far player as well as the near one, and each side's track is the right person, measured on three corpus videos.

**Architecture:** Stage 0 measures: the worker's real loop runs locally per corpus video, a JVM scorer reads its pose file through the phone's real selection and scores both sides against audited references. Stage 1 adds a zoom pass to the worker (follow a small player, re-find a lost one by searching its half of the court), plus filter changes kept only on evidence. Stage 2 (conditional) replaces the phone's per-frame pick with a per-side path.

**Tech Stack:** Python 3 + ultralytics + OpenCV (badminton-tracker `backend/`), Kotlin Multiplatform JVM tests (`:analysis`), the local corpus at `~/shuttl-corpus/`.

**Spec:** `docs/plans/2026-09-29-two-player-tracking-design.md`

## Global Constraints

- Singles only: one player per side of the net.
- Pose file format unchanged (RawInference version 2).
- Worker pose model stays `yolo26m-pose.pt`, full-frame pass `imgsz=960`, `conf=0.15`, `iou=0.5`, batch 8.
- Zoom constants: `ZOOM_TRIGGER_MODEL_PX = 80`, `ZOOM_IMGSZ = 640`, follow window `2.6 h x 1.8 h` (min 200 x 160 px), continuation radius `0.6 h`, `LOST_FRAMES = 3`, search margin 2 m, headroom 2.5 m, search scale 1.5x (imgsz capped at 1920), `SEARCH_BACKOFF_S = 1.0` then every fifth frame, merge IoU 0.3, `ZOOM_TRACK_ID_BASE = 1_000_000`, ankle confidence 0.5.
- Every test against the marks maps ankles into the marking frame through `frame_to_marking` first; no Find search on a frame whose camera is lost.
- Scorer: right person within 1.0 m; reference-absent frames are unknown and excluded; camera-lost frames excluded from denominators; pose-file frame `k` joins reference frame `k - 1`.
- Gates (design section 7): aSASa Far coverage >= 0.70, wrong 0, median <= 15 cm; Near sides no more than 1 point below baseline, wrong no higher; `743d7fb1` Far coverage above baseline, wrong no higher; `IMG_5892` Near 0.95 / 8 cm / 25 cm, Far samples <= baseline; local CPU time per frame <= 2.5x baseline.
- Deploying the worker is the owner's step. No em dashes in prose, comments, commits.
- Commits: no agent attribution trailer (owner's standing instruction).

## Review Focus

1. A handheld video whose camera pans: the zoom's side-of-net and inside-the-lines tests must read ankles in the marking frame. Pinned in Task 6 (non-identity camera case).
2. A side that is empty all video (`IMG_5892` Far): the Find search must adopt nobody and back off. Pinned in Task 6 (backoff case) and measured in Task 7.
3. The umpire or a bystander standing still just off the court: acquisition requires ankles inside the lines; the oracle and any phone path pay a per-frame cost outside the lines. Pinned in Task 6 (acquisition rejects margin-only person) and Task 2 (oracle path).
4. A zoom detection that overlaps a full-frame detection of the same person: replaced, not duplicated, and the BoT-SORT track id kept. Pinned in Task 6 (merge cases).
5. A video shorter than the warm-up, or a frame where the full-frame pass found nobody (`boxes is None`): merge must still produce well-formed arrays. Pinned in Task 6 (empty-frame merge).

---

### Task 1: The harness runs any corpus video and records its cost

**Files:**
- Modify: `badminton-tracker/backend/scripts/run_phase2_locally.py`
- Corpus (not in a repo): `~/shuttl-corpus/d71b0798-c4ec-4038-bd3f-6967429c36f8/{source.mov,video.json}`

- [ ] Copy aSASa into the corpus: `source.mov` from `/tmp/asasa/hall-20s.mov`; `video.json` = `{"id": "d71b0798-...", "manual_court_keypoints": <the emulator entry's keypoints, including marked_at_s>}`.
- [ ] Add `--video-id` (default `IMG_5892`); defaults become `~/shuttl-corpus/<id>/source.*` (first of `source.mov`, `source.mp4`), marks from `marks_ideal.json` else `video.json`, out `~/shuttl-corpus/<id>/cloud-local/poses.raw`. Explicit `--video/--marks/--out` still win.
- [ ] Write `cloud-local/run.json` beside the pose file: `{"video_id", "frames", "seconds", "seconds_per_frame", "git_sha", "max_frames"}`, `git_sha` from `git rev-parse --short HEAD` in badminton-tracker.
- [ ] Run for `IMG_5892` (unchanged default) and aSASa; `743d7fb1` with `--max-frames 1500` (iteration) and full (baseline). Each baseline pose file is kept as `cloud-local/baseline.raw` and `baseline-run.json`.
- [ ] Commit (badminton-tracker): `feat(scripts): the local Phase 2 harness runs any corpus video and records its cost`.

### Task 2: References for both players (mobile `tools/models`)

**Files:**
- Create: `tools/models/players_reference.py`
- Create: `analysis/src/jvmMain/kotlin/com/badmintontracker/analysis/compare/CorpusRunExport.kt` + Gradle task `exportCorpusRun`

**Interfaces:**
- Produces: `~/shuttl-corpus/<id>/cloud-local/frames.json` (from the Kotlin export: per pose-file frame `k`: `timestamp`, `camera` 3x3 or null, and the phone's selection per side `{frame, x, y, personIndex}`); `~/shuttl-corpus/<id>/players_reference.json`:
  `{"video_id", "frames", "sides": {"NEAR": {"empty": bool, "positions": {"<i>": [x, y]}, "candidates": {"<i>": [[x, y, conf, inside_lines], ...]}}, "FAR": {...}}, "audit": [{"frame", "side", "action": "clear"|"choose", "candidate", "note"}]}`

- [ ] `CorpusRunExport` (JVM main): args `<corpusDir> [poses file]`; decodes the pose file, reads marks, runs `selectPlayers`, writes `frames.json`. Camera per frame = `raw.camera?.at(frame.timestamp)` exactly as `selectPlayers` reads it (null when not followed or lost).
- [ ] `players_reference.py --video-id <id>`: decodes sequentially; per frame `i` detects with `yolo26x-pose.pt` at `imgsz=1920` on the full frame plus 2x crops of each half (the half-court region of design 4.1), dedupes by IoU 0.5; per detection: ankles >= 0.5, midpoint mapped through the inverse of pose-file frame `i+1`'s camera into the marking frame, then to court metres through the 12-point resolved homography (`pose_metrics_probe.resolved_homography`), side by net line, on court within 2 m, torso <= 0.9 m. Frames whose camera is null while the pose file has a camera are skipped (camera-lost).
- [ ] Identity per side by shortest path: nodes are candidates; edges up to 15 frames apart, forbidden above 8 m/s; edge cost `0.5 * frames skipped + 1.0 * metres`; node cost `-ln(conf) + 3.0 if outside the lines of that half`. Writes `positions` (the path) and `candidates` (all, for audit choices).
- [ ] Run for aSASa and `743d7fb1` (MPS, background). `IMG_5892`: `NEAR` from `reference_tracks.json` `ref_track`, `FAR` `{"empty": true}`.
- [ ] Commit (mobile): `feat(tools): a player reference for both sides, and a corpus run export`.

### Task 3: The two-player scorer (JVM)

**Files:**
- Create: `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/player/TwoPlayerCorpusTest.kt`
- Create: `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/player/TwoPlayerScore.kt`

**Interfaces:**
- Produces: `fun scoreSide(samples: List<PlayerSample>, reference: SideReference, excluded: Set<Int>): SideScore` with `SideScore(samples, right, wrong, unknown, falsePositives, medianM, p95M, coverage)`; the test prints one table row per video and side for the cloud file and (Task 4) the device-style Near.

- [ ] Write `TwoPlayerScoreTest` cases first (plain unit, no corpus): join `k -> k-1`; a sample in a reference-absent frame is unknown, not wrong; a sample on an empty side is a false positive; excluded (camera-lost) frames leave every count and the coverage denominator.
- [ ] Implement `TwoPlayerScore.kt`; run `./gradlew :analysis:jvmTest --tests '*TwoPlayerScore*'` green.
- [ ] `TwoPlayerCorpusTest`: per corpus id, skip loudly without files (`ANALYSIS_REQUIRE_CORPUS=1` fails instead); prints the table; no gates yet. Optional file override via system property `twoPlayer.poses=baseline.raw` so baseline and new runs score from one test.
- [ ] Commit (mobile): `test: score both players of a local cloud run against the references`.

### Task 4: Device-style files (stage 2's trigger)

**Files:**
- Create: `tools/models/device_style_poses.py`

- [ ] Runs `pose_dump.py`'s decode (`posen.960.fp16.onnx`) over every frame, keeps every person, encodes a RawInference through badminton-tracker's `raw_inference_codec.encode` with frames numbered from 1, the cloud-local file's header and its camera, into `~/shuttl-corpus/<id>/device-style/poses.raw`.
- [ ] `TwoPlayerCorpusTest` adds a device-style Near row per video.
- [ ] Commit (mobile): `feat(tools): a device-style pose file, every person the nano graph finds`.

### Task 5: Audit the references, record the baseline

**Files:**
- Create: `tools/models/players_reference_audit.py`
- Modify: `docs/plans/2026-09-29-two-player-tracking-design.md` (new section 10, "Verification, as run")

- [ ] The audit tool reads `players_reference.json` and a `frames.json` selection, lists frames where the two disagree by more than 1 m or exactly one has somebody, plus 20 evenly spaced frames per side, and renders montages (12 crops per image, reference pick green, system pick magenta, other candidates grey, frame number and side printed).
- [ ] Review every montage by eye. Record each correction in `audit` (`clear`, or `choose` another candidate) and re-apply them (`players_reference.py --apply-audit`).
- [ ] Record the baseline table (cloud rows, device-style Near rows, local seconds per frame) in the design doc's section 10.
- [ ] Commit (mobile): `docs: the two-player baseline, on audited references`.

### Task 6: `player_zoom` (pure, tested first)

**Files:**
- Create: `badminton-tracker/backend/player_zoom.py`
- Create: `badminton-tracker/backend/scripts/verify_player_zoom.py`

**Interfaces:**
- Produces:
  - `CourtFrame.from_marks(marks: dict) -> CourtFrame | None` (4-corner homography marking px -> court m, its inverse, net line in marking px)
  - `follow_window(last_box, frame_w, frame_h, empty_frames) -> Rect`
  - `search_region(court, side: str, camera_now, frame_w, frame_h) -> Rect | None`; `search_imgsz(rect) -> int`
  - `pick_follow(dets, last_box, side, court, frame_to_marking, empty_frames) -> Detection | None`
  - `pick_search(dets, side, court, frame_to_marking) -> Detection | None`
  - `merge(kpts_xy, kpts_conf, boxes, track_ids, picks: list[tuple[Detection, int]]) -> (kpts_xy, kpts_conf, boxes, track_ids)`
  - `PlayerZoom(court, frame_w, frame_h, fps)` with `.plan(sides: dict[int, str], camera_now, marks_describe_frame: bool) -> list[ZoomRequest]`, `.pick(request, dets, frame_to_marking) -> Detection | None`, `.track_id(pid) -> int`, `.observe(emitted: dict[int, tuple | None])`
  - `Detection(box: tuple[float, float, float, float], kpts: np.ndarray (17, 2), conf: np.ndarray (17,))`, `Rect(x0, y0, x1, y1)`, `ZoomRequest(pid, kind: "follow"|"search", rect, imgsz)`

- [ ] Write `verify_player_zoom.py` sections first, in the `check(label, cond)` style of `verify_camera_motion.py`:
  1. `follow_window`: centred on the box, `2.6 h x 1.8 h`, minimum 200 x 160, clipped at every frame edge, grows by `1 + 0.5 * empty_frames`.
  2. `search_region` on aSASa's marks, identity camera: covers the far half plus 2 m (the `/tmp/asasa` numbers: x 295..1384, y 386..683 within 25 px); `search_imgsz` 1600-ish and never above 1920; with a camera that shifts 100 px right the rect shifts 100 px right; `None` when the half projects off-frame.
  3. `pick_follow`: nearest continuing detection within `0.6 h`; rejects one on the other side of the net; rejects unconfident ankles; with a non-identity `frame_to_marking` the side test uses mapped ankles (a detection on the far side in frame pixels but near side after mapping is rejected for the far slot).
  4. `pick_search`: rejects a person inside the 2 m margin but outside the lines (the umpire case), picks the most confident inside.
  5. `merge`: replacement at IoU > 0.3 keeps the BoT-SORT id and swaps keypoints/box; an append gets the zoom id; `boxes is None` and zero-row inputs give well-formed arrays.
  6. `PlayerZoom.plan`: a big filled slot gets nothing; a small filled slot gets `follow`; three empty frames give `search`; after `SEARCH_BACKOFF_S * fps` empty frames search only every fifth frame; `marks_describe_frame=False` gives no search; both follow crops share `ZOOM_IMGSZ`.
- [ ] Run `python backend/scripts/verify_player_zoom.py`: FAIL (module missing).
- [ ] Implement `player_zoom.py` (numpy + cv2 only; no ultralytics import).
- [ ] Run the verify script: all PASS. Also run `verify_camera_motion.py` and `verify_tracker_invariants.py` unchanged: PASS.
- [ ] Commit (badminton-tracker): `feat(backend): a zoom pass that follows a small player and re-finds a lost one`.

### Task 7: The zoom pass in the Phase 2 loop

**Files:**
- Modify: `badminton-tracker/backend/modal_supabase_processor.py` (`_run_full_yolo_loop`: model setup near `:3091`, per-frame pose handling `:3619-3660`, after `match_skeletons` `:3835`)

- [ ] Load a second `YOLO(POSE_MODEL_NAME)` as `zoom_model` (no tracker), build `PlayerZoom` from the marks when they are present (`None` otherwise, which leaves the loop exactly as today).
- [ ] Per frame, before `distinct_people`: `requests = player_zoom.plan(sides=identity_tracker.court_sides, camera_now=camera_now, marks_describe_frame=(not camera_tracking) or camera_following)`; follow requests share one `zoom_model.predict([crops], imgsz=ZOOM_IMGSZ, conf=0.15, iou=0.5, half=yolo_half, verbose=False)`; each search runs alone at its own imgsz; picks are mapped back and merged into `kpts_data`, `kpts_conf`, `boxes`, `track_ids`.
- [ ] After `matched_players`: `player_zoom.observe({pid: emitted _orig bbox or None for pid in (0, 1)})`.
- [ ] Log once per run: zoom calls, search calls, picks merged vs appended.
- [ ] Canary: harness on `IMG_5892`, then `CloudLocalRunCorpusTest` (must still pass 0.95 / 8 cm / 25 cm) and the scorer's `IMG_5892` Far false positives (<= baseline).
- [ ] aSASa, then `743d7fb1 --max-frames 1500`, scorer tables; iterate on constants only with a recorded reason.
- [ ] Commit (badminton-tracker): `feat(backend): Phase 2 zooms in on a small player and searches a lost one's half`.

### Task 8: Filter changes, each kept only on evidence

**Files:**
- Modify: `badminton-tracker/backend/modal_supabase_processor.py` (`is_sitting_pose` `:3704`, movement `:3272`/`:3802`, court polygon `:3290`)
- Modify: `badminton-tracker/backend/scripts/verify_player_zoom.py` (or a new `verify_phase2_filters.py`) for each kept change

- [ ] Sitting from the joints: lift `is_sitting_pose` to module level (unchanged behaviour, verified by cases), then add the joint rule behind `SITTING_FROM_JOINTS = True`. Cases: a lunge (box wider than tall, hips well above knees) is standing; a seated judge (hips at knee height) is sitting; legs not visible falls back to aspect ratio.
- [ ] Movement in metres behind `MOVEMENT_IN_METRES`, floor `MIN_CUMULATIVE_MOVEMENT_M = 1.5`.
- [ ] Court margin in metres behind `COURT_MARGIN_M` (try 0.5 and 1.0).
- [ ] For each: harness on all three videos, scorer tables; keep only with a gain and no gate regression; a rejected change is reverted and its numbers recorded in section 10.
- [ ] Commit per kept change (badminton-tracker), e.g. `fix(backend): a lunge is not a seated judge; sitting is read from the joints`.

### Task 9: Final numbers and the gates in the scorer

**Files:**
- Modify: `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/player/TwoPlayerCorpusTest.kt`
- Modify: `docs/plans/2026-09-29-two-player-tracking-design.md` (section 10)

- [ ] Full-length runs of all three videos on the final worker; scorer tables; local time per frame.
- [ ] Assert the gates from the Global Constraints in `TwoPlayerCorpusTest` against the baseline numbers written into the test as constants.
- [ ] `./gradlew :analysis:jvmTest` green (corpus present); `ANALYSIS_REQUIRE_CORPUS=1` run green.
- [ ] Commit (mobile): `test: the two-player gates, against today's worker`.

### Task 10: Stage 2 decision, and the per-side path if needed

**Files (only if triggered):**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/SidePath.kt`
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PlayerTrack.kt` (`selectPlayers`), `NearPlayer.kt` (`RejectionReason.NOT_ON_PATH` appended last; `select` returns every passing candidate)
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/SidePathTest.kt`

- [ ] Decide from Task 9's cloud rows (both sides) and device-style Near rows: any wrong-person sample means build; record the decision and numbers in section 10 either way.
- [ ] If building: tests first. Single candidate per frame gives today's selection exactly; a one-frame jump to a person 5 m away is skipped; a still person outside the lines loses to a moving player inside them over 3 s; skips longer than `MAX_SKIP_S` split the path; rejection counts still sum with samples to `framesWithPose`.
- [ ] Implement: nodes (frame, candidate); edges up to `MAX_SKIP_S = 0.5` s, none above `MAX_SPEED_MPS = 8.0`; edge `0.5 * frames skipped + 1.0 * metres`; node `-ln(conf) + 3.0 outside the lines`; start/end anywhere; unpicked frames count `NOT_ON_PATH`.
- [ ] `./gradlew :analysis:jvmTest :analysis:iosSimulatorArm64Test` green; scorer tables re-run.
- [ ] Commit (mobile): `feat(analysis): each side keeps one player over time, not the most confident person per frame`.

### Task 11: On the emulator

- [ ] Build a v3 cloud track for aSASa from the final local run through the phone's own code (`CorpusRunExport` writes the track in `PlayerTrackStore`'s v3 text format), plant it at `files/cloud-tracks/d71b0798-....track` with `run-as` (keep the original to restore), and check on the emulator (ANDROID_SERIAL=emulator-5554): the Near/Far toggle appears, the Far heatmap sits in the far half, Base draws, light and dark. Restore the original file afterwards.
- [ ] Hand over in the final report: the owner deploys the worker, re-analyses aSASa, and records the A10G Phase 2 time per frame against the previous run.
