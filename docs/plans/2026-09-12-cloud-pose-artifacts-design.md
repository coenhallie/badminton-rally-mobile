# Design: a cloud run produces the heatmap and the skeleton, through the device's own pipeline

**Date:** 2026-09-12
**Status:** Proposal, pending approval
**Spans two repos:** `badminton-rally-mobile` and `badminton-tracker` (Modal
worker, deployed infrastructure).
**Follows:** `2026-08-31-web-analysis-pipeline-reference.md` (this acts on its
§10 and closes its §8.5), `2026-08-31-on-device-analysis-pipeline-design.md`
(this reuses its §5.1 boundary rather than crossing it),
`2026-09-07-skeleton-view-design.md`.

A video analysed on this phone gets a heatmap, a base position panel and a
skeleton overlay. The same video analysed in the cloud gets rally clips and
nothing else. The coach who uploads, which is the path for anything longer than
a few minutes, sees an Analytics row that opens onto a court with no data on it.

This document closes that gap. It does not do so by teaching the cloud to draw
a heatmap. It does so by having the cloud hand the phone the one thing the
phone's own pipeline is missing: per-frame pose output for a video it never
decoded.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The cloud already computes skeletons, and the app never asks for them

- `modal_supabase_processor.py:4406` `_process_analytics_worker` is Phase 2. It
  runs `_run_full_yolo_loop` (`:2536`), a full per-frame YOLO-pose plus BoT-SORT
  pass, and produces `skeleton_frames`: 17 COCO keypoints per player per frame
  for the whole match.
- `PlayerIdentityTracker` (`:1273`) assigns those skeletons to stable player ids
  with a hard net-side constraint, so the two players are identified, not merely
  detected.
- The frame timestamp it records is `cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0`
  (`:2849`), the container presentation time.
- None of this reaches a phone. `VideosRepository.kt:41` reads
  `status == "phase1_complete" || status == "completed"` as success, with the
  comment "phase 2 analytics is desktop-only". `observeProcessing` therefore
  completes before Phase 2 could ever start.
- `start-analytics` (`supabase/functions/start-analytics/index.ts`) already
  exists, already validates the JWT and ownership, and already accepts
  `phase1_complete` or `failed_phase2`. Nothing prevents mobile calling it. No
  new edge function is needed.

### 1.2 The blob cannot be the transport

- Phase 2 merges `skeleton_data` into `results.json` and overwrites the same
  object key. Every keypoint carries a name and a confidence; every player
  carries nine body angles and a pose classification.
- The reference doc's §2 prices this at "tens to hundreds of megabytes for a
  full match" and notes the worker is provisioned at 8 GB specifically to
  serialize it. The web app downloads the whole thing into a Vue ref.
- A phone cannot do that, and the reference doc's §2 closes with the right
  instruction: **measure one before designing around it.**

### 1.3 No court-coordinate track exists anywhere in the cloud

- `_compute_analytics` (`:3508`) returns per-player average speed, max speed,
  total distance and a position list in **video pixels**, then literally `None`
  for shuttle metrics, court detection and zone analytics.
- The web app's heatmap is computed in the browser at view time, in
  video-normalised thirds rather than court coordinates.
- So the heatmap half of this request is not a plumbing change. Nothing
  server-side has ever produced what `CourtHeatmapView` draws.

### 1.4 What the device path does, and where its seam is

- `LocalInferenceEngine.kt:18` is the platform layer's whole contract, and
  `:9-11` states the rule this design rests on: an implementation "decodes
  frames, runs models, and emits raw model output. It never computes a metric,
  never assigns a player_id, never decides a rally boundary. Everything with a
  history of drifting between implementations lives in `:analysis`, written
  once."
- `RawInference` (`raw/RawInference.kt`) is that output. `RawInferenceCodec.kt:24`
  is its wire format, little-endian and fixed width, and `:5-8` pins the byte
  order explicitly **because the writer is Swift and the reader is Kotlin**.
- `LocalAnalysisCoordinator.analyse` (`:82`) takes a `RawInference` and produces
  everything: shuttle tracks (`:118`, `:125`), rallies (`:135`), and the near
  player (`:148`). It does no I/O and no networking.
- `selectNearPlayer` (`PlayerTrack.kt:70`) returns the track and the poses from
  one pass, so "the heatmap's position and the skeleton's joints are the same
  person in the same frame" holds by construction (`:47-51`).
- `NearPlayerSelector` (`NearPlayer.kt:92`) is where the measured judgement
  lives: ankle-only ground point (`:187`, with the 2026-09-07 measurement table
  justifying the removal of the hip fallback), `MAX_TORSO_M = 0.9` (`:313`),
  `MAX_COURT_RESIDUAL_M = 1.0` (`:302`), net-line side classification (`:251`),
  and a four-gate ordering that reports the furthest gate reached.
- `PlayerPose.timestamp` (`PlayerTrack.kt:35-38`) is documented as the container
  presentation time, "never frame / fps: on a variable-frame-rate source the two
  disagree and the skeleton would drift off the body."

### 1.5 The stores and panels, which are entry-keyed and single-track

- `PlayerTrackStore.kt:22` writes a `v2` text table (`:174`) of
  `frame,courtX,courtY` to `player-tracks/<entryId>.track` (`:161`, `:165`).
  `SkeletonStore.kt:53` writes a `SKEL` v2 binary (`:202`) carrying fps, video
  size, the 12 court marks and 17 keypoints per pose.
- iOS mirrors both: `PlayerTrackStore.swift`, `SkeletonStore.swift:43`.
- `HeatmapPanel.kt:59` and `SkeletonPanel.kt:96` read them through
  `LocalAnalysisRunner`, keyed by entry id. `heatmapSource` (`:40`) resolves the
  in-memory run against the stored one.
- `availablePanels` (`AnalyticsPanel.kt:24`) offers Heatmap always, Base on a
  track with bounded clips, Skeleton on a stored skeleton.
- `analyticsRowState` (`AnalyticsRowState.kt:29`) returns `NOT_ON_DEVICE` for
  any match with no local entry, which covers a match uploaded from another
  phone.
- `LocalVideoEntry.id` is documented at `:140` as the "client UUID; becomes
  videos.id on Analyze". Entry id and video id are the same namespace.

### 1.6 The corruption path that gates all of this

- `cut_and_upload_rally_clips` upserts on `(video_id, rally_index)`
  (`modal_supabase_processor.py:283`), so `rally_clips.id` survives while
  `start_timestamp`, `end_timestamp` and the stored MP4 all change.
- `rally_annotations.timestamp_seconds` is an offset into the clip file. The app
  seeks straight to it: `ClipDetailViewModel.kt:121` emits
  `(a.timestampSeconds * 1000).toLong()`.
- `delete_stale_rally_clips` (`:322`) deletes annotations on every clip beyond
  the new count (`:353`).
- Phase 2 calls both (`:4797`, `:4804`). The reference doc's §8.5 records this
  as no longer latent: this app ships `AnnotationsRepository`, so notes exist to
  be desynchronised and deleted.
- Mobile is safe today only because it never calls `start-analytics`. This
  design's first act is to remove that accident, so the defect has to be fixed
  first.

---

## 2. The fact the design turns on

The cloud does not need to learn how to draw a heatmap. It needs to become a
third writer of a format the repo already defined for exactly this purpose.

`RawInferenceCodec` exists because Swift writes it and Kotlin reads it. Python
writing it too is the same move a third time. Once Modal emits a `RAWI` stream,
the phone runs `selectNearPlayer` over it, writes the same two stores a device
run writes, and `HeatmapPanel`, `SkeletonPanel` and `CourtHeatmapView` draw it
with no knowledge that the pose came from an A10G instead of an NPU.

The alternative, computing the track and the skeleton server-side and shipping
finished artifacts, requires porting `NearPlayerSelector`, `Homography` and the
court-fit residual check into Python. That is a second implementation of the
most carefully measured code in `:analysis`, in the repo whose own reference doc
(§10) says the deciding criterion is "the only one that puts a single
implementation behind both clients". It was the first approach considered here
and it was rejected on that ground.

---

## 3. Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Where the pose arithmetic runs | On the phone, in `:analysis`, over a downloaded `RAWI` stream | **Port the selection to Python and ship finished `track`/`skeleton` files.** Smaller download, but it duplicates `NearPlayerSelector`'s four gates, the ankle-only ground point, `MAX_TORSO_M` and the court-fit residual into a second language. Every one of those is a measured value, and the two-language history in these repos is a history of drift. **Download the skeleton blob and compute on device.** Tens to hundreds of MB, and the app would have to parse a named-field JSON the worker needs 8 GB to write. **A fourth edge function computing on demand.** Per-view latency and a third home for the algorithms. |
| What crosses the wire | The two persons the cloud's identity tracker retained, per frame, as `RawPerson` entries | **Every detected person.** Faithful to "raw model output", but a wide badminton shot detects crowd and officials; at four persons a frame the artifact roughly doubles. **One person (near only).** Would match the device path exactly, but discards the far player the cloud tracks well and the phone does not. |
| Who decides which player is which | `:analysis`, from `isFarSide` and the court marks | **Carry the cloud's `player_id` across the boundary.** It would work, but it puts an identity assignment in the wire format, which `LocalInferenceEngine.kt:9-11` forbids, and it would mean two rules for "which side is this player on". The cloud tracker still earns its keep: it chooses *which* two detections cross the wire. `:analysis` decides what they mean. |
| Rally truth for a cloud video | `rally_clips`, unchanged | **Re-derive rallies on the phone from the same stream.** The stream would carry shuttle and boxes and `analyse` would produce a fourth rally list (reference §8.9 already counts three), disagreeing with the clips the coach has already annotated. Made structural, not conventional: the cloud path calls a pose-only entry point that cannot reach the rally detectors. |
| Provenance of the stored artifacts | Cloud artifacts in their own store namespace; cloud wins when both exist | **One namespace with a flag.** `skeletonAction` (`SkeletonDecision.kt:18-21`) holds that "a completed run is the new truth for its entry", so a later rally-only device run would silently delete a cloud skeleton. A separate namespace makes that impossible rather than merely discouraged. |
| Store format for two players | A multi-track `v3` added alongside `v2`, written only by the cloud path; readers accept either | **Bump both stores to `v3` and migrate.** `PlayerTrackStore.has` (`:61`) and `load` (`:72`) compare the header against one `VERSION` for exact equality, so the bump refuses every track file already on every phone, and the documented recovery is a half-hour re-run. The local path will never write two tracks anyway (§9), so a multi-track local format is a format nobody writes. |
| Compression of the artifact | None | Gzip would roughly halve it, but Kotlin/Native has no built-in inflate and adding a dependency to both platforms costs more than the bytes are worth for a one-time download. Revisit if the measurement in §10 comes back far above the estimate. |
| Panel availability without a local video | Heatmap yes, Skeleton no | The heatmap needs only a track. The skeleton overlays playback and has nothing to draw over. |

---

## 4. Prerequisite: the Phase 2 re-cut must stop corrupting notes

This is in `badminton-tracker` and it lands before anything else. It is a live
defect for web-triggered Phase 2 today; it merely happens to gate us.

An annotation's `timestamp_seconds` is an offset from its clip's start. When the
re-cut moves that clip's start, the offset must move with it:

```
new_offset = old_offset + (old_clip_start - new_clip_start)
```

`cut_and_upload_rally_clips` reads each existing `(video_id, rally_index)` row's
`start_timestamp` before the upsert, and after the upsert shifts that clip's
annotations by the delta when it exceeds a small epsilon. An annotation whose
shifted offset falls outside the new clip window is clamped to the window and
flagged rather than moved silently, because a note pointing at footage that no
longer exists is worse than one pointing a second off.

`delete_stale_rally_clips` keeps deleting rows beyond the new count, but its
annotation delete (`:353`) gets a log line naming how many notes it destroyed,
so the loss is recorded rather than silent.

Verified by a replay test in `backend/scripts/`, in the shape
`verify_rally_bounds.py` already uses: synthesise a clip set, annotate it,
re-cut with shifted bounds, assert every surviving note lands on the same frame
of footage it was written against.

---

## 5. The artifact

### 5.1 A Python writer for `RAWI`

`backend/raw_inference_codec.py`, byte-compatible with
`RawInferenceCodec.kt:32`. Same magic, same version, same little-endian
fixed-width layout. The Kotlin codec's own test
(`RawInferenceCodecTest.kt`) gets a Python counterpart writing a fixture the
Kotlin test then decodes, so the two writers are checked against one reader
rather than against each other's prose.

### 5.2 What Phase 2 emits

After `_run_full_yolo_loop` returns, and before `skeleton_frames` is freed, the
worker writes one `RAWI` stream:

- **Header.** `fps`, `totalFrames`, `videoWidth`, `videoHeight` from the Phase 2
  probe. `modelVersion` stamped `cloud:<pose model>:<worker revision>`, distinct
  from any on-device stamp, so provenance is readable from the file.
- **Per frame.** `frame`, `timestamp` set to the `CAP_PROP_POS_MSEC` value the
  loop already records (`:2849`), which is the presentation time
  `PlayerPose.timestamp` requires. `shuttle` null and `boxes` empty: this
  artifact is pose only, by §3's rally decision. `persons` holds the two the
  identity tracker retained, each with its box and 17 COCO keypoints in source
  video pixels.
- **Destination.** `results/{owner}/{video_id}/poses.raw`, the existing bucket
  with the existing owner-prefix RLS. No migration, no new policy.
- **Discovery.** `results_meta` gains `poses_artifact_path`,
  `poses_frame_count` and `poses_codec_version`, written in the same update
  that already rewrites it at `:4844`. A list screen can answer "is there a
  cloud heatmap" without fetching anything.

Coordinates are source-video pixels, already scaled back from model input size,
matching the contract in `RawInference.kt:12-16`.

### 5.3 Size

At two persons a frame the per-frame cost is 4 + 8 + 1 + 4 + 4 + 2 x 232 bytes,
about 485 bytes, which is roughly **26 MB for a 30-minute match at 30 fps**.

That is an estimate from the format, not a measurement, and the reference doc's
§2 is emphatic that this is the mistake to avoid. The implementation plan
measures one real artifact from one real match before the download path ships,
and this document is wrong until it does.

---

## 6. The device side

### 6.1 A pose-only entry point

`LocalAnalysisCoordinator` gains a second entry point taking an already
materialised `RawInference`. It calls the player selection and returns the
track, the poses and the video dimensions. It does not call `buildFilteredTrack`,
`buildFusionTrack` or `runPhase1FromTracks`, and cannot: the rally invariant in
§3 is enforced by the shape of the function, not by remembering not to read a
field.

`analyse` (`:82`) is already factored as a pure `RawInference` -> outcome
function, and its only use of `videoPath` is
`filename = videoPath.substringAfterLast('/')` at `:169`, for the
`AnalysisResult`. The pose-only path produces no `AnalysisResult`, so it needs
no path at all.

`LocalInferenceEngine` is deliberately **not** implemented for the cloud. Its
`run(videoPath, onProgress)` signature (`:24`) describes a decode-and-inference
pass over a local file; the cloud path has no local file and its progress is an
upload plus a Modal queue plus a download. A fake engine taking a path nobody
reads would be a worse lie than a second entry point.

### 6.2 Fetching

A `CloudPoseRepository` in `shared`, alongside `MediaRepository`, signs a URL
for `poses_artifact_path` and streams it to disk. The fetch runs when the detail
screen is opened and no cloud artifact is stored yet, with a progress line in
place of the panel, and opportunistically when a foregrounded pipeline observes
`completed`. Once stored it is never fetched again.

The second trigger depends on §6.3 and does not work without it. `completed` is
a status `observeProcessing` never reaches today, because the flow terminates at
`phase1_complete` (§1.1). Wiring the opportunistic fetch before the status
machine is extended produces a trigger that can never fire, which is why §12
keeps the two in one step.

### 6.3 Triggering Phase 2

`ProcessingUpdate.isSuccess` (`VideosRepository.kt:41`) splits in two:
`phase1_complete` remains a usable result, because the clips are real and the
coach should not wait on pose to watch a rally, while `completed` becomes the
terminal state for the analytics artifact. `processing_phase2` and
`failed_phase2` join the status machine. `startAnalytics(videoId)` calls the
existing edge function.

`AnalyzeStage` gains one stage for the second wait, and `cloudAnalysisStatus`
(`LocalVideoEntry.kt`) gains its line. That function's own test file records why
it is shared: four screens render it and three of them had written it out
separately and drifted. The new stage goes in there with the others.

`AnalyzeStage` is persisted in the local video registry, and
`LocalVideoEntry.kt:146-148` warns that a registry written before a field
existed must still decode or `load()` swallows the failure and returns an empty
library. An added enum **case** is a different risk from an added field, and the
plan checks how `AnalyzeStage` deserializes against a registry written by the
current build before committing to the name. `LocalVideoEntry.kt:165-166` also
records that Swift constructs this type with every argument spelled out, so any
change here is a two-platform edit.

### 6.4 Where the artifacts land

`PlayerTrackStore` and `SkeletonStore` gain a source dimension as a
subdirectory, `local/` and `cloud/`. `skeletonAction` continues to govern the
local subtree only, so a rally-only device run can no longer delete a cloud
skeleton. When both exist, the panels read the cloud one: it comes from a larger
model and carries both players. A source picker is out of scope (§10).

The store key is the video id, which `LocalVideoEntry.kt:140` establishes is the
same value as the entry id whenever a local entry exists. A match uploaded from
another phone has no entry and lands under its video id alone, which is what
makes §8 possible.

---

## 7. Both players

`:analysis` gains `selectPlayers`, alongside `selectNearPlayer` rather than
replacing it, reusing `groundPoint`, `onCourt` and `plausibleScale` unchanged
and keeping the best-confidence candidate on **each** side of `isFarSide`
instead of rejecting the far one. `NearPlayerSelector`'s gates are untouched;
only the near-side filter at `NearPlayer.kt:137` moves from a rejection to a
partition.

This is most of the mobile work in this change, because both stores are
single-track formats today:

- A multi-track format is added, `v3` for the track table and `SKEL` v3 for the
  skeleton, each carrying a track count and a side tag per track. **The local
  writer keeps writing v2.** Only the cloud path ever writes v3, because only a
  cloud run ever has two usable players (§9). The readers widen to accept either
  version; nothing narrows.

  This is not the same as bumping the version, and the difference is data loss.
  `PlayerTrackStore.has` (`:61`) and `load` (`:72`) both compare the header
  against a single `VERSION` for exact equality, so changing that constant to
  `"v3"` would refuse every track file already on every phone, and the recovery
  its own KDoc names (`:41-53`) is a re-run that costs half an hour.
  `SkeletonStore` is the more forgiving of the two: `has` (`:99`) already
  accepts v1 or v2 from the header alone. The two stores are therefore not
  symmetric today, and a plan that treats them as symmetric silently wipes
  heatmaps.

  The v1 refusal stays absolute. Its reason (`PlayerTrackStore.kt:41-53`) is
  that v1 tracks were built on a homography that could be metres off, so they
  are wrong rather than merely old. v2's geometry is correct, so that reason
  does not reach it.
- `HeatmapPanel` and `SkeletonPanel` gain a Near/Far toggle, **shown only when
  the loaded data has two tracks**. A device run stores one, so the device UI is
  unchanged on both platforms.
- Four store files and four panel files, two per platform.

Labels are "Near" and "Far", camera-relative. `videos.player_labels` carries the
user's names and Phase 2's thumbnails, and using them is a follow-up (§10).

---

## 8. Availability

`availablePanels` (`AnalyticsPanel.kt:24`) takes a `hasVideo` argument and gates
Skeleton on it. `analyticsRowState` gains a state between `READY` and
`NOT_ON_DEVICE`: a match with a stored track but no local video, which opens the
heatmap and the base position and offers no skeleton.

Scope is the user's own matches. A match **shared** by another coach stays inert:
the `videos` SELECT policy is owner-only, and widening it would also hand share
recipients `storage_path`, `results_meta` and `player_labels`. Doing it properly
means the RPC treatment `list_match_metadata` already uses, and that is a
separate change (§10).

---

## 9. Parity is not identity, and the app should say so

Cloud pose comes from a large YOLO-pose model on an A10G. Device pose comes from
a nano-class model on a phone. The code downstream is identical; the keypoints
going into it are not. Two consequences a coach will meet:

- **The same video analysed both ways produces two different heatmaps.** Close,
  not equal. This is why cloud wins when both exist rather than the panels
  averaging or offering both.
- **The far player is good in the cloud and poor on the phone.**
  `NearPlayer.kt:77-82` records the measurement: nano reaches 44% far-player
  coverage against 93% near, and even the largest model reaches about 70% far.
  So the Near/Far toggle will in practice appear on cloud results and not on
  device results, and that is a property of the models, not a bug in the toggle.

`PlayerTrack.coverage` already exists to report exactly this, and the panels
already surface it. Nothing new is needed beyond not hiding it.

---

## 10. Deliberately not in this pass

- **A source picker between a cloud and a device analysis of one video.** Cloud
  wins silently. Adding a third selector to a screen that will already have
  panel tabs and a player toggle is a worse screen.
- **Shared matches.** §8. Needs an RPC, and the RLS reasoning deserves its own
  document.
- **Player names and thumbnails from `videos.player_labels`.** The toggle says
  Near and Far. Phase 2 already captures the thumbnails (`:4730`).
- **Rallies from the cloud stream.** §3. `rally_clips` stays the rally truth for
  cloud videos, and the artifact carries no shuttle and no boxes so the question
  cannot be reopened by accident.
- **Everything else Phase 2 computes.** Speed, distance, shot events, recovery,
  reaction, placement. The reference doc's §5.2 catalogues them and its §10
  prices moving them properly. This design ships the two panels that already
  exist on the phone, not a new analytics surface.
- **Compression.** §3.
- **The `results.json` blob.** Untouched. The web app keeps reading it exactly
  as it does today, so this change adds no fourth implementation of anything and
  the web app needs no coordinated release.

---

## 11. How this is verified

**The codec, across languages.** The Python writer produces a fixture that
`RawInferenceCodecTest` decodes in Kotlin and asserts field for field. A format
checked by one reader, not by two prose descriptions.

**The selection, against the tracker as oracle.** Per the established practice
in these repos, run `badminton-tracker`'s Python directly over a corpus video to
produce the pose input, feed the resulting `RAWI` to `:analysis`, and compare the
court positions against `courtPositions()` rather than raw-table residuals: the
near and far resolution goes through net side, and a raw residual misleads here.

**`selectPlayers` against `selectNearPlayer`.** On a single-player fixture the
two must agree sample for sample. A partition that changes the near player's
track is a regression, not a feature.

**The stores, both versions, both platforms.** A v2 file written by the current
build must still load after v3 exists, on Android and on iOS, and must present
as a single-track result with no toggle. Tested per store rather than once,
because the two do not start from the same place: `SkeletonStore.has` already
accepts more than one version and `PlayerTrackStore.has` accepts exactly one
(§7). The regression to guard is a phone whose existing heatmaps vanish after an
app update, and it is invisible to any test that writes its fixture with the new
writer.

**The annotation shift (§4).** A replay test in `backend/scripts/`: annotate a
clip set, re-cut with moved bounds, assert every surviving note lands on the
same frame of footage it was written against.

**The measurement (§5.3).** One real artifact from one real match, measured
before the download path ships. If it comes back materially above 26 MB, §3's
compression decision reopens.

**End to end, on a device.** A real upload, Phase 2 triggered from the phone,
the artifact fetched, and the heatmap and skeleton drawn. Per the standing rule
for this repo: pin `ANDROID_SERIAL` for the verification build, and be picky
about the UI when checking it.

---

## 12. Sequencing

1. `badminton-tracker`: the annotation shift (§4). Lands and deploys alone.
2. `badminton-tracker`: the Python `RAWI` writer and its cross-language fixture
   (§5.1).
3. `badminton-tracker`: Phase 2 emits the artifact and records it in
   `results_meta` (§5.2). Measure one (§5.3).
4. `:analysis`: `selectPlayers` (§7).
5. `shared`: pose-only entry point, `CloudPoseRepository`, status machine (§6).
6. Stores to v3 and the panel toggle, Android then iOS (§7).
7. Availability and row states (§8).

Steps 1 to 3 are in the other repo and deploy independently of the app, so the
mobile work in 4 to 7 can be built against a measured artifact rather than a
predicted one.
