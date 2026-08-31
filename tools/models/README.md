# On-device model tooling

Scripts for the Stage 0 on-device ML gates: acquire and pin the source
weights, convert TrackNet to ONNX, prove numerical parity, and measure
shuttle coverage against real footage. This directory only contains
tooling; none of the acquisition, conversion, or measurement steps below
have been run yet in this environment (see "Blocked on").

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
   python tools/models/check_tracknet_parity.py --tracker-repo ../badminton-tracker
   ```

   Runs the same random input through PyTorch and through ONNX Runtime and
   compares heatmap peak locations rather than raw tensors, because the
   pipeline only ever consumes the argmax of each heatmap. Passes when the
   largest peak shift across all trials is at most 1 pixel at 512x288.
   Random input is a deliberately harsh test: if noise passes, real footage
   (which produces much sharper peaks) will too. This is a hard gate -
   Task 4 must not run against a build that fails this check.

4. **Measure shuttle coverage against real footage** - the 0a gate,
   `measure_shuttle_coverage.py`

   ```bash
   python tools/models/measure_shuttle_coverage.py <video.mp4> --corpus corpus/<video_id>
   ```

   Runs the converted fp16 ONNX model over every frame of a real source
   video (from `tools/corpus/`, see `tools/corpus/README.md`), thresholds
   each heatmap peak at 0.5 the same way the cloud does, and compares the
   fraction of frames carrying a visible shuttle against what the cloud
   recorded in that video's `results.json`. Writes
   `tools/models/reports/coverage-<video_id>.json` and prints a coverage
   ratio (local coverage / cloud coverage).

   **This is the gate that decides whether the rest of the on-device plan
   proceeds.** A coverage ratio at or above 0.90 on every corpus video means
   proceed; below that on any of them means stop and report rather than
   continuing to later tasks; the design's assumption that on-device
   perception can match the cloud would not hold, and the remaining plan
   should not be executed until that is resolved. This has not been run yet
   in this environment; no result is recorded.

## Blocked on

Four environment gaps stop every measurement step above from having been
run as part of this change:

- No Supabase credentials exist (Task 1 cannot fetch)
- The Modal CLI is not installed (Task 2 cannot pull TrackNet/InpaintNet
  weights)
- `onnx` and `onnxruntime` are not installed (Tasks 3 and 4 cannot execute)
- No source `.mp4` or corpus exists (Task 4 cannot measure)

Everything in this directory is the tooling those steps need once a human
supplies the missing credentials, CLI, packages, and source footage. No
weights have been pulled, no `manifest.json` or vendored TrackNetV3 licence
exists yet, no ONNX export has been produced, and no parity or coverage
numbers have been recorded.
