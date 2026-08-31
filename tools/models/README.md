# On-device model tooling

Scripts for the Stage 0 on-device ML gates: acquire and pin the source
weights, convert TrackNet to ONNX, prove numerical parity, and measure
shuttle coverage against real footage. This directory only contains
tooling; none of the acquisition, conversion, or measurement steps below
have been run yet in this environment (see "Blocked on").

Run every script below from the repo root; their paths
(`tools/models/weights/`, `tools/models/onnx/`, `tools/models/reports/`) are
relative to it.

## Sequence

The scripts are meant to run in this order, each depending on the previous
step's output:

1. **Pull and pin the weights** - `pull_weights.py`

   ```bash
   python tools/models/pull_weights.py --tracker-repo ../badminton-tracker
   ```

   Pulls `tracknet.pt` and `inpaintnet.pt` from the Modal volume
   `badminton-tracker-models`, copies the badminton detector weight out of
   the (read-only) `badminton-tracker` checkout, and resolves
   `yolo26m-pose.pt` through Ultralytics. Writes all four to
   `tools/models/weights/` and records each one's source, SHA-256, byte
   size, and retrieval time in `tools/models/manifest.json`.

   The Modal pulls are skipped (not re-fetched) if their destination file
   already exists, since `modal volume get` refuses to overwrite one - so a
   re-run after a partial failure is safe. To force a full refetch, clear
   `tools/models/weights/` first.

   The TrackNetV3 licence also needs verifying and vendoring as part of this
   step: confirm the upstream licence at
   `https://github.com/qaz812345/TrackNetV3`, save it to
   `tools/models/licenses/TrackNetV3-LICENSE.txt`, and record here whether
   the published checkpoints (as opposed to the code) carry separate terms.
   `manifest.json` and the licence file are not yet part of this repo; see
   "Blocked on" below.

2. **Export TrackNet to ONNX** - `export_tracknet.py`

   ```bash
   python tools/models/export_tracknet.py --tracker-repo ../badminton-tracker
   ```

   Loads `tools/models/weights/tracknet.pt`, builds the `TrackNet` module
   from `badminton-tracker/backend/tracknet/model.py`, and exports it with
   static shapes: input `(1, 27, 288, 512)` (8 sequence frames plus one
   background frame, 3 channels each, at 512x288), output `(1, 8, 288, 512)`
   (one heatmap per input frame). Writes `tools/models/onnx/tracknet.onnx`
   (fp32) and `tools/models/onnx/tracknet.fp16.onnx` (fp16, `keep_io_types`
   so the I/O tensors stay fp32).

3. **Check numerical parity** - `check_tracknet_parity.py`

   ```bash
   python tools/models/check_tracknet_parity.py --tracker-repo ../badminton-tracker --video <video.mp4>
   ```

   Runs the same decoded input through PyTorch and through ONNX Runtime
   (`--onnx`, default `tools/models/onnx/tracknet.fp16.onnx`) and compares
   heatmap peak locations rather than raw tensors, because the pipeline
   only ever consumes the argmax of each heatmap. Passes when the largest
   peak shift across all trials is at most 1 pixel at 512x288.

   **Prefer `--video` with a real source clip.** TrackNet ends in a sigmoid
   (`backend/tracknet/model.py:120`), so on uniform random noise the whole
   heatmap comes out nearly flat, and the argmax of a near-flat field is
   maximally sensitive to fp16 rounding - the opposite of a conservative
   stand-in for real footage. If `--video` is omitted, the script falls
   back to synthetic noise and reports **PARITY INCONCLUSIVE** (exit 0)
   rather than PARITY FAILED, since a noise-only failure is not evidence
   the conversion is broken. Only a run against real video produces a
   pass/fail gate. Task 4 must not run against a build that fails a
   real-video parity check.

4. **Measure shuttle coverage against real footage** - the 0a gate,
   `measure_shuttle_coverage.py`

   ```bash
   python tools/models/measure_shuttle_coverage.py <video.mp4> \
       --tracker-repo ../badminton-tracker \
       [--onnx tools/models/onnx/tracknet.fp16.onnx] \
       [--corpus corpus/<video_id>]
   ```

   Runs two independent measurements over the same real source video (from
   `tools/corpus/`, see `tools/corpus/README.md`) and writes both to
   `tools/models/reports/coverage-<name>.json`:

   - **(a) THE GATE - conversion fidelity.** PyTorch TrackNet versus the
     exported ONNX fp16 model, on identically decoded frames with
     identical postprocessing. Reports per-frame visibility agreement and
     the pixel delta (at 512x288) on frames both consider visible. Needs
     no cloud data (`--corpus` is optional for this part) and is what the
     script's exit code is based on.
   - **(b) INFORMATIONAL - reimplementation fidelity, only if `--corpus` is
     given.** Local PyTorch output versus the cloud's
     `results.json["shuttle_positions"]`. This is explicitly **not** a
     gate: the cloud value is a filtered track (court-ROI rejection,
     static-cluster suppression, minimum-movement suppression on top of
     blob detection and InpaintNet gap-fill), and the local value here is
     raw model output, so a divergence is expected and says nothing about
     the ONNX conversion. No coverage ratio is computed for this section.

   **(a) is the gate that decides whether the rest of the on-device plan
   proceeds.** Visibility agreement at or above 99% and a p95 delta at or
   below 2px on every corpus video means proceed; falling short on any of
   them means stop and report rather than continuing to later tasks; the
   design's assumption that the on-device conversion preserves the
   PyTorch model's behavior would not hold, and the remaining plan should
   not be executed until that is resolved. This has not been run yet in
   this environment; no result is recorded.

   If the shuttle is visible on too small a fraction of frames to measure a
   position delta on at all (wrong video, a clip with no play, or similar),
   the script prints **GATE UNMEASURABLE** and exits 2 rather than a false
   PASS - agreeing "not visible" on every frame is not evidence the
   conversion is fine.

## Blocked on

Four environment gaps stop every measurement step above from having been
run as part of this change:

- No Supabase credentials exist (Task 1 cannot fetch)
- The Modal CLI is not installed (Task 2 cannot pull TrackNet/InpaintNet
  weights)
- `onnx`, `onnxruntime`, and `onnxconverter-common` are not installed (Task 3
  needs all three - `export_tracknet.py` imports `onnxconverter_common`
  directly for the fp16 conversion pass, not just `onnx`/`onnxruntime` -
  and Task 4 needs `onnxruntime`)
- No source `.mp4` or corpus exists (Task 4 cannot measure)

Everything in this directory is the tooling those steps need once a human
supplies the missing credentials, CLI, packages, and source footage. No
weights have been pulled, no `manifest.json` or vendored TrackNetV3 licence
exists yet, no ONNX export has been produced, and no parity or coverage
numbers have been recorded.
