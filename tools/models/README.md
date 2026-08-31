# On-device model tooling

Scripts for the Stage 0 on-device ML gates: acquire and pin the source
weights, convert TrackNet and InpaintNet to ONNX, prove numerical parity,
and measure shuttle coverage against real footage. This directory only
contains tooling; none of the acquisition, conversion, or measurement steps
below have been run yet in this environment (see "Blocked on").

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

2. **Export TrackNet and InpaintNet to ONNX** - `export_tracknet.py`

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

   Also loads `tools/models/weights/inpaintnet.pt`, builds `InpaintNet`, and
   exports it to `tools/models/onnx/inpaintnet.onnx` /
   `inpaintnet.fp16.onnx`. Unlike TrackNet's frame axis, InpaintNet's input
   `(1, 3, length)` has a **dynamic** length axis, not a static one:
   production chunks a trajectory with `chunk_size=256`, `stride=128`
   (`inference.py:356-357`) and pads only to the next multiple of 8
   (`inference.py:395`), so real chunk lengths range from 16 to 256. The
   traced graph is only valid for lengths that are a multiple of 8 - see the
   comment in `_export_inpaintnet` for why that is guaranteed by
   production's own padding and is not a workaround. InpaintNet is now a
   required export, not optional: the on-device gate below runs it by
   default alongside TrackNet.

3. **Check numerical parity**

   - TrackNet - `check_tracknet_parity.py`

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

   - InpaintNet - `check_inpaintnet_parity.py` (a sibling script, not an
     extension of the one above - see its module docstring for why)

     ```bash
     python tools/models/check_inpaintnet_parity.py --tracker-repo ../badminton-tracker
     ```

     Compares PyTorch and ONNX Runtime output on synthetic trajectory
     input. There is no `--video`-equivalent real-input mode: InpaintNet's
     real input is a trajectory already produced by running TrackNet plus
     blob detection over a real video, so a "real" mode here would depend
     on the very TrackNet ONNX conversion the sibling script exists to
     gate. This script's result is therefore **always PARITY
     INCONCLUSIVE** (exit 0), for the same reason `check_tracknet_parity.py`
     treats its synthetic fallback as inconclusive: InpaintNet also ends in
     a sigmoid, so synthetic input is not representative of the correlated,
     mostly-in-range trajectories it actually sees.

4. **Measure shuttle coverage against real footage** - the 0a gate,
   `measure_shuttle_coverage.py`

   ```bash
   python tools/models/measure_shuttle_coverage.py <video.mp4> \
       --tracker-repo ../badminton-tracker \
       [--onnx tools/models/onnx/tracknet.fp16.onnx] \
       [--inpaintnet-onnx tools/models/onnx/inpaintnet.fp16.onnx] \
       [--tracknet-only] \
       [--corpus corpus/<video_id>]
   ```

   Runs production's own `TrackNetInference.track_video`
   (`badminton-tracker/backend/tracknet/inference.py`) twice over the same
   real source video (from `tools/corpus/`, see `tools/corpus/README.md`):
   once with the real PyTorch TrackNet and InpaintNet, once with those same
   model attributes swapped for a thin ONNX Runtime shim (by default both
   TrackNet and InpaintNet are swapped - the deployed on-device
   configuration; pass `--tracknet-only` to swap only TrackNet and isolate
   its conversion when the combined gate fails). Every other step -
   median background, blob detection, InpaintNet gap-filling, coordinate
   scaling - is the same production code on both sides, so a divergence can
   only come from the ONNX conversion. Writes both results to
   `tools/models/reports/coverage-<name>.json`:

   - **(a) THE GATE - conversion fidelity.** Reports per-frame visibility
     agreement and the pixel delta (converted back to the 512x288 model
     space) on frames both sides consider visible. Needs no cloud data
     (`--corpus` is optional for this part) and is what the script's exit
     code is based on.
   - **(b) INFORMATIONAL - reimplementation fidelity, only if `--corpus` is
     given.** Local `track_video` output versus the cloud's
     `results.json["shuttle_positions"]`. This is explicitly **not** a
     gate, but the caveat is now much weaker than it used to be: the local
     side runs the same background/blob-detection/InpaintNet/scaling
     pipeline the cloud does, via the same `track_video`. What remains is
     the cloud's court-ROI polygon rejection, static-cluster suppression,
     and minimum-movement suppression on top of its own `track_video`
     output, plus the ONNX conversion and any decode differences - not a
     whole reimplemented pipeline. No coverage ratio is computed for this
     section.

   **HONEST CAVEAT: this is not the identical comparison production runs.**
   The script forces `device="cpu"` so it runs anywhere, which means the
   PyTorch reference side is fp32. Production only ever runs on CUDA at
   fp16 (`TrackNetInference.use_half` is true only when
   `torch.cuda.is_available()`), comparing fp16-vs-fp16. This gate instead
   compares fp32-vs-fp16, which is **stricter** than the fp16-to-fp16
   comparison it stands in for (fp32 starts with less rounding error, so
   any divergence measured here is not smaller than fp16-to-fp16 would
   show). Stricter is the safe direction for a gate, but a reader should
   not read these numbers as literally what production sees on GPU.

   **Performance note:** this runs `track_video`'s full pipeline (including
   median-background sampling over up to 300 frames) twice, entirely on
   CPU. Use a short clip.

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
   conversion is fine. A video that will not open at all (bad path,
   unreadable file) is now caught by `track_video`'s own `cap.isOpened()`
   check (`inference.py:145-146`) rather than by this script reopening it -
   this script no longer decodes video itself, so that loud-failure
   responsibility now belongs to production code, not a reimplementation
   of it. It surfaces the same way: printed to stderr, exit 2.

## Blocked on

Four environment gaps stop every measurement step above from having been
run as part of this change:

- No Supabase credentials exist (Task 1 cannot fetch)
- The Modal CLI is not installed (Task 2 cannot pull TrackNet/InpaintNet
  weights)
- `onnx`, `onnxruntime`, and `onnxconverter-common` are not installed (Task 3
  needs all three - `export_tracknet.py` imports `onnxconverter_common`
  directly for the fp16 conversion pass, not just `onnx`/`onnxruntime`, for
  both TrackNet and InpaintNet - and Task 4 needs `onnxruntime`)
- No source `.mp4` or corpus exists (Task 4 cannot measure)

Everything in this directory is the tooling those steps need once a human
supplies the missing credentials, CLI, packages, and source footage. No
weights have been pulled, no `manifest.json` or vendored TrackNetV3 licence
exists yet, no ONNX export has been produced, and no parity or coverage
numbers have been recorded.
