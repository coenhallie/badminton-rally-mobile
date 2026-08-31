# On-Device Pipeline: Stage 0 Gates and the `:analysis` Phase 1 Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the two questions that can kill the on-device pipeline, then build and verify the pure-Kotlin analytics layer that Phase 1 needs, tested in CI against recorded cloud output.

**Architecture:** Tasks 1 to 5 are Stage 0: acquire and pin the weights, verify licensing, and measure whether TrackNet survives ONNX conversion and whether the pose model is fast enough. Tasks 6 to 15 create a new `:analysis` Kotlin Multiplatform module holding every Phase 1 metric, ported from `badminton-tracker` and verified against a captured corpus of real cloud output. No device code and no inference code is in this plan.

**Tech Stack:** Kotlin Multiplatform (2.3.20), kotlin.test + kotest assertions, Gradle version catalog, Python 3.11 with PyTorch/Ultralytics/ONNX for the conversion scripts.

**Spec:** `docs/plans/2026-08-31-on-device-analysis-pipeline-design.md`

## Global Constraints

- Bare `§N` refers to the plan's spec above. `ref §N` refers to `docs/plans/2026-08-31-web-analysis-pipeline-reference.md`.
- **Never use the em dash character in prose.** Use a plain dash. Applies to code comments, commit messages and docs.
- **No agent attribution in commits.** No `Co-Authored-By` trailer, no "Generated with" footer.
- `badminton-tracker` is **read-only** for this work. Conversion scripts and corpus tooling live in this repo under `tools/`.
- All ported constants must match their source exactly. Where a constant is duplicated between the Python and TypeScript originals, the plan names which one wins.
- Tolerances for golden-file tests: exact equality for discrete values (rally counts, frame indices, shot counts). Relative `1e-6` for floats.
- Kotlin test style follows the repo: `kotlin.test.Test`, kotest `shouldBe`, snake_case test names, and a comment explaining why a test matters when the reason is not obvious.
- Model conversion is **fp16 only**. int8 is out of scope (§8).
- Stage 0 artifacts (tasks 2 to 5) are throwaway measurement code. They live under `tools/` and are not shipped.

---

## Gate between Task 5 and Task 6

**Do not start Task 6 until Task 4 has passed its gate.** If TrackNet shuttle coverage does not survive ONNX conversion, the on-device pipeline does not work, and porting 2,500 lines of analytics to Kotlin is wasted. Task 5's throughput numbers do not block Task 6, but they decide whether Stage 3 (Phase 2 on device) is viable at all, so they should be in hand before the device-layer plan is written.

---

## File Structure

**Stage 0, throwaway measurement tooling:**
- `tools/models/pull_weights.py` - fetch weights from the Modal volume and Ultralytics, write the manifest
- `tools/models/manifest.json` - pinned SHAs for every source weight and converted artifact
- `tools/models/licenses/` - vendored upstream licence texts
- `tools/models/export_tracknet.py` - TrackNet and InpaintNet to ONNX fp16
- `tools/models/export_yolo.py` - detector and pose model to ONNX fp16
- `tools/models/check_tracknet_parity.py` - PyTorch versus ONNX Runtime on identical input
- `tools/models/measure_shuttle_coverage.py` - the 0a gate
- `tools/models/measure_pose_throughput.py` - the 0b measurement, run on device

**Corpus tooling:**
- `tools/corpus/fetch_corpus.py` - pull `results.json`, the `videos` row and `rally_clips` rows for a video id
- `tools/corpus/trim_corpus.py` - cut a captured corpus entry down to a CI-sized fixture

**The `:analysis` module:**
- `analysis/build.gradle.kts`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/CourtGeometry.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Polygon.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/VideoMetadata.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shuttle/ShuttleTrack.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shots/ShotDetection.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/GradientRallyDetector.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/ShotGapRallyDetector.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/RallyCombination.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/ClipWindows.kt`
- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/Phase1Pipeline.kt`
- matching test files under `analysis/src/commonTest/`
- `analysis/src/commonTest/resources/corpus/` - trimmed fixtures

Split by responsibility, not by layer. Geometry has no knowledge of rallies; shot detection has no knowledge of clip padding.

---

## Task 1: Capture the verification corpus

Everything else measures against this. `badminton-tracker` has no saved `results.json` anywhere (ref §8.11), so this task also closes a gap the reference doc identified.

**Files:**
- Create: `tools/corpus/fetch_corpus.py`
- Create: `tools/corpus/trim_corpus.py`
- Create: `tools/corpus/README.md`

**Interfaces:**
- Consumes: nothing
- Produces: a corpus directory per video at `<corpus_root>/<video_id>/` containing `results.json`, `video.json` (the `videos` row), and `clips.json` (the `rally_clips` rows). Trimmed fixtures land in `analysis/src/commonTest/resources/corpus/<name>/` with the same three files.

- [ ] **Step 1: Write the fetch script**

```python
#!/usr/bin/env python3
"""Pull one video's full cloud output into a local corpus directory.

Reads SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY from the environment.
Service role because results.json sits behind per-owner storage RLS and we
want this to work for any video, not only the caller's.
"""
import argparse, json, os, sys
from pathlib import Path

from supabase import create_client


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video_id")
    ap.add_argument("--out", default="corpus", help="corpus root directory")
    args = ap.parse_args()

    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set", file=sys.stderr)
        return 2

    sb = create_client(url, key)

    row = sb.table("videos").select("*").eq("id", args.video_id).single().execute().data
    if not row:
        print(f"no videos row for {args.video_id}", file=sys.stderr)
        return 1
    if not row.get("results_storage_path"):
        print(f"{args.video_id} has no results_storage_path yet", file=sys.stderr)
        return 1

    blob = sb.storage.from_("results").download(row["results_storage_path"])
    results = json.loads(blob)

    clips = (
        sb.table("rally_clips")
        .select("*")
        .eq("video_id", args.video_id)
        .order("rally_index")
        .execute()
        .data
    )

    out = Path(args.out) / args.video_id
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps(results))
    (out / "video.json").write_text(json.dumps(row))
    (out / "clips.json").write_text(json.dumps(clips))

    shuttle = results.get("shuttle_positions", {})
    visible = sum(1 for p in shuttle.values() if p.get("visible"))
    print(f"captured {args.video_id}")
    print(f"  phase           : {results.get('phase')}")
    print(f"  fps             : {results.get('fps')}")
    print(f"  total_frames    : {results.get('total_frames')}")
    print(f"  rallies         : {len(results.get('rallies', []))}")
    print(f"  rally_clips rows: {len(clips)}")
    print(f"  shuttle visible : {visible}/{len(shuttle)}")
    print(f"  skeleton_data   : {len(results.get('skeleton_data', []))} frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Write the trim script**

Full captures are too large to commit. This cuts one to a time window, renumbering nothing so frame indices stay comparable to the source.

```python
#!/usr/bin/env python3
"""Trim a captured corpus entry to a time window for use as a CI fixture.

Frame numbers and timestamps are NOT rebased. A fixture is a window onto the
original, so any index in it means the same thing it meant in the full capture.
"""
import argparse, json
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="corpus/<video_id> directory")
    ap.add_argument("dst", help="output fixture directory")
    ap.add_argument("--start", type=float, required=True, help="seconds")
    ap.add_argument("--end", type=float, required=True, help="seconds")
    args = ap.parse_args()

    src, dst = Path(args.src), Path(args.dst)
    results = json.loads((src / "results.json").read_text())
    fps = float(results.get("fps") or 30.0)
    f0, f1 = int(args.start * fps), int(args.end * fps)

    results["shuttle_positions"] = {
        k: v for k, v in results.get("shuttle_positions", {}).items() if f0 <= int(k) <= f1
    }
    if results.get("skeleton_data"):
        results["skeleton_data"] = [
            f for f in results["skeleton_data"] if f0 <= f["frame"] <= f1
        ]
    results["rallies"] = [
        r for r in results.get("rallies", [])
        if r["start_timestamp"] >= args.start and r["end_timestamp"] <= args.end
    ]
    results["trimmed_window"] = {"start": args.start, "end": args.end,
                                 "start_frame": f0, "end_frame": f1}

    clips = [
        c for c in json.loads((src / "clips.json").read_text())
        if c["start_timestamp"] >= args.start and c["end_timestamp"] <= args.end
    ]

    dst.mkdir(parents=True, exist_ok=True)
    (dst / "results.json").write_text(json.dumps(results))
    (dst / "video.json").write_text((src / "video.json").read_text())
    (dst / "clips.json").write_text(json.dumps(clips))
    print(f"trimmed to {args.start}-{args.end}s: "
          f"{len(results['shuttle_positions'])} shuttle frames, "
          f"{len(results['rallies'])} rallies, {len(clips)} clips")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 3: Capture at least three real videos**

Run: `python tools/corpus/fetch_corpus.py <video_id> --out corpus`

Pick videos that differ: one with many short rallies, one with long rallies, and one where the cloud produced a low rally count. Record the printed summary for each in `tools/corpus/README.md`.

Expected: each prints `phase: completed`. A video still at `phase1` has no `skeleton_data`, which is fine for this plan but will not serve the later Phase 2 work, so note which is which.

- [ ] **Step 4: Produce trimmed fixtures**

Run: `python tools/corpus/trim_corpus.py corpus/<video_id> analysis/src/commonTest/resources/corpus/<name> --start 0 --end 180`

Pick a window containing at least three complete rallies. Confirm the printed rally count is at least 3.

- [ ] **Step 5: Write the README**

Document how to capture, how to trim, which videos are in the corpus and what each is meant to exercise, and state plainly that the full corpus is not committed and lives outside the repo.

- [ ] **Step 6: Commit**

```bash
git add tools/corpus analysis/src/commonTest/resources/corpus
git commit -m "test: capture a verification corpus of real cloud output

Closes the gap the pipeline reference identified: no saved results.json
existed anywhere, so every threshold in the cloud pipeline was
unfalsifiable. Trimmed fixtures are committed for CI; the full corpus
stays outside the repo."
```

---

## Task 2: Pin and vendor the weights, verify licensing

This is §7's 0c plus the acquisition every later task depends on.

**Files:**
- Create: `tools/models/pull_weights.py`
- Create: `tools/models/manifest.json`
- Create: `tools/models/licenses/TrackNetV3-LICENSE.txt`
- Create: `tools/models/README.md`

**Interfaces:**
- Consumes: nothing
- Produces: weights on disk at `tools/models/weights/{tracknet,inpaintnet,badminton,pose}.pt`, and `manifest.json` mapping each logical name to `{source, sha256, retrieved_at}`.

- [ ] **Step 1: Write the acquisition script**

```python
#!/usr/bin/env python3
"""Fetch every source weight the on-device pipeline needs and pin its SHA.

Three different provenances, deliberately handled separately so a change in
any one of them is visible in the manifest diff:
  - tracknet/inpaintnet: Modal volume badminton-tracker-models
  - badminton detector : copied from the badminton-tracker checkout
  - yolo26m-pose       : resolved by Ultralytics, which is why it needs pinning
"""
import argparse, hashlib, json, shutil, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

WEIGHTS = Path("tools/models/weights")
MANIFEST = Path("tools/models/manifest.json")


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pull_from_modal(remote: str, dest: Path) -> None:
    subprocess.run(
        ["modal", "volume", "get", "badminton-tracker-models", remote, str(dest)],
        check=True,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True,
                    help="path to the badminton-tracker checkout (read-only)")
    args = ap.parse_args()

    WEIGHTS.mkdir(parents=True, exist_ok=True)
    entries = {}

    pull_from_modal("tracknet/TrackNet_best.pt", WEIGHTS / "tracknet.pt")
    entries["tracknet"] = {"source": "modal://badminton-tracker-models/tracknet/TrackNet_best.pt"}

    pull_from_modal("tracknet/InpaintNet_best.pt", WEIGHTS / "inpaintnet.pt")
    entries["inpaintnet"] = {"source": "modal://badminton-tracker-models/tracknet/InpaintNet_best.pt"}

    src = Path(args.tracker_repo) / "backend/models/badminton/weights/best.pt"
    if not src.exists():
        print(f"detector weight not found at {src}", file=sys.stderr)
        return 1
    shutil.copy2(src, WEIGHTS / "badminton.pt")
    entries["badminton"] = {"source": "badminton-tracker:backend/models/badminton/weights/best.pt"}

    # Ultralytics resolves this by name at runtime in the cloud worker, which is
    # exactly why it is unpinned there. Downloading it once and recording the
    # SHA is the whole point of this step.
    from ultralytics import YOLO
    m = YOLO("yolo26m-pose.pt")
    shutil.copy2(m.ckpt_path, WEIGHTS / "pose.pt")
    entries["pose"] = {"source": "ultralytics://yolo26m-pose.pt"}

    now = datetime.now(timezone.utc).isoformat()
    for name, meta in entries.items():
        p = WEIGHTS / f"{name}.pt"
        meta["sha256"] = sha256(p)
        meta["bytes"] = p.stat().st_size
        meta["retrieved_at"] = now
        print(f"{name:12} {meta['bytes']:>12,} bytes  {meta['sha256'][:16]}")

    MANIFEST.write_text(json.dumps({"weights": entries}, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Run it**

Run: `python tools/models/pull_weights.py --tracker-repo ../badminton-tracker`
Expected: four lines printed with non-zero sizes, and `manifest.json` written.

- [ ] **Step 3: Verify the TrackNet licence and vendor it**

Open `https://github.com/qaz812345/TrackNetV3` and confirm the repository licence. `badminton-tracker/backend/tracknet/__init__.py:4` and `model.py:8` both record it as MIT, but that is a docstring comment and no `LICENSE` file was ever vendored (§3.5).

Save the upstream licence text verbatim to `tools/models/licenses/TrackNetV3-LICENSE.txt`.

Then answer, in `tools/models/README.md`, the question the code licence does not answer: **do the published checkpoints carry terms separate from the code?** Look for a model card, a release note, or a dataset statement. Record what you found, including "nothing stated" if that is the finding.

- [ ] **Step 4: Add `weights/` to gitignore, commit the manifest and licence**

The weights themselves are large binaries and are not committed. The manifest pins them; `pull_weights.py` reproduces them.

```bash
echo "tools/models/weights/" >> .gitignore
git add .gitignore tools/models/pull_weights.py tools/models/manifest.json \
        tools/models/licenses tools/models/README.md
git commit -m "build: pin the on-device model weights and vendor upstream licences

yolo26m-pose was unpinned in the cloud worker, resolved by Ultralytics
at runtime, so no conversion of it was reproducible. All four source
weights now carry a recorded SHA."
```

---

## Task 3: Export TrackNet to ONNX and prove numerical parity

Conversion fidelity on the desktop, before any device is involved. If the ONNX graph does not match PyTorch here, nothing downstream is worth measuring.

**Files:**
- Create: `tools/models/export_tracknet.py`
- Create: `tools/models/check_tracknet_parity.py`

**Interfaces:**
- Consumes: `tools/models/weights/tracknet.pt`, `inpaintnet.pt` from Task 2
- Produces: `tools/models/onnx/tracknet.onnx` (fp16), and a parity report on stdout

- [ ] **Step 1: Write the export script**

```python
#!/usr/bin/env python3
"""TrackNetV3 to ONNX at fp16.

Input is (1, 27, 288, 512): seq_len=8 frames plus one background frame, three
channels each, at the 512x288 the model was trained on. Output is
(1, 8, 288, 512), one heatmap per input frame.

Static shapes deliberately. The mobile runtimes prefer them, and the sequence
length is fixed by the checkpoint anyway.
"""
import argparse, sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--out", default="tools/models/onnx/tracknet.onnx")
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
    from tracknet.model import TrackNet

    ckpt = torch.load("tools/models/weights/tracknet.pt", map_location="cpu")
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    bg_mode = params.get("bg_mode", "concat")
    in_dim = (seq_len + 1) * 3 if bg_mode == "concat" else seq_len * 3
    print(f"seq_len={seq_len} bg_mode={bg_mode} in_dim={in_dim}")

    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    dummy = torch.randn(1, in_dim, 288, 512)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model, dummy, str(out),
        input_names=["frames"], output_names=["heatmaps"],
        opset_version=17, dynamic_axes=None,
    )

    # fp16 as a separate pass so the fp32 graph exists for the parity check.
    import onnx
    from onnxconverter_common import float16
    m16 = float16.convert_float_to_float16(onnx.load(str(out)), keep_io_types=True)
    onnx.save(m16, str(out.with_suffix(".fp16.onnx")))
    print(f"wrote {out} and {out.with_suffix('.fp16.onnx')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Write the parity check**

```python
#!/usr/bin/env python3
"""PyTorch versus ONNX Runtime on identical input.

Compares heatmap PEAK LOCATIONS, not raw tensors. The pipeline only ever uses
the argmax of each heatmap, so a small activation difference that leaves the
peak in the same pixel is harmless, and a large one that moves it is not.
Comparing tensors directly would flag the first and could hide the second.
"""
import argparse, sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch


def peaks(hm: np.ndarray) -> list[tuple[int, int]]:
    out = []
    for i in range(hm.shape[1]):
        flat = int(np.argmax(hm[0, i]))
        out.append((flat % hm.shape[3], flat // hm.shape[3]))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--trials", type=int, default=32)
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
    from tracknet.model import TrackNet

    ckpt = torch.load("tools/models/weights/tracknet.pt", map_location="cpu")
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    in_dim = (seq_len + 1) * 3 if params.get("bg_mode", "concat") == "concat" else seq_len * 3
    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    sess = ort.InferenceSession("tools/models/onnx/tracknet.fp16.onnx",
                                providers=["CPUExecutionProvider"])

    moved, total, max_shift = 0, 0, 0
    rng = np.random.default_rng(0)
    for _ in range(args.trials):
        x = rng.random((1, in_dim, 288, 512), dtype=np.float32)
        with torch.no_grad():
            ref = model(torch.from_numpy(x)).numpy()
        got = sess.run(None, {"frames": x})[0]
        for (rx, ry), (gx, gy) in zip(peaks(ref), peaks(got)):
            total += 1
            shift = max(abs(rx - gx), abs(ry - gy))
            if shift:
                moved += 1
                max_shift = max(max_shift, shift)

    print(f"heatmaps compared : {total}")
    print(f"peaks moved       : {moved} ({100 * moved / total:.2f}%)")
    print(f"largest shift     : {max_shift} px (at 512x288)")
    # A peak that moves by 1px at 512x288 is well inside the noise the static
    # cluster filter already tolerates. Anything larger changes shuttle
    # trajectories and therefore shot detection.
    ok = max_shift <= 1
    print("PARITY OK" if ok else "PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 3: Run both**

Run: `python tools/models/export_tracknet.py --tracker-repo ../badminton-tracker`
Run: `python tools/models/check_tracknet_parity.py --tracker-repo ../badminton-tracker`

Expected: `PARITY OK`, with the largest peak shift at 0 or 1 px. Random input is a deliberately harsh test since real frames produce far sharper peaks; if random noise passes, real footage will.

If it fails, do not proceed to Task 4. Try opset 18, then `keep_io_types=False`, then fp32 to isolate whether fp16 is the cause. Record what you tried in `tools/models/README.md`.

- [ ] **Step 4: Commit**

```bash
git add tools/models/export_tracknet.py tools/models/check_tracknet_parity.py
git commit -m "build: export TrackNetV3 to ONNX and check heatmap peak parity

Compares peak locations rather than raw tensors, because the pipeline
only consumes the argmax of each heatmap."
```

---

## Task 4: The 0a gate, conversion fidelity on real footage

> **Corrected 2026-08-31, after the final whole-branch review.** The original
> version of this task compared raw ONNX argmax output against
> `results.json["shuttle_positions"]` and gated on the ratio. That was wrong, and
> it was my error in writing this plan. `results.json["shuttle_positions"]` is
> **not** raw TrackNet output: it is `_build_shuttle_positions_dict` output
> (`modal_supabase_processor.py:4073`, stored at `:4193`), which applies court-ROI
> rejection, static-cluster suppression and minimum-movement suppression, on top
> of the blob detection and InpaintNet gap-filling that `TrackNetInference` already
> applies. Comparing a raw argmax against that measures pipeline divergence, not
> conversion fidelity, and could produce both a spurious FAIL and a falsely
> reassuring PASS. This task now applies the same principle the design states in
> its own §2: **the two error sources must never be measured together.**

Parity on synthetic input (Task 3) is necessary but not sufficient. This task
runs the converted model over real footage and answers the question 0a actually
asks: does the ONNX fp16 conversion preserve shuttle detection?

**Files:**
- Create: `tools/models/measure_shuttle_coverage.py`

**Interfaces:**
- Consumes: `tools/models/onnx/tracknet.fp16.onnx`, `tools/models/weights/tracknet.pt`, a source video, and optionally a corpus entry from Task 1
- Produces: a two-section report on stdout and `tools/models/reports/coverage-<name>.json`

- [ ] **Step 1: Write the measurement script**

The script performs two measurements in one pass over the video, and they must
stay separate:

**(a) The gate: conversion fidelity.** Run the same video through the **PyTorch**
TrackNet and through the **ONNX fp16** model, applying byte-identical
postprocessing to both, so the two sides differ only by the conversion. Report
per-frame visibility agreement, and the pixel-delta distribution over frames
where both are visible. This is the pass/fail gate and it needs no cloud data at
all. The exit code is driven by this section alone.

**(b) Informational: reimplementation fidelity.** Compare the local PyTorch
result against the cloud's `results.json["shuttle_positions"]`. This is **not** a
gate, and the script must say so in its output: the cloud value is a filtered
track and the local value is not, so divergence here is expected and is not
evidence about the conversion. Report only the suppression-invariant statistics -
count of frames where both are visible, plus median and p95 pixel delta over
those frames. Do not compute or print a coverage ratio for this section.

Requirements the reviewer found the first version missing, all of which apply:
check `cap.isOpened()` after every `cv2.VideoCapture` and fail loudly with a
distinct exit code rather than printing a gate verdict; release the capture in a
`finally`; accept the ONNX path as an argument defaulting to
`tools/models/onnx/tracknet.fp16.onnx` rather than hardcoding it; and flush the
trailing partial frame buffer, padding it by repeating the last real frame
exactly as production does at `backend/tracknet/inference.py:274-276`, keeping
only the real frames' output planes.

The implemented script is the source of truth for the details; this task
specifies what it must measure and why, not its line-by-line form.

- [ ] **Step 2: Run against every video you have**

Run: `python tools/models/measure_shuttle_coverage.py <video.mp4>`

Section (a), the gate, needs only the video and the two model files. Add
`--corpus corpus/<video_id>` to additionally produce section (b), which is
informational and requires a captured corpus entry for that same video.

- [ ] **Step 3: Record the outcome and decide**

Write the numbers into `tools/models/README.md`.

**The gate is section (a) alone.** Both sides run identical weights through
identical postprocessing and differ only by the fp16 ONNX conversion, so
agreement should be near-perfect.

An earlier draft of this step gated on a directional count ratio, ONNX visible
frames over PyTorch visible frames. That is the weaker test and it was replaced
during implementation: a conversion that loses a hundred frames and gains a
hundred different ones scores a perfect 1.0 on a count ratio while agreeing
almost nowhere. The gate is therefore **symmetric per-frame visibility
agreement** - the fraction of frames on which the two sides make the same
visible/not-visible call - which cannot be fooled that way.

Proceed when, on every video tested, visibility agreement is at least 0.99 and
the p95 pixel delta on both-visible frames is at most 2 px at the model's own
512 x 288 scale. A third constant, a minimum visible fraction of 0.05, decides
only whether the run is measurable at all: on footage where the shuttle is
almost never visible the two sides agree trivially, which would otherwise be a
false pass. That case reports UNMEASURABLE rather than a verdict.

**All three constants are provisional.** None is derived from production data,
because none could be - no run has ever happened. Treat the first real execution
as calibration: record what agreement and what p95 delta a known-good conversion
actually produces, then set these from that. A gate whose numbers were guessed is
worth exactly as much as the first measurement that justifies them.

Below the bar, **stop and report** rather than continuing to Task 6. The design's
assumption that on-device perception can match the cloud does not hold, and the
remaining plan should not be executed until that is resolved.

**Section (b) never gates anything.** It compares a raw local track against the
cloud's filtered one, so a difference there is expected and says nothing about
the conversion. Record it, because a wild divergence is worth knowing about
before the Kotlin port reimplements that filtering in Task 9, but never let it
decide whether Task 6 starts.

- [ ] **Step 4: Commit**

```bash
git add tools/models/measure_shuttle_coverage.py tools/models/reports tools/models/README.md
git commit -m "test: measure TrackNet ONNX conversion fidelity on real footage

Gates on PyTorch versus ONNX through identical postprocessing, so the
number reflects the conversion alone. The comparison against the cloud
track is reported separately and gates nothing, because that track is
filtered and the local one is not."
```

---

## Task 5: The 0b measurement, sustained pose throughput

Needs a physical device. Produces the number that sets the capability-routing threshold (§5.6) and decides whether Phase 2 on device is viable at all.

**Files:**
- Create: `tools/models/export_yolo.py`
- Create: `tools/models/measure_pose_throughput.py`

**Interfaces:**
- Consumes: `tools/models/weights/pose.pt`, `badminton.pt`
- Produces: `tools/models/onnx/{pose,badminton}.fp16.onnx`, and `tools/models/reports/throughput-<device>.json`

- [ ] **Step 1: Write the YOLO export script**

```python
#!/usr/bin/env python3
"""Detector and pose model to ONNX fp16, at the sizes the cloud actually uses.

The cloud runs pose at imgsz=960 and the detector at the Ultralytics default.
Both sizes are exported for pose so the 960-versus-640 trade can be measured
rather than assumed.
"""
import argparse
from pathlib import Path

from ultralytics import YOLO


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="tools/models/onnx")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    for name, weight, sizes in [
        ("badminton", "tools/models/weights/badminton.pt", [640]),
        ("pose", "tools/models/weights/pose.pt", [960, 640]),
    ]:
        for size in sizes:
            model = YOLO(weight)
            path = model.export(format="onnx", imgsz=size, half=True, simplify=True)
            suffix = "" if size == sizes[0] else f".{size}"
            dest = out / f"{name}{suffix}.fp16.onnx"
            Path(path).rename(dest)
            print(f"{name} @ {size} -> {dest} ({dest.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Run the export**

Run: `python tools/models/export_yolo.py`
Expected: three files written, sizes printed. Record the byte sizes in the README; they decide bundle-versus-download (§5.4).

- [ ] **Step 3: Build a minimal throughput harness**

A bare app target, not part of the shipping app, that loads `pose.fp16.onnx` through ONNX Runtime and runs it repeatedly on a fixed input for **ten minutes**, recording per-inference wall time.

The requirement that matters: report throughput **bucketed by minute**, not as a single mean. A mean over ten minutes hides thermal throttling, which is the entire thing being measured. Report minute-by-minute median ms/frame, plus the ratio of minute 10 to minute 1.

Run it at 960 and at 640, on the oldest device you intend to support and on a current flagship.

- [ ] **Step 4: Record the numbers and derive the threshold**

Write `tools/models/reports/throughput-<device>.json` with, per configuration: minute-by-minute median ms/frame, the minute-10-over-minute-1 ratio, and the projected wall clock for a 30-minute 30fps video (54,000 frames) using the **minute-10** figure, not the mean.

From those, propose the §5.6 routing threshold as a multiple of video duration, and say which tested devices fall on each side of it.

- [ ] **Step 5: Commit**

```bash
git add tools/models/export_yolo.py tools/models/measure_pose_throughput.py \
        tools/models/reports tools/models/README.md
git commit -m "test: measure sustained pose throughput on device

Bucketed per minute rather than averaged, because a mean over a long
run hides the thermal throttling that decides whether this is viable."
```

---

## Task 6: Create the `:analysis` module

**Files:**
- Create: `analysis/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/VideoMetadata.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/VideoMetadataTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: the `:analysis` Gradle module targeting android/jvm/iosX64/iosArm64/iosSimulatorArm64, and `normalizeFps(value: Double?): FpsResult` where `data class FpsResult(val fps: Double, val substituted: Boolean)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoMetadataTest {

    @Test
    fun a_valid_frame_rate_passes_through() {
        normalizeFps(59.94) shouldBe FpsResult(59.94, substituted = false)
    }

    @Test
    fun zero_is_substituted_with_thirty() {
        // OpenCV returns 0 for some containers and for variable-frame-rate
        // sources. An unclamped 0 makes every rally detector bail on its
        // fps <= 0 guard and makes the speed loop divide by zero.
        normalizeFps(0.0) shouldBe FpsResult(30.0, substituted = true)
    }

    @Test
    fun negative_null_and_non_finite_are_substituted() {
        normalizeFps(-1.0) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(null) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(Double.NaN) shouldBe FpsResult(30.0, substituted = true)
        normalizeFps(Double.POSITIVE_INFINITY) shouldBe FpsResult(30.0, substituted = true)
    }
}
```

- [ ] **Step 2: Create the module**

`settings.gradle.kts`, add after the `:shared` include:

```kotlin
include(":analysis")
```

`analysis/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Deliberately no Supabase, no Ktor, no I/O. This module is pure
        // computation so it can be tested without a device or a network.
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
        }
    }
}

android {
    namespace = "com.badmintontracker.analysis"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest`
Expected: compilation failure, `normalizeFps` unresolved.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.badmintontracker.analysis

/** Default frame rate assumed when a source reports none. Mirrors the cloud worker. */
const val DEFAULT_FPS: Double = 30.0

data class FpsResult(val fps: Double, val substituted: Boolean)

/**
 * Coerce a probed frame rate to something usable.
 *
 * Port of `normalize_fps` in the cloud worker. An unusable rate is quietly
 * destructive in two ways: every rally detector bails on its `fps <= 0` guard,
 * and the speed loop divides by it. Substituting loudly is better than either.
 */
fun normalizeFps(value: Double?, default: Double = DEFAULT_FPS): FpsResult {
    if (value == null || !value.isFinite() || value <= 0.0) {
        return FpsResult(default, substituted = true)
    }
    return FpsResult(value, substituted = false)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest`
Expected: PASS.

- [ ] **Step 6: Wire it into CI**

In `.github/workflows/ci.yml`, change the `shared-tests` job's run step to cover both modules:

```yaml
      - run: ./gradlew :shared:jvmTest :analysis:jvmTest
```

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts analysis .github/workflows/ci.yml
git commit -m "feat: add the :analysis module for on-device metrics

Pure computation, no Supabase and no I/O, so every ported algorithm is
testable in CI without a device."
```

---

## Task 7: Court geometry and homography

Ported from `badminton-tracker/src/utils/homography.ts`, not from `speed_calc.py`. The TypeScript implements Hartley-normalized DLT by hand with no OpenCV, and the Python documents itself as a port of the same reference (§3.7).

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/CourtGeometry.kt`
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/HomographyTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class Point(val x: Double, val y: Double)`
  - `object Court { const val LENGTH = 13.4; const val WIDTH_DOUBLES = 6.1; const val SERVICE_LINE = 1.98 }`
  - `data class CourtKeypoints(...)` with the 12 named points, `fromMap(Map<String, List<Double>>): CourtKeypoints?`
  - `fun calculateHomography(src: List<Point>, dst: List<Point>): Matrix3x3?`
  - `fun Matrix3x3.apply(x: Double, y: Double): Point?`
  - `fun CourtKeypoints.homography(): Matrix3x3?`
  - `typealias Matrix3x3 = List<List<Double>>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.geometry

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class HomographyTest {

    // A synthetic camera: the court rectangle seen as a trapezoid, far edge
    // narrower than the near edge. Any real overhead frame is this shape.
    private val corners = listOf(
        Point(600.0, 200.0),   // top_left     -> (0, 0)
        Point(1320.0, 200.0),  // top_right    -> (6.1, 0)
        Point(1700.0, 950.0),  // bottom_right -> (6.1, 13.4)
        Point(220.0, 950.0),   // bottom_left  -> (0, 13.4)
    )
    private val courtCorners = listOf(
        Point(0.0, 0.0),
        Point(Court.WIDTH_DOUBLES, 0.0),
        Point(Court.WIDTH_DOUBLES, Court.LENGTH),
        Point(0.0, Court.LENGTH),
    )

    @Test
    fun the_four_corners_map_onto_the_court_rectangle() {
        val h = calculateHomography(corners, courtCorners)
        h shouldNotBe null
        corners.zip(courtCorners).forEach { (px, expected) ->
            val got = h!!.apply(px.x, px.y)!!
            // Absolute tolerance in metres: a corner must land on its corner.
            kotlin.math.abs(got.x - expected.x) shouldBeLessThan 1e-6
            kotlin.math.abs(got.y - expected.y) shouldBeLessThan 1e-6
        }
    }

    @Test
    fun the_centre_of_the_image_quad_maps_near_the_centre_of_the_court() {
        val h = calculateHomography(corners, courtCorners)!!
        // Not the centroid of the pixel quad: perspective means the image
        // centroid is NOT the court centre. This asserts only that it lands
        // inside the court, which is the property that catches a transposed
        // or mirrored matrix.
        val got = h.apply(960.0, 575.0)!!
        (got.x > 0.0 && got.x < Court.WIDTH_DOUBLES) shouldBe true
        (got.y > 0.0 && got.y < Court.LENGTH) shouldBe true
    }

    @Test
    fun three_points_is_not_enough() {
        calculateHomography(corners.take(3), courtCorners.take(3)).shouldBeNull()
    }

    @Test
    fun collinear_source_points_are_rejected() {
        // A degenerate configuration has no unique homography. Returning a
        // matrix here would silently produce garbage metres for every frame.
        val collinear = listOf(
            Point(0.0, 0.0), Point(10.0, 0.0), Point(20.0, 0.0), Point(30.0, 0.0),
        )
        calculateHomography(collinear, courtCorners).shouldBeNull()
    }

    @Test
    fun a_point_behind_the_camera_plane_returns_null() {
        // w == 0 means the point projects to infinity. Dividing by it would
        // yield an infinity that propagates into distance totals.
        val h = listOf(
            listOf(1.0, 0.0, 0.0),
            listOf(0.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0),
        )
        h.apply(5.0, 5.0).shouldBeNull()
    }
}

private infix fun Double.shouldBeLessThan(other: Double) {
    if (this >= other) throw AssertionError("$this was not less than $other")
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*HomographyTest*"`
Expected: compilation failure, unresolved references.

- [ ] **Step 3: Write `CourtGeometry.kt`**

```kotlin
package com.badmintontracker.analysis.geometry

data class Point(val x: Double, val y: Double)

/** BWF court dimensions in metres. Mirrors COURT_DIMENSIONS and speed_calc.py. */
object Court {
    const val LENGTH: Double = 13.4
    const val WIDTH_DOUBLES: Double = 6.1
    const val WIDTH_SINGLES: Double = 5.18
    const val SERVICE_LINE: Double = 1.98
}

/**
 * The 12 manually-placed court keypoints, in video pixels.
 *
 * Field names match the persisted `videos.manual_court_keypoints` shape
 * exactly, so there is no remapping between storage and use.
 */
data class CourtKeypoints(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
    val netLeft: Point,
    val netRight: Point,
    val serviceLineNearLeft: Point,
    val serviceLineNearRight: Point,
    val serviceLineFarLeft: Point,
    val serviceLineFarRight: Point,
    val centerNear: Point,
    val centerFar: Point,
) {
    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        private val ORDER = listOf(
            "top_left", "top_right", "bottom_right", "bottom_left",
            "net_left", "net_right",
            "service_line_near_left", "service_line_near_right",
            "service_line_far_left", "service_line_far_right",
            "center_near", "center_far",
        )

        /** Returns null when any of the 12 keys is absent or malformed. */
        fun fromMap(raw: Map<String, List<Double>>): CourtKeypoints? {
            val pts = ORDER.map { key ->
                val v = raw[key] ?: return null
                if (v.size < 2) return null
                Point(v[0], v[1])
            }
            return CourtKeypoints(
                pts[0], pts[1], pts[2], pts[3], pts[4], pts[5],
                pts[6], pts[7], pts[8], pts[9], pts[10], pts[11],
            )
        }
    }
}

/** Court-plane positions of the 12 keypoints, in metres, in the same order. */
val COURT_KEYPOINT_POSITIONS: List<Point> = listOf(
    Point(0.0, 0.0),
    Point(Court.WIDTH_DOUBLES, 0.0),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH),
    Point(0.0, Court.LENGTH),
    Point(0.0, Court.LENGTH / 2),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2),
    Point(0.0, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(0.0, Court.LENGTH / 2 + Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES, Court.LENGTH / 2 + Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES / 2, Court.LENGTH / 2 - Court.SERVICE_LINE),
    Point(Court.WIDTH_DOUBLES / 2, Court.LENGTH / 2 + Court.SERVICE_LINE),
)
```

- [ ] **Step 4: Write `Homography.kt`**

```kotlin
package com.badmintontracker.analysis.geometry

import kotlin.math.abs
import kotlin.math.sqrt

typealias Matrix3x3 = List<List<Double>>

/**
 * Hartley-normalized DLT homography.
 *
 * Ported from badminton-tracker's src/utils/homography.ts rather than from
 * speed_calc.py, because the TypeScript is dependency-free and the Python
 * documents itself as a port of the same reference. Keeping OpenCV out of this
 * module is the whole reason the TS is the source of truth here.
 */
fun calculateHomography(src: List<Point>, dst: List<Point>): Matrix3x3? {
    if (src.size < 4 || dst.size < 4 || src.size != dst.size) return null

    val (nSrc, tSrc) = normalizePoints(src) ?: return null
    val (nDst, tDst) = normalizePoints(dst) ?: return null

    // Two rows per correspondence: the standard DLT constraint on h with
    // h33 fixed to 1, leaving 8 unknowns.
    val a = ArrayList<List<Double>>(nSrc.size * 2)
    val b = ArrayList<Double>(nSrc.size * 2)
    for (i in nSrc.indices) {
        val (x, y) = nSrc[i]
        val (u, v) = nDst[i]
        a.add(listOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y))
        b.add(u)
        a.add(listOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y))
        b.add(v)
    }

    val h = solveLeastSquares(a, b) ?: return null
    val hn = listOf(
        listOf(h[0], h[1], h[2]),
        listOf(h[3], h[4], h[5]),
        listOf(h[6], h[7], 1.0),
    )

    // Undo normalization: H = Tdst^-1 * Hn * Tsrc
    val tDstInv = invert3x3(tDst) ?: return null
    return multiply3x3(multiply3x3(tDstInv, hn), tSrc)
}

/** Apply a homography to a pixel. Returns null when the point projects to infinity. */
fun Matrix3x3.apply(x: Double, y: Double): Point? {
    val w = this[2][0] * x + this[2][1] * y + this[2][2]
    if (abs(w) < 1e-12) return null
    return Point(
        (this[0][0] * x + this[0][1] * y + this[0][2]) / w,
        (this[1][0] * x + this[1][1] * y + this[1][2]) / w,
    )
}

/** Video pixels to court metres, using all 12 keypoints for a better fit. */
fun CourtKeypoints.homography(): Matrix3x3? {
    val src = listOf(
        topLeft, topRight, bottomRight, bottomLeft, netLeft, netRight,
        serviceLineNearLeft, serviceLineNearRight,
        serviceLineFarLeft, serviceLineFarRight, centerNear, centerFar,
    )
    return calculateHomography(src, COURT_KEYPOINT_POSITIONS)
}

private fun normalizePoints(points: List<Point>): Pair<List<Point>, Matrix3x3>? {
    val n = points.size
    val cx = points.sumOf { it.x } / n
    val cy = points.sumOf { it.y } / n
    val meanDist = points.sumOf {
        sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy))
    } / n
    if (meanDist < 1e-12) return null  // all points coincident
    val s = sqrt(2.0) / meanDist
    val t = listOf(
        listOf(s, 0.0, -s * cx),
        listOf(0.0, s, -s * cy),
        listOf(0.0, 0.0, 1.0),
    )
    return points.map { Point((it.x - cx) * s, (it.y - cy) * s) } to t
}

private fun multiply3x3(a: Matrix3x3, b: Matrix3x3): Matrix3x3 =
    (0..2).map { r -> (0..2).map { c -> (0..2).sumOf { k -> a[r][k] * b[k][c] } } }

private fun invert3x3(m: Matrix3x3): Matrix3x3? {
    val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
              m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
              m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    if (abs(det) < 1e-12) return null
    return listOf(
        listOf(
            (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det,
            (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det,
            (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det,
        ),
        listOf(
            (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det,
            (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det,
            (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det,
        ),
        listOf(
            (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det,
            (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det,
            (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det,
        ),
    )
}

/** Normal equations plus Gaussian elimination with partial pivoting. */
private fun solveLeastSquares(a: List<List<Double>>, b: List<Double>): List<Double>? {
    val n = a[0].size
    val ata = Array(n) { DoubleArray(n) }
    val atb = DoubleArray(n)
    for (r in a.indices) {
        for (i in 0 until n) {
            atb[i] += a[r][i] * b[r]
            for (j in 0 until n) ata[i][j] += a[r][i] * a[r][j]
        }
    }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (abs(ata[r][col]) > abs(ata[pivot][col])) pivot = r
        if (abs(ata[pivot][col]) < 1e-12) return null
        val tmp = ata[col]; ata[col] = ata[pivot]; ata[pivot] = tmp
        val tb = atb[col]; atb[col] = atb[pivot]; atb[pivot] = tb
        for (r in 0 until n) {
            if (r == col) continue
            val f = ata[r][col] / ata[col][col]
            if (f == 0.0) continue
            for (c in col until n) ata[r][c] -= f * ata[col][c]
            atb[r] -= f * atb[col]
        }
    }
    return (0 until n).map { atb[it] / ata[it][it] }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*HomographyTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry
git commit -m "feat: port court geometry and the DLT homography to Kotlin

Ported from the TypeScript rather than speed_calc.py, because the TS is
dependency-free and keeps OpenCV out of :analysis."
```

---

## Task 8: Net-line validation and point-in-polygon

Two small pure helpers that later tasks need. `validNetLine` guards the identity tracker in the Phase 2 work; `Polygon.contains` replaces `cv2.pointPolygonTest` in the shuttle ROI filter.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Polygon.kt`
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/CourtGeometry.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/PolygonTest.kt`

**Interfaces:**
- Consumes: `Point` from Task 7
- Produces:
  - `fun validNetLine(left: Point?, right: Point?, width: Double, height: Double): Boolean`
  - `fun List<Point>.expandedAbout(centroidFactor: Double): List<Point>`
  - `fun List<Point>.contains(p: Point): Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.geometry

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PolygonTest {

    private val quad = listOf(
        Point(100.0, 100.0), Point(300.0, 100.0),
        Point(320.0, 300.0), Point(80.0, 300.0),
    )

    @Test
    fun a_point_inside_is_contained() {
        quad.contains(Point(200.0, 200.0)) shouldBe true
    }

    @Test
    fun a_point_outside_is_not() {
        quad.contains(Point(50.0, 200.0)) shouldBe false
        quad.contains(Point(200.0, 50.0)) shouldBe false
    }

    @Test
    fun a_point_level_with_a_vertex_does_not_double_count_the_crossing() {
        // The classic ray-casting bug: a horizontal ray through a vertex
        // counts two edge crossings instead of one and reports inside as
        // outside. The half-open y test below is what prevents it.
        quad.contains(Point(200.0, 100.0)) shouldBe true
    }

    @Test
    fun expanding_about_the_centroid_scales_every_vertex() {
        val e = quad.expandedAbout(2.0)
        val cx = quad.sumOf { it.x } / 4
        e[0].x shouldBe cx + (quad[0].x - cx) * 2.0
    }

    @Test
    fun a_degenerate_net_line_is_rejected() {
        // Both endpoints at the origin is the classic unplaced-keypoint
        // payload. Accepting it makes every position in the frame classify as
        // one court side, silently disabling player identity.
        validNetLine(Point(0.0, 0.0), Point(0.0, 0.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun endpoints_too_close_horizontally_are_rejected() {
        // The net spans the court width; with almost no horizontal separation
        // the y-at-x interpolation is ill-conditioned.
        validNetLine(Point(900.0, 500.0), Point(905.0, 505.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun endpoints_outside_the_frame_are_rejected() {
        validNetLine(Point(-1.0, 500.0), Point(1800.0, 500.0), 1920.0, 1080.0) shouldBe false
        validNetLine(Point(100.0, 500.0), Point(2100.0, 500.0), 1920.0, 1080.0) shouldBe false
    }

    @Test
    fun a_real_net_line_is_accepted() {
        validNetLine(Point(220.0, 560.0), Point(1700.0, 545.0), 1920.0, 1080.0) shouldBe true
    }

    @Test
    fun a_null_endpoint_is_rejected() {
        validNetLine(null, Point(1700.0, 545.0), 1920.0, 1080.0) shouldBe false
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*PolygonTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `Polygon.kt`**

```kotlin
package com.badmintontracker.analysis.geometry

import kotlin.math.abs
import kotlin.math.max

/**
 * Ray-casting point-in-polygon, replacing cv2.pointPolygonTest.
 *
 * The `(yi > p.y) != (yj > p.y)` test is half-open in y deliberately: a ray
 * passing exactly through a vertex must count one crossing, not two.
 */
fun List<Point>.contains(p: Point): Boolean {
    if (size < 3) return false
    var inside = false
    var j = size - 1
    for (i in indices) {
        val (xi, yi) = this[i]
        val (xj, yj) = this[j]
        if ((yi > p.y) != (yj > p.y)) {
            val xCross = xi + (p.y - yi) / (yj - yi) * (xj - xi)
            if (p.x <= xCross) inside = !inside
        }
        j = i
    }
    return inside
}

/** Scale every vertex away from the polygon's centroid. Mirrors the worker's ROI margins. */
fun List<Point>.expandedAbout(centroidFactor: Double): List<Point> {
    if (isEmpty()) return this
    val cx = sumOf { it.x } / size
    val cy = sumOf { it.y } / size
    return map { Point(cx + (it.x - cx) * centroidFactor, cy + (it.y - cy) * centroidFactor) }
}

/**
 * Is this pair of net endpoints usable as a court-side divider?
 *
 * Port of `valid_net_line`. A degenerate line fails silently and
 * catastrophically rather than loudly: with dx == 0 the y-at-x interpolation
 * is skipped and every position compares against a midline of 0, so the whole
 * frame classifies as one side. That single condition disables the identity
 * tracker's strongest anchor while the startup log still claims real keypoints.
 */
fun validNetLine(left: Point?, right: Point?, width: Double, height: Double): Boolean {
    if (left == null || right == null) return false
    for (p in listOf(left, right)) {
        if (!p.x.isFinite() || !p.y.isFinite() || p.x < 0 || p.y < 0) return false
    }
    if (left.x == 0.0 && left.y == 0.0 && right.x == 0.0 && right.y == 0.0) return false
    val minDx = if (width > 0) max(2.0, 0.01 * width) else 2.0
    if (abs(right.x - left.x) < minDx) return false
    if (width > 0 && (left.x > width || right.x > width)) return false
    if (height > 0 && (left.y > height || right.y > height)) return false
    return true
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*PolygonTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Polygon.kt \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/PolygonTest.kt
git commit -m "feat: port net-line validation and point-in-polygon

Replaces cv2.pointPolygonTest so the shuttle ROI filter needs no
native dependency."
```

---

## Task 9: Shuttle track filtering

Port of `_build_shuttle_positions_dict`. Court ROI plus static-cluster rejection, producing the filtered track the gradient detector consumes.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shuttle/ShuttleTrack.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/shuttle/ShuttleTrackTest.kt`

**Interfaces:**
- Consumes: `Point`, `contains`, `expandedAbout` from Tasks 7 and 8
- Produces:
  - `data class ShuttleSample(val x: Double, val y: Double, val visible: Boolean)`
  - `fun buildFilteredTrack(raw: Map<Int, ShuttleSample>, fps: Double, videoWidth: Int, videoHeight: Int, courtCorners: List<Point>?): Map<Int, ShuttleSample>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShuttleTrackTest {

    private val corners = listOf(
        Point(200.0, 200.0), Point(1700.0, 200.0),
        Point(1700.0, 900.0), Point(200.0, 900.0),
    )

    private fun moving(count: Int, step: Double = 60.0) =
        (0 until count).associateWith {
            ShuttleSample(400.0 + it * step, 400.0 + it * step * 0.3, visible = true)
        }

    @Test
    fun a_moving_shuttle_inside_the_court_survives() {
        val out = buildFilteredTrack(moving(10), 30.0, 1920, 1080, corners)
        out.values.count { it.visible } shouldBe 10
    }

    @Test
    fun a_position_outside_the_court_roi_is_dropped() {
        val raw = mapOf(0 to ShuttleSample(50.0, 50.0, visible = true))
        buildFilteredTrack(raw, 30.0, 1920, 1080, corners)[0]!!.visible shouldBe false
    }

    @Test
    fun a_stationary_false_positive_is_suppressed_after_confirmation() {
        // A logo or a shuttle on the floor sits still. The filter needs three
        // observations before it trusts a cluster, so the first few survive and
        // everything after is dropped. That trailing suppression is the point.
        val raw = (0 until 30).associateWith { ShuttleSample(800.0, 500.0, visible = true) }
        val out = buildFilteredTrack(raw, 30.0, 1920, 1080, corners)
        out.values.count { it.visible } shouldBe 1
    }

    @Test
    fun an_invisible_input_stays_invisible() {
        val raw = mapOf(0 to ShuttleSample(0.0, 0.0, visible = false))
        buildFilteredTrack(raw, 30.0, 1920, 1080, corners)[0]!!.visible shouldBe false
    }

    @Test
    fun with_no_court_corners_the_roi_filter_is_skipped() {
        val raw = mapOf(0 to ShuttleSample(50.0, 50.0, visible = true))
        buildFilteredTrack(raw, 30.0, 1920, 1080, null)[0]!!.visible shouldBe true
    }

    @Test
    fun thresholds_scale_with_frame_rate() {
        // At 60fps the shuttle moves half as far between frames, so a fixed
        // pixel threshold would classify real movement as static. The 30/fps
        // scale factor is what keeps behaviour equivalent across frame rates.
        val slow = moving(10, step = 6.0)
        val at30 = buildFilteredTrack(slow, 30.0, 1920, 1080, corners).values.count { it.visible }
        val at60 = buildFilteredTrack(slow, 60.0, 1920, 1080, corners).values.count { it.visible }
        (at60 > at30) shouldBe true
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*ShuttleTrackTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `ShuttleTrack.kt`**

```kotlin
package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.contains
import com.badmintontracker.analysis.geometry.expandedAbout
import kotlin.math.max
import kotlin.math.sqrt

data class ShuttleSample(val x: Double, val y: Double, val visible: Boolean) {
    companion object {
        val INVISIBLE = ShuttleSample(0.0, 0.0, visible = false)
    }
}

private const val STATIC_COUNT_THRESHOLD = 3

private class Cluster(var x: Double, var y: Double, var count: Int)

/**
 * Court ROI plus static-cluster rejection over a raw shuttle track.
 *
 * Port of `_build_shuttle_positions_dict`. Note this is the FILTERED track:
 * the cloud's raw per-frame track uses a different, more permissive ROI, and
 * that divergence is the cause of the trimmed rally tails described in the
 * clipping audit. This port deliberately implements only the filtered one, and
 * the raw track is simply the unfiltered input.
 *
 * Thresholds scale with resolution and with frame rate. The 30/fps factor
 * matters: at 60fps a shuttle covers half the pixels per frame, so a fixed
 * threshold would call real movement static.
 */
fun buildFilteredTrack(
    raw: Map<Int, ShuttleSample>,
    fps: Double,
    videoWidth: Int,
    videoHeight: Int,
    courtCorners: List<Point>?,
): Map<Int, ShuttleSample> {
    val fpsScale = if (fps > 0) 30.0 / fps else 1.0
    val longEdge = max(videoWidth, videoHeight).toDouble()
    val staticDist = max(4.0, 0.013 * longEdge * fpsScale)
    val minMove = max(2.0, 0.007 * longEdge * fpsScale)

    // 1.02 margin then 1.15 uniform, matching the worker's filtered track.
    val roi = courtCorners?.expandedAbout(1.02)?.expandedAbout(1.15)

    val clusters = ArrayList<Cluster>()
    var prev: Point? = null
    val out = LinkedHashMap<Int, ShuttleSample>(raw.size)

    for (frame in raw.keys.sorted()) {
        val s = raw.getValue(frame)
        if (!s.visible) {
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }
        val p = Point(s.x, s.y)

        if (roi != null && !roi.contains(p)) {
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        val hit = clusters.firstOrNull { dist(p, it.x, it.y) < staticDist }
        if (hit != null) {
            hit.count += 1
            hit.x = (hit.x * (hit.count - 1) + p.x) / hit.count
            hit.y = (hit.y * (hit.count - 1) + p.y) / hit.count
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        val last = prev
        if (last != null && dist(p, last.x, last.y) < minMove) {
            val near = clusters.firstOrNull { dist(p, it.x, it.y) < staticDist * 2 }
            if (near != null) near.count += 1 else clusters.add(Cluster(p.x, p.y, 1))
            out[frame] = ShuttleSample.INVISIBLE
            continue
        }

        // Prune only on an accepted position. Pruning every frame is the defect
        // that leaves this mechanism dead in both of the cloud's per-frame
        // loops: a cluster is created with count 1 and removed in the same
        // iteration, so it can never reach the threshold.
        clusters.retainAll { it.count >= STATIC_COUNT_THRESHOLD }

        out[frame] = ShuttleSample(p.x, p.y, visible = true)
        prev = p
    }
    return out
}

private fun dist(p: Point, x: Double, y: Double): Double =
    sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*ShuttleTrackTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shuttle \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/shuttle
git commit -m "feat: port shuttle track ROI and static-cluster filtering

Prunes clusters only on an accepted position, which is the placement
that makes the mechanism actually work; the cloud's per-frame loops
prune every frame and the filter is inert there."
```

---

## Task 10: Shot detection

Port of `detect_shuttle_shots` in `shot_detection.py`, which is itself a port of `utils/shotDetection.ts`. One implementation replaces both.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shots/ShotDetection.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/shots/ShotDetectionTest.kt`

**Interfaces:**
- Consumes: `ShuttleSample` from Task 9
- Produces:
  - `data class FrameSample(val frame: Int, val timestamp: Double, val shuttle: ShuttleSample?)`
  - `data class Shot(val frame: Int, val timestamp: Double, val x: Double, val y: Double)`
  - `fun detectShuttleShots(frames: List<FrameSample>, fps: Double, minShotGapSec: Double = 0.6, minSpeedSq: Double = 225.0, cosAngleMax: Double = 0.0, rejectOutliers: Boolean = true, autoStrideSec: Double = 0.3): List<Shot>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.shots

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShotDetectionTest {

    private fun frames(points: List<Pair<Double, Double>?>, fps: Double = 30.0) =
        points.mapIndexed { i, p ->
            FrameSample(
                frame = i,
                timestamp = i / fps,
                shuttle = p?.let { ShuttleSample(it.first, it.second, visible = true) },
            )
        }

    /** A shuttle travelling right, reversing, travelling left. One reversal. */
    private fun oneReversal(): List<FrameSample> {
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(30) { pts.add(100.0 + it * 40.0 to 300.0) }
        repeat(30) { pts.add(1300.0 - it * 40.0 to 300.0) }
        return frames(pts)
    }

    @Test
    fun a_direction_reversal_is_a_shot() {
        val shots = detectShuttleShots(oneReversal(), fps = 30.0)
        shots.size shouldBe 1
    }

    @Test
    fun a_stationary_shuttle_produces_no_shots() {
        // Jitter below the minimum speed must not register. Without this gate
        // a shuttle sitting on the floor generates a shot every few frames.
        val pts = (0 until 60).map { 800.0 + (it % 2) * 2.0 to 500.0 }
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }

    @Test
    fun two_reversals_closer_than_the_minimum_gap_yield_one_shot() {
        // minShotGapFrames = max(3, fps * 0.6) = 18 at 30fps.
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(10) { pts.add(100.0 + it * 60.0 to 300.0) }
        repeat(4) { pts.add(700.0 - it * 60.0 to 300.0) }
        repeat(10) { pts.add(460.0 + it * 60.0 to 300.0) }
        detectShuttleShots(frames(pts), fps = 30.0).size shouldBe 1
    }

    @Test
    fun a_single_frame_glitch_is_rejected_as_an_outlier() {
        // One TrackNet frame lands 800px away and comes back. Without outlier
        // rejection that fabricates two reversals out of nothing.
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(60) { pts.add(100.0 + it * 20.0 to 300.0) }
        pts[30] = 100.0 + 30 * 20.0 + 900.0 to 1000.0
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }

    @Test
    fun a_long_invisible_gap_does_not_build_velocity_across_it() {
        // Velocity computed across a multi-second gap is meaningless and would
        // read as a reversal at the far side of an inter-rally pause.
        val pts = ArrayList<Pair<Double, Double>?>()
        repeat(10) { pts.add(100.0 + it * 60.0 to 300.0) }
        repeat(120) { pts.add(null) }
        repeat(10) { pts.add(700.0 - it * 60.0 to 300.0) }
        detectShuttleShots(frames(pts), fps = 30.0) shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*ShotDetectionTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `ShotDetection.kt`**

```kotlin
package com.badmintontracker.analysis.shots

import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FrameSample(val frame: Int, val timestamp: Double, val shuttle: ShuttleSample?)

data class Shot(val frame: Int, val timestamp: Double, val x: Double, val y: Double)

/** Single-frame jump beyond this squared distance, on both sides, is a glitch. */
private const val OUTLIER_DIST_SQ = 400.0 * 400.0

/** Beyond this gap between sampled positions, velocity is meaningless. */
private const val MAX_GAP_S = 2.5

private data class Sample(val frame: Int, val t: Double, val x: Double, val y: Double)

/**
 * Shots as shuttle direction reversals.
 *
 * One implementation replacing two: shot_detection.py and
 * utils/shotDetection.ts are the same algorithm maintained separately, and
 * their docstrings name each other as sync targets.
 *
 * The wrist-proximity and acceleration gates present in the TS source are
 * deliberately absent. The rally caller passes null for both, so they were
 * never active on this path.
 */
fun detectShuttleShots(
    frames: List<FrameSample>,
    fps: Double,
    minShotGapSec: Double = 0.6,
    minSpeedSq: Double = 225.0,
    cosAngleMax: Double = 0.0,
    rejectOutliers: Boolean = true,
    autoStrideSec: Double = 0.3,
): List<Shot> {
    if (fps <= 0) return emptyList()
    val minShotGapFrames = max(3, (fps * minShotGapSec).toInt())

    val raw = frames.mapNotNull { f ->
        f.shuttle?.takeIf { it.visible }?.let { Sample(f.frame, f.timestamp, it.x, it.y) }
    }
    if (raw.size < 5) return emptyList()

    val cleaned = if (rejectOutliers) filterOutliers(raw) else raw

    // Stride only on dense tracks. Sparse client-side data is already spread
    // out; TrackNet gives consecutive frames whose velocity vectors turn too
    // gradually for a reversal to register without subsampling.
    val coverage = raw.size.toDouble() / max(frames.size, 1)
    val strideFrames = if (coverage > 0.5) max(3, (fps * autoStrideSec).roundToInt()) else 0
    val samples = if (strideFrames > 0 && cleaned.isNotEmpty()) {
        val acc = arrayListOf(cleaned.first())
        for (s in cleaned.drop(1)) if (s.frame - acc.last().frame >= strideFrames) acc.add(s)
        acc
    } else cleaned
    if (samples.size < 3) return emptyList()

    val shots = ArrayList<Shot>()
    var lastShotFrame = Int.MIN_VALUE
    for (i in 2 until samples.size) {
        val a = samples[i - 2]
        val b = samples[i - 1]
        val c = samples[i]
        if (b.t - a.t > MAX_GAP_S || c.t - b.t > MAX_GAP_S) continue

        val vx1 = b.x - a.x; val vy1 = b.y - a.y
        val vx2 = c.x - b.x; val vy2 = c.y - b.y
        val s1 = vx1 * vx1 + vy1 * vy1
        val s2 = vx2 * vx2 + vy2 * vy2
        if (s1 < minSpeedSq && s2 < minSpeedSq) continue

        val dot = vx1 * vx2 + vy1 * vy2
        val threshold = if (cosAngleMax < 0) cosAngleMax * sqrt(s1 * s2) else 0.0
        if (dot >= threshold) continue
        if (b.frame - lastShotFrame < minShotGapFrames) continue

        shots.add(Shot(b.frame, b.t, b.x, b.y))
        lastShotFrame = b.frame
    }
    return shots
}

/** Drop a position whose squared distance to BOTH neighbours exceeds the threshold. */
private fun filterOutliers(points: List<Sample>): List<Sample> {
    if (points.size < 3) return points
    val out = arrayListOf(points.first())
    for (i in 1 until points.size - 1) {
        val prev = points[i - 1]; val cur = points[i]; val next = points[i + 1]
        val dPrev = (cur.x - prev.x) * (cur.x - prev.x) + (cur.y - prev.y) * (cur.y - prev.y)
        val dNext = (cur.x - next.x) * (cur.x - next.x) + (cur.y - next.y) * (cur.y - next.y)
        if (dPrev > OUTLIER_DIST_SQ && dNext > OUTLIER_DIST_SQ) continue
        out.add(cur)
    }
    out.add(points.last())
    return out
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*ShotDetectionTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/shots \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/shots
git commit -m "feat: port shuttle shot detection to Kotlin

Collapses shot_detection.py and utils/shotDetection.ts, which are the
same algorithm maintained twice, into one implementation."
```

---

## Task 11: The shot-gap rally detector

Port of `detect_rallies_from_shots`. This is the detector whose output drives clip cutting and which the browser reproduces exactly.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/ShotGapRallyDetector.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally/ShotGapRallyDetectorTest.kt`

**Interfaces:**
- Consumes: `FrameSample`, `Shot`, `detectShuttleShots` from Task 10
- Produces:
  - `data class Rally(val id: Int, val startFrame: Int, val endFrame: Int, val startTimestamp: Double, val endTimestamp: Double, val durationSeconds: Double)`
  - `fun detectRalliesFromShots(frames: List<FrameSample>, fps: Double): List<Rally>`
  - constants `MIN_SHOTS = 2`, `RALLY_GAP_SECONDS = 3.1`, `MIN_RALLY_DURATION_S = 0.8`, `SHUTTLE_VISIBILITY_THRESHOLD = 0.25`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ShotGapRallyDetectorTest {

    private val fps = 30.0

    /** Build frames where the shuttle oscillates, producing a shot per swing. */
    private fun rallyFrames(startFrame: Int, swings: Int, framesPerSwing: Int = 24):
        List<FrameSample> {
        val out = ArrayList<FrameSample>()
        var f = startFrame
        for (s in 0 until swings) {
            val forward = s % 2 == 0
            for (i in 0 until framesPerSwing) {
                val x = if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0
                out.add(FrameSample(f, f / fps, ShuttleSample(x, 400.0, visible = true)))
                f++
            }
        }
        return out
    }

    private fun idleFrames(startFrame: Int, count: Int): List<FrameSample> =
        (0 until count).map { FrameSample(startFrame + it, (startFrame + it) / fps, null) }

    @Test
    fun a_single_exchange_is_one_rally() {
        val r = detectRalliesFromShots(rallyFrames(0, swings = 6), fps)
        r.size shouldBe 1
        r[0].id shouldBe 1
    }

    @Test
    fun a_gap_longer_than_the_threshold_splits_two_rallies() {
        // RALLY_GAP_SECONDS is 3.1s; 120 idle frames at 30fps is 4s.
        val frames = rallyFrames(0, 6) + idleFrames(144, 120) + rallyFrames(264, 6)
        detectRalliesFromShots(frames, fps).size shouldBe 2
    }

    @Test
    fun a_trailing_isolated_shot_does_not_weld_dead_air_onto_the_last_rally() {
        // The defect this guards: when the final shot is both last AND beyond
        // the gap threshold, including it stretched the previous rally's end
        // across the whole inter-rally pause.
        val frames = rallyFrames(0, 6) + idleFrames(144, 150) + rallyFrames(294, 2)
        val r = detectRalliesFromShots(frames, fps)
        r.size shouldBe 1
        (r[0].endTimestamp < 144 / fps + 0.5) shouldBe true
    }

    @Test
    fun a_rally_shorter_than_the_minimum_duration_is_rejected() {
        detectRalliesFromShots(rallyFrames(0, swings = 2, framesPerSwing = 6), fps) shouldBe
            emptyList()
    }

    @Test
    fun a_window_with_too_little_shuttle_data_is_rejected() {
        // Replay cuts and crowd shots produce shots from noise but carry almost
        // no shuttle. Below 25% visibility in the window the rally is dropped.
        val sparse = rallyFrames(0, 6).mapIndexed { i, f ->
            if (i % 5 == 0) f else f.copy(shuttle = null)
        }
        val padded = sparse + idleFrames(144, 400)
        detectRalliesFromShots(padded, fps) shouldBe emptyList()
    }

    @Test
    fun too_few_frames_yields_nothing() {
        detectRalliesFromShots(rallyFrames(0, 1).take(5), fps) shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*ShotGapRallyDetectorTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `ShotGapRallyDetector.kt`**

```kotlin
package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shots.detectShuttleShots

const val MIN_SHOTS: Int = 2
const val RALLY_GAP_SECONDS: Double = 3.1
const val MIN_RALLY_DURATION_S: Double = 0.8

/** Fraction of frames in a candidate window that must carry a shuttle position. */
const val SHUTTLE_VISIBILITY_THRESHOLD: Double = 0.25

data class Rally(
    val id: Int,
    val startFrame: Int,
    val endFrame: Int,
    val startTimestamp: Double,
    val endTimestamp: Double,
    val durationSeconds: Double,
)

/**
 * Group shots into rallies by inter-shot gap.
 *
 * Port of `detect_rallies_from_shots`, which is itself the Python twin of the
 * browser's grouping loop. This is the separation that drives clip cutting.
 *
 * The `require_players` gate in the Python original is absent here: this
 * module runs before any player data exists, which is the same position Phase 1
 * is in, and the cloud passes require_players=False there for the same reason.
 */
fun detectRalliesFromShots(frames: List<FrameSample>, fps: Double): List<Rally> {
    if (frames.size < 10) return emptyList()
    val shots = detectShuttleShots(frames, fps)
    if (shots.size < MIN_SHOTS) return emptyList()

    fun shuttleActive(startTs: Double, endTs: Double): Boolean {
        var total = 0
        var visible = 0
        for (f in frames) {
            if (f.timestamp < startTs || f.timestamp > endTs) continue
            total++
            if (f.shuttle?.visible == true) visible++
        }
        return total == 0 || visible.toDouble() / total >= SHUTTLE_VISIBILITY_THRESHOLD
    }

    val detected = ArrayList<Rally>()
    var rallyStart = 0
    for (i in 1 until shots.size) {
        val gap = shots[i].timestamp - shots[i - 1].timestamp
        val isLast = i == shots.size - 1
        if (gap <= RALLY_GAP_SECONDS && !isLast) continue

        // Shot i joins the current rally only when it is the final shot AND
        // close enough to its predecessor. A shot that is both last and beyond
        // the gap starts a new one-shot rally, which is then rejected. Including
        // it welded the whole inter-rally pause onto the previous rally's end.
        val endExclusive = if (isLast && gap <= RALLY_GAP_SECONDS) i + 1 else i
        val group = shots.subList(rallyStart, endExclusive)
        if (group.size >= MIN_SHOTS) {
            val first = group.first()
            val last = group.last()
            val duration = last.timestamp - first.timestamp
            if (duration >= MIN_RALLY_DURATION_S && shuttleActive(first.timestamp, last.timestamp)) {
                detected.add(
                    Rally(
                        id = detected.size + 1,
                        startFrame = first.frame,
                        endFrame = last.frame,
                        startTimestamp = first.timestamp,
                        endTimestamp = last.timestamp,
                        durationSeconds = duration,
                    )
                )
            }
        }
        rallyStart = i
    }
    return detected
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*ShotGapRallyDetectorTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally
git commit -m "feat: port the shot-gap rally detector to Kotlin

Includes the trailing-isolated-shot guard, which is what stops an
inter-rally pause being welded onto the previous rally's end."
```

---

## Task 12: The gradient rally detector

Port of `rally_detection.detect_rallies`. Same shot-reversal idea as Task 10 but with its own stride and grouping rules, run over the filtered track. Its output is unioned with the shot-gap detector's for the stored rally list.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/GradientRallyDetector.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally/GradientRallyDetectorTest.kt`

**Interfaces:**
- Consumes: `ShuttleSample` from Task 9, `Rally` from Task 11
- Produces: `fun detectRalliesGradient(shuttlePositions: Map<Int, ShuttleSample>, fps: Double, totalFrames: Int, minRallyDurationS: Double = 0.8, minGapDurationS: Double = 3.0): List<Rally>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class GradientRallyDetectorTest {

    private val fps = 30.0

    private fun oscillating(startFrame: Int, swings: Int, framesPerSwing: Int = 24):
        Map<Int, ShuttleSample> {
        val out = LinkedHashMap<Int, ShuttleSample>()
        var f = startFrame
        for (s in 0 until swings) {
            val forward = s % 2 == 0
            for (i in 0 until framesPerSwing) {
                val x = if (forward) 300.0 + i * 45.0 else 1380.0 - i * 45.0
                out[f] = ShuttleSample(x, 400.0, visible = true)
                f++
            }
        }
        return out
    }

    @Test
    fun an_oscillating_shuttle_produces_one_rally() {
        val r = detectRalliesGradient(oscillating(0, 6), fps, totalFrames = 200)
        r.size shouldBe 1
    }

    @Test
    fun the_end_frame_carries_a_landing_buffer() {
        // The detected window ends at the last racket contact, so the shuttle
        // is still airborne. The cloud adds half a second for it to land.
        val r = detectRalliesGradient(oscillating(0, 6), fps, totalFrames = 400)
        val lastShotFrame = 6 * 24
        (r[0].endFrame >= lastShotFrame) shouldBe true
    }

    @Test
    fun an_empty_track_yields_nothing() {
        detectRalliesGradient(emptyMap(), fps, totalFrames = 100) shouldBe emptyList()
    }

    @Test
    fun a_non_positive_frame_rate_yields_nothing() {
        // The guard that made an unreadable frame rate silently produce zero
        // rallies in the cloud. Normalising fps upstream is what prevents it.
        detectRalliesGradient(oscillating(0, 6), 0.0, totalFrames = 200) shouldBe emptyList()
    }

    @Test
    fun two_separated_exchanges_split_into_two_rallies() {
        val combined = oscillating(0, 6) + oscillating(300, 6)
        detectRalliesGradient(combined, fps, totalFrames = 600).size shouldBe 2
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*GradientRallyDetectorTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `GradientRallyDetector.kt`**

```kotlin
package com.badmintontracker.analysis.rally

import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlin.math.max

/**
 * Rally detection over a filtered shuttle track.
 *
 * Port of `rally_detection.detect_rallies`. Overhead-camera thresholds only,
 * matching the cloud: the app supports one camera position.
 *
 * Note the deliberate constant mismatch with the shot-gap detector: this one
 * groups on a 3.0s gap and that one on 3.1s. The cloud has the same split and
 * the clipping audit calls it harmless but unshared. Reproducing it keeps the
 * union comparable; unifying them would be a behaviour change to measure, not
 * a tidy-up to make silently.
 */
fun detectRalliesGradient(
    shuttlePositions: Map<Int, ShuttleSample>,
    fps: Double,
    totalFrames: Int,
    minRallyDurationS: Double = 0.8,
    minGapDurationS: Double = 3.0,
): List<Rally> {
    if (shuttlePositions.isEmpty() || fps <= 0) return emptyList()

    val minShotGapFrames = max(3, (fps * 0.6).toInt())
    val minSpeedSq = 15.0 * 15.0
    val strideFrames = max(3, (fps * 0.3).toInt())

    val all = (0 until totalFrames).mapNotNull { f ->
        shuttlePositions[f]?.takeIf { it.visible }?.let { Triple(f, it.x, it.y) }
    }
    if (all.size < 5) return emptyList()

    val pts = arrayListOf(all.first())
    for (p in all.drop(1)) if (p.first - pts.last().first >= strideFrames) pts.add(p)
    if (pts.size < 3) return emptyList()

    val shotFrames = ArrayList<Int>()
    var lastShot = Int.MIN_VALUE
    for (i in 2 until pts.size) {
        val (f0, x0, y0) = pts[i - 2]
        val (f1, x1, y1) = pts[i - 1]
        val (_, x2, y2) = pts[i]
        val vx1 = x1 - x0; val vy1 = y1 - y0
        val vx2 = x2 - x1; val vy2 = y2 - y1
        val s1 = vx1 * vx1 + vy1 * vy1
        val s2 = vx2 * vx2 + vy2 * vy2
        if (s1 < minSpeedSq && s2 < minSpeedSq) continue
        if (vx1 * vx2 + vy1 * vy2 >= 0.0) continue
        if (f1 - lastShot < minShotGapFrames) continue
        shotFrames.add(f1)
        lastShot = f1
    }
    if (shotFrames.size < MIN_SHOTS) return emptyList()

    val rallyGapFrames = max(1, (minGapDurationS * fps).toInt())
    val minRallyFrames = max(1, (minRallyDurationS * fps).toInt())
    val landingBuffer = max(1, (0.5 * fps).toInt())

    val out = ArrayList<Rally>()
    var groupStart = 0
    for (i in 1 until shotFrames.size) {
        val gap = shotFrames[i] - shotFrames[i - 1]
        val isLast = i == shotFrames.size - 1
        if (gap <= rallyGapFrames && !isLast) continue

        val endIdx = if (isLast && gap <= rallyGapFrames) i else i - 1
        val group = shotFrames.subList(groupStart, endIdx + 1)
        if (group.size >= MIN_SHOTS) {
            val startFrame = group.first()
            val endFrame = group.last() + landingBuffer
            if (endFrame - startFrame >= minRallyFrames) {
                out.add(
                    Rally(
                        id = out.size + 1,
                        startFrame = startFrame,
                        endFrame = endFrame,
                        startTimestamp = startFrame / fps,
                        endTimestamp = endFrame / fps,
                        durationSeconds = (endFrame - startFrame) / fps,
                    )
                )
            }
        }
        groupStart = i
    }
    return out
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*GradientRallyDetectorTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/GradientRallyDetector.kt \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally/GradientRallyDetectorTest.kt
git commit -m "feat: port the gradient rally detector to Kotlin

Keeps the 3.0s versus 3.1s gap mismatch with the shot-gap detector, so
the union stays comparable to the cloud's."
```

---

## Task 13: Rally refinement, union and clip padding

Three combinators that turn two rally lists into the stored list and the clip list.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/RallyCombination.kt`
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/ClipWindows.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally/RallyCombinationTest.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally/ClipWindowsTest.kt`

**Interfaces:**
- Consumes: `Rally` from Task 11
- Produces:
  - `fun refineRallies(filtered: List<Rally>, raw: List<Rally>, fps: Double, maxExtensionSec: Double = RALLY_GAP_SECONDS): List<Rally>`
  - `fun unionRallies(a: List<Rally>, b: List<Rally>, fps: Double, overlapThreshold: Double = 0.5): List<Rally>`
  - `data class ClipWindow(val rally: Rally, val clipStart: Double, val clipEnd: Double)`
  - `fun padRallyWindows(rallies: List<Rally>, videoDuration: Double?, preRoll: Double = CLIP_PRE_ROLL_S, postRoll: Double = CLIP_POST_ROLL_S): List<ClipWindow>`
  - constants `CLIP_PRE_ROLL_S = 2.0`, `CLIP_POST_ROLL_S = 1.5`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.analysis.rally

import io.kotest.matchers.shouldBe
import kotlin.test.Test

private fun rally(id: Int, start: Double, end: Double, fps: Double = 30.0) = Rally(
    id = id,
    startFrame = (start * fps).toInt(),
    endFrame = (end * fps).toInt(),
    startTimestamp = start,
    endTimestamp = end,
    durationSeconds = end - start,
)

class RallyCombinationTest {

    @Test
    fun union_merges_two_detections_of_the_same_rally() {
        val a = listOf(rally(1, 10.0, 20.0))
        val b = listOf(rally(1, 10.5, 21.0))
        val u = unionRallies(a, b, 30.0)
        u.size shouldBe 1
        u[0].startTimestamp shouldBe 10.0
        u[0].endTimestamp shouldBe 21.0
    }

    @Test
    fun union_keeps_rallies_that_do_not_overlap_enough() {
        val u = unionRallies(listOf(rally(1, 10.0, 20.0)), listOf(rally(1, 40.0, 50.0)), 30.0)
        u.size shouldBe 2
        u.map { it.id } shouldBe listOf(1, 2)
    }

    @Test
    fun refine_widens_toward_the_raw_bounds_but_never_past_a_neighbour() {
        // The filtered track's splits are trustworthy but its tails are
        // over-trimmed; the raw track has better bounds but fabricates
        // rallies. Refinement takes the list from one and the edges from
        // the other, without letting a clip reach into its neighbour.
        val filtered = listOf(rally(1, 10.0, 20.0), rally(2, 23.5, 33.0))
        val raw = listOf(rally(1, 9.5, 25.0))
        val r = refineRallies(filtered, raw, 30.0)
        (r[0].startTimestamp <= 10.0) shouldBe true
        (r[0].endTimestamp <= 23.5) shouldBe true
    }

    @Test
    fun refine_drops_raw_rallies_that_overlap_nothing() {
        val filtered = listOf(rally(1, 10.0, 20.0))
        val raw = listOf(rally(1, 40.0, 50.0))
        val r = refineRallies(filtered, raw, 30.0)
        r.size shouldBe 1
        r[0].startTimestamp shouldBe 10.0
    }
}

class ClipWindowsTest {

    @Test
    fun padding_adds_pre_and_post_roll() {
        // A serve is not a direction reversal, so the first detected shot is
        // the RETURN of serve; the serve itself is always before the window.
        // And the window ends at the last contact, so the outcome is after it.
        val w = padRallyWindows(listOf(rally(1, 30.0, 40.0)), videoDuration = 120.0)
        w[0].clipStart shouldBe 28.0
        w[0].clipEnd shouldBe 41.5
    }

    @Test
    fun padding_never_reaches_into_a_neighbour() {
        val w = padRallyWindows(
            listOf(rally(1, 10.0, 20.0), rally(2, 21.0, 30.0)),
            videoDuration = 120.0,
        )
        (w[0].clipEnd <= 21.0) shouldBe true
        (w[1].clipStart >= 20.0) shouldBe true
    }

    @Test
    fun padding_is_clamped_to_the_video() {
        val w = padRallyWindows(listOf(rally(1, 1.0, 10.0)), videoDuration = 10.5)
        w[0].clipStart shouldBe 0.0
        w[0].clipEnd shouldBe 10.5
    }

    @Test
    fun padding_never_shrinks_the_detected_window() {
        // Relevant when incoming rallies already overlap: clamping to a
        // neighbour must not cut into the rally's own detected bounds.
        val w = padRallyWindows(
            listOf(rally(1, 10.0, 25.0), rally(2, 20.0, 30.0)),
            videoDuration = 120.0,
        )
        (w[0].clipEnd >= 25.0) shouldBe true
        (w[1].clipStart <= 20.0) shouldBe true
    }

    @Test
    fun input_is_sorted_and_not_mutated() {
        val input = listOf(rally(1, 30.0, 40.0), rally(2, 10.0, 20.0))
        val w = padRallyWindows(input, videoDuration = 120.0)
        w.map { it.rally.startTimestamp } shouldBe listOf(10.0, 30.0)
        input[0].startTimestamp shouldBe 30.0
    }

    @Test
    fun a_missing_duration_leaves_the_post_roll_unclamped() {
        val w = padRallyWindows(listOf(rally(1, 30.0, 40.0)), videoDuration = null)
        w[0].clipEnd shouldBe 41.5
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :analysis:jvmTest --tests "*RallyCombinationTest*" --tests "*ClipWindowsTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `RallyCombination.kt`**

```kotlin
package com.badmintontracker.analysis.rally

import kotlin.math.max
import kotlin.math.min

/**
 * Merge the filtered track's rally list with the raw track's bounds.
 *
 * The filtered track's splits are trustworthy but its noise rejection trims
 * rally tails; the raw track has accurate bounds but fabricates rallies in
 * idle windows. So: keep the filtered list, widen each edge toward any
 * overlapping raw bound by at most `maxExtensionSec`, and clamp to neighbours.
 * Raw rallies overlapping nothing are dropped entirely.
 */
fun refineRallies(
    filtered: List<Rally>,
    raw: List<Rally>,
    fps: Double,
    maxExtensionSec: Double = RALLY_GAP_SECONDS,
): List<Rally> {
    val safeFps = if (fps > 0) fps else 30.0
    val out = ArrayList<Rally>(filtered.size)
    for ((i, f) in filtered.withIndex()) {
        var start = f.startTimestamp
        var end = f.endTimestamp
        val overlapping = raw.filter {
            min(end, it.endTimestamp) > max(start, it.startTimestamp)
        }
        if (overlapping.isNotEmpty()) {
            val rawStart = overlapping.minOf { it.startTimestamp }
            val rawEnd = overlapping.maxOf { it.endTimestamp }
            start = max(start - maxExtensionSec, min(rawStart, start))
            end = min(end + maxExtensionSec, max(rawEnd, end))
        }
        if (i > 0) start = max(start, filtered[i - 1].endTimestamp + 0.05)
        if (i + 1 < filtered.size) end = min(end, filtered[i + 1].startTimestamp - 0.05)
        out.add(
            Rally(
                id = i + 1,
                startFrame = (start * safeFps).toInt(),
                endFrame = (end * safeFps).toInt(),
                startTimestamp = start,
                endTimestamp = end,
                durationSeconds = end - start,
            )
        )
    }
    return out
}

/**
 * Combine two rally lists, deduplicating by temporal overlap.
 *
 * Two rallies are the same when their overlap exceeds `overlapThreshold` of
 * the SHORTER one's duration. Overlapping pairs collapse to the union of
 * their bounds.
 */
fun unionRallies(
    a: List<Rally>,
    b: List<Rally>,
    fps: Double,
    overlapThreshold: Double = 0.5,
): List<Rally> {
    val safeFps = if (fps > 0) fps else 30.0
    val combined = ArrayList<DoubleArray>()  // [start, end]

    for (r in (a + b).sortedBy { it.startTimestamp }) {
        var absorbed = false
        for (c in combined) {
            val s = max(r.startTimestamp, c[0])
            val e = min(r.endTimestamp, c[1])
            if (e <= s) continue
            val shorter = min(r.endTimestamp - r.startTimestamp, c[1] - c[0])
            if (shorter <= 0 || (e - s) / shorter < overlapThreshold) continue
            c[0] = min(c[0], r.startTimestamp)
            c[1] = max(c[1], r.endTimestamp)
            absorbed = true
            break
        }
        if (!absorbed) combined.add(doubleArrayOf(r.startTimestamp, r.endTimestamp))
    }

    return combined.mapIndexed { i, c ->
        Rally(
            id = i + 1,
            startFrame = (c[0] * safeFps).toInt(),
            endFrame = (c[1] * safeFps).toInt(),
            startTimestamp = c[0],
            endTimestamp = c[1],
            durationSeconds = c[1] - c[0],
        )
    }
}
```

- [ ] **Step 4: Write `ClipWindows.kt`**

```kotlin
package com.badmintontracker.analysis.rally

import kotlin.math.max
import kotlin.math.min

/**
 * Clip padding, in seconds.
 *
 * Detected rally bounds are first-shot to last-shot contact, and both ends cut
 * off footage the viewer needs. A serve is not a direction reversal, so the
 * first detected shot is the RETURN of serve and the serve is always outside
 * the window. The window ends at the last contact, so the shuttle is still
 * airborne and the outcome is never on screen.
 */
const val CLIP_PRE_ROLL_S: Double = 2.0
const val CLIP_POST_ROLL_S: Double = 1.5

data class ClipWindow(val rally: Rally, val clipStart: Double, val clipEnd: Double) {
    val durationSeconds: Double get() = clipEnd - clipStart
}

/**
 * Widen each rally by pre and post roll.
 *
 * Padding may only consume dead air BETWEEN rallies: it never reaches into a
 * neighbour's detected window, so no two clips duplicate footage. It also never
 * shrinks a window below what was detected, which matters when the incoming
 * rallies themselves overlap. Returns a new list sorted by start; inputs are
 * not mutated.
 *
 * Padding applies at cut time only. The analytical rally bounds are unchanged,
 * so metrics are unaffected; the clip row stores the padded bounds because
 * those describe the file the apps actually play.
 */
fun padRallyWindows(
    rallies: List<Rally>,
    videoDuration: Double?,
    preRoll: Double = CLIP_PRE_ROLL_S,
    postRoll: Double = CLIP_POST_ROLL_S,
): List<ClipWindow> {
    val ordered = rallies.sortedBy { it.startTimestamp }
    return ordered.mapIndexed { i, r ->
        var clipStart = r.startTimestamp - preRoll
        var clipEnd = r.endTimestamp + postRoll

        if (i > 0) clipStart = max(clipStart, ordered[i - 1].endTimestamp)
        if (i + 1 < ordered.size) clipEnd = min(clipEnd, ordered[i + 1].startTimestamp)

        clipStart = max(0.0, min(clipStart, r.startTimestamp))
        clipEnd = max(clipEnd, r.endTimestamp)
        if (videoDuration != null && videoDuration > 0) clipEnd = min(clipEnd, videoDuration)

        ClipWindow(r, clipStart, clipEnd)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :analysis:jvmTest --tests "*RallyCombinationTest*" --tests "*ClipWindowsTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/RallyCombination.kt \
        analysis/src/commonMain/kotlin/com/badmintontracker/analysis/rally/ClipWindows.kt \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/rally
git commit -m "feat: port rally refinement, union and clip padding to Kotlin"
```

---

## Task 14: The corpus fixture loader

Test-only. Reads a trimmed fixture into the types the pipeline consumes, so Task 15's golden-file tests have something to run against.

**Files:**
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/corpus/CorpusFixture.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/corpus/CorpusFixtureTest.kt`
- Modify: `analysis/build.gradle.kts`

**Interfaces:**
- Consumes: `ShuttleSample`, `CourtKeypoints`, `Rally`
- Produces:
  - `data class CorpusEntry(val name: String, val fps: Double, val totalFrames: Int, val videoWidth: Int, val videoHeight: Int, val shuttlePositions: Map<Int, ShuttleSample>, val cloudRallies: List<Rally>, val cloudClips: List<CloudClip>, val keypoints: CourtKeypoints?)`
  - `data class CloudClip(val rallyIndex: Int, val startTimestamp: Double, val endTimestamp: Double)`
  - `expect fun loadCorpusEntry(name: String): CorpusEntry`

- [ ] **Step 1: Add the resources source set**

In `analysis/build.gradle.kts`, inside the `kotlin { sourceSets { ... } }` block, add:

```kotlin
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions)
        }
        jvmTest.resources.srcDir("src/commonTest/resources")
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.badmintontracker.analysis.corpus

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CorpusFixtureTest {

    @Test
    fun the_fixture_loads_with_a_usable_frame_rate_and_shuttle_track() {
        val e = loadCorpusEntry("sample")
        (e.fps > 0) shouldBe true
        (e.shuttlePositions.isNotEmpty()) shouldBe true
        (e.cloudRallies.isNotEmpty()) shouldBe true
    }

    @Test
    fun the_fixture_carries_the_twelve_court_keypoints() {
        // Without keypoints the ROI filter is skipped, and the comparison
        // would measure something different from what the cloud actually ran.
        // CourtKeypoints.fromMap returns null unless all twelve are present,
        // so a non-null result is the assertion that all twelve survived.
        val keypoints = loadCorpusEntry("sample").keypoints
        (keypoints != null) shouldBe true
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*CorpusFixtureTest*"`
Expected: compilation failure, `loadCorpusEntry` unresolved.

- [ ] **Step 4: Write the loader**

`analysis/src/commonTest/kotlin/com/badmintontracker/analysis/corpus/CorpusFixture.kt`:

```kotlin
package com.badmintontracker.analysis.corpus

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlinx.serialization.json.*

data class CloudClip(val rallyIndex: Int, val startTimestamp: Double, val endTimestamp: Double)

data class CorpusEntry(
    val name: String,
    val fps: Double,
    val totalFrames: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val shuttlePositions: Map<Int, ShuttleSample>,
    val cloudRallies: List<Rally>,
    val cloudClips: List<CloudClip>,
    val keypoints: CourtKeypoints?,
)

/** Reads the three fixture files. JVM-only; the golden tests run on jvmTest. */
expect fun readFixtureFile(name: String, file: String): String

fun loadCorpusEntry(name: String): CorpusEntry {
    val results = Json.parseToJsonElement(readFixtureFile(name, "results.json")).jsonObject
    val video = Json.parseToJsonElement(readFixtureFile(name, "video.json")).jsonObject
    val clips = Json.parseToJsonElement(readFixtureFile(name, "clips.json")).jsonArray

    val shuttle = results["shuttle_positions"]?.jsonObject.orEmpty().entries.associate { (k, v) ->
        val o = v.jsonObject
        k.toInt() to ShuttleSample(
            x = o["x"]!!.jsonPrimitive.double,
            y = o["y"]!!.jsonPrimitive.double,
            visible = o["visible"]!!.jsonPrimitive.boolean,
        )
    }

    val rallies = results["rallies"]?.jsonArray.orEmpty().mapIndexed { i, e ->
        val o = e.jsonObject
        Rally(
            id = o["id"]?.jsonPrimitive?.int ?: (i + 1),
            startFrame = o["start_frame"]!!.jsonPrimitive.int,
            endFrame = o["end_frame"]!!.jsonPrimitive.int,
            startTimestamp = o["start_timestamp"]!!.jsonPrimitive.double,
            endTimestamp = o["end_timestamp"]!!.jsonPrimitive.double,
            durationSeconds = o["duration_seconds"]!!.jsonPrimitive.double,
        )
    }

    val keypointsRaw = video["manual_court_keypoints"]?.takeIf { it !is JsonNull }
        ?.jsonObject?.entries?.associate { (k, v) ->
            k to v.jsonArray.map { it.jsonPrimitive.double }
        }

    return CorpusEntry(
        name = name,
        fps = results["fps"]!!.jsonPrimitive.double,
        totalFrames = results["total_frames"]!!.jsonPrimitive.int,
        videoWidth = results["video_width"]?.jsonPrimitive?.int ?: 1920,
        videoHeight = results["video_height"]?.jsonPrimitive?.int ?: 1080,
        shuttlePositions = shuttle,
        cloudRallies = rallies,
        cloudClips = clips.map {
            val o = it.jsonObject
            CloudClip(
                rallyIndex = o["rally_index"]!!.jsonPrimitive.int,
                startTimestamp = o["start_timestamp"]!!.jsonPrimitive.double,
                endTimestamp = o["end_timestamp"]!!.jsonPrimitive.double,
            )
        },
        keypoints = keypointsRaw?.let { CourtKeypoints.fromMap(it) },
    )
}
```

`analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/corpus/CorpusFixture.jvm.kt`:

```kotlin
package com.badmintontracker.analysis.corpus

actual fun readFixtureFile(name: String, file: String): String {
    val path = "/corpus/$name/$file"
    val stream = object {}.javaClass.getResourceAsStream(path)
        ?: error("fixture not found: $path. Run tools/corpus/trim_corpus.py first.")
    return stream.bufferedReader().use { it.readText() }
}
```

- [ ] **Step 5: Rename one fixture to `sample`**

The tests reference a fixture named `sample`. Ensure `analysis/src/commonTest/resources/corpus/sample/` exists with the three files, from Task 1 step 4.

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :analysis:jvmTest --tests "*CorpusFixtureTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add analysis/build.gradle.kts \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/corpus \
        analysis/src/jvmTest
git commit -m "test: load captured cloud output as test fixtures"
```

---

## Task 15: The Phase 1 pipeline and its golden-file test

Ties every previous task together and asserts the Kotlin port agrees with the cloud on real recorded data. This is the deliverable that makes the whole plan worth executing.

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/Phase1Pipeline.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/Phase1PipelineTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 6 to 14
- Produces:
  - `data class Phase1Input(val rawShuttle: Map<Int, ShuttleSample>, val fps: Double, val totalFrames: Int, val videoWidth: Int, val videoHeight: Int, val videoDuration: Double?, val keypoints: CourtKeypoints?)`
  - `data class Phase1Output(val storedRallies: List<Rally>, val clipWindows: List<ClipWindow>, val filteredTrack: Map<Int, ShuttleSample>)`
  - `fun runPhase1(input: Phase1Input): Phase1Output`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis

import com.badmintontracker.analysis.corpus.loadCorpusEntry
import com.badmintontracker.analysis.rally.Rally
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

class Phase1PipelineTest {

    private fun run(name: String): Pair<Phase1Output, List<Rally>> {
        val e = loadCorpusEntry(name)
        val out = runPhase1(
            Phase1Input(
                rawShuttle = e.shuttlePositions,
                fps = e.fps,
                totalFrames = e.totalFrames,
                videoWidth = e.videoWidth,
                videoHeight = e.videoHeight,
                videoDuration = e.totalFrames / e.fps,
                keypoints = e.keypoints,
            )
        )
        return out to e.cloudRallies
    }

    @Test
    fun the_stored_rally_count_matches_the_cloud() {
        val (out, cloud) = run("sample")
        out.storedRallies.size shouldBe cloud.size
    }

    @Test
    fun every_stored_rally_lines_up_with_a_cloud_rally() {
        // Bounds are compared with a tolerance of one frame. Exact equality
        // would be the wrong assertion: the cloud's timestamps come from
        // container PTS and the fixture's from the same source, but the
        // gradient detector derives its own from frame/fps, so sub-frame
        // disagreement is expected and harmless.
        val (out, cloud) = run("sample")
        val tolerance = 1.0 / loadCorpusEntry("sample").fps
        out.storedRallies.zip(cloud.sortedBy { it.startTimestamp }).forEach { (mine, theirs) ->
            (abs(mine.startTimestamp - theirs.startTimestamp) <= tolerance) shouldBe true
            (abs(mine.endTimestamp - theirs.endTimestamp) <= tolerance) shouldBe true
        }
    }

    @Test
    fun clip_windows_match_the_rally_clips_rows_the_cloud_wrote() {
        // rally_clips stores PADDED bounds, which describe the file the apps
        // play. This is the assertion that proves local clips would be cut at
        // the same offsets, which is what makes annotations portable.
        val e = loadCorpusEntry("sample")
        val (out, _) = run("sample")
        if (e.cloudClips.isEmpty()) return
        out.clipWindows.size shouldBe e.cloudClips.size
        out.clipWindows.zip(e.cloudClips.sortedBy { it.rallyIndex }).forEach { (mine, theirs) ->
            (abs(mine.clipStart - theirs.startTimestamp) <= 0.1) shouldBe true
            (abs(mine.clipEnd - theirs.endTimestamp) <= 0.1) shouldBe true
        }
    }

    @Test
    fun clip_windows_never_overlap() {
        val (out, _) = run("sample")
        out.clipWindows.zipWithNext().forEach { (a, b) ->
            (a.clipEnd <= b.clipStart) shouldBe true
        }
    }

    @Test
    fun an_empty_shuttle_track_produces_no_rallies_and_no_clips() {
        val out = runPhase1(
            Phase1Input(emptyMap(), 30.0, 1000, 1920, 1080, 33.3, null)
        )
        out.storedRallies shouldBe emptyList()
        out.clipWindows shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests "*Phase1PipelineTest*"`
Expected: compilation failure.

- [ ] **Step 3: Write `Phase1Pipeline.kt`**

```kotlin
package com.badmintontracker.analysis

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.ClipWindow
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.rally.detectRalliesFromShots
import com.badmintontracker.analysis.rally.detectRalliesGradient
import com.badmintontracker.analysis.rally.padRallyWindows
import com.badmintontracker.analysis.rally.refineRallies
import com.badmintontracker.analysis.rally.unionRallies
import com.badmintontracker.analysis.shots.FrameSample
import com.badmintontracker.analysis.shuttle.ShuttleSample
import com.badmintontracker.analysis.shuttle.buildFilteredTrack

data class Phase1Input(
    val rawShuttle: Map<Int, ShuttleSample>,
    val fps: Double,
    val totalFrames: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoDuration: Double?,
    val keypoints: CourtKeypoints?,
)

data class Phase1Output(
    /** Gradient union shot-gap. Goes into results.json, matching the cloud. */
    val storedRallies: List<Rally>,
    /** The confirmed separation, padded. Drives clip cutting. */
    val clipWindows: List<ClipWindow>,
    val filteredTrack: Map<Int, ShuttleSample>,
)

/**
 * Phase 1: raw shuttle track in, rally list and clip windows out.
 *
 * Two rally sets leave here, exactly as in the cloud worker:
 *   - storedRallies: gradient union raw shot-gap, the "combined" set, so both
 *     timelines remain available to any consumer of results.json
 *   - clipWindows:   filtered shot-gap refined toward raw bounds, then padded.
 *     This is the separation the apps actually see as clips.
 *
 * The two shot-gap runs use different tracks on purpose. The filtered track's
 * noise rejection gives trustworthy splits but trims tails; the raw track has
 * accurate bounds but fabricates rallies in idle windows.
 */
fun runPhase1(input: Phase1Input): Phase1Output {
    val fps = normalizeFps(input.fps).fps

    val filtered = buildFilteredTrack(
        raw = input.rawShuttle,
        fps = fps,
        videoWidth = input.videoWidth,
        videoHeight = input.videoHeight,
        courtCorners = input.keypoints?.corners,
    )

    val gradient = detectRalliesGradient(filtered, fps, input.totalFrames)

    fun frames(track: Map<Int, ShuttleSample>): List<FrameSample> =
        (0 until input.totalFrames).map { f ->
            FrameSample(f, f / fps, track[f]?.takeIf { it.visible })
        }

    val rawShotGap = detectRalliesFromShots(frames(input.rawShuttle), fps)
    val filteredShotGap = detectRalliesFromShots(frames(filtered), fps)

    val stored = unionRallies(gradient, rawShotGap, fps)
    val clipRallies = refineRallies(filteredShotGap, rawShotGap, fps)
        .ifEmpty { stored }

    return Phase1Output(
        storedRallies = stored,
        clipWindows = padRallyWindows(clipRallies, input.videoDuration),
        filteredTrack = filtered,
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :analysis:jvmTest`
Expected: PASS, all tests in the module.

If the rally counts disagree with the cloud, that is the plan working as intended. Do **not** loosen the assertion. Diagnose by comparing intermediate outputs: does `filteredTrack` visibility match the cloud's `shuttle_positions`? Do the shot counts match? Record the discrepancy and its cause. A genuine, understood divergence gets added to the design's §6 register with its reason; anything else is a porting bug to fix.

- [ ] **Step 5: Run the whole suite and both other modules**

Run: `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: all PASS. Confirms nothing in the new module broke existing builds.

- [ ] **Step 6: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/Phase1Pipeline.kt \
        analysis/src/commonTest/kotlin/com/badmintontracker/analysis/Phase1PipelineTest.kt
git commit -m "feat: Phase 1 pipeline in Kotlin, verified against cloud output

Produces both rally sets the cloud produces: the gradient/shot-gap
union for storage, and the refined filtered separation for clips. The
golden-file test asserts agreement with recorded cloud results on real
footage."
```

---

## Done criteria

- `./gradlew :analysis:jvmTest` passes in CI on every push.
- Task 4's gate passed on every corpus video, with the coverage numbers recorded.
- Task 5's throughput numbers exist, with a proposed §5.6 routing threshold.
- `tools/models/manifest.json` pins all four source weights.
- The TrackNet licence is vendored and the checkpoint question is answered in writing.
- Any divergence from cloud found in Task 15 is either fixed or added to the design's §6 register with a reason.

## What comes next, and why it is not in this plan

The device layer is a separate plan, written once Tasks 4 and 5 have produced numbers: the `RawInference` binary format, the iOS single decode pass, ONNX Runtime integration, TrackNet heatmap peak extraction, device-side clip cutting, the Supabase sync path, and the A/B comparator's levels 1 and 2. Task 15's golden-file test is level 3 of that comparator, already built.

Writing those tasks now would mean committing detail to decisions that Task 4 can invalidate and Task 5 should shape.
