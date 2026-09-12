# Cloud pose artifacts - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A video analysed in the cloud gets the same heatmap and skeleton panels a video analysed on the phone gets, with both players rather than one, by having Modal emit raw pose output in the format the device pipeline already reads.

**Architecture:** Modal's Phase 2 becomes a third writer of the `RAWI` wire format (Swift writes it, Kotlin reads it, now Python writes it). It uploads a compact per-frame pose stream to the existing `results` bucket. The phone downloads that stream and runs the `:analysis` player selection it already ships, writing the same two on-disk stores a device run writes, into a separate `cloud` namespace. No analysis algorithm is ported to Python and no second implementation of anything is created. `rally_clips` stays the rally truth for cloud videos, enforced by a pose-only entry point that cannot reach the rally detectors.

**Tech Stack:** Python 3 (Modal worker, `backend/` in `badminton-tracker`, no pytest - standalone `verify_*.py` scripts), Kotlin Multiplatform (`analysis`/`shared`/`androidApp`), Jetpack Compose + Material 3, SwiftUI with `@Observable`/`@MainActor`, SKIE bridging, kotest assertions in Kotlin tests, XCTest on iOS.

**Spec:** `docs/plans/2026-09-12-cloud-pose-artifacts-design.md`

**Second repo:** Tasks 1 to 5 are in `badminton-tracker` (sibling checkout at `../badminton-tracker`). Tasks 6 to 15 are in this repo. The two halves deploy independently: steps 1 to 3 ship to Modal without any app release, and the mobile work is built against a measured artifact rather than a predicted one.

## Global Constraints

- No em dash (`—`) anywhere written by hand: code comments, commit messages, docs, UI copy. Use a plain dash `-`. Existing file content that is not otherwise being rewritten is left alone.
- No agent attribution on commits: no `Co-Authored-By` trailer, no "Generated with" footer.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`. Run `xcodegen generate` from `iosApp/` after adding or deleting Swift files. `project.yml` lists `sources: [Sources, Assets.xcassets]` as directories, so new files under `Sources/` need no `project.yml` change.
- Every iOS build/test command must be prefixed with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, and simulator builds need `CODE_SIGNING_ALLOWED=NO`. Run `xattr -cr iosApp` first if codesign complains about `com.apple.provenance`.
- iOS test names mirror Android test names one for one. That parity is the cross-platform check; if the two lists diverge, the two surfaces have diverged.
- TDD: write the failing test, run it and see it fail for the stated reason, implement, run it and see it pass, commit.
- **`rally_index` is 1-based throughout this project.** No display adds `+ 1`.
- **The cloud path never produces a rally.** No step in this plan may call `buildFilteredTrack`, `buildFusionTrack`, `runPhase1FromTracks` or any rally detector on a cloud-sourced `RawInference`. `rally_clips` is the rally truth for a cloud video (spec §3). If a step seems to need one, the design is being misread.
- **Nothing in this plan changes `results.json`.** The web app keeps reading it exactly as today, so no coordinated web release is needed (spec §10).
- Android verification builds pin the emulator: `ANDROID_SERIAL=emulator-5554`. A phone is often attached and `installDebug` hits every connected device.
- Migrations, if any, are committed but never applied by the agent. `supabase db push` is the owner's step. This plan needs none: the `results` bucket and its owner-prefix RLS already exist.
- **Deploying the Modal worker is the owner's step.** Tasks 1 to 4 land code; the owner deploys. Task 5 needs a deployed worker.

**Test commands:**

```bash
# badminton-tracker: pure-function guards, no GPU, no network, milliseconds
cd ../badminton-tracker
python backend/scripts/verify_annotation_shift.py
python backend/scripts/verify_raw_inference_codec.py

# this repo: analysis + shared, one class
./gradlew :analysis:jvmTest --tests "com.badmintontracker.analysis.player.SelectPlayersTest"
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CloudPoseTest"
# analysis + shared, everything
./gradlew :shared:jvmTest :analysis:jvmTest
# androidApp
./gradlew :androidApp:testDebugUnitTest
# iOS (unit + UI)
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

---

## File structure

**`badminton-tracker`**

| File | Responsibility |
|---|---|
| `backend/raw_inference_codec.py` (new) | The `RAWI` wire format as Python writes it. Dataclasses plus one `encode`. No decoder: there is one reader and it is the Kotlin one. |
| `backend/scripts/verify_raw_inference_codec.py` (new) | Byte-level invariants on the encoder (magic, version, exact length) and the fixture generator for Task 3. |
| `backend/scripts/verify_annotation_shift.py` (new) | The pure re-anchoring rule for annotations across a clip re-cut. |
| `backend/modal_supabase_processor.py` (modify) | `shift_annotation_offsets` (new pure helper), its wiring into `cut_and_upload_rally_clips`, a loss log in `delete_stale_rally_clips`, and the Phase 2 artifact emit. |

**This repo**

| File | Responsibility |
|---|---|
| `analysis/.../player/NearPlayer.kt` (modify) | `CourtSide`, and `select` generalised to take a side. Gates untouched. |
| `analysis/.../player/PlayerTrack.kt` (modify) | `PlayerSelection`, `selectPlayers`; `selectNearPlayer` delegates. |
| `analysis/src/commonTest/resources/raw/cloud-poses.rawi` (new) | The Python-written fixture the Kotlin codec test decodes. |
| `androidApp/.../localanalysis/PlayerTrackStore.kt` (modify) | `TrackSource`, multi-track `v3` alongside `v2`, readers widen. |
| `androidApp/.../localanalysis/SkeletonStore.kt` (modify) | Same, `SKEL` v3 alongside v1/v2. |
| `iosApp/Sources/LocalAnalysis/PlayerTrackStore.swift`, `SkeletonStore.swift` (modify) | The iOS ports of both. |
| `shared/.../local/CloudPose.kt` (new) | The pose-only entry point: `RawInference` in, per-side tracks and poses out. Cannot reach a rally detector. |
| `shared/.../repo/CloudPoseRepository.kt` (new) | Signs and streams `poses_artifact_path` to a local file. |
| `shared/.../repo/VideosRepository.kt` (modify) | Status machine past `phase1_complete`; `startAnalytics`. |
| `shared/.../localvideo/LocalVideoEntry.kt` (modify) | The second-wait stage and its `cloudAnalysisStatus` line. |
| `shared/.../analytics/AnalyticsPanel.kt`, `AnalyticsRowState.kt` (modify) | `hasVideo` gating and the track-without-video row state. |
| `androidApp/.../localanalysis/HeatmapPanel.kt`, `SkeletonPanel.kt` (modify) | Cloud-over-local resolution and the Near/Far toggle. |
| `iosApp/Sources/Analytics/AnalyticsDetailView.swift`, `iosApp/Sources/LocalAnalysis/SkeletonPanel.swift` (modify) | The iOS ports of both. |

---

## Task 1: Annotations survive a clip re-cut

Spec §4. This is the prerequisite, and it is in `badminton-tracker`. It is a live defect for web-triggered Phase 2 today; it gates us because this plan is what lets mobile trigger Phase 2 for the first time.

An annotation's `timestamp_seconds` is an offset from its clip's start (`ClipDetailViewModel.kt:121` seeks straight to it). When a re-cut moves the clip's start, the offset must move with it or the note points at different footage.

**Files:**
- Modify: `backend/modal_supabase_processor.py` (add `shift_annotation_offsets` near `pad_rally_windows` at `:91`; wire into `cut_and_upload_rally_clips` at `:132`; log the loss in `delete_stale_rally_clips` at `:322`)
- Test: `backend/scripts/verify_annotation_shift.py` (new)

**Interfaces:**
- Produces: `ANNOTATION_SHIFT_EPSILON_S: float`; `shift_annotation_offsets(annotations: list[dict], old_clip_start: float, new_clip_start: float, new_clip_end: float) -> list[dict]` returning `[{"id": str, "timestamp_seconds": float, "clamped": bool}]`, one entry per annotation whose offset actually changes.

- [ ] **Step 1: Write the failing test**

Create `backend/scripts/verify_annotation_shift.py`:

```python
"""
Offline regression suite for annotation re-anchoring across a clip re-cut.

Usage:
    python backend/scripts/verify_annotation_shift.py

No GPU, no video, no network - this is a pure function, so it runs in
milliseconds. Run it after ANY change to:
    backend/modal_supabase_processor.py  (shift_annotation_offsets,
                                          cut_and_upload_rally_clips)

Covers the defect recorded as §8.5 of
badminton-rally-mobile/docs/plans/2026-08-31-web-analysis-pipeline-reference.md:
the Phase 2 re-cut upserts on (video_id, rally_index), so rally_clips.id
survives while the footage under it moves, and every note's offset into that
footage silently becomes an offset into different footage.

Exits non-zero on the first failing assertion group.
"""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = str(Path(__file__).resolve().parents[1])
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from modal_supabase_processor import (  # noqa: E402
    ANNOTATION_SHIFT_EPSILON_S,
    shift_annotation_offsets,
)

FAILURES: list[str] = []


def check(label: str, cond: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    if not cond:
        FAILURES.append(label)


def note(note_id: str, offset: float) -> dict:
    return {"id": note_id, "timestamp_seconds": offset}


print("a clip that did not move leaves every note alone")
# The re-cut runs on every Phase 2, including ones whose boundaries agree with
# Phase 1's. Writing a row per note on every run would be a needless write and
# would churn updated_at on data nobody touched.
check(
    "no shift under the epsilon",
    shift_annotation_offsets(
        [note("a", 3.0), note("b", 7.5)],
        old_clip_start=10.0,
        new_clip_start=10.0 + ANNOTATION_SHIFT_EPSILON_S / 2,
        new_clip_end=30.0,
    )
    == [],
)

print("a clip that starts EARLIER pushes its notes later in the file")
# New footage is prepended, so the same moment of play now sits further into
# the clip. delta = old_start - new_start = +2.0.
shifted = shift_annotation_offsets(
    [note("a", 3.0)], old_clip_start=10.0, new_clip_start=8.0, new_clip_end=30.0
)
check("one row written", len(shifted) == 1, str(shifted))
check("offset moved by the boundary delta", shifted[0]["timestamp_seconds"] == 5.0, str(shifted))
check("not clamped", shifted[0]["clamped"] is False)
check("id carried", shifted[0]["id"] == "a")

print("a clip that starts LATER pulls its notes earlier in the file")
shifted = shift_annotation_offsets(
    [note("a", 6.0)], old_clip_start=10.0, new_clip_start=12.0, new_clip_end=30.0
)
check("offset moved by the boundary delta", shifted[0]["timestamp_seconds"] == 4.0, str(shifted))

print("a note that falls off the front is clamped and flagged, not moved silently")
# The alternative is a negative offset, which the apps coerce to 0 anyway
# (ClipDetailViewModel.addAnnotation does `coerceAtLeast(0f)`), losing the
# fact that the note no longer describes footage that exists.
shifted = shift_annotation_offsets(
    [note("a", 1.0)], old_clip_start=10.0, new_clip_start=14.0, new_clip_end=30.0
)
check("clamped to the window start", shifted[0]["timestamp_seconds"] == 0.0, str(shifted))
check("and flagged", shifted[0]["clamped"] is True)

print("a note that falls off the end is clamped to the window, not past it")
shifted = shift_annotation_offsets(
    [note("a", 18.0)], old_clip_start=10.0, new_clip_start=6.0, new_clip_end=26.0
)
# delta = +4.0 -> 22.0, but the window is only 26.0 - 6.0 = 20.0 long.
check("clamped to the window length", shifted[0]["timestamp_seconds"] == 20.0, str(shifted))
check("and flagged", shifted[0]["clamped"] is True)

print("a degenerate window does not produce a negative bound")
shifted = shift_annotation_offsets(
    [note("a", 5.0)], old_clip_start=10.0, new_clip_start=20.0, new_clip_end=20.0
)
check("clamped to zero", shifted[0]["timestamp_seconds"] == 0.0, str(shifted))

print("every note on a moved clip is re-anchored, not just the first")
shifted = shift_annotation_offsets(
    [note("a", 1.0), note("b", 2.0), note("c", 3.0)],
    old_clip_start=10.0,
    new_clip_start=9.0,
    new_clip_end=30.0,
)
check("three rows", len(shifted) == 3, str(shifted))
check(
    "each moved by the same delta",
    [row["timestamp_seconds"] for row in shifted] == [2.0, 3.0, 4.0],
    str(shifted),
)

print("an empty note list is not an error")
check(
    "no rows",
    shift_annotation_offsets([], old_clip_start=1.0, new_clip_start=2.0, new_clip_end=9.0) == [],
)

if FAILURES:
    print(f"\n{len(FAILURES)} FAILED: " + ", ".join(FAILURES))
    sys.exit(1)
print("\nall checks passed")
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd ../badminton-tracker && python backend/scripts/verify_annotation_shift.py
```

Expected: FAIL with `ImportError: cannot import name 'ANNOTATION_SHIFT_EPSILON_S' from 'modal_supabase_processor'`.

- [ ] **Step 3: Write the pure helper**

In `backend/modal_supabase_processor.py`, immediately after `pad_rally_windows` (ends around `:131`):

```python
# How far a clip boundary must move before its notes are re-anchored, in
# seconds. Below this a re-cut that agreed with the previous cut would write a
# row per note on every Phase 2 run, churning updated_at on data nobody
# touched. Above it, a note is pointing at footage it was not written against.
ANNOTATION_SHIFT_EPSILON_S = 0.05


def shift_annotation_offsets(
    annotations: List[Dict[str, Any]],
    old_clip_start: float,
    new_clip_start: float,
    new_clip_end: float,
) -> List[Dict[str, Any]]:
    """Re-anchor a clip's notes when the re-cut moves the clip's boundaries.

    `rally_annotations.timestamp_seconds` is an OFFSET INTO THE CLIP FILE, not
    a position in the source video: both apps seek straight to it (see
    ClipDetailViewModel.seekTo). The re-cut upserts on
    (video_id, rally_index), so the row id survives while the footage beneath
    it moves, and the offset has to move with it:

        new_offset = old_offset + (old_clip_start - new_clip_start)

    A note whose re-anchored offset falls outside the new window is clamped to
    the window and flagged rather than moved silently. A note pointing a second
    off is recoverable by a coach who watches it; a note pointing at footage
    that no longer exists is not, and the flag is what lets a caller say so.

    Returns one row per note whose offset ACTUALLY CHANGES, so a re-cut that
    reproduced the previous boundaries writes nothing.
    """
    delta = old_clip_start - new_clip_start
    if abs(delta) < ANNOTATION_SHIFT_EPSILON_S:
        return []

    window = max(0.0, new_clip_end - new_clip_start)
    rows: List[Dict[str, Any]] = []
    for annotation in annotations:
        old_offset = float(annotation.get("timestamp_seconds") or 0.0)
        moved = old_offset + delta
        clamped_offset = min(max(moved, 0.0), window)
        rows.append({
            "id": annotation["id"],
            "timestamp_seconds": clamped_offset,
            "clamped": clamped_offset != moved,
        })
    return rows
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd ../badminton-tracker && python backend/scripts/verify_annotation_shift.py
```

Expected: PASS, `all checks passed`.

- [ ] **Step 5: Commit the pure half**

```bash
cd ../badminton-tracker
git add backend/modal_supabase_processor.py backend/scripts/verify_annotation_shift.py
git commit -m "fix(phase2): re-anchor annotation offsets when a re-cut moves a clip

The re-cut upserts on (video_id, rally_index), so rally_clips.id survives
while start_timestamp and the stored MP4 both change, and every note's
offset into that clip silently became an offset into different footage.
Recorded as 8.5 of the mobile repo's pipeline reference and latent only
until an annotation writer shipped, which it has.

Pure rule plus its guard here; the wiring follows."
```

- [ ] **Step 6: Wire it into the re-cut**

In `cut_and_upload_rally_clips` (`:132`), read the existing bounds once before the per-rally loop:

```python
    # The bounds this re-cut is about to overwrite, read BEFORE the upsert.
    # rally_clips.id survives an upsert on (video_id, rally_index), so the
    # notes hanging off these rows outlive the footage they describe unless
    # their offsets move with it. See shift_annotation_offsets.
    previous_bounds: Dict[int, float] = {}
    try:
        existing = (
            sb.table("rally_clips")
            .select("rally_index,start_timestamp")
            .eq("video_id", video_id)
            .execute()
        ).data or []
        previous_bounds = {
            int(row["rally_index"]): float(row["start_timestamp"])
            for row in existing
            if row.get("start_timestamp") is not None
        }
    except Exception as e:
        # An unreadable previous cut is not a reason to abandon the re-cut,
        # but it IS a reason to say so: with no previous bounds every note on
        # this video keeps an offset nobody checked.
        print(f"[MODAL] previous clip bounds unreadable, notes will not be re-anchored: {e}")
```

Then immediately after the existing `rally_clips` upsert for `rally_id` (the block ending `on_conflict="video_id,rally_index"`), add:

```python
            # Re-anchor this clip's notes against its new bounds.
            old_start = previous_bounds.get(rally_id)
            if old_start is not None:
                try:
                    existing_notes = (
                        sb.table("rally_annotations")
                        .select("id,timestamp_seconds")
                        .eq("clip_id", clip_row_id_for(sb, video_id, rally_id))
                        .execute()
                    ).data or []
                    shifts = shift_annotation_offsets(
                        existing_notes,
                        old_clip_start=old_start,
                        new_clip_start=clip_start,
                        new_clip_end=clip_end,
                    )
                    clamped = 0
                    for row in shifts:
                        sb.table("rally_annotations").update(
                            {"timestamp_seconds": row["timestamp_seconds"]}
                        ).eq("id", row["id"]).execute()
                        clamped += 1 if row["clamped"] else 0
                    if shifts:
                        sb.table("processing_logs").insert({
                            "video_id": video_id,
                            "owner_id": owner_id,
                            "message": (
                                f"rally {rally_id}: re-anchored {len(shifts)} note(s) "
                                f"across a {old_start - clip_start:+.2f}s boundary move"
                                + (f", {clamped} clamped to the clip window" if clamped else "")
                            ),
                            "level": "warning" if clamped else "info",
                            "category": "processing",
                        }).execute()
                except Exception as e:
                    print(f"[MODAL] annotation re-anchor failed for rally {rally_id}: {e}")
```

And add the small lookup helper beside `shift_annotation_offsets`:

```python
def clip_row_id_for(sb, video_id: str, rally_index: int) -> Optional[str]:
    """The rally_clips.id for one rally of one video, or None.

    Separate from the upsert because the upsert's response shape has changed
    between supabase-py versions and this needs exactly one field.
    """
    rows = (
        sb.table("rally_clips")
        .select("id")
        .eq("video_id", video_id)
        .eq("rally_index", rally_index)
        .limit(1)
        .execute()
    ).data or []
    return rows[0]["id"] if rows else None
```

- [ ] **Step 7: Record the loss in the stale delete**

In `delete_stale_rally_clips` (`:322`), replace the annotation delete at `:353`:

```python
    clip_ids = [r["id"] for r in stale]
    try:
        doomed = (
            sb.table("rally_annotations").select("id").in_("clip_id", clip_ids).execute()
        ).data or []
        if doomed:
            # Said out loud rather than swallowed. These notes are a coach's
            # own writing and this is the only record that they existed.
            print(
                f"[MODAL] deleting {len(doomed)} annotation(s) with "
                f"{len(clip_ids)} stale clip(s) for video {video_id}"
            )
        sb.table("rally_annotations").delete().in_("clip_id", clip_ids).execute()
    except Exception as e:
        print(f"[MODAL] stale clip annotation cleanup failed: {e}")
```

- [ ] **Step 8: Re-run both guards**

```bash
cd ../badminton-tracker
python backend/scripts/verify_annotation_shift.py
python backend/scripts/verify_rally_bounds.py
```

Expected: both print `all checks passed`. The second is unchanged by this task and is run to prove the edit to `modal_supabase_processor.py` did not break its import or its constants.

- [ ] **Step 9: Commit**

```bash
cd ../badminton-tracker
git add backend/modal_supabase_processor.py
git commit -m "fix(phase2): wire the annotation re-anchor into the clip re-cut

Reads each clip's previous start before the upsert, shifts that clip's
notes by the boundary delta after it, and logs the move. A note that
falls outside the new window is clamped and the log says so at warning
level. delete_stale_rally_clips now counts the notes it destroys."
```

---

## Task 2: The `RAWI` wire format, written by Python

Spec §5.1. Byte-for-byte the format in `analysis/.../raw/RawInferenceCodec.kt:32`.

There is deliberately **no Python decoder**. The format has one reader and it is the Kotlin one; a Python decoder would be a second reader that could agree with a buggy Python writer and pass. This task's guard therefore checks byte-level invariants (magic, version, exact byte length from the layout formula), and Task 3 does the real check by handing Kotlin a Python-written fixture.

**Files:**
- Create: `backend/raw_inference_codec.py`
- Test: `backend/scripts/verify_raw_inference_codec.py`

**Interfaces:**
- Produces: `MAGIC: bytes`, `VERSION: int`, dataclasses `RawBox(class_id, confidence, x1, y1, x2, y2)`, `RawKeypoint(x, y, confidence)`, `RawPerson(box, keypoints)`, `RawShuttle(x, y, confidence, visible)`, `RawFrame(frame, timestamp, shuttle=None, boxes=[], persons=[])`, `RawHeader(fps, total_frames, video_width, video_height, model_version)`, and `encode(header: RawHeader, frames: list[RawFrame]) -> bytes`.

- [ ] **Step 1: Write the failing test**

Create `backend/scripts/verify_raw_inference_codec.py`:

```python
"""
Byte-level guard for the Python writer of the RawInference (RAWI) format.

Usage:
    python backend/scripts/verify_raw_inference_codec.py [--fixture PATH]

No GPU, no network. Run it after ANY change to:
    backend/raw_inference_codec.py

WHAT THIS DOES NOT DO. It does not decode its own output. The RAWI format has
exactly one reader, RawInferenceCodec.kt in badminton-rally-mobile, and a
Python decoder here would be a second reader capable of agreeing with a buggy
writer. The authoritative check is the fixture this script writes with
--fixture, which that Kotlin reader decodes in RawInferenceCodecTest.

So the checks here are the ones a decoder cannot help with: the magic, the
version, and the exact byte length computed from the layout rather than from
the encoder. A struct format string typo that widens a field shows up here.

Exits non-zero on the first failing assertion group.
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

BACKEND_DIR = str(Path(__file__).resolve().parents[1])
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from raw_inference_codec import (  # noqa: E402
    MAGIC,
    VERSION,
    RawBox,
    RawFrame,
    RawHeader,
    RawKeypoint,
    RawPerson,
    RawShuttle,
    encode,
)

FAILURES: list[str] = []


def check(label: str, cond: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    if not cond:
        FAILURES.append(label)


# Distinct, non-round values everywhere, so a field-order swap cannot pass by
# two fields happening to hold the same number. Mirrors the sample in
# RawInferenceCodecTest.kt.
HEADER = RawHeader(
    fps=59.94,
    total_frames=1234,
    video_width=1920,
    video_height=1080,
    model_version="cloud:yolo11x-pose:abc123",
)

FRAMES = [
    RawFrame(
        frame=7,
        timestamp=1.0 / 3.0,
        shuttle=None,
        boxes=[],
        persons=[
            RawPerson(
                box=RawBox(class_id=1, confidence=0.9, x1=10.0, y1=20.0, x2=30.0, y2=40.0),
                keypoints=[RawKeypoint(i * 1.5, i * 2.5, i * 0.01) for i in range(17)],
            ),
            RawPerson(
                box=RawBox(class_id=1, confidence=0.7, x1=11.0, y1=21.0, x2=31.0, y2=41.0),
                keypoints=[RawKeypoint(i * 1.25, i * 2.25, i * 0.02) for i in range(17)],
            ),
        ],
    ),
    RawFrame(frame=8, timestamp=2.0 / 3.0, shuttle=None, boxes=[], persons=[]),
]

blob = encode(HEADER, FRAMES)

print("the stream identifies itself")
check("magic", blob[:4] == MAGIC, repr(blob[:4]))
check("version is little-endian at offset 4", struct.unpack_from("<i", blob, 4)[0] == VERSION)

print("the header is laid out exactly as the Kotlin reader expects")
# magic 4, version 4, fps 8, totalFrames 4, videoWidth 4, videoHeight 4,
# then an int32 length-prefixed UTF-8 modelVersion, then an int32 frame count.
check("fps at offset 8", struct.unpack_from("<d", blob, 8)[0] == HEADER.fps)
check("totalFrames at offset 16", struct.unpack_from("<i", blob, 16)[0] == HEADER.total_frames)
check("videoWidth at offset 20", struct.unpack_from("<i", blob, 20)[0] == HEADER.video_width)
check("videoHeight at offset 24", struct.unpack_from("<i", blob, 24)[0] == HEADER.video_height)
model_len = struct.unpack_from("<i", blob, 28)[0]
check("modelVersion length", model_len == len(HEADER.model_version.encode("utf-8")))
check(
    "modelVersion bytes",
    blob[32:32 + model_len] == HEADER.model_version.encode("utf-8"),
)
check("frame count follows the string", struct.unpack_from("<i", blob, 32 + model_len)[0] == 2)

print("the total length matches the layout, computed independently of the encoder")
BOX = 4 + 4 + 4 * 4          # classId i32, confidence f32, four f32 corners
KEYPOINT = 4 + 4 + 4         # x f32, y f32, confidence f32
PERSON = BOX + 4 + 17 * KEYPOINT
header_bytes = 4 + 4 + 8 + 4 + 4 + 4 + 4 + model_len + 4
frame_fixed = 4 + 8 + 1 + 4 + 4   # frame, timestamp, shuttleFlag, boxCount, personCount
expected = header_bytes + frame_fixed + 2 * PERSON + frame_fixed
check("byte length", len(blob) == expected, f"got {len(blob)}, expected {expected}")

print("a present shuttle adds exactly its own record")
with_shuttle = encode(
    HEADER,
    [RawFrame(frame=7, timestamp=0.0,
              shuttle=RawShuttle(x=1.0, y=2.0, confidence=0.5, visible=True))],
)
without = encode(HEADER, [RawFrame(frame=7, timestamp=0.0, shuttle=None)])
# x f32, y f32, confidence f32, visible i8
check("13 bytes", len(with_shuttle) - len(without) == 13,
      f"delta {len(with_shuttle) - len(without)}")

print("a non-ASCII model version is length-prefixed in BYTES, not characters")
# The Kotlin reader does bytes(int()).decodeToString(). A length in characters
# would truncate the string and then misread every byte after it.
wide = encode(
    RawHeader(fps=30.0, total_frames=1, video_width=2, video_height=3,
              model_version="cloud:pöse"),
    [],
)
check("byte length prefix", struct.unpack_from("<i", wide, 28)[0] == len("cloud:pöse".encode("utf-8")))

print("an empty stream is well formed")
empty = encode(HEADER, [])
check("frame count zero", struct.unpack_from("<i", empty, 32 + model_len)[0] == 0)
check("nothing after it", len(empty) == header_bytes)

parser = argparse.ArgumentParser()
parser.add_argument("--fixture", help="write the two-person sample to this path for the Kotlin test")
args = parser.parse_args()
if args.fixture:
    Path(args.fixture).parent.mkdir(parents=True, exist_ok=True)
    Path(args.fixture).write_bytes(blob)
    print(f"\nfixture written: {args.fixture} ({len(blob)} bytes)")

if FAILURES:
    print(f"\n{len(FAILURES)} FAILED: " + ", ".join(FAILURES))
    sys.exit(1)
print("\nall checks passed")
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd ../badminton-tracker && python backend/scripts/verify_raw_inference_codec.py
```

Expected: FAIL with `ModuleNotFoundError: No module named 'raw_inference_codec'`.

- [ ] **Step 3: Write the encoder**

Create `backend/raw_inference_codec.py`:

```python
"""The RawInference ("RAWI") wire format, as Python writes it.

Byte for byte the format defined in badminton-rally-mobile at
analysis/src/commonMain/kotlin/com/badmintontracker/analysis/raw/
RawInferenceCodec.kt. Little-endian, fixed width, no framing.

Three writers now exist and one reader. Swift writes this on iPhone, the
Android layer writes it on device, and this module writes it in the cloud;
RawInferenceCodec.kt reads all three. That asymmetry is the point of the
format: everything with a history of drifting between implementations lives
in :analysis on the phone, written once, and the only thing that crosses a
language boundary is model output.

There is no decoder here on purpose. A second reader could agree with a buggy
writer; the check that matters is scripts/verify_raw_inference_codec.py
--fixture, whose output the Kotlin reader decodes in its own test.

Layout:
  magic        4 bytes  "RAWI"
  version      int32
  fps          float64
  totalFrames  int32
  videoWidth   int32
  videoHeight  int32
  modelVersion int32 length + UTF-8 bytes
  frameCount   int32
  per frame:   frame int32, timestamp float64, shuttleFlag int8,
               [shuttle], boxCount int32, [boxes], personCount int32,
               [persons]

Coordinates are SOURCE-VIDEO pixels, matching RawInference.kt:12-16. Anything
in model input space has to be scaled back before it reaches this module.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import List, Optional

MAGIC = b"RAWI"
VERSION = 1


@dataclass
class RawBox:
    class_id: int
    confidence: float
    x1: float
    y1: float
    x2: float
    y2: float


@dataclass
class RawKeypoint:
    x: float
    y: float
    confidence: float


@dataclass
class RawPerson:
    box: RawBox
    keypoints: List[RawKeypoint]


@dataclass
class RawShuttle:
    x: float
    y: float
    confidence: float
    visible: bool


@dataclass
class RawFrame:
    frame: int
    timestamp: float
    shuttle: Optional[RawShuttle] = None
    boxes: List[RawBox] = field(default_factory=list)
    persons: List[RawPerson] = field(default_factory=list)


@dataclass
class RawHeader:
    fps: float
    total_frames: int
    video_width: int
    video_height: int
    model_version: str


def _box(b: RawBox) -> bytes:
    return struct.pack("<ifffff", b.class_id, b.confidence, b.x1, b.y1, b.x2, b.y2)


def encode(header: RawHeader, frames: List[RawFrame]) -> bytes:
    """The whole stream as bytes.

    Built in one bytearray rather than streamed: the caller writes this to a
    file in one go, and a 30-minute match is tens of megabytes, which is well
    inside the worker's 8 GB. If that ever stops being true, the shape to
    reach for is a generator of frame records, not a partial file.
    """
    out = bytearray()
    out += MAGIC
    out += struct.pack("<i", VERSION)
    out += struct.pack("<d", header.fps)
    out += struct.pack("<iii", header.total_frames, header.video_width, header.video_height)
    model = header.model_version.encode("utf-8")
    out += struct.pack("<i", len(model))
    out += model
    out += struct.pack("<i", len(frames))
    for f in frames:
        out += struct.pack("<i", f.frame)
        out += struct.pack("<d", f.timestamp)
        if f.shuttle is None:
            out += b"\x00"
        else:
            out += b"\x01"
            out += struct.pack("<fff", f.shuttle.x, f.shuttle.y, f.shuttle.confidence)
            out += b"\x01" if f.shuttle.visible else b"\x00"
        out += struct.pack("<i", len(f.boxes))
        for b in f.boxes:
            out += _box(b)
        out += struct.pack("<i", len(f.persons))
        for p in f.persons:
            out += _box(p.box)
            out += struct.pack("<i", len(p.keypoints))
            for k in p.keypoints:
                out += struct.pack("<fff", k.x, k.y, k.confidence)
    return bytes(out)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd ../badminton-tracker && python backend/scripts/verify_raw_inference_codec.py
```

Expected: PASS, `all checks passed`.

- [ ] **Step 5: Commit**

```bash
cd ../badminton-tracker
git add backend/raw_inference_codec.py backend/scripts/verify_raw_inference_codec.py
git commit -m "feat(backend): write the RawInference wire format from Python

Phase 2 already computes per-frame pose; what it has never had is a way
to hand that to a phone. RAWI is the format the mobile repo already
defined for a cross-language producer, so Python becomes its third
writer rather than the cloud growing its own.

No decoder: one reader exists and it is the Kotlin one. The guard here
checks the layout invariants a decoder cannot help with; the real check
is the fixture the Kotlin codec test decodes."
```

---

## Task 3: The fixture that proves the two languages agree

Spec §11, "The codec, across languages". A format described in two places and checked in neither is a format that has already drifted. This task makes one reader check the other writer.

**Files:**
- Create: `analysis/src/commonTest/resources/raw/cloud-poses.rawi` (generated by Task 2's script, committed)
- Modify: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt`

**Interfaces:**
- Consumes: `encode` and the sample values from Task 2's `verify_raw_inference_codec.py`.
- Produces: nothing other code calls. This is a guard.

- [ ] **Step 1: Generate the fixture**

```bash
cd ../badminton-tracker
python backend/scripts/verify_raw_inference_codec.py \
  --fixture ../badminton-rally-mobile/analysis/src/commonTest/resources/raw/cloud-poses.rawi
```

Expected: `fixture written: .../cloud-poses.rawi (NNN bytes)`.

- [ ] **Step 2: Write the failing test**

Append to `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt`, and add `import com.badmintontracker.analysis.corpus.readResourceBytesOrNull` at the top:

```kotlin
    /**
     * The stream Python writes decodes to the values Python was given.
     *
     * This is the ONLY check that the cloud's writer and the phone's reader
     * agree. `backend/raw_inference_codec.py` has no decoder on purpose, so
     * nothing on that side can confirm its own output; the fixture is
     * regenerated by
     *   python backend/scripts/verify_raw_inference_codec.py --fixture <path>
     * and the sample values below are that script's, copied by hand. If the
     * two ever disagree, the format has drifted and a cloud analysis would
     * produce a skeleton drawn from misread floats rather than an error.
     *
     * Null on native, where readResourceBytesOrNull has no resources to read.
     * Skipped rather than failed for the reason the corpus fixtures are:
     * a guard that cannot run on a target must not stop that target's suite.
     */
    @Test
    fun the_python_writer_and_this_reader_agree() {
        val bytes = readResourceBytesOrNull("/raw/cloud-poses.rawi") ?: return

        val decoded = RawInferenceCodec.decode(bytes)

        decoded.header.version shouldBe 1
        decoded.header.fps shouldBe 59.94
        decoded.header.totalFrames shouldBe 1234
        decoded.header.videoWidth shouldBe 1920
        decoded.header.videoHeight shouldBe 1080
        decoded.header.modelVersion shouldBe "cloud:yolo11x-pose:abc123"

        decoded.frames.size shouldBe 2

        val first = decoded.frames[0]
        first.frame shouldBe 7
        first.timestamp shouldBe (1.0 / 3.0)
        first.shuttle shouldBe null
        first.boxes shouldBe emptyList()
        first.persons.size shouldBe 2

        // Both players, in the order the worker wrote them. Order is not
        // meaningful to :analysis - the side gates decide who is who - but a
        // reader that silently reordered would hide a writer that did.
        val near = first.persons[0]
        near.box shouldBe RawBox(classId = 1, confidence = 0.9f, x1 = 10f, y1 = 20f, x2 = 30f, y2 = 40f)
        near.keypoints.size shouldBe 17
        near.keypoints[0] shouldBe RawKeypoint(0f, 0f, 0f)
        near.keypoints[16] shouldBe RawKeypoint(16 * 1.5f, 16 * 2.5f, 16 * 0.01f)

        val far = first.persons[1]
        far.box.confidence shouldBe 0.7f
        far.keypoints[16] shouldBe RawKeypoint(16 * 1.25f, 16 * 2.25f, 16 * 0.02f)

        val second = decoded.frames[1]
        second.frame shouldBe 8
        second.timestamp shouldBe (2.0 / 3.0)
        second.persons shouldBe emptyList()
    }
```

- [ ] **Step 3: Run test to verify it fails**

First confirm it fails for the right reason by temporarily corrupting one byte:

```bash
cd /Users/coenhallie/Desktop/projects/badminton-rally-mobile
cp analysis/src/commonTest/resources/raw/cloud-poses.rawi /tmp/cloud-poses.bak
printf 'X' | dd of=analysis/src/commonTest/resources/raw/cloud-poses.rawi bs=1 seek=9 conv=notrunc
./gradlew :analysis:jvmTest --tests "com.badmintontracker.analysis.raw.RawInferenceCodecTest"
```

Expected: FAIL on the `fps` assertion. Then restore:

```bash
cp /tmp/cloud-poses.bak analysis/src/commonTest/resources/raw/cloud-poses.rawi
```

This is the step that proves the test can fail. A fixture test that was never seen red is a test that might be reading nothing: `readResourceBytesOrNull` returns null for a missing path and the test would pass by returning early.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :analysis:jvmTest --tests "com.badmintontracker.analysis.raw.RawInferenceCodecTest"
```

Expected: PASS, including the two pre-existing round-trip tests.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonTest/resources/raw/cloud-poses.rawi \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt
git commit -m "test(raw): the Python writer and the Kotlin reader agree

A fixture written by backend/raw_inference_codec.py, decoded here. The
Python side has no decoder on purpose, so this is the only place the
cloud's writer is checked against the phone's reader."
```

---

## Task 4: Phase 2 emits the artifact

Spec §5.2. The worker already holds everything this needs; it currently frees it and uploads a JSON blob no phone can read.

**Files:**
- Modify: `backend/modal_supabase_processor.py` (add `raw_inference_from_skeleton_frames` beside the codec import; call it in `_process_analytics_worker` before `del skeleton_frames` at the block ending `:4826`; extend the `results_meta` dict at `:4844`)
- Modify: `backend/modal_supabase_processor.py` image definition (mount `raw_inference_codec.py` the way `supabase_helpers.py` is mounted)
- Test: `backend/scripts/verify_raw_inference_codec.py` (extend)

**Interfaces:**
- Consumes: `encode`, `RawHeader`, `RawFrame`, `RawPerson`, `RawBox`, `RawKeypoint` from Task 2.
- Produces: `POSES_ARTIFACT_NAME = "poses.raw"`; `raw_inference_from_skeleton_frames(skeleton_frames, fps, total_frames, video_width, video_height, model_version) -> tuple[RawHeader, list[RawFrame]]`; `results_meta` keys `poses_artifact_path`, `poses_frame_count`, `poses_codec_version`.

- [ ] **Step 1: Write the failing test**

Append to `backend/scripts/verify_raw_inference_codec.py`, before the `--fixture` block:

```python
print("skeleton frames project onto RawFrames without losing a player")
from modal_supabase_processor import raw_inference_from_skeleton_frames  # noqa: E402

SKELETON_FRAMES = [
    {
        "frame": 1,
        "timestamp": 0.0333,
        "players": [
            {
                "player_id": 0,
                "keypoints": [
                    {"name": f"k{i}", "x": float(i), "y": float(i) * 2, "confidence": 0.5}
                    for i in range(17)
                ],
                "bbox": {"x1": 1.0, "y1": 2.0, "x2": 3.0, "y2": 4.0, "confidence": 0.8},
            },
            {
                "player_id": 1,
                "keypoints": [
                    {"name": f"k{i}", "x": float(i) + 100, "y": float(i), "confidence": 0.4}
                    for i in range(17)
                ],
                "bbox": {"x1": 5.0, "y1": 6.0, "x2": 7.0, "y2": 8.0, "confidence": 0.6},
            },
        ],
    },
    # A frame the pose model produced nothing for. It must still appear, with
    # zero persons: selectPlayers counts framesWithPose from a non-empty
    # persons list, so dropping the frame and emitting an empty one are
    # different facts about coverage.
    {"frame": 2, "timestamp": 0.0667, "players": []},
]

hdr, frames = raw_inference_from_skeleton_frames(
    SKELETON_FRAMES,
    fps=30.0,
    total_frames=2,
    video_width=1920,
    video_height=1080,
    model_version="cloud:test",
)
check("every frame kept", len(frames) == 2, f"got {len(frames)}")
check("both players on the first frame", len(frames[0].persons) == 2)
check("frame index carried", frames[0].frame == 1)
check("presentation timestamp carried, not frame/fps", frames[0].timestamp == 0.0333)
check("17 keypoints", len(frames[0].persons[0].keypoints) == 17)
check("keypoint values carried", frames[0].persons[0].keypoints[3].x == 3.0)
check("keypoint confidence carried", frames[0].persons[0].keypoints[3].confidence == 0.5)
check("box carried", frames[0].persons[0].box.x2 == 3.0)
check("box confidence carried", frames[0].persons[0].box.confidence == 0.8)
check("an empty frame survives as an empty frame", frames[1].persons == [])

print("the artifact carries no shuttle and no boxes beyond the players")
# Spec 3: rally_clips is the rally truth for a cloud video. An artifact with a
# shuttle track is an artifact something could derive rallies from, and the
# invariant is structural rather than remembered.
check("no shuttle", all(f.shuttle is None for f in frames))
check("no loose boxes", all(f.boxes == [] for f in frames))

print("a player with the wrong keypoint count is dropped, not written short")
# A short keypoint list would decode into a PlayerPose that
# NearPlayerSelector.groundPoint rejects by size, silently costing coverage
# with nothing saying why. Dropped here, where it can be counted.
partial = [{"frame": 1, "timestamp": 0.0,
            "players": [{"player_id": 0,
                         "keypoints": [{"name": "k", "x": 1.0, "y": 2.0, "confidence": 0.5}],
                         "bbox": {"x1": 0.0, "y1": 0.0, "x2": 1.0, "y2": 1.0, "confidence": 0.5}}]}]
_, short_frames = raw_inference_from_skeleton_frames(
    partial, fps=30.0, total_frames=1, video_width=100, video_height=100,
    model_version="cloud:test",
)
check("dropped", short_frames[0].persons == [], str(short_frames[0].persons))

print("a player with no bbox still contributes its keypoints")
# The box confidence only ranks candidates within a frame
# (NearPlayerSelector picks the highest boxConfidence among those that pass
# the gates). A missing box is a worse rank, not a missing player.
no_box = [{"frame": 1, "timestamp": 0.0,
           "players": [{"player_id": 0,
                        "keypoints": [{"name": f"k{i}", "x": float(i), "y": float(i),
                                       "confidence": 0.9} for i in range(17)]}]}]
_, boxless = raw_inference_from_skeleton_frames(
    no_box, fps=30.0, total_frames=1, video_width=100, video_height=100,
    model_version="cloud:test",
)
check("kept", len(boxless[0].persons) == 1)
check("zero-confidence box", boxless[0].persons[0].box.confidence == 0.0)
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd ../badminton-tracker && python backend/scripts/verify_raw_inference_codec.py
```

Expected: FAIL with `ImportError: cannot import name 'raw_inference_from_skeleton_frames'`.

- [ ] **Step 3: Write the projection**

In `backend/modal_supabase_processor.py`, beside the `supabase_helpers` import near `:35`:

```python
from raw_inference_codec import (  # noqa: E402
    RawBox,
    RawFrame,
    RawHeader,
    RawKeypoint,
    RawPerson,
    encode as encode_raw_inference,
)
```

And with the other module-level constants:

```python
# The Phase 2 pose artifact, in the results bucket beside results.json. Small
# enough for a phone to fetch, and in the format the phone's own pipeline
# already reads, so no analysis code has to exist twice.
POSES_ARTIFACT_NAME = "poses.raw"
COCO_KEYPOINT_COUNT = 17
```

Then the projection, beside `shift_annotation_offsets`:

```python
def raw_inference_from_skeleton_frames(
    skeleton_frames: List[Dict[str, Any]],
    fps: float,
    total_frames: int,
    video_width: int,
    video_height: int,
    model_version: str,
) -> Tuple[RawHeader, List[RawFrame]]:
    """Project Phase 2's skeleton frames onto the RawInference wire shape.

    A projection, not a computation. Nothing here decides which player is
    which, which side of the net anyone is on, or whether a detection is
    plausible; all of that is :analysis's on the phone, and putting any of it
    here would be the second implementation this whole design exists to avoid
    (see LocalInferenceEngine.kt:9-11).

    What it does decide is what crosses the wire. Two things, both about size
    or soundness rather than meaning:

      * only the players the identity tracker retained, which is what keeps a
        30-minute match at tens of megabytes rather than a hundred-odd with
        the crowd included;
      * no shuttle and no boxes, because rally_clips is the rally truth for a
        cloud video and an artifact carrying a shuttle track is an artifact
        something could derive a fourth rally list from.

    A player whose keypoint list is not COCO-17 is dropped rather than written
    short: the phone's ground-point gate rejects a short list by size, so a
    truncated player would cost coverage with nothing able to say why.

    The timestamp is the frame's container presentation time as the Phase 2
    loop recorded it (CAP_PROP_POS_MSEC), never frame / fps. PlayerPose's own
    KDoc is explicit that playback matches on the former and that the two
    disagree on variable-frame-rate footage.
    """
    frames: List[RawFrame] = []
    for entry in skeleton_frames:
        persons: List[RawPerson] = []
        for player in entry.get("players") or []:
            keypoints = player.get("keypoints") or []
            if len(keypoints) != COCO_KEYPOINT_COUNT:
                continue
            bbox = player.get("bbox") or {}
            persons.append(
                RawPerson(
                    box=RawBox(
                        class_id=0,
                        confidence=float(bbox.get("confidence") or 0.0),
                        x1=float(bbox.get("x1") or 0.0),
                        y1=float(bbox.get("y1") or 0.0),
                        x2=float(bbox.get("x2") or 0.0),
                        y2=float(bbox.get("y2") or 0.0),
                    ),
                    keypoints=[
                        RawKeypoint(
                            x=float(k.get("x") or 0.0),
                            y=float(k.get("y") or 0.0),
                            confidence=float(k.get("confidence") or 0.0),
                        )
                        for k in keypoints
                    ],
                )
            )
        frames.append(
            RawFrame(
                frame=int(entry.get("frame") or 0),
                timestamp=float(entry.get("timestamp") or 0.0),
                shuttle=None,
                boxes=[],
                persons=persons,
            )
        )
    header = RawHeader(
        fps=fps,
        total_frames=total_frames,
        video_width=video_width,
        video_height=video_height,
        model_version=model_version,
    )
    return header, frames
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd ../badminton-tracker && python backend/scripts/verify_raw_inference_codec.py
```

Expected: PASS, `all checks passed`.

- [ ] **Step 5: Mount the new module into the Modal image**

Find the image definition that mounts `supabase_helpers.py` as `/root/supabase_helpers.py` and add `raw_inference_codec.py` the same way. Without this the worker raises `ModuleNotFoundError` on its first Phase 2 run, in the cloud, where the failure is a `failed_phase2` status and a log line.

```bash
cd ../badminton-tracker && grep -n "supabase_helpers.py" backend/modal_supabase_processor.py
```

Add the matching entry beside it.

- [ ] **Step 6: Upload the artifact from the worker**

In `_process_analytics_worker`, inside the `try` that ends with `del skeleton_frames` (the block after the clip re-cut), and **before** that `del`:

```python
        # The pose artifact: the same frames the merged JSON carries, in the
        # format the phones read. Written before `del skeleton_frames` because
        # it is projected from them, and non-fatal because a video with clips
        # and no heatmap is a worse result than a video with neither.
        poses_path = None
        poses_frame_count = 0
        try:
            pose_header, pose_frames = raw_inference_from_skeleton_frames(
                skeleton_frames,
                fps=fps,
                total_frames=total_frames,
                video_width=video_width,
                video_height=video_height,
                model_version=f"cloud:{POSE_MODEL_NAME}:{WORKER_REVISION}",
            )
            blob = encode_raw_inference(pose_header, pose_frames)
            poses_frame_count = len(pose_frames)
            candidate = f"{owner_id_holder['owner_id']}/{video_id}/{POSES_ARTIFACT_NAME}"
            await asyncio.to_thread(
                lambda: supabase_client().storage.from_("results").upload(
                    candidate,
                    blob,
                    {"content-type": "application/octet-stream", "upsert": "true"},
                )
            )
            poses_path = candidate
            await send_log(
                f"Pose artifact uploaded: {poses_frame_count} frames, "
                f"{len(blob) / (1024 * 1024):.1f} MB",
                "success", "processing",
            )
        except Exception as pose_err:
            print(f"[MODAL] [phase2] Pose artifact upload failed: {pose_err}")
            await send_log(
                f"Pose artifact upload failed (non-fatal): {pose_err}",
                "warning", "processing",
            )
```

`POSE_MODEL_NAME` and `WORKER_REVISION`: use whatever the module already has for the pose weights filename and its deployment stamp. If no revision constant exists, add `WORKER_REVISION = os.environ.get("MODAL_IMAGE_ID", "unknown")` beside `MODELS_PATH`. The stamp only has to distinguish a cloud artifact from an on-device one and identify which worker wrote it.

- [ ] **Step 7: Record it in `results_meta`**

Extend the completed-phase `results_meta` dict at `:4844`:

```python
            "poses_artifact_path": poses_path,
            "poses_frame_count": poses_frame_count,
            "poses_codec_version": VERSION_RAW_INFERENCE,
```

with `from raw_inference_codec import VERSION as VERSION_RAW_INFERENCE` added to the import block from Step 3. A null `poses_artifact_path` is the honest value when the upload failed, and it is what the app gates on.

- [ ] **Step 8: Run both guards**

```bash
cd ../badminton-tracker
python backend/scripts/verify_raw_inference_codec.py
python backend/scripts/verify_rally_bounds.py
python backend/scripts/verify_annotation_shift.py
```

Expected: all three print `all checks passed`. The latter two are run because this task edits the module they import.

- [ ] **Step 9: Commit**

```bash
cd ../badminton-tracker
git add backend/modal_supabase_processor.py backend/scripts/verify_raw_inference_codec.py
git commit -m "feat(phase2): upload a pose artifact the phones can read

Projects the skeleton frames Phase 2 already computes onto the RAWI wire
shape and puts them in the results bucket beside results.json. Two
players, no shuttle, no boxes: rally_clips stays the rally truth for a
cloud video, and an artifact with a shuttle track is one something could
derive a fourth rally list from.

Non-fatal. A video with clips and no heatmap beats a video with neither.
results_meta records the path, the frame count and the codec version so
a list screen can gate without fetching."
```

---

## Task 5: Measure one

Spec §5.3 and §11. The design's 26 MB is arithmetic from the layout, not a measurement, and the pipeline reference's §2 says plainly that designing around an unmeasured payload is the mistake that got the web app where it is. **This task is a gate, not a formality: the download path in Task 11 is built against this number.**

**Prerequisite:** the owner has deployed the worker with Tasks 1 to 4.

**Files:**
- Modify: `docs/plans/2026-09-12-cloud-pose-artifacts-design.md` (§5.3, replacing the estimate with the measurement)

- [ ] **Step 1: Run one real match through Phase 2**

Pick a video already at `phase1_complete` with `manual_court_keypoints` set, and call `start-analytics` for it. From a shell with the owner's JWT:

```bash
curl -s -X POST "$SUPABASE_URL/functions/v1/start-analytics" \
  -H "Authorization: Bearer $SUPABASE_JWT" \
  -H "Content-Type: application/json" \
  -d '{"video_id":"'"$VIDEO_ID"'"}'
```

Expected: `202`-ish success body. Then watch `videos.status` move `processing_phase2` to `completed`, and `processing_logs` for the "Pose artifact uploaded" line, which prints the size.

- [ ] **Step 2: Record the numbers**

From the log line and the `videos` row, record: match duration, fps, `total_frames`, `poses_frame_count`, artifact size in MB, and MB per minute of footage.

- [ ] **Step 3: Update the design doc**

Replace §5.3's closing paragraph with the measurement, keeping the arithmetic above it so the two can be compared. State the measured MB per minute, because that is the number Task 11's fetch policy needs.

- [ ] **Step 4: Decide whether §3's compression row reopens**

If the measured size is materially above the estimate (say, more than half again), stop and raise it rather than proceeding: the "no compression" decision was made against 26 MB and does not survive 60. Note the outcome in the design doc either way.

- [ ] **Step 5: Commit**

```bash
git add docs/plans/2026-09-12-cloud-pose-artifacts-design.md
git commit -m "docs: measure the pose artifact rather than estimating it

5.3 priced a 30-minute match at 26 MB from the layout. This is the
number from a real one, which is what the fetch policy is built against."
```

---

## Task 6: Both players, from the same gates

Spec §7. `NearPlayerSelector`'s four gates are untouched. The only change is that the near-side filter stops being a rejection and becomes a partition.

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt` (add `CourtSide`; generalise `select` at `:126`; the `isFarSide(ground)` rejection at `:137`)
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PlayerTrack.kt` (add `PlayerSelection` and `selectPlayers`; `selectNearPlayer` at `:70` delegates)
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/SelectPlayersTest.kt`

**Interfaces:**
- Produces: `enum class CourtSide { NEAR, FAR }`; `NearPlayerSelector.select(frame: PoseFrame, side: CourtSide): Result`; `data class PlayerSelection(val side: CourtSide, val track: PlayerTrack, val poses: List<PlayerPose>)`; `fun selectPlayers(raw: RawInference, keypoints: CourtKeypoints): List<PlayerSelection>` returning exactly two entries, `NEAR` first.
- Unchanged: `selectNearPlayer(raw, keypoints): NearPlayerSelection` keeps its signature and its behaviour.

- [ ] **Step 1: Write the failing test**

Create `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/SelectPlayersTest.kt`:

```kotlin
package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * Both players out of one pass, from the gates that already decided one.
 *
 * The cloud runs a large pose model on a GPU and tracks both players well;
 * the phone runs a nano-class model and reaches 44% far-player coverage
 * against 93% near (see NearPlayerSelector's own KDoc). So this exists for
 * the cloud path, and the near half has to stay bit-identical to what the
 * device path already ships - which is what the first test here asserts.
 */
class SelectPlayersTest {

    // A court whose marks fit, borrowed from NearPlayerSelectionTest's shape:
    // a wide camera behind the near baseline, net across the middle.
    // Corpus video 743d7fb1's marks, as the cloud stored them. Lifted
    // verbatim from NearPlayerSelectionTest rather than invented: two test
    // files describing two different cameras is how a partition test passes
    // while the real one fails. The net line sits at y ~ 664, so the near
    // half is BELOW it in the frame and the far half above.
    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    /**
     * The same marks with the top two corners swapped.
     *
     * The documented failure shape: well-placed marks fit to 0.37m at worst
     * on the corpus and a mis-ordered corner fits to 3.5m, which is past
     * MAX_COURT_RESIDUAL_M of 1.0. Chosen over a degenerate all-zero set
     * because a set that cannot produce a homography at all takes a
     * different branch, and the branch that matters is the one a coach
     * reaches by clicking corners in the wrong order.
     */
    private val swappedCorners = keypoints.copy(
        topLeft = keypoints.topRight,
        topRight = keypoints.topLeft,
    )

    private fun person(ankleX: Double, ankleY: Double, confidence: Float): RawPerson {
        // A plausible figure standing at (ankleX, ankleY): shoulders and hips
        // above the ankles at a scale the court supports, so plausibleScale
        // passes rather than rejecting for a torso that is too long.
        val k = MutableList(Coco.COUNT) { RawKeypoint(ankleX.toFloat(), ankleY.toFloat(), 0.9f) }
        val torso = 60f
        k[Coco.LEFT_SHOULDER] = RawKeypoint(ankleX.toFloat() - 12f, ankleY.toFloat() - torso * 2, 0.9f)
        k[Coco.RIGHT_SHOULDER] = RawKeypoint(ankleX.toFloat() + 12f, ankleY.toFloat() - torso * 2, 0.9f)
        k[Coco.LEFT_HIP] = RawKeypoint(ankleX.toFloat() - 10f, ankleY.toFloat() - torso, 0.9f)
        k[Coco.RIGHT_HIP] = RawKeypoint(ankleX.toFloat() + 10f, ankleY.toFloat() - torso, 0.9f)
        k[Coco.LEFT_ANKLE] = RawKeypoint(ankleX.toFloat() - 8f, ankleY.toFloat(), 0.9f)
        k[Coco.RIGHT_ANKLE] = RawKeypoint(ankleX.toFloat() + 8f, ankleY.toFloat(), 0.9f)
        return RawPerson(
            box = RawBox(0, confidence, ankleX.toFloat() - 30f, ankleY.toFloat() - 200f,
                         ankleX.toFloat() + 30f, ankleY.toFloat()),
            keypoints = k,
        )
    }

    private fun inference(vararg frames: RawFrame) = RawInference(
        header = RawHeader(
            version = 1, fps = 30.0, totalFrames = frames.size,
            videoWidth = 1920, videoHeight = 1080, modelVersion = "cloud:test",
        ),
        frames = frames.toList(),
    )

    @Test
    fun the_near_half_is_exactly_what_select_near_player_returns() {
        // The whole safety argument for this change. selectNearPlayer is what
        // every device run on every phone already uses; if the partition moved
        // its output by one sample, this would be a silent regression in
        // shipped heatmaps rather than a new feature.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
            RawFrame(1, 1.0 / 30.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
        )

        val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }
        val legacy = selectNearPlayer(raw, keypoints)

        near.track shouldBe legacy.track
        near.poses shouldBe legacy.poses
    }

    @Test
    fun a_frame_with_a_player_on_each_side_fills_both_tracks() {
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        selections[0].track.samples.size shouldBe 1
        selections[1].track.samples.size shouldBe 1
        // Different people, so different court positions. Equal positions here
        // would mean one detection was handed to both sides.
        selections[0].track.samples[0].courtPosition shouldNotBe
            selections[1].track.samples[0].courtPosition
    }

    @Test
    fun the_poses_belong_to_the_player_whose_track_they_sit_beside() {
        // The invariant NearPlayerSelection.kt states for one player, now for
        // two: the heatmap's position and the skeleton's joints are the same
        // person in the same frame. Crossed sides here would draw the far
        // player's skeleton over the near player's heatmap.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.forEach { it.poses.size shouldBe it.track.samples.size }
        // The near player's ankles are lower in the frame (larger y) than the
        // far player's, because the camera sits behind the near baseline.
        val nearAnkleY = selections[0].poses[0].keypoints[Coco.LEFT_ANKLE].y
        val farAnkleY = selections[1].poses[0].keypoints[Coco.LEFT_ANKLE].y
        (nearAnkleY > farAnkleY) shouldBe true
    }

    @Test
    fun a_side_with_nobody_on_it_gets_an_empty_track_not_a_missing_one() {
        // Empty rather than absent, for LocalAnalysisOutcome's reason: a match
        // played on one half and a match where the far player was never found
        // are different facts, and PlayerTrack already distinguishes them
        // through framesWithPose and its rejection counts.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer())))

        val selections = selectPlayers(raw, keypoints)

        selections.size shouldBe 2
        selections[1].side shouldBe CourtSide.FAR
        selections[1].track.samples shouldBe emptyList()
        selections[1].track.framesWithPose shouldBe 1
        selections[1].track.rejections[RejectionReason.WRONG_SIDE] shouldBe 1
    }

    @Test
    fun frames_the_model_never_ran_on_dilute_neither_side() {
        // framesWithPose is the denominator of coverage, and a frame with no
        // detections at all is not a failure to find anybody.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), emptyList()),
            RawFrame(1, 1.0 / 30.0, null, emptyList(), listOf(nearPlayer(), farPlayer())),
        )

        selectPlayers(raw, keypoints).forEach { it.track.framesWithPose shouldBe 1 }
    }

    @Test
    fun unusable_marks_produce_two_empty_tracks_and_say_why() {
        // Not one empty track and one absent: the marks are about the court,
        // not about either player, so the failure applies to both equally.
        val raw = inference(RawFrame(0, 0.0, null, emptyList(), listOf(nearPlayer(), farPlayer())))

        val selections = selectPlayers(raw, swappedCorners)

        selections.size shouldBe 2
        selections.forEach {
            it.track.samples shouldBe emptyList()
            it.track.rejections[RejectionReason.BAD_COURT] shouldBe 1
        }
    }

    @Test
    fun the_higher_confidence_detection_wins_within_one_side() {
        // Unchanged from the near-only selector, and worth pinning: two
        // detections on one side is the ordinary case when the model finds a
        // player and a line judge behind them.
        val raw = inference(
            RawFrame(0, 0.0, null, emptyList(), listOf(
                person(NEAR_X, NEAR_Y, confidence = 0.4f),
                person(NEAR_X + 40, NEAR_Y, confidence = 0.95f),
            )),
        )

        val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }

        near.track.samples.size shouldBe 1
        near.poses[0].keypoints[Coco.LEFT_ANKLE].x shouldBe (NEAR_X + 40 - 8)
    }

    private fun nearPlayer() = person(NEAR_X, NEAR_Y, confidence = 0.9f)
    private fun farPlayer() = person(FAR_X, FAR_Y, confidence = 0.8f)

    private companion object {
        // Pixel positions either side of the marked net line. Chosen from the
        // same geometry NearPlayerSelectionTest uses, so both files describe
        // one camera rather than two imagined ones.
        const val NEAR_X = 960.0
        const val NEAR_Y = 900.0
        const val FAR_X = 960.0
        const val FAR_Y = 420.0
    }
}
```

The marks above are `NearPlayerSelectionTest`'s, copied rather than shared: two small test files each stating the camera they describe read better than one indirection, and the values are pinned by a comment naming the corpus video they came from. Add `import com.badmintontracker.analysis.geometry.CourtKeypoints` and `import com.badmintontracker.analysis.geometry.Point`.

Confirm `swappedCorners` actually produces `BAD_COURT` before relying on it: if the swap happens to fit within `MAX_COURT_RESIDUAL_M` (1.0m), the last test passes for the wrong reason. Print `NearPlayerSelector(swappedCorners, 1920.0, 1080.0).courtFitResidualM` once and check it exceeds 1.0.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :analysis:jvmTest --tests "com.badmintontracker.analysis.player.SelectPlayersTest"
```

Expected: FAIL to compile, `Unresolved reference: CourtSide` and `Unresolved reference: selectPlayers`.

- [ ] **Step 3: Add `CourtSide` and generalise the selector**

In `NearPlayer.kt`, above `PosePerson`:

```kotlin
/**
 * Which half of the court a player stands on.
 *
 * Decided by the marked net line, never by a pixel midline: on an angled
 * camera a midline misclassifies play at the net, and the net is already
 * marked by hand. See [NearPlayerSelector.isFarSide].
 *
 * NEAR is the half the camera sits behind. It is the only half a phone can
 * track reliably - nano reaches 44% coverage on the far player against 93%
 * near - so a device run fills NEAR and leaves FAR empty, while a cloud run
 * on a large model fills both.
 */
enum class CourtSide { NEAR, FAR }
```

Replace `fun select(frame: PoseFrame): Result {` at `:126` with:

```kotlin
    /** The near player, which is what every caller wanted before there were two. */
    fun select(frame: PoseFrame): Result = select(frame, CourtSide.NEAR)

    /**
     * The best candidate on [side], or the furthest gate anyone on that side
     * reached.
     *
     * Every gate is the same for both sides. Only the side test changed, from
     * a rejection of the far half to a partition between the two, so a track
     * taken for NEAR here is the same track this returned before FAR existed.
     */
    fun select(frame: PoseFrame, side: CourtSide): Result {
```

and inside it replace the far-side rejection at `:137`:

```kotlin
            if (sideOf(ground) != side) {
                reason = worse(reason, RejectionReason.WRONG_SIDE)
                continue
            }
```

Then beside `isFarSide` at `:251`:

```kotlin
    private fun sideOf(point: Point): CourtSide =
        if (isFarSide(point)) CourtSide.FAR else CourtSide.NEAR
```

- [ ] **Step 4: Add `selectPlayers` and delegate `selectNearPlayer`**

In `PlayerTrack.kt`, replace the body of `selectNearPlayer` (`:70`) and add above it:

```kotlin
/**
 * One player's whole story from a run: which half they were on, where they
 * stood, and their joints.
 *
 * [poses] holds one entry per entry in `track.samples`, at the same frame, for
 * the reason [NearPlayerSelection] states: the heatmap's position and the
 * skeleton's joints are the same person in the same frame, and returning them
 * together is what makes that true.
 */
data class PlayerSelection(
    val side: CourtSide,
    val track: PlayerTrack,
    val poses: List<PlayerPose>,
)

/**
 * Both players' tracks and poses from one pass over the frames.
 *
 * Always two entries, NEAR then FAR, even when a side was never occupied: an
 * empty track and an absent one are different facts, and [PlayerTrack] already
 * tells them apart through framesWithPose and its rejection counts. A caller
 * that wants only the near player should call [selectNearPlayer], which is
 * this function's near half and nothing else.
 *
 * One pass, not two: a second pass over a 54,000-frame stream to find the
 * other player would double the work and, worse, would let the two halves
 * disagree about framesWithPose if the loop ever grew a condition.
 */
fun selectPlayers(raw: RawInference, keypoints: CourtKeypoints): List<PlayerSelection> {
    val selector = NearPlayerSelector(
        keypoints,
        raw.header.videoWidth.toDouble(),
        raw.header.videoHeight.toDouble(),
    )
    // An unusable court is not a thin track, it is no track, and it is a fact
    // about the marks rather than about either player - so both sides carry it.
    if (!selector.usable) {
        return CourtSide.entries.map { side ->
            PlayerSelection(
                side,
                PlayerTrack(emptyList(), 0, mapOf(RejectionReason.BAD_COURT to raw.frames.size)),
                emptyList(),
            )
        }
    }

    val samples = CourtSide.entries.associateWith { ArrayList<PlayerSample>() }
    val poses = CourtSide.entries.associateWith { ArrayList<PlayerPose>() }
    val rejections = CourtSide.entries.associateWith { mutableMapOf<RejectionReason, Int>() }
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a
        // player, so they must not dilute coverage on either side.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val poseFrame = PoseFrame(frame.frame, frame.persons.map { it.toPosePerson() })
        for (side in CourtSide.entries) {
            val result = selector.select(poseFrame, side)
            val sample = result.sample
            val person = result.person
            if (sample != null && person != null) {
                samples.getValue(side).add(sample)
                poses.getValue(side).add(
                    PlayerPose(frame.frame, frame.timestamp, person.keypoints, person.keypointConfidence),
                )
            } else {
                result.rejection?.let { r ->
                    val counts = rejections.getValue(side)
                    counts[r] = (counts[r] ?: 0) + 1
                }
            }
        }
    }

    return CourtSide.entries.map { side ->
        PlayerSelection(
            side,
            PlayerTrack(samples.getValue(side), framesWithPose, rejections.getValue(side)),
            poses.getValue(side),
        )
    }
}

/** [buildNearPlayerTrack], keeping the chosen person's joints as well. */
fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection {
    // The near half of [selectPlayers], so the two can never disagree about
    // the player every device run on every phone already has stored.
    val near = selectPlayers(raw, keypoints).first { it.side == CourtSide.NEAR }
    return NearPlayerSelection(near.track, near.poses)
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :analysis:jvmTest
```

Expected: PASS. `SelectPlayersTest` is new; `NearPlayerSelectionTest`, `PlayerTrackTest`, `NearPlayerSelectorTest` and `DevicePoseDumpTest` are the regression check on the near half and must be green unchanged. If any of them moved, stop: the partition changed shipped behaviour and that is not this task.

- [ ] **Step 6: Run the full suite on both targets**

```bash
./gradlew :shared:jvmTest :analysis:jvmTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt \
        analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PlayerTrack.kt \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/SelectPlayersTest.kt
git commit -m "feat(analysis): select both players from the same gates

The far side stops being a rejection and becomes a partition. Every gate
is untouched: the ankle-only ground point, the torso scale bound, the
court residual and the ordering all apply to both halves, and
selectNearPlayer is now literally the near half of selectPlayers so the
two cannot drift.

Only the cloud will fill both. Nano reaches 44% far-player coverage
against 93% near, which is why the device path has always taken one."
```

---

## Task 7: The Android stores carry more than one track

Spec §7. **Read the spec's §7 before starting.** The two stores are not symmetric about versions today and a change that treats them as symmetric deletes every heatmap on every phone.

`PlayerTrackStore.has` (`:61`) and `load` (`:72`) compare the header against a single `VERSION` for exact equality. `SkeletonStore.has` (`:99`) already accepts v1 or v2. So this task **adds** a multi-track version that the readers accept alongside what they accept now; it does not bump anything.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/PlayerTrackStore.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt` (`:119`, `:125`, `:133`, `:136`, and the save sites at `:223` and `:237`)
- Test: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/PlayerTrackStoreTest.kt`, `SkeletonStoreTest.kt`

**Interfaces:**
- Consumes: `CourtSide`, `PlayerSelection` from Task 6.
- Produces:
  - `enum class TrackSource(val trackDir: String, val skeletonDir: String)` with `LOCAL("player-tracks", "skeletons")` and `CLOUD("cloud-tracks", "cloud-skeletons")`.
  - `PlayerTrackStore.SideTrack(side: CourtSide, track: PlayerTrack)`; `PlayerTrackStore.Stored(tracks: List<SideTrack>, fps: Double)` with `val near: PlayerTrack?`.
  - `PlayerTrackStore.save(entryId, track, fps)` unchanged (writes `v2` under `LOCAL`); `saveAll(entryId, source, selections: List<PlayerSelection>, fps)` writes `v3`.
  - `PlayerTrackStore.has(entryId, source = TrackSource.LOCAL)`, `load(entryId, source = TrackSource.LOCAL)`.
  - `SkeletonStore.SidePoses(side: CourtSide, poses: List<PlayerPose>)`; `SkeletonStore.Stored(tracks: List<SidePoses>, fps, videoWidth, videoHeight, marks)` with `val poses: List<PlayerPose>` returning the near list.
  - `SkeletonStore.saveAll(entryId, source, selections, fps, videoWidth, videoHeight, marks)` writes `SKEL` v3.
  - `LocalAnalysisRunner.storedTrack(entryId)` and `storedSkeleton(entryId)` resolve **cloud first, then local**.

- [ ] **Step 1: Write the failing test for the track store**

Append to `androidApp/src/test/java/com/badmintontracker/android/localanalysis/PlayerTrackStoreTest.kt`:

```kotlin
    @Test
    fun `a v2 file written by the previous build still loads`() {
        // The regression this whole task is shaped around. has() and load()
        // compared the header against ONE version string for exact equality,
        // so a naive bump would have refused every track already on every
        // phone, and the store's own KDoc names the recovery: a re-run that
        // costs half an hour.
        //
        // Written as bytes rather than through save(), deliberately. A fixture
        // produced by the new writer would pass this test even if the new
        // writer had stopped writing v2 entirely, which is exactly the bug.
        val store = PlayerTrackStore(root)
        File(root, "player-tracks").mkdirs()
        File(root, "player-tracks/e1.track").writeText(
            "v2 29.97 120\n" +
                "0,1.5,2.5\n" +
                "1,1.6,2.6\n",
        )

        store.has("e1") shouldBe true
        val stored = store.load("e1")!!
        stored.fps shouldBe 29.97
        stored.tracks.size shouldBe 1
        stored.tracks[0].side shouldBe CourtSide.NEAR
        stored.tracks[0].track.framesWithPose shouldBe 120
        stored.tracks[0].track.samples.size shouldBe 2
        stored.near!!.samples[1].courtPosition shouldBe Point(1.6, 2.6)
    }

    @Test
    fun `a v1 file is still refused`() {
        // Unchanged and deliberate. v1 tracks were built on a homography that
        // could be metres off, so they are wrong rather than merely old, and
        // the reason the refusal exists does not reach v2.
        val store = PlayerTrackStore(root)
        File(root, "player-tracks").mkdirs()
        File(root, "player-tracks/e1.track").writeText("v1 30.0 10\n0,1.0,2.0,1\n")

        store.has("e1") shouldBe false
        store.load("e1") shouldBe null
    }

    @Test
    fun `two tracks round trip through v3`() {
        val store = PlayerTrackStore(root)
        val near = PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 10, emptyMap())
        val far = PlayerTrack(listOf(PlayerSample(0, Point(5.0, 11.0))), 10, emptyMap())

        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, near, emptyList()),
                PlayerSelection(CourtSide.FAR, far, emptyList()),
            ),
            fps = 30.0,
        )

        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].track shouldBe near
        stored.tracks[1].track shouldBe far
        stored.fps shouldBe 30.0
    }

    @Test
    fun `the cloud and local subtrees do not see each other`() {
        // Separate directories rather than a flag in one file, so
        // skeletonAction's "a completed run is the new truth for its entry"
        // cannot reach a cloud artifact. A device run that asked for rallies
        // only must not be able to delete a cloud heatmap.
        val store = PlayerTrackStore(root)
        store.save("e1", PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 5, emptyMap()), 30.0)

        store.has("e1", TrackSource.LOCAL) shouldBe true
        store.has("e1", TrackSource.CLOUD) shouldBe false
    }

    @Test
    fun `a side with no samples still round trips as an empty track`() {
        // A match played on one half of the court is a real thing, and an
        // empty far track says so. Dropping it would make "nobody was there"
        // indistinguishable from "this file predates the far player".
        val store = PlayerTrackStore(root)
        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 10, emptyMap()), emptyList()),
                PlayerSelection(CourtSide.FAR, PlayerTrack(emptyList(), 10, emptyMap()), emptyList()),
            ),
            fps = 30.0,
        )

        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.tracks.size shouldBe 2
        stored.tracks[1].track.samples shouldBe emptyList()
        stored.tracks[1].track.framesWithPose shouldBe 10
    }
```

Add the imports the new tests need: `com.badmintontracker.analysis.player.CourtSide`, `PlayerSelection`, `PlayerSample`, `PlayerTrack`, `com.badmintontracker.analysis.geometry.Point`, `java.io.File`.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "*PlayerTrackStoreTest*"
```

Expected: FAIL to compile, `Unresolved reference: TrackSource` / `saveAll`.

- [ ] **Step 3: Implement the track store**

In `PlayerTrackStore.kt`, add above the class:

```kotlin
/**
 * Which analysis produced an artifact: this phone's, or the cloud's.
 *
 * Separate directories rather than a field inside one file. skeletonAction
 * holds that "a completed run is the new truth for its entry", which is right
 * for a device run and wrong across the two sources: a rally-only run would
 * otherwise delete a cloud skeleton the coach waited on a GPU for. Two
 * subtrees make that impossible rather than merely discouraged.
 *
 * LOCAL's directories are the ones already on every phone, unchanged, because
 * moving them would orphan every artifact a device run has ever written.
 */
enum class TrackSource(val trackDir: String, val skeletonDir: String) {
    LOCAL("player-tracks", "skeletons"),
    CLOUD("cloud-tracks", "cloud-skeletons"),
}
```

Then, inside the class:

```kotlin
    /** One player's path, and which half of the court they were on. */
    data class SideTrack(val side: CourtSide, val track: PlayerTrack)

    data class Stored(val tracks: List<SideTrack>, val fps: Double) {
        /**
         * The near player, or null when the file holds only a far one.
         *
         * Every caller that predates the far player wants this, and reading
         * `tracks[0]` at each of them would break silently the first time a
         * file arrived far-first.
         */
        val near: PlayerTrack? get() = tracks.firstOrNull { it.side == CourtSide.NEAR }?.track
    }

    /**
     * One near-player track, in the v2 format.
     *
     * Still what a device run writes, and deliberately. Only the cloud has two
     * usable players, so a multi-track local file is a file nobody writes and
     * every other build would refuse.
     */
    fun save(entryId: String, track: PlayerTrack, fps: Double) { /* body unchanged */ }

    /**
     * Every track from one analysis, in the v3 format.
     *
     * v3 is a SECOND format the readers accept, not a replacement for v2. See
     * [has] for what changing VERSION would have cost.
     *
     *   v3 <fps> <trackCount>
     *   <side> <framesWithPose> <sampleCount>
     *   <frame>,<x>,<y>   x sampleCount
     *   ...repeated per track
     */
    fun saveAll(
        entryId: String,
        source: TrackSource,
        selections: List<PlayerSelection>,
        fps: Double,
    ) {
        // Nothing to draw is nothing to offer, matching save(): no file, so
        // has() stays false rather than answering true for an empty court.
        if (selections.none { it.track.samples.isNotEmpty() }) return
        val file = fileFor(entryId, source).apply { parentFile?.mkdirs() }
        val text = buildString {
            append(VERSION_V3).append(' ').append(fps).append(' ').append(selections.size).append('\n')
            selections.forEach { selection ->
                append(selection.side.name).append(' ')
                    .append(selection.track.framesWithPose).append(' ')
                    .append(selection.track.samples.size).append('\n')
                selection.track.samples.forEach {
                    append(it.frame).append(',')
                        .append(it.courtPosition.x).append(',')
                        .append(it.courtPosition.y).append('\n')
                }
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Failed to rename ${tmp.absolutePath} to ${file.absolutePath}")
        }
    }
```

Replace `has` and `load` with versions that accept either format:

```kotlin
    fun has(entryId: String, source: TrackSource = TrackSource.LOCAL): Boolean {
        val file = fileFor(entryId, source)
        if (!file.isFile) return false
        return runCatching {
            file.bufferedReader().use { it.readLine() }?.split(' ')?.firstOrNull() in READABLE_VERSIONS
        }.getOrDefault(false)
    }

    fun load(entryId: String, source: TrackSource = TrackSource.LOCAL): Stored? {
        val file = fileFor(entryId, source)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            val header = lines.firstOrNull()?.split(' ') ?: return null
            when (header.getOrNull(0)) {
                VERSION -> loadV2(header, lines)
                VERSION_V3 -> loadV3(header, lines)
                else -> null
            }
        }.getOrNull()
    }

    /** The single-track format: one header line, then every sample. */
    private fun loadV2(header: List<String>, lines: List<String>): Stored {
        val fps = header[1].toDouble()
        val framesWithPose = header[2].toInt()
        val samples = lines.drop(1).mapNotNull(::parseSample)
        // Rejections are not stored: they explain a thin track while it is
        // being produced, and the count that matters afterwards, coverage, is
        // recoverable from the samples.
        return Stored(
            listOf(SideTrack(CourtSide.NEAR, PlayerTrack(samples, framesWithPose, emptyMap()))),
            fps,
        )
    }

    /** The multi-track format: a header line, then a section per track. */
    private fun loadV3(header: List<String>, lines: List<String>): Stored? {
        val fps = header[1].toDouble()
        val trackCount = header[2].toInt()
        val tracks = ArrayList<SideTrack>(trackCount)
        var at = 1
        repeat(trackCount) {
            val section = lines.getOrNull(at)?.split(' ') ?: return null
            val side = CourtSide.entries.firstOrNull { it.name == section.getOrNull(0) } ?: return null
            val framesWithPose = section[1].toInt()
            val sampleCount = section[2].toInt()
            // Bounds-checked against the file's own length before the slice,
            // so a truncated file returns null rather than throwing out of a
            // runCatching that would swallow it as "no track".
            if (at + 1 + sampleCount > lines.size) return null
            val samples = lines.subList(at + 1, at + 1 + sampleCount).mapNotNull(::parseSample)
            tracks.add(SideTrack(side, PlayerTrack(samples, framesWithPose, emptyMap())))
            at += 1 + sampleCount
        }
        return Stored(tracks, fps)
    }

    private fun parseSample(line: String): PlayerSample? {
        if (line.isBlank()) return null
        val f = line.split(',')
        return PlayerSample(f[0].toInt(), Point(f[1].toDouble(), f[2].toDouble()))
    }

    private fun fileFor(entryId: String, source: TrackSource = TrackSource.LOCAL) =
        File(root, "${source.trackDir}/$entryId.track")
```

and in the companion:

```kotlin
        const val VERSION = "v2"

        /**
         * The multi-track format, ADDED alongside v2 rather than replacing it.
         *
         * Both [has] and [load] compared the header against VERSION for exact
         * equality, so changing that constant would have refused every file
         * already written by every previous build. v1 stays refused for its
         * own reason (see [has]); v2 is correct, merely single-track.
         */
        const val VERSION_V3 = "v3"
        val READABLE_VERSIONS = setOf(VERSION, VERSION_V3)
```

- [ ] **Step 4: Run the track store tests**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "*PlayerTrackStoreTest*"
```

Expected: PASS, including every pre-existing test in the file.

- [ ] **Step 5: Write the failing test for the skeleton store**

Append to `androidApp/src/test/java/com/badmintontracker/android/localanalysis/SkeletonStoreTest.kt`:

```kotlin
    @Test
    fun `a v2 file written by the previous build still loads`() {
        // SkeletonStore already read v1 and v2 from the header alone, unlike
        // PlayerTrackStore. Pinned anyway: the two stores start from
        // different places and a change made to both at once is exactly when
        // that asymmetry gets flattened.
        val store = SkeletonStore(root)
        store.save("e1", listOf(pose(frame = 0, t = 0.0)), fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = null)

        store.has("e1") shouldBe true
        val stored = store.load("e1")!!
        stored.tracks.size shouldBe 1
        stored.tracks[0].side shouldBe CourtSide.NEAR
        stored.poses.size shouldBe 1
    }

    @Test
    fun `two players round trip through v3`() {
        val store = SkeletonStore(root)
        val near = listOf(pose(frame = 0, t = 0.0), pose(frame = 1, t = 0.033))
        val far = listOf(pose(frame = 0, t = 0.0))

        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, emptyTrack(), near),
                PlayerSelection(CourtSide.FAR, emptyTrack(), far),
            ),
            fps = 59.94, videoWidth = 1920, videoHeight = 1080, marks = null,
        )

        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.fps shouldBe 59.94
        stored.videoWidth shouldBe 1920
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].poses shouldBe near
        stored.tracks[1].poses shouldBe far
        // The near list, for every caller that predates the far player.
        stored.poses shouldBe near
    }

    @Test
    fun `the court marks travel with a v3 file too`() {
        // A reading in metres needs the homography those marks fit, and the
        // local entry that holds them can be deleted while this file lives
        // on. That reasoning does not change because there are two players.
        val store = SkeletonStore(root)
        val marks = someKeypoints()

        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(pose(0, 0.0)))),
            fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = marks,
        )

        store.load("e1", TrackSource.CLOUD)!!.marks shouldBe marks
    }

    @Test
    fun `a deleted local skeleton leaves the cloud one alone`() {
        // The concrete consequence of the two subtrees. skeletonAction
        // returns DELETE for a run that asked for rallies only, and that run
        // must not be able to reach a cloud artifact.
        val store = SkeletonStore(root)
        store.save("e1", listOf(pose(0, 0.0)), 30.0, 1920, 1080, null)
        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(pose(0, 0.0)))),
            fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = null,
        )

        store.delete("e1")

        store.has("e1", TrackSource.LOCAL) shouldBe false
        store.has("e1", TrackSource.CLOUD) shouldBe true
    }
```

Reuse the file's existing `pose(...)`, `someKeypoints()` and `root` helpers; add `emptyTrack()` returning `PlayerTrack(emptyList(), 0, emptyMap())` if the file has none.

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "*SkeletonStoreTest*"
```

Expected: FAIL to compile, `Unresolved reference: saveAll` / `tracks`.

- [ ] **Step 7: Implement the skeleton store**

In `SkeletonStore.kt`, extend the header documentation with the v3 layout and change `Stored`:

```kotlin
    /** One player's joints, and which half of the court they were on. */
    data class SidePoses(val side: CourtSide, val poses: List<PlayerPose>)

    data class Stored(
        val tracks: List<SidePoses>,
        val fps: Double,
        val videoWidth: Int,
        val videoHeight: Int,
        val marks: CourtKeypoints?,
    ) {
        /** The near player's joints, which is what every caller wanted before there were two. */
        val poses: List<PlayerPose>
            get() = tracks.firstOrNull { it.side == CourtSide.NEAR }?.poses ?: emptyList()
    }
```

The v3 layout, documented at the top of the class beside the v1/v2 one:

```
 *   v3: magic "SKEL", version i32 = 3, fps f64, videoWidth i32, videoHeight i32,
 *   hasMarks i32, 12 x (x f64, y f64), trackCount i32, then per track:
 *     side i32 (CourtSide.ordinal), poseCount i32, then poses as in v2.
```

`save` keeps writing version 2 and is otherwise unchanged. Add:

```kotlin
    /**
     * Every player's joints from one analysis, in the v3 format.
     *
     * v3 is a THIRD version this store reads, not a replacement: v1 and v2
     * files already on phones keep loading, and a device run keeps writing v2
     * because it only ever has the near player.
     */
    fun saveAll(
        entryId: String,
        source: TrackSource,
        selections: List<PlayerSelection>,
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: CourtKeypoints?,
    ) {
        val withPoses = selections.filter { it.poses.isNotEmpty() }
        // Nothing to draw is nothing to offer, matching save().
        if (withPoses.isEmpty()) return
        val poseCount = withPoses.sumOf { it.poses.size }
        val bytes = HEADER_BYTES_V3 + withPoses.size * TRACK_HEADER_BYTES_V3 + poseCount * POSE_BYTES
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION_V3).putDouble(fps).putInt(videoWidth).putInt(videoHeight)
        buffer.putInt(if (marks != null) 1 else 0)
        val pixels = marks?.pixels()
        repeat(12) { i ->
            val point = pixels?.get(i)
            buffer.putDouble(point?.x ?: 0.0).putDouble(point?.y ?: 0.0)
        }
        buffer.putInt(withPoses.size)
        withPoses.forEach { selection ->
            buffer.putInt(selection.side.ordinal).putInt(selection.poses.size)
            selection.poses.forEach { pose -> buffer.putPose(pose) }
        }
        writeAtomically(fileFor(entryId, source), buffer.array())
    }
```

Extract the per-pose write from `save` into `ByteBuffer.putPose(pose: PlayerPose)` carrying the existing `require(pose.keypoints.size == Coco.COUNT)` check verbatim, and the temp-file-then-rename from `save` into `writeAtomically(file, bytes)`. Both stay exactly as they are; this is so v2 and v3 cannot diverge on the pose record or on atomicity.

`has` accepts `1`, `2` or `VERSION_V3`; `load` dispatches on the version to a `loadV2` (existing body, wrapped into a single `SidePoses(CourtSide.NEAR, poses)`) and a new `loadV3`. `loadV3` must keep v2's length discipline: reject a negative or implausible `trackCount` and `poseCount` before allocating or slicing, so a truncated file returns null rather than a plausible short one.

`fileFor(entryId, source = TrackSource.LOCAL) = File(root, "${source.skeletonDir}/$entryId.skel")`, and `has`/`load`/`delete` all take the same defaulted `source` parameter.

Companion additions:

```kotlin
        const val VERSION_V3 = 3
        const val HEADER_BYTES_V3 = 4 + 4 + 8 + 4 + 4 + 4 + MARKS_BYTES + 4
        const val TRACK_HEADER_BYTES_V3 = 4 + 4
```

- [ ] **Step 8: Resolve cloud over local in the runner**

In `LocalAnalysisRunner.kt`, replace the four accessors at `:119`, `:125`, `:133`, `:136`:

```kotlin
    /**
     * The track to draw for this entry: the cloud's if there is one, else this
     * phone's.
     *
     * Cloud wins, and silently. It comes from a large model on a GPU and
     * carries both players, so it is strictly the better track; a picker
     * between the two would be a third selector on a screen that already has
     * panel tabs and a player toggle. See the design's 3 and 10.
     */
    fun storedTrack(entryId: String): PlayerTrackStore.Stored? =
        tracks.load(entryId, TrackSource.CLOUD) ?: tracks.load(entryId, TrackSource.LOCAL)

    fun hasStoredTrack(entryId: String): Boolean =
        tracks.has(entryId, TrackSource.CLOUD) || tracks.has(entryId, TrackSource.LOCAL)

    /** The cloud's skeleton if there is one, else this phone's. See [storedTrack]. */
    fun storedSkeleton(entryId: String): SkeletonStore.Stored? =
        skeletons.load(entryId, TrackSource.CLOUD) ?: skeletons.load(entryId, TrackSource.LOCAL)

    fun hasStoredSkeleton(entryId: String): Boolean =
        skeletons.has(entryId, TrackSource.CLOUD) || skeletons.has(entryId, TrackSource.LOCAL)
```

The save sites at `:223` and `:237` are untouched: a device run keeps calling `save`/`delete` with the default `LOCAL` source, and `skeletonAction` keeps governing that subtree alone.

- [ ] **Step 9: Run the Android suite**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: PASS, including `HeatmapSourceTest`, which reads `Stored` and will need its construction updated to the new shape. Update its fixtures; do not change what it asserts.

- [ ] **Step 10: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/ \
        androidApp/src/test/java/com/badmintontracker/android/localanalysis/
git commit -m "feat(android): the stores carry both players, and a cloud subtree

A multi-track v3 ADDED alongside v2, not replacing it. PlayerTrackStore
compared its header against one version for exact equality in both has()
and load(), so a bump would have refused every track file already on
every phone and the documented recovery is a half-hour re-run.

Cloud artifacts live in their own directories so skeletonAction, which
holds that a completed run is the new truth for its entry, cannot reach
them. The runner resolves cloud first, then local."
```

---

## Task 8: The iOS stores, matching

Spec §7. The iOS port of Task 7. `iosApp/Sources/LocalAnalysis/PlayerTrackStore.swift:28` holds `version = "v2"` and `:62` compares against it for exact equality, exactly as Android did; `SkeletonStore.swift:43` holds `version: Int32 = 2`.

**Files:**
- Modify: `iosApp/Sources/LocalAnalysis/PlayerTrackStore.swift`
- Modify: `iosApp/Sources/LocalAnalysis/SkeletonStore.swift`
- Modify: `iosApp/Sources/LocalAnalysis/LocalAnalysisRunner.swift` (`:233`, `:237`, `:242`, `:246`)
- Test: `iosApp/Tests/AnalysisStoreTests.swift`

**Interfaces:**
- Produces: the Swift mirrors of Task 7's API. `TrackSource` is a Swift enum with the same two cases and the same directory names, declared in `PlayerTrackStore.swift`; `Stored` gains `tracks` and a `near` / `poses` convenience; `saveAll(entryId:source:selections:fps:...)`; `has(entryId:source:)` and `load(entryId:source:)` defaulting to `.local`.

- [ ] **Step 1: Write the failing tests**

Append to `iosApp/Tests/AnalysisStoreTests.swift`, mirroring Task 7's Android test names one for one:

```swift
    func testAV2FileWrittenByThePreviousBuildStillLoads() throws {
        // Same regression as Android's. version was compared for exact
        // equality, so a bump refuses every track already on every phone.
        // Written as text rather than through save(), so a writer that
        // stopped emitting v2 cannot make this pass.
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("player-tracks")
        try "v2 29.97 120\n0,1.5,2.5\n1,1.6,2.6\n"
            .write(to: directory.appendingPathComponent("e1.track"), atomically: true, encoding: .utf8)

        XCTAssertTrue(store.has(entryId: "e1"))
        let stored = try XCTUnwrap(store.load(entryId: "e1"))
        XCTAssertEqual(stored.fps, 29.97)
        XCTAssertEqual(stored.tracks.count, 1)
        XCTAssertEqual(stored.tracks[0].side, .near)
        XCTAssertEqual(stored.near?.samples.count, 2)
    }

    func testAV1FileIsStillRefused() throws {
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("player-tracks")
        try "v1 30.0 10\n0,1.0,2.0,1\n"
            .write(to: directory.appendingPathComponent("e1.track"), atomically: true, encoding: .utf8)

        XCTAssertFalse(store.has(entryId: "e1"))
        XCTAssertNil(store.load(entryId: "e1"))
    }

    func testTwoTracksRoundTripThroughV3() throws {
        let store = PlayerTrackStore()
        try store.saveAll(entryId: "e1", source: .cloud, selections: [
            sideTrack(.near, x: 1.0, y: 2.0),
            sideTrack(.far, x: 5.0, y: 11.0),
        ], fps: 30.0)

        let stored = try XCTUnwrap(store.load(entryId: "e1", source: .cloud))
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(stored.tracks[1].track.samples.first?.courtPosition.x, 5.0)
    }

    func testTheCloudAndLocalSubtreesDoNotSeeEachOther() throws {
        let store = PlayerTrackStore()
        try store.save(entryId: "e1", track: singleSampleTrack(), fps: 30.0)

        XCTAssertTrue(store.has(entryId: "e1", source: .local))
        XCTAssertFalse(store.has(entryId: "e1", source: .cloud))
    }

    func testTwoPlayersRoundTripThroughSkeletonV3() throws {
        let store = SkeletonStore()
        try store.saveAll(entryId: "e1", source: .cloud, selections: [
            sidePoses(.near, count: 2),
            sidePoses(.far, count: 1),
        ], fps: 59.94, videoWidth: 1920, videoHeight: 1080, marks: nil)

        let stored = try XCTUnwrap(store.load(entryId: "e1", source: .cloud))
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(stored.tracks[0].poses.count, 2)
        XCTAssertEqual(stored.poses.count, 2)   // the near list
    }

    func testADeletedLocalSkeletonLeavesTheCloudOneAlone() throws {
        let store = SkeletonStore()
        try store.save(entryId: "e1", poses: onePose(), fps: 30.0,
                       videoWidth: 1920, videoHeight: 1080, marks: nil)
        try store.saveAll(entryId: "e1", source: .cloud, selections: [sidePoses(.near, count: 1)],
                          fps: 30.0, videoWidth: 1920, videoHeight: 1080, marks: nil)

        try store.delete(entryId: "e1")

        XCTAssertFalse(store.has(entryId: "e1", source: .local))
        XCTAssertTrue(store.has(entryId: "e1", source: .cloud))
    }
```

Add the small fixture helpers `sideTrack(_:x:y:)`, `sidePoses(_:count:)`, `singleSampleTrack()` and `onePose()` beside the file's existing helpers, constructing the SKIE-bridged `PlayerSelection`, `PlayerTrack`, `PlayerSample` and `PlayerPose`.

- [ ] **Step 2: Run tests to verify they fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/AnalysisStoreTests
```

Expected: FAIL to compile, `cannot find 'TrackSource' in scope`.

- [ ] **Step 3: Implement both stores**

Port Task 7's implementation, keeping every reason comment. The byte layout of `SKEL` v3 must match Android's exactly - little-endian, `side` as `CourtSide.ordinal` as `Int32`, the 12 marks always written whether or not they exist - because both platforms read files the cloud writes and each other's is the only cross-check. The text layout of track `v3` must match exactly for the same reason.

`AnalysisFiles.directory(_:)` takes the directory name, so the two sources thread through as `AnalysisFiles.directory(source.trackDir)` and `AnalysisFiles.directory(source.skeletonDir)`.

- [ ] **Step 4: Resolve cloud over local in the iOS runner**

`LocalAnalysisRunner.swift` `:233`, `:237`, `:242`, `:246`:

```swift
    /// The track to draw for this entry: the cloud's if there is one, else
    /// this phone's. Cloud wins silently - see the Android twin and the
    /// design's 3 and 10.
    nonisolated func storedTrack(entryId: String) -> PlayerTrackStore.Stored? {
        tracks.load(entryId: entryId, source: .cloud) ?? tracks.load(entryId: entryId, source: .local)
    }

    nonisolated func hasStoredTrack(entryId: String) -> Bool {
        tracks.has(entryId: entryId, source: .cloud) || tracks.has(entryId: entryId, source: .local)
    }

    nonisolated func storedSkeleton(entryId: String) -> SkeletonStore.Stored? {
        skeletons.load(entryId: entryId, source: .cloud) ?? skeletons.load(entryId: entryId, source: .local)
    }

    nonisolated func hasStoredSkeleton(entryId: String) -> Bool {
        skeletons.has(entryId: entryId, source: .cloud) || skeletons.has(entryId: entryId, source: .local)
    }
```

`storedTrackIds(among:)` at `:253` must also consult both subtrees, or the Analytics list will show a cloud-analysed match as un-analysed while its detail screen draws a heatmap.

- [ ] **Step 5: Run the iOS suite**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS. `BasePositionPanelTests` and `AnalysisStoreTests` both read `Stored` and will need their fixtures updated to the new shape; do not change what they assert.

- [ ] **Step 6: Check the test-name parity**

List the new test names on both platforms and confirm they mirror one for one. A name on one side with no twin on the other means the two surfaces have diverged.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/LocalAnalysis/ iosApp/Tests/AnalysisStoreTests.swift
git commit -m "feat(ios): the stores carry both players, and a cloud subtree

The port of the Android change, byte for byte: both platforms read files
the cloud writes, so each other's layout is the only cross-check there
is. v2 keeps loading; the local writer keeps writing it."
```

---

## Task 9: The pose-only entry point

Spec §6.1 and the plan's rally invariant. The cloud path must not be *able* to produce a rally, rather than merely be trusted not to. That is what this task buys: a function whose return type has no rally in it and whose body never reaches a detector.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPose.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseTest.kt`

**Interfaces:**
- Consumes: `selectPlayers`, `PlayerSelection`, `CourtSide` from Task 6.
- Produces: `data class CloudPoseOutcome(val selections: List<PlayerSelection>, val fps: Double, val videoWidth: Int, val videoHeight: Int)`; `fun poseOnlyAnalysis(raw: RawInference, keypoints: CourtKeypoints): CloudPoseOutcome` where `keypoints` is the wire type `com.badmintontracker.shared.model.CourtKeypoints`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the phone does with a pose stream the cloud sent it.
 *
 * The whole of the cloud path's computation, and deliberately almost nothing:
 * it runs the same player selection a device run runs and stops. It does NOT
 * detect rallies. rally_clips is the rally truth for a cloud video (design 3),
 * because three rally lists already exist for one video and a fourth, computed
 * on the phone from a different track, would disagree with the clips the coach
 * has already annotated.
 *
 * That invariant is structural: [CloudPoseOutcome] has no rally field, so
 * there is nothing for a caller to read and nothing for a later edit to start
 * filling in. These tests pin the parts a type cannot.
 */
class CloudPoseTest {

    private fun raw(fps: Double = 30.0, frames: List<RawFrame> = emptyList()) = RawInference(
        header = RawHeader(
            version = 1, fps = fps, totalFrames = frames.size,
            videoWidth = 1920, videoHeight = 1080, modelVersion = "cloud:test",
        ),
        frames = frames,
    )

    @Test
    fun both_sides_come_back_even_from_a_stream_with_no_people_in_it() {
        // Empty rather than absent, matching LocalAnalysisOutcome's reasoning:
        // "nobody was found" and "this build does not know about the far
        // player" must not look the same to a store or a panel.
        val outcome = poseOnlyAnalysis(raw(frames = listOf(RawFrame(0, 0.0, null, emptyList(), emptyList()))), marks)

        outcome.selections.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        outcome.selections.forEach { it.track.samples shouldBe emptyList() }
    }

    @Test
    fun the_video_dimensions_travel_with_the_poses() {
        // A renderer fits joints to a display box using these. Taking them
        // from anywhere else pairs them with the poses by coincidence, which
        // is the mistake SkeletonStore's KDoc exists to prevent.
        val outcome = poseOnlyAnalysis(raw(), marks)

        outcome.videoWidth shouldBe 1920
        outcome.videoHeight shouldBe 1080
    }

    @Test
    fun an_impossible_frame_rate_is_normalised_the_way_every_other_path_normalises_it() {
        // A zero fps reaches here from a container the prober could not read,
        // and it divides into every occupancy figure the heatmap draws.
        // normalizeFps is the one place that decision lives.
        poseOnlyAnalysis(raw(fps = 0.0), marks).fps shouldBe 30.0
    }

    @Test
    fun a_sane_frame_rate_is_passed_through_unrounded() {
        // 59.94 is not 60, and rounding it would drift a 30-minute skeleton
        // by seconds against its own video.
        poseOnlyAnalysis(raw(fps = 59.94), marks).fps shouldBe 59.94
    }

    @Test
    fun a_stream_that_happens_to_carry_a_shuttle_still_produces_only_poses() {
        // The cloud writes no shuttle into this artifact, and the phone does
        // not depend on that: even handed one, this path has nowhere to put
        // it. A regression that started emitting shuttle data server-side
        // must not quietly start producing rallies here.
        val withShuttle = raw(
            frames = listOf(
                RawFrame(
                    frame = 0, timestamp = 0.0,
                    shuttle = com.badmintontracker.analysis.raw.RawShuttle(1f, 2f, 0.9f, true),
                    boxes = emptyList(), persons = emptyList(),
                ),
            ),
        )

        val outcome = poseOnlyAnalysis(withShuttle, marks)

        outcome.selections.size shouldBe 2
        outcome.selections.forEach { it.track.samples shouldBe emptyList() }
    }

    /**
     * The wire keypoints, as the court-marking screen produces them and as
     * videos.manual_court_keypoints stores them.
     *
     * LocalAnalysisCoordinatorTest's fixture, copied: a square-on camera with
     * the net across the middle. Marks that do NOT fit a court would make
     * every test in this file assert BAD_COURT, which passes for the wrong
     * reason.
     */
    private val marks = CourtKeypoints(
        topLeft = listOf(200f, 200f), topRight = listOf(1700f, 200f),
        bottomRight = listOf(1700f, 900f), bottomLeft = listOf(200f, 900f),
        netLeft = listOf(200f, 550f), netRight = listOf(1700f, 550f),
        serviceLineNearLeft = listOf(200f, 430f), serviceLineNearRight = listOf(1700f, 430f),
        serviceLineFarLeft = listOf(200f, 670f), serviceLineFarRight = listOf(1700f, 670f),
        centerNear = listOf(950f, 430f), centerFar = listOf(950f, 670f),
    )
}
```

Add `import com.badmintontracker.shared.model.CourtKeypoints`. The fixture is `LocalAnalysisCoordinatorTest`'s, at that file's `:19`.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CloudPoseTest"
```

Expected: FAIL to compile, `Unresolved reference: poseOnlyAnalysis`.

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPose.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.analysis.normalizeFps
import com.badmintontracker.analysis.player.PlayerSelection
import com.badmintontracker.analysis.player.selectPlayers
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.toAnalysis

/**
 * What one cloud analysis leaves on the phone.
 *
 * Deliberately narrower than [LocalAnalysisOutcome], and the difference IS the
 * design. There is no AnalysisResult here and no clip window, because
 * rally_clips is the rally truth for a cloud video: three rally lists already
 * exist for one video and disagree (see the 2026-08-31 pipeline reference,
 * 8.9), and a fourth computed on the phone would move boundaries under clips a
 * coach has already annotated.
 *
 * A type with no rally in it is a stronger guarantee than a comment asking
 * nobody to read one.
 */
data class CloudPoseOutcome(
    /** Both players, NEAR first, each with its track and its joints. */
    val selections: List<PlayerSelection>,
    val fps: Double,
    /** What the poses are measured in, so a renderer can fit them to a display box. */
    val videoWidth: Int,
    val videoHeight: Int,
)

/**
 * Runs the player selection over a pose stream the cloud produced.
 *
 * The cloud's whole contribution is the stream. Which player is which, which
 * half of the court they are on, whether a detection stands on a plausible
 * ground point at a plausible scale, and where that puts them in court metres
 * are all decided here, by the same [selectPlayers] a device run uses, from
 * the same court marks. Nothing about this function knows the poses came from
 * an A10G rather than an NPU, which is the point: one implementation, and the
 * only thing that crossed a language boundary was model output.
 *
 * @param keypoints the WIRE type, as the court-marking screen produces it and
 *   as videos.manual_court_keypoints stores it. Converted to the compute type
 *   here so exactly one conversion site exists, matching
 *   [LocalAnalysisCoordinator.analyze].
 */
fun poseOnlyAnalysis(raw: RawInference, keypoints: CourtKeypoints): CloudPoseOutcome =
    CloudPoseOutcome(
        selections = selectPlayers(raw, keypoints.toAnalysis()),
        // The same normalisation every other path applies. A container the
        // prober could not read arrives as a zero, and it divides into every
        // occupancy figure the heatmap draws.
        fps = normalizeFps(raw.header.fps).fps,
        videoWidth = raw.header.videoWidth,
        videoHeight = raw.header.videoHeight,
    )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CloudPoseTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPose.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseTest.kt
git commit -m "feat(shared): run the cloud's pose stream through the device's selection

The whole of the cloud path's computation: the same selectPlayers a
device run uses, over the same court marks. No rally detector is
reachable from here and CloudPoseOutcome has no rally field, because
rally_clips is the rally truth for a cloud video and a fourth list
computed on the phone would move boundaries under annotated clips."
```

---

## Task 10: The status machine reaches past Phase 1

Spec §6.3. `ProcessingUpdate.isSuccess` (`VideosRepository.kt:41`) treats `phase1_complete` as done, with a comment saying Phase 2 is desktop-only. Both halves of that stop being true here.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt` (`:38-44`, the interface at `:100`, `observeProcessing`, and `VideosRepositoryImpl`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntry.kt` (`AnalyzeStage` at `:7`, `isAnalysisRunning` at `:17`, `cloudAnalysisStatus`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/CloudAnalysisStatusTest.kt`, and a new `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ProcessingUpdateTest.kt`

**Interfaces:**
- Produces:
  - `object VideoStatus` with `UPLOADED`, `PROCESSING_PHASE1`, `PHASE1_COMPLETE`, `PROCESSING_PHASE2`, `COMPLETED`.
  - `ProcessingUpdate.hasClips: Boolean`, `.hasAnalytics: Boolean`, `.isAnalyticsTerminal: Boolean`; `.isSuccess` and `.isTerminal` keep their current meanings.
  - `VideosRepository.startAnalytics(videoId: String): Result<Unit>`.
  - `VideosRepository.observeProcessing(videoId, pollIntervalMs, awaitAnalytics: Boolean = false)`.
  - `AnalyzeStage.MEASURING`.

- [ ] **Step 1: Write the failing test for the update type**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ProcessingUpdateTest.kt`:

```kotlin
package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Which cloud statuses mean which kind of "done".
 *
 * There are two kinds now and conflating them is the bug this guards. Phase 1
 * ends with watchable clips and the pipeline STOPS: nothing runs Phase 2
 * unless someone calls start-analytics. Phase 2 ends with the pose artifact.
 * A wait for the first that kept waiting would hang on every video nobody
 * asked for analytics on; a wait for the second that stopped at the first
 * would fetch an artifact that does not exist yet.
 */
class ProcessingUpdateTest {

    private fun update(status: String) = ProcessingUpdate(status, progress = null, error = null)

    @Test
    fun phase_one_completion_means_clips_but_not_analytics() {
        val u = update(VideoStatus.PHASE1_COMPLETE)
        u.hasClips shouldBe true
        u.hasAnalytics shouldBe false
    }

    @Test
    fun completion_means_both() {
        // Phase 2 merges into Phase 1's results rather than replacing them,
        // so a completed video still has its clips.
        val u = update(VideoStatus.COMPLETED)
        u.hasClips shouldBe true
        u.hasAnalytics shouldBe true
    }

    @Test
    fun a_clip_wait_ends_at_phase_one_because_nothing_advances_on_its_own() {
        update(VideoStatus.PHASE1_COMPLETE).isTerminal shouldBe true
    }

    @Test
    fun an_analytics_wait_does_not_end_at_phase_one() {
        // The concrete failure this prevents: an app that triggered analytics
        // and then stopped observing at phase1_complete would report success
        // and fetch a poses.raw that Modal has not written yet.
        update(VideoStatus.PHASE1_COMPLETE).isAnalyticsTerminal shouldBe false
        update(VideoStatus.PROCESSING_PHASE2).isAnalyticsTerminal shouldBe false
        update(VideoStatus.COMPLETED).isAnalyticsTerminal shouldBe true
    }

    @Test
    fun a_phase_two_failure_ends_both_kinds_of_wait() {
        // failed_phase2 is a real status the edge function rolls back to, and
        // a wait that did not recognise it would poll until the app died.
        val u = update("failed_phase2")
        u.isFailure shouldBe true
        u.isTerminal shouldBe true
        u.isAnalyticsTerminal shouldBe true
        u.hasAnalytics shouldBe false
    }

    @Test
    fun a_phase_one_failure_is_still_a_failure() {
        update("failed_phase1").isFailure shouldBe true
    }

    @Test
    fun a_status_nobody_recognises_is_neither_done_nor_failed() {
        // A status added server-side that this build has never heard of must
        // keep the wait running, not end it as a success.
        val u = update("processing_phase3")
        u.hasClips shouldBe false
        u.isFailure shouldBe false
        u.isTerminal shouldBe false
        u.isAnalyticsTerminal shouldBe false
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.ProcessingUpdateTest"
```

Expected: FAIL to compile, `Unresolved reference: VideoStatus`.

- [ ] **Step 3: Implement the update type**

Replace `VideosRepository.kt:36-44`:

```kotlin
/**
 * The statuses the videos row holds, as the pipeline writes them.
 *
 * Named rather than spelled out at each comparison: three of these are
 * compared in more than one place, and a typo in a string literal reads as
 * "this video is not finished" rather than as an error.
 */
object VideoStatus {
    const val UPLOADED = "uploaded"
    const val PROCESSING_PHASE1 = "processing_phase1"
    const val PHASE1_COMPLETE = "phase1_complete"
    const val PROCESSING_PHASE2 = "processing_phase2"
    const val COMPLETED = "completed"
}

/**
 * Snapshot of the cloud pipeline's state for one video (from the videos row).
 * [progress] is normalized to 0f..1f (the DB stores it as a 0..100 percentage).
 *
 * There are two kinds of "done" here and they are not interchangeable. Phase 1
 * ends with watchable rally clips and the pipeline then STOPS: Phase 2 runs
 * only when something calls start-analytics. Phase 2 ends with the pose
 * artifact the heatmap and skeleton are drawn from. A caller waiting for clips
 * wants [isTerminal]; a caller waiting for the artifact wants
 * [isAnalyticsTerminal].
 */
data class ProcessingUpdate(val status: String, val progress: Float?, val error: String?) {
    /** Phase 1 is done: the rally clips exist. Phase 2 merges into its results, so it keeps them. */
    val hasClips: Boolean get() = status == VideoStatus.PHASE1_COMPLETE || status == VideoStatus.COMPLETED

    /**
     * Phase 2 is done.
     *
     * Not the same as "a pose artifact exists": the upload is non-fatal on the
     * worker, so a completed video can still have a null poses_artifact_path.
     * That is the field to gate a fetch on, not this.
     */
    val hasAnalytics: Boolean get() = status == VideoStatus.COMPLETED

    val isSuccess: Boolean get() = hasClips
    val isFailure: Boolean get() = status.startsWith("failed")
    val isTerminal: Boolean get() = isSuccess || isFailure

    /** A wait for the pose artifact. phase1_complete does NOT end it: Phase 2 is still to come. */
    val isAnalyticsTerminal: Boolean get() = hasAnalytics || isFailure
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.ProcessingUpdateTest"
```

Expected: PASS.

- [ ] **Step 5: Add `startAnalytics` and the analytics wait**

In the `VideosRepository` interface, beside `startProcessing` at `:100`:

```kotlin
    /**
     * Invoke the start-analytics Edge Function, which runs Phase 2: the full
     * pose pass whose artifact the heatmap and skeleton are drawn from.
     *
     * Only valid from `phase1_complete` or `failed_phase2`; the function
     * answers 409 otherwise, and flips the status to `processing_phase2`
     * BEFORE calling Modal, so a double tap cannot start two workers.
     */
    suspend fun startAnalytics(videoId: String): Result<Unit>
```

and change the observer's signature:

```kotlin
    /**
     * Poll the videos row until a terminal status, emitting every change.
     *
     * @param awaitAnalytics keep polling past `phase1_complete` until Phase 2
     *   finishes. False for the ordinary upload-and-clips wait, because
     *   nothing advances a video past `phase1_complete` on its own and such a
     *   wait would never end.
     */
    fun observeProcessing(
        videoId: String,
        pollIntervalMs: Long = 5_000,
        awaitAnalytics: Boolean = false,
    ): Flow<ProcessingUpdate>
```

In `VideosRepositoryImpl`, implement `startAnalytics` exactly as `startProcessing` at `:226` does, with `function = "start-analytics"`, and change the observer's stop condition from `it.isTerminal` to `if (awaitAnalytics) it.isAnalyticsTerminal else it.isTerminal`.

- [ ] **Step 6: Write the failing test for the new stage**

Append to `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/CloudAnalysisStatusTest.kt`:

```kotlin
    @Test
    fun the_pose_pass_is_named_apart_from_the_clip_pass() {
        // Two waits, minutes apart, and the second is the longer one. A coach
        // who saw "Analyzing" twice would reasonably think the app had
        // restarted itself. What the second pass produces is the heatmap, so
        // that is what it is named after.
        cloudAnalysisStatus(AnalyzeStage.MEASURING, progress(pipeline = 0.4f)) shouldBe "Measuring movement 40%…"
        cloudAnalysisStatus(AnalyzeStage.MEASURING, null) shouldBe "Measuring movement…"
    }

    @Test
    fun the_pose_pass_counts_as_running() {
        // Drives the row spinner and, more importantly, the removal guard:
        // deleting the entry mid-run would leave the finished artifact with
        // no entry to land against.
        isAnalysisRunning(AnalyzeStage.MEASURING) shouldBe true
        canRemoveLocalVideo(AnalyzeStage.MEASURING) shouldBe false
    }
```

- [ ] **Step 7: Run test to verify it fails**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.CloudAnalysisStatusTest"
```

Expected: FAIL to compile, `Unresolved reference: MEASURING`.

- [ ] **Step 8: Add the stage**

In `LocalVideoEntry.kt`, extend the enum and the two predicates:

```kotlin
enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, MEASURING, FAILED, ANALYZED }

fun isAnalysisRunning(stage: AnalyzeStage): Boolean =
    stage == AnalyzeStage.UPLOADING ||
        stage == AnalyzeStage.PROCESSING ||
        stage == AnalyzeStage.MEASURING
```

and the `cloudAnalysisStatus` branch:

```kotlin
    // Named apart from PROCESSING for the same reason PROCESSING is named
    // apart from UPLOADING: they are different promises minutes apart, and
    // the coach waiting on this one is waiting for a heatmap, not for clips.
    AnalyzeStage.MEASURING -> rowPercent("Measuring movement", progress?.pipelineProgress)
```

`MEASURING` is placed after `PROCESSING` and before `FAILED` so the enum reads in execution order, matching `AnalyzeStep`'s comment at `:10`.

- [ ] **Step 9: Verify a registry written by the current build still decodes**

`LocalVideoEntry` is `@Serializable` and persisted, and its own comment at `:146-148` warns that a registry that fails to decode makes `load()` return an empty library - every video on the phone gone from the UI. An added enum *case* is a different risk from an added field and must be checked rather than assumed.

```bash
./gradlew :shared:jvmTest --tests "*LocalVideo*"
```

Then add a test to the shared suite that decodes a registry JSON string containing `"stage":"PROCESSING"` and `"stage":"ANALYZED"` written by the current format, asserting both decode to the right stage. If `AnalyzeStage` serializes by ordinal rather than by name anywhere, stop: inserting `MEASURING` in the middle would silently reinterpret every stored `FAILED` as `MEASURING`, and the case must be appended at the end instead.

- [ ] **Step 10: Note the Swift construction site**

`LocalVideoEntry.kt:165-166` records that Swift constructs this type with every argument spelled out. Build the iOS target now rather than discovering it in Task 14:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Expected: SUCCESS. A new enum case needs no Swift change, but any `switch` over `AnalyzeStage` in `iosApp/Sources` that is exhaustive will now fail to compile. Fix each by naming the new case explicitly rather than adding a `default:`, which would silently swallow the next stage too.

- [ ] **Step 11: Run everything**

```bash
./gradlew :shared:jvmTest :analysis:jvmTest
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntry.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ProcessingUpdateTest.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/CloudAnalysisStatusTest.kt
git commit -m "feat(shared): the status machine reaches past phase 1

Two kinds of done, told apart. Phase 1 ends with watchable clips and the
pipeline stops there; Phase 2 ends with the pose artifact and runs only
when something calls start-analytics, which this adds. A wait for clips
that kept waiting would hang on every video nobody asked analytics for;
a wait for the artifact that stopped at phase1_complete would fetch a
file Modal has not written.

MEASURING is the second wait's stage, named for what it produces rather
than as a second Analyzing."
```

---

## Task 11: Fetching the artifact and installing it

Spec §6.2. The network half, plus the sequencing that turns a downloaded stream into two stored files.

The sequencing lives in `shared` behind an injected capability, following `LocalAnalysisCoordinator`: "the platform supplies the capability, and the shared code owns the sequencing. That is also what lets the whole pipeline be exercised in CI against a fake." The stores are platform types, so saving is a lambda the platform passes in.

**Memory note.** `RawInferenceCodec.decode` materialises the whole stream: a 30-minute match is roughly 1.8M `RawKeypoint` objects on top of the bytes. This is the same magnitude an on-device Stage 3 run already holds (`AndroidLocalInferenceEngine` returns a full-match `RawInference`), so it is an exercised load rather than a new one - but the decode and the selection must both run off the main thread, and the byte array must be released before the outcome is saved.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/CloudPoseRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinator.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt` (`:63-66`, construct the repository beside `media`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinatorTest.kt`

**Interfaces:**
- Consumes: `poseOnlyAnalysis`, `CloudPoseOutcome` (Task 9); `VideoStatus` (Task 10).
- Produces:
  - `data class CloudPoseArtifact(val videoId: String, val storagePath: String, val frameCount: Int)`
  - `interface CloudPoseRepository { suspend fun artifact(videoId: String): Result<CloudPoseArtifact?>; suspend fun download(artifact: CloudPoseArtifact, onProgress: (Float) -> Unit): Result<ByteArray> }`
  - `class CloudPoseCoordinator(repository: CloudPoseRepository)` with `suspend fun install(videoId: String, keypoints: CourtKeypoints, onProgress: (Float) -> Unit = {}, save: (CloudPoseOutcome) -> Unit): Result<Boolean>` - `false` when the cloud has no artifact for this video, `true` when one was fetched and saved.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinatorTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.repo.CloudPoseArtifact
import com.badmintontracker.shared.repo.CloudPoseRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Turning a cloud artifact into two stored files, sequenced here so the whole
 * path runs in CI against a fake with no phone and no Supabase.
 */
class CloudPoseCoordinatorTest {

    private class FakeRepository(
        private val artifact: CloudPoseArtifact? = null,
        private val bytes: ByteArray? = null,
        private val artifactError: Throwable? = null,
        private val downloadError: Throwable? = null,
    ) : CloudPoseRepository {
        var downloads = 0
        val progress = mutableListOf<Float>()

        override suspend fun artifact(videoId: String): Result<CloudPoseArtifact?> =
            artifactError?.let { Result.failure(it) } ?: Result.success(artifact)

        override suspend fun download(
            artifact: CloudPoseArtifact,
            onProgress: (Float) -> Unit,
        ): Result<ByteArray> {
            downloads++
            onProgress(0.5f)
            return downloadError?.let { Result.failure(it) } ?: Result.success(bytes!!)
        }
    }

    // The same wire marks CloudPoseTest uses, so the two files cannot
    // disagree about what a usable court looks like.
    private val marks = CourtKeypoints(
        topLeft = listOf(200f, 200f), topRight = listOf(1700f, 200f),
        bottomRight = listOf(1700f, 900f), bottomLeft = listOf(200f, 900f),
        netLeft = listOf(200f, 550f), netRight = listOf(1700f, 550f),
        serviceLineNearLeft = listOf(200f, 430f), serviceLineNearRight = listOf(1700f, 430f),
        serviceLineFarLeft = listOf(200f, 670f), serviceLineFarRight = listOf(1700f, 670f),
        centerNear = listOf(950f, 430f), centerFar = listOf(950f, 670f),
    )

    private fun streamOf(frames: Int): ByteArray = RawInferenceCodec.encode(
        RawInference(
            header = RawHeader(1, 30.0, frames, 1920, 1080, "cloud:test"),
            frames = emptyList(),
        ),
    )

    @Test
    fun a_video_the_cloud_has_no_artifact_for_installs_nothing() = runTest {
        // The ordinary case for every video analysed before this shipped, and
        // for every video whose Phase 2 artifact upload failed - which is
        // non-fatal on the worker, so results_meta carries a null path.
        val repository = FakeRepository(artifact = null)
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1", marks) { saved++ }

        result.getOrNull() shouldBe false
        saved shouldBe 0
        repository.downloads shouldBe 0
    }

    @Test
    fun an_artifact_is_downloaded_decoded_and_handed_to_the_sink_once() = runTest {
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", frameCount = 0),
            bytes = streamOf(frames = 0),
        )
        var saved: CloudPoseOutcome? = null

        val result = CloudPoseCoordinator(repository).install("v1", marks) { saved = it }

        result.getOrNull() shouldBe true
        repository.downloads shouldBe 1
        saved!!.videoWidth shouldBe 1920
        saved!!.fps shouldBe 30.0
        // Both sides always, even from an empty stream: a panel needs to tell
        // "nobody was found" from "this build predates the far player".
        saved!!.selections.size shouldBe 2
    }

    @Test
    fun progress_from_the_download_reaches_the_caller() = runTest {
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0),
            bytes = streamOf(0),
        )
        val seen = mutableListOf<Float>()

        CloudPoseCoordinator(repository).install("v1", marks, onProgress = { seen.add(it) }) {}

        seen.contains(0.5f) shouldBe true
        // Completion is the coordinator's to report, after the decode and the
        // selection rather than when the bytes land. Same rule
        // LocalAnalysisCoordinator applies to its engine.
        seen.last() shouldBe 1f
    }

    @Test
    fun a_failed_lookup_is_a_failure_the_caller_can_render_not_a_crash() = runTest {
        val repository = FakeRepository(artifactError = IllegalStateException("HTTP 503"))
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1", marks) { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }

    @Test
    fun a_failed_download_saves_nothing() = runTest {
        // Half a stream must never reach a store. The stores write
        // atomically, so a partial file cannot become visible, but a partial
        // DECODE would throw mid-selection and the sink must not have been
        // called with whatever had accumulated.
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0),
            downloadError = IllegalStateException("connection lost"),
        )
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1", marks) { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }

    @Test
    fun a_corrupt_stream_is_a_failure_rather_than_an_empty_analysis() = runTest {
        // RawInferenceCodec throws on a bad magic or a truncated stream, on
        // purpose: "decoding a half-written file into a plausible shorter
        // video would move every rally boundary with nothing reporting why".
        // That exception must surface as a failed install, not as a video
        // that quietly has no heatmap.
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0),
            bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1", marks) { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CloudPoseCoordinatorTest"
```

Expected: FAIL to compile, `Unresolved reference: CloudPoseRepository`.

- [ ] **Step 3: Write the repository**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/CloudPoseRepository.kt`:

```kotlin
package com.badmintontracker.shared.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where one video's cloud pose artifact lives, and how big it is.
 *
 * [frameCount] is read from results_meta so a caller can say what it is about
 * to fetch before fetching it. It is the worker's count of emitted frames, not
 * a byte size.
 */
data class CloudPoseArtifact(
    val videoId: String,
    val storagePath: String,
    val frameCount: Int,
)

/**
 * The cloud's per-frame pose output for one video.
 *
 * Two calls rather than one because they cost very different things: the
 * lookup is a single row read a list screen can afford, and the download is
 * tens of megabytes a list screen must never start.
 */
interface CloudPoseRepository {
    /** Null when Phase 2 has not run, or ran and could not upload its artifact. */
    suspend fun artifact(videoId: String): Result<CloudPoseArtifact?>

    /** @param onProgress fraction in [0, 1) of the transfer. Completion is the caller's to report. */
    suspend fun download(artifact: CloudPoseArtifact, onProgress: (Float) -> Unit): Result<ByteArray>
}

class CloudPoseRepositoryImpl(private val client: SupabaseClient) : CloudPoseRepository {

    @Serializable
    private data class MetaRow(@SerialName("results_meta") val resultsMeta: JsonObject? = null)

    override suspend fun artifact(videoId: String): Result<CloudPoseArtifact?> = runCatching {
        val meta = client.postgrest.from("videos")
            .select(Columns.list("results_meta")) { filter { eq("id", videoId) } }
            .decodeSingleOrNull<MetaRow>()
            ?.resultsMeta
            ?: return@runCatching null
        // A null path is the honest value the worker writes when the artifact
        // upload failed, which is non-fatal there. Treated the same as "Phase
        // 2 never ran": there is nothing to fetch either way.
        val path = meta["poses_artifact_path"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        CloudPoseArtifact(
            videoId = videoId,
            storagePath = path,
            frameCount = meta["poses_frame_count"]?.jsonPrimitive?.int ?: 0,
        )
    }.annotateHttpStatus()

    override suspend fun download(
        artifact: CloudPoseArtifact,
        onProgress: (Float) -> Unit,
    ): Result<ByteArray> = runCatching {
        client.storage.from("results")
            .downloadAuthenticated(artifact.storagePath) {
                downloadProgress { bytes, total ->
                    if (total > 0) onProgress((bytes.toFloat() / total).coerceIn(0f, 0.999f))
                }
            }
    }.annotateHttpStatus()
}
```

Check `downloadAuthenticated`'s exact name and progress callback shape against the supabase-kt version in `gradle/libs.versions.toml` before writing it; the upload side in `VideosRepositoryImpl` (`:279`) is the nearest existing example of this library's transfer API.

- [ ] **Step 4: Write the coordinator**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinator.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.CloudPoseRepository

/**
 * Fetches one video's cloud pose artifact and turns it into a stored analysis.
 *
 * The cloud sibling of [LocalAnalysisCoordinator], and the same shape: the
 * platform supplies the capabilities (the network, and the stores behind
 * [save]) and this owns the sequencing, so the whole path runs in CI against a
 * fake with no phone and no Supabase.
 *
 * What it does NOT do is detect a rally. [poseOnlyAnalysis] has nowhere to put
 * one and [CloudPoseOutcome] has no field for one; rally_clips is the rally
 * truth for a cloud video.
 */
class CloudPoseCoordinator(
    private val repository: CloudPoseRepository,
    private val log: (String) -> Unit = {},
) {

    /**
     * @param save called once, with the finished analysis, before this
     *   returns. A lambda rather than a store because the stores are platform
     *   types; the platform decides where an artifact lands, this decides when
     *   there is one to land.
     * @return `false` when the cloud has no artifact for this video, which is
     *   an ordinary outcome and not a failure: every video analysed before
     *   this shipped is in that state, and so is one whose artifact upload
     *   failed on the worker.
     */
    suspend fun install(
        videoId: String,
        keypoints: CourtKeypoints,
        onProgress: (Float) -> Unit = {},
        save: (CloudPoseOutcome) -> Unit,
    ): Result<Boolean> = runCatching {
        val artifact = repository.artifact(videoId).getOrThrow() ?: return@runCatching false
        log("cloud pose: ${artifact.frameCount} frames at ${artifact.storagePath}")

        // Clamped below 1.0 by the repository: completion is this
        // coordinator's to report, after the selection that follows the
        // transfer, not the transfer's when the bytes land.
        val bytes = repository.download(artifact) { onProgress(it.coerceAtMost(0.999f)) }.getOrThrow()

        // Throws on a bad magic or a truncated stream, deliberately, and the
        // throw becomes a failed Result here rather than a video that quietly
        // has no heatmap. See RawInferenceCodec's Reader.take.
        val raw = RawInferenceCodec.decode(bytes)
        val outcome = poseOnlyAnalysis(raw, keypoints)
        outcome.selections.forEach { selection ->
            log(
                "cloud pose ${selection.side}: ${selection.track.samples.size} samples over " +
                    "${selection.track.framesWithPose} pose frames",
            )
        }

        save(outcome)
        onProgress(1f)
        true
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CloudPoseCoordinatorTest"
```

Expected: PASS.

- [ ] **Step 6: Construct it in `RallyApp`**

Beside `media` at `:64`:

```kotlin
    val cloudPoses:  CloudPoseRepository   = CloudPoseRepositoryImpl(client)
```

and expose a `CloudPoseCoordinator` built from it, the way the other coordinators are exposed, so both platforms take the same instance.

- [ ] **Step 7: Wire the Android sink**

In `LocalAnalysisRunner.kt`, add a method that runs the coordinator and saves into the **cloud** subtree:

```kotlin
    /**
     * Fetch and store this video's cloud analysis, if it has one.
     *
     * Writes into TrackSource.CLOUD, which is what keeps skeletonAction - "a
     * completed run is the new truth for its entry" - from being able to
     * delete it on the next rally-only device run.
     *
     * The decode and the selection both run on Dispatchers.Default: a
     * 30-minute stream materialises roughly 1.8M keypoint objects, which is
     * the same magnitude an on-device run already holds but is not something
     * to do on the thread drawing a frame.
     */
    suspend fun installCloudAnalysis(
        entryId: String,
        keypoints: CourtKeypoints,
        onProgress: (Float) -> Unit = {},
    ): Result<Boolean> = withContext(Dispatchers.Default) {
        cloudPoses.install(entryId, keypoints, onProgress) { outcome ->
            tracks.saveAll(entryId, TrackSource.CLOUD, outcome.selections, outcome.fps)
            skeletons.saveAll(
                entryId, TrackSource.CLOUD, outcome.selections,
                fps = outcome.fps,
                videoWidth = outcome.videoWidth,
                videoHeight = outcome.videoHeight,
                marks = keypoints.toAnalysis(),
            )
        }
    }
```

The store key is the video id, which `LocalVideoEntry.kt:140` establishes is the entry id whenever a local entry exists. A match uploaded from another phone has no entry and lands under its video id alone, which is what Task 12 depends on.

- [ ] **Step 8: Wire the iOS sink**

The same method on `LocalAnalysisRunner.swift`, `nonisolated` and off the main actor, saving into `.cloud`.

- [ ] **Step 9: Trigger the fetch**

Two callers, and both are needed (spec §6.2):

1. **On opening the Analytics detail screen** when `hasStoredTrack(entryId)` is false. Shows the progress line in place of the panel. This is the path that works for a match uploaded from another phone, where nothing local ever observed the pipeline.
2. **Opportunistically**, when a foregrounded pipeline observes `completed`. This depends on Task 10: `observeProcessing` never reaches `completed` without `awaitAnalytics = true`, so a trigger wired without it can never fire.

- [ ] **Step 10: Run everything and commit**

```bash
./gradlew :shared:jvmTest :analysis:jvmTest :androidApp:testDebugUnitTest
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/CloudPoseRepository.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinator.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinatorTest.kt \
        androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt \
        iosApp/Sources/LocalAnalysis/LocalAnalysisRunner.swift
git commit -m "feat: fetch the cloud pose artifact and store it as an analysis

Lookup and download split, because a single row read is something a list
screen can afford and tens of megabytes is not. The sequencing is shared
and the stores are a lambda, so the whole path runs in CI against a fake
with no phone and no Supabase.

A corrupt or truncated stream is a failed install rather than a video
that quietly has no heatmap."
```

---

## Task 12: A heatmap without a local video

Spec §8. `analyticsRowState` (`AnalyticsRowState.kt:29`) makes any match with no local entry inert, which covers a match uploaded from another of the user's own phones. A heatmap needs only a track; the skeleton overlays playback and has nothing to draw over.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsPanel.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/AnalyticsRowState.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/AvailablePanelsTest.kt`, `AnalyticsRowStateTest.kt`

**Interfaces:**
- Produces: `availablePanels(hasTrack: Boolean, hasBoundedClips: Boolean, hasSkeleton: Boolean, hasVideo: Boolean)`; `AnalyticsRowState.READY_NO_VIDEO`; `analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean)` keeping its signature.

- [ ] **Step 1: Write the failing tests**

Append to `AvailablePanelsTest.kt`:

```kotlin
    @Test
    fun the_skeleton_needs_a_video_to_draw_over() {
        // A cloud-analysed match reaches a phone that never held the file: it
        // was uploaded from another of this coach's phones. The heatmap is a
        // court and a track and needs neither the video nor a player; the
        // skeleton is an overlay, and offering a tab that can only ever say
        // "no video" is worse than not offering it.
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true, hasVideo = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }

    @Test
    fun with_a_video_every_panel_with_content_is_offered() {
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = true, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base, AnalyticsPanel.Skeleton)
    }

    @Test
    fun the_base_position_does_not_need_a_video() {
        // It is measured from the track and the rally windows, both of which
        // are stored. Nothing in it reads a frame.
        availablePanels(hasTrack = true, hasBoundedClips = true, hasSkeleton = false, hasVideo = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }
```

Append to `AnalyticsRowStateTest.kt`:

```kotlin
    @Test
    fun a_cloud_analysed_match_with_no_local_video_opens_its_heatmap() {
        // Not NOT_ON_DEVICE: there IS something to show. The track came down
        // from the cloud and is on this phone; only the footage is elsewhere.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = true) shouldBe
            AnalyticsRowState.READY_NO_VIDEO
    }

    @Test
    fun a_match_with_neither_is_still_inert() {
        // Nothing to show and no way to make anything: offering to analyse it
        // would promise something that cannot run.
        analyticsRowState(hasLocalEntry = false, hasStoredTrack = false) shouldBe
            AnalyticsRowState.NOT_ON_DEVICE
    }

    @Test
    fun a_local_video_with_a_track_is_unchanged() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = true) shouldBe AnalyticsRowState.READY
    }

    @Test
    fun a_local_video_with_no_track_is_unchanged() {
        analyticsRowState(hasLocalEntry = true, hasStoredTrack = false) shouldBe AnalyticsRowState.ANALYSABLE
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.analytics.*"
```

Expected: FAIL to compile, `No value passed for parameter 'hasVideo'` and `Unresolved reference: READY_NO_VIDEO`.

- [ ] **Step 3: Implement**

`AnalyticsPanel.kt`:

```kotlin
/**
 * Which panels this entry has content for.
 *
 * The heatmap is always offered: it is the screen's own subject and it says
 * for itself when there is no track. The other two are offered only when they
 * would not be empty themselves. Base needs a track with samples and rally
 * windows with bounds, which is what the base position is measured from;
 * skeleton needs a stored skeleton AND the video it overlays.
 *
 * [hasVideo] is the newest of the four and the least obvious. A cloud analysis
 * reaches a phone that never held the footage - it was uploaded from another
 * of this coach's phones - and the heatmap and the base position are both
 * computed entirely from the stored track, so they work there. The skeleton is
 * an overlay on playback, and a tab that could only ever say "no video on this
 * phone" is worse than one that is not offered.
 */
fun availablePanels(
    hasTrack: Boolean,
    hasBoundedClips: Boolean,
    hasSkeleton: Boolean,
    hasVideo: Boolean,
): List<AnalyticsPanel> = buildList {
    add(AnalyticsPanel.Heatmap)
    if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Base)
    if (hasSkeleton && hasVideo) add(AnalyticsPanel.Skeleton)
}
```

`AnalyticsRowState.kt`:

```kotlin
enum class AnalyticsRowState {
    /** A stored player track exists and the video is here. Opens every panel it has content for. */
    READY,

    /**
     * A stored track exists but the video does not, and cannot.
     *
     * A cloud analysis of footage uploaded from another phone: the track came
     * down, the file stayed there. Opens the heatmap and the base position,
     * which read only the track; the skeleton is not offered, because it
     * overlays playback.
     */
    READY_NO_VIDEO,

    /** The video is on this phone but has not been analysed yet. */
    ANALYSABLE,

    /** No local video, no track, and no way to get either. Inert. */
    NOT_ON_DEVICE,
}

/**
 * Classifies one match.
 *
 * The track decides whether there is anything to show and the local entry
 * decides whether the footage is here; the two questions used to be one. A
 * track can outlive the video it came from, because a run's result is kept on
 * disk while the file itself can be removed - so a track with no entry is
 * READY_NO_VIDEO rather than READY, and a coach who taps it gets the heatmap
 * rather than a skeleton tab with nothing behind it.
 */
fun analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean): AnalyticsRowState = when {
    hasLocalEntry && hasStoredTrack -> AnalyticsRowState.READY
    hasLocalEntry -> AnalyticsRowState.ANALYSABLE
    hasStoredTrack -> AnalyticsRowState.READY_NO_VIDEO
    else -> AnalyticsRowState.NOT_ON_DEVICE
}
```

- [ ] **Step 4: Fix the four call sites**

`AnalyticsDetailScreen.kt:116` and `AnalyticsDetailView.swift:99` pass `hasVideo`: on Android `entry?.uri != null`, on iOS the same resolution `SkeletonPanel` already uses. Both Analytics list screens must render `READY_NO_VIDEO` as a tappable row, not fall through a `when` into the inert branch. On Android, an exhaustive `when` over the enum will fail to compile until it is handled, which is the intended outcome; on iOS, find every `switch` over `AnalyticsRowState` and name the new case rather than adding a `default:`.

- [ ] **Step 5: Run everything**

```bash
./gradlew :shared:jvmTest :analysis:jvmTest :androidApp:testDebugUnitTest
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS on both.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/ \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/analytics/ \
        androidApp/src/main/java/com/badmintontracker/android/analytics/ \
        iosApp/Sources/Analytics/
git commit -m "feat: a cloud heatmap on a phone that never held the video

The track decides whether there is anything to show, the local entry
decides whether the footage is here, and those used to be one question.
A match uploaded from another of the coach's phones now opens its
heatmap and base position; the skeleton stays gated on a local video,
because it overlays playback and has nothing to draw over."
```

---

## Task 13: The Near/Far toggle on Android

Spec §7. Shown only when the loaded data has two tracks, so a device run's UI is unchanged.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/HeatmapPanel.kt` (`heatmapSource` at `:40`, `HeatmapPanel` at `:59`)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt` (`:96`)
- Test: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/HeatmapSourceTest.kt`

**Interfaces:**
- Consumes: `PlayerTrackStore.Stored.tracks`, `SkeletonStore.Stored.tracks` (Task 7); `CourtSide` (Task 6).
- Produces: `internal data class HeatmapSource(val tracks: List<PlayerTrackStore.SideTrack>, val fps: Double)`; `internal fun heatmapSource(done: LocalAnalysisState.Done?, stored: PlayerTrackStore.Stored?): HeatmapSource?`.

- [ ] **Step 1: Write the failing test**

Append to `HeatmapSourceTest.kt`:

```kotlin
    @Test
    fun `a stored pair of tracks is offered as a pair`() {
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 2)),
            ),
            fps = 30.0,
        )

        val source = heatmapSource(done = null, stored = stored)!!

        source.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        source.fps shouldBe 30.0
    }

    @Test
    fun `an in-memory run is still one near track`() {
        // A device run produces the near player and nothing else, so the
        // in-memory branch cannot grow a second track and the toggle will not
        // appear for it. Pinned because a reader of the pair type above would
        // reasonably assume otherwise.
        val done = doneWith(track = trackWith(samples = 4), fps = 25.0)

        val source = heatmapSource(done = done, stored = null)!!

        source.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR)
    }

    @Test
    fun `an empty in-memory run still falls through to a stored pair`() {
        // Unchanged behaviour, restated against the new shape. A run that
        // asked for no pose metric completes with an EMPTY PlayerTrack, and
        // preferring it blindly replaced a perfectly good stored heatmap with
        // "No pose data for this video". Court marking seeds its metrics to
        // RALLY_CLIPS alone, so a pose-less run is the DEFAULT.
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 2)),
            ),
            fps = 30.0,
        )
        val done = doneWith(track = trackWith(samples = 0), fps = 25.0)

        heatmapSource(done, stored)!!.tracks.size shouldBe 2
    }

    @Test
    fun `a side with no samples is not offered as a choice`() {
        // A toggle whose second option draws an empty court is a control that
        // cannot usefully be actuated, which is the same objection the tab row
        // was gated on. One usable track means no toggle.
        val stored = PlayerTrackStore.Stored(
            tracks = listOf(
                PlayerTrackStore.SideTrack(CourtSide.NEAR, trackWith(samples = 3)),
                PlayerTrackStore.SideTrack(CourtSide.FAR, trackWith(samples = 0)),
            ),
            fps = 30.0,
        )

        heatmapSource(done = null, stored = stored)!!.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR)
    }

    @Test
    fun `neither in memory nor stored is still nothing`() {
        heatmapSource(done = null, stored = null) shouldBe null
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "*HeatmapSourceTest*"
```

Expected: FAIL to compile, `Unresolved reference: tracks`.

- [ ] **Step 3: Implement `heatmapSource`**

```kotlin
/**
 * The tracks to draw and the frame rate they were sampled at, which must
 * travel together.
 *
 * A list because a cloud analysis carries both players. Returning the pair is
 * still the point: the fps belongs to the tracks it was measured with, and
 * picking each independently paired them by coincidence.
 */
internal data class HeatmapSource(val tracks: List<PlayerTrackStore.SideTrack>, val fps: Double)

internal fun heatmapSource(
    done: LocalAnalysisState.Done?,
    stored: PlayerTrackStore.Stored?,
): HeatmapSource? = when {
    // A device run produces the near player only, so this branch is always a
    // single track. The empty check is unchanged and load-bearing: see the
    // KDoc below and HeatmapSourceTest.
    done != null && done.playerTrack.samples.isNotEmpty() ->
        HeatmapSource(listOf(PlayerTrackStore.SideTrack(CourtSide.NEAR, done.playerTrack)), done.fps)
    // Only sides anyone was actually found on. A toggle whose second option
    // draws an empty court is a control that cannot usefully be actuated.
    stored != null -> stored.tracks.filter { it.track.samples.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.let { HeatmapSource(it, stored.fps) }
    else -> null
}
```

Keep the existing KDoc on this function verbatim above the new one. Every paragraph of it is still true and it records two measured decisions.

- [ ] **Step 4: Add the toggle to both panels**

In `HeatmapPanel`, when `source.tracks.size > 1`, draw a `ShuttlPillTabs` above `CourtHeatmapView` labelled from the sides ("Near", "Far"), holding the choice in `rememberSaveable`. With one track, draw nothing extra - the panel is exactly what it is today.

`CourtHeatmapView(track = ..., fps = ...)` is unchanged: it takes one track, and which one is the panel's decision.

`SkeletonPanel` takes the same treatment against `SkeletonStore.Stored.tracks`, and the toggle sits with the existing metric selector rather than above it, so one row of controls does not become two.

Labels are "Near" and "Far", camera-relative. `videos.player_labels` carries the coach's own names and Phase 2's thumbnails; using them is explicitly out of scope (spec §10).

- [ ] **Step 5: Run the suite and look at it**

```bash
./gradlew :androidApp:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:installDebug
```

Then open a cloud-analysed match and check the toggle at both theme settings and at a large font scale. Per the standing rule for this repo, pixel-level sloppiness is a defect: check the toggle's alignment against the tab row above it, and check that switching sides does not resize the court.

`installDebug` hits every connected adb device, hence the pinned serial: a phone is often attached.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/ \
        androidApp/src/test/java/com/badmintontracker/android/localanalysis/HeatmapSourceTest.kt
git commit -m "feat(android): a Near/Far toggle where there are two players

Shown only when the loaded analysis has two tracks with samples in them,
so a device run - which only ever finds the near player reliably - looks
exactly as it did. A side nobody was found on is not offered: a toggle
whose second option draws an empty court is a control that cannot be
usefully actuated."
```

---

## Task 14: The Near/Far toggle on iOS

The port of Task 13. Same rule, same gating, same labels.

**Files:**
- Modify: `iosApp/Sources/Analytics/AnalyticsDetailView.swift` (`:99`, `:209`, `:228-234`)
- Modify: `iosApp/Sources/LocalAnalysis/SkeletonPanel.swift` (`:247`)
- Modify: `iosApp/Sources/Analytics/CourtHeatmapView.swift` (`:16`) if it resolves its own track
- Test: `iosApp/Tests/BasePositionPanelTests.swift`, `iosApp/Tests/AnalyticsRowsTests.swift`

**Interfaces:**
- Consumes: `PlayerTrackStore.Stored.tracks`, `SkeletonStore.Stored.tracks` (Task 8); `CourtSide` bridged by SKIE (Task 6); `AnalyticsPanelKt.availablePanels(hasTrack:hasBoundedClips:hasSkeleton:hasVideo:)` (Task 12).
- Produces: `struct HeatmapSource { let tracks: [PlayerTrackStore.SideTrack]; let fps: Double }` and `func heatmapSource(done: LocalAnalysisState.Done?, stored: PlayerTrackStore.Stored?) -> HeatmapSource?`, matching the Android names exactly so the two can be read side by side.

- [ ] **Step 1: Write the failing tests**

Five tests, one for each of Task 13's, with the same names transliterated to XCTest (`testAStoredPairOfTracksIsOfferedAsAPair`, `testAnInMemoryRunIsStillOneNearTrack`, `testAnEmptyInMemoryRunStillFallsThroughToAStoredPair`, `testASideWithNoSamplesIsNotOfferedAsAChoice`, `testNeitherInMemoryNorStoredIsStillNothing`), asserting the same five things against the Swift `heatmapSource`. If iOS has no such function and resolves the track inline in `AnalyticsDetailView.reload` (`:228`), extract it first, as a pure function with the same name and the same five cases: an inline resolution cannot be tested, and the two platforms disagreeing about which track to draw is precisely what the shared rule exists to prevent.

- [ ] **Step 2: Run tests to verify they fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: FAIL to compile.

- [ ] **Step 3: Implement**

Port Task 13, keeping the reason comments. `AnalyticsDetailView.reload` (`:228`) must pass `hasVideo` into `availablePanels` (Task 12) and carry the loaded tracks into the panel state at `:209`.

- [ ] **Step 4: Check the test-name parity**

List the new Android and iOS test names side by side. A name on one side with no twin means the two surfaces have diverged, which is the one thing this parity rule exists to catch.

- [ ] **Step 5: Run the suite and look at it**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Then open the app in the simulator on a cloud-analysed match and check the toggle in light and dark. Note that `simctl` appearance does not flip this app's theme: change it in the app's own settings. And `simctl io screenshot` reports success while keeping a stale file if the target already exists, so write each capture to a fresh path.

Signing in on the simulator needs the owner: the session is Keychain-only and cannot be scripted. To check the panels without credentials, plant a track and a skeleton in the app container directly.

- [ ] **Step 6: Commit**

```bash
git add iosApp/Sources/ iosApp/Tests/
git commit -m "feat(ios): a Near/Far toggle where there are two players

The port of the Android change, gated by the same rule against the same
stored shape. Test names mirror the Android ones one for one."
```

---

## Task 15: End to end, on a real match

Spec §11. Everything above this is a unit test or a fake. This is the only step that proves a coach gets a heatmap.

**Prerequisite:** Tasks 1 to 4 deployed by the owner, and Task 5's measurement recorded.

- [ ] **Step 1: Upload and analyse a real video from the phone**

Pick a match not previously uploaded. Mark the court, analyse, and let Phase 1 finish. Confirm the rally clips appear as they always have. Nothing about this step should look different from today.

- [ ] **Step 2: Annotate a clip, then trigger Phase 2**

Write a note on a rally at a recognisable moment and record which frame it lands on. Then trigger analytics and let Phase 2 re-cut the clips.

Afterwards, open that note again. **It must land on the same moment of footage.** This is Task 1's fix observed on the real path rather than in a replay, and it is the one check the offline suite cannot make.

- [ ] **Step 3: Watch the second wait**

The row reads "Measuring movement N%…", distinct from the "Analyzing" of Phase 1. Confirm the entry cannot be removed while it runs.

- [ ] **Step 4: Confirm the artifact and the panels**

`processing_logs` carries the "Pose artifact uploaded" line with its size. The app fetches it, and the Analytics detail screen offers Heatmap, Base and Skeleton, with a Near/Far toggle on the first two.

Check the skeleton against the video: play the overlay and confirm the joints sit on the player rather than beside them. A systematic offset means a timestamp base problem, not a rendering one - `PlayerPose.timestamp` must be the presentation time, and the worker takes it from `CAP_PROP_POS_MSEC`.

Check the heatmap against the play: the near player's density should sit around their base position, not be a uniform wash and not be metres off the court. A track that is metres out is a court-fit problem, and the marks are the first thing to re-examine.

- [ ] **Step 5: Confirm the far player is the far player**

Switch the toggle. The far track must be on the far half of the court, not a mirrored copy of the near one. A mirrored heatmap means the net line is being read the wrong way round.

- [ ] **Step 6: Confirm a device run cannot delete the cloud analysis**

Run an on-device analysis of the same video with rallies only, which is the default metric set after court marking. When it finishes, the heatmap and skeleton must still be there. This is the `skeletonAction` hazard from spec §6.4 observed rather than reasoned about, and it is the failure mode a unit test can prove impossible but only a real run proves nobody wired around.

- [ ] **Step 7: Confirm the no-video path**

On a second phone signed into the same account, with the video absent: the match's Analytics row is tappable, the heatmap draws, and there is no Skeleton tab.

- [ ] **Step 8: Record what was seen**

Add a short verification note to the design doc: which video, which device, artifact size, and what each panel showed. Screenshots go through a scratch path and are copied into `docs/` only after being looked at - a blind capture chain drifts, and an unexamined screenshot is not evidence.

- [ ] **Step 9: Commit**

```bash
git add docs/plans/2026-09-12-cloud-pose-artifacts-design.md
git commit -m "docs: verification notes from the first real cloud analysis"
```

---

## Self-review notes

**Spec coverage.** §4 is Task 1. §5.1 is Task 2, §5.2 Task 4, §5.3 Task 5. §6.1 is Task 9, §6.2 Task 11, §6.3 Task 10, §6.4 Tasks 7, 8 and 11. §7 is Tasks 6, 7, 8, 13 and 14. §8 is Task 12. §9 needs no task: it is a statement about what a coach will see, and Task 15 steps 4 and 5 are where it gets checked. §10 is the deliberately-excluded list and has no tasks by construction. §11's six verification items map to Tasks 3, 6, 7, 8, 1, 5 and 15 respectively. §12's sequencing is this plan's task order.

**Two things an executor must not treat as mechanical.**

Task 7 is the one that can lose user data. `PlayerTrackStore` compares its header against one version string for exact equality in both `has` and `load`, so "bump the version" is not a refactor, it is every existing heatmap on every phone refusing to load with a half-hour re-run as the documented recovery. The task adds a version rather than changing one, and its first test writes a v2 file as raw text precisely so a writer that stopped emitting v2 cannot make it pass.

Task 5 is a gate, not a formality. The 26 MB in the spec is arithmetic from the byte layout. If a real artifact comes back materially larger, the "no compression" decision in spec §3 was made against the wrong number and reopens before Task 11 is built on it.
