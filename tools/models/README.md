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
   (`inference.py:367-368`) and pads only to the next multiple of 8
   (`inference.py:401`), so real chunk lengths range from 16 to 256. The
   traced graph is only valid for lengths that are a multiple of 8 - see the
   comment in `_export_inpaintnet` for why that is guaranteed by
   production's own padding and is not a workaround. InpaintNet is now a
   required export, not optional: the on-device gate below runs it by
   default alongside TrackNet.

   **Run `check_inpaintnet_parity.py` (step 3 below) immediately after this
   export, before step 4.** Tracing at a single length does not prove the
   dynamic axis actually behaves dynamically in the exported graph - see
   that script's module docstring for the specific risk (a `sizes` constant
   baked from the trace instead of a length-agnostic `scales`). It sweeps
   multiple lengths by default for exactly this reason; do not skip it or
   narrow it to a single `--lengths 256` and call the export verified.

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

     **Sweeps multiple trajectory lengths by default** (`--lengths`,
     default `16,24,32,64,128,136,192,248,256`), not just the 256 the
     export was traced at - a single-length run cannot tell whether the
     exported graph's Resize nodes are genuinely length-agnostic or just
     happen to work at the traced length. See the module docstring for
     why. Do not narrow this to a single length and treat the result as a
     verified export.

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
   `tools/models/reports/coverage-<name>-<full|tracknet-only>.json` (the
   configuration is part of the filename so a `--tracknet-only` isolation
   run does not overwrite the deployed-config report for the same video).
   The report also records `provenance`: the video path, `--tracker-repo`
   path and its git commit if it is a git checkout, both weights paths,
   both ONNX paths, `swapped`, `batch_size`, and `max_bg_samples` - enough
   to trace the artifact back to exactly what produced it.

   - **(a) THE GATE - conversion fidelity.** Has THREE terms, not two.
     Reports per-frame visibility agreement and the pixel delta (converted
     back to the 512x288 model space) on frames both sides consider
     visible - but those two are both whole-video, frame-denominated
     aggregates, and InpaintNet by construction only ever touches a small
     minority of frames (the gaps it fills). A broken ONNX InpaintNet that
     fills gaps at wrong-but-in-range coordinates can be structurally
     invisible to both of them: p95 discards the worst 5% of frames before
     the threshold check even runs, and a fixed handful of divergent
     frames sits near the 0.99 agreement boundary at one video length and
     clears it at another. The third term exists specifically to catch
     that: it diffs each side's `_run_inpaintnet` input against its output
     (via a bound-method wrap, the same technique as the model shims) to
     get the exact set of frames that side's InpaintNet pass filled, then
     requires the two sets to match exactly and the MAX (not p95 or
     median) delta over their union to stay within `GATE_DELTA_PX_MAX` - a
     max over a small set is the only statistic that cannot look past a
     single wrong-but-in-range inpainted frame. The report records
     `inpainted_frames_torch`, `inpainted_frames_onnx`,
     `inpaint_union_size`, `inpaint_sets_match`, and
     `max_inpaint_delta_px_at_512x288`. Needs no cloud data (`--corpus` is
     optional for this part) and is what the script's exit code is based
     on.

     **Every one of the three terms is satisfiable by an empty sample**,
     and forgetting that is the single root cause of four separate review
     findings on this script. An agreement ratio over zero frames is 1.0;
     a p95 over zero deltas is undefined; set equality over two empty sets
     holds; a max over an empty union is within any bound. So each term
     reports its verdict together with the size of the sample it was
     computed over, in `gate.gate_terms.<term>` as `ok`, `value`,
     `n_measured`, `n_required`, and a plain-English `sample` description,
     and the same denominators are printed next to each verdict on stdout.
     A term whose `n_measured` is below its `n_required` measured nothing,
     whatever its `ok` says.

     **That rule is enforced, not just documented.** The gate passes only
     when the run was measurable AND every term is both `ok` and standing
     at or above its own floor - one loop over the table, rather than
     hand-written conjuncts that a fourth term could be added beside. And a
     term cannot be registered without declaring a floor: `n_required`
     missing, zero, negative or non-integer raises rather than passing
     (`_register_gate_term`), so a floorless term is a startup crash
     (exit 4), never a silent PASS. The single legitimate zero is
     `--tracknet-only`'s InpaintNet-scoped term, where both sides run the
     real PyTorch InpaintNet and there is no conversion for the term to
     have measured; that waiver has to be argued in writing via
     `floor_waived_because`, which is recorded in the JSON, rather than
     passed as a bare `0`. The term still runs there and can still fail,
     catching a TrackNet drift that changes which frames get inpainted.
     Terms found under their floor are listed in the report's top-level
     `underfloor_gate_terms`.
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
   CPU. Use a short clip - but a short clip is not sufficient on its own.
   `_run_inpaintnet` skips itself entirely on a trajectory that is
   nearly-fully visible or nearly-fully missing (`inference.py:363`), which
   a short, easy, fully-tracked clip is likely to be. **Pick (or trim to) a
   clip that contains a real gap in shuttle detection** - a moment the
   shuttle is lost behind a player, a fast smash, a frame it leaves the
   frame - or the InpaintNet half of the default, deployed configuration is
   never actually exercised. "Exercised" means a gap InpaintNet actually
   bridges, not merely one that makes `_run_inpaintnet` call the model:
   production applies its own bounds and continuity checks to every
   candidate (`inference.py:424-446`) and rejects them on the torch side
   too, so a gap the model cannot plausibly fill leaves the same
   no-evidence result as no gap at all. See exit code 3 below for how the
   script reports when that happened.

   **(a) is the gate that decides whether the rest of the on-device plan
   proceeds.** Visibility agreement at or above 99%, a whole-video p95
   delta at or below 2px, AND matching inpainted-frame sets with a max
   delta at or below 2px on the InpaintNet-scoped term over a non-empty
   inpainted-frame union - on every corpus video - means proceed; falling
   short on any of the three, or measuring any of them over an
   under-floor sample, means stop and
   report rather than continuing to later tasks; the design's assumption
   that the on-device conversion preserves the PyTorch model's behavior
   would not hold, and the remaining plan should not be executed until
   that is resolved. This has not been run yet in this environment; no
   result is recorded.

   **Exit codes** (also documented in the script's module docstring),
   checked worst-first so a real failure is never masked by a weaker
   signal further down the list:

   | Code | Meaning |
   | --- | --- |
   | 0 | GATE PASS - all three gate terms measured clean **over a non-empty sample each**. Under the deployed configuration (both models swapped) that last part is the substantive claim: at least one frame was actually filled by one side's InpaintNet pass, torch and ONNX filled exactly the same frames, and their coordinates matched. A nonzero InpaintNet call count is **not** what exit 0 asserts and never was sufficient to assert it - production rejects inpaint candidates on the torch side too (`inference.py:424`, `:425`, `:429-446`), so InpaintNet can run on both sides and change nothing on either, which is evidence of nothing. That case is exit 3 |
   | 1 | GATE FAIL - visibility agreement, whole-video p95 delta, or the InpaintNet-scoped term missed threshold, on the gate's own measured terms. Takes priority over 3: a catastrophic ONNX collapse can itself be severe enough to also leave the InpaintNet-scoped term with an empty sample, and that must be reported as FAIL, not steered toward "pick a better clip" |
   | 2 | UNMEASURABLE - video would not open, decoded no frames, the shuttle was visible too rarely in the torch reference to measure anything (`GATE_MIN_VISIBLE_FRACTION = 0.05`, i.e. below 5%), or argparse itself rejected the command line (its own usage-error exit code, unrelated to and unchanged by this script). A torch-internal `RuntimeError` from the reference `track_video` also lands here rather than at 4: production raises `RuntimeError` only for an unusable input (`inference.py:134`, `:146`, `:244`), so the script reads it that way. Both codes are non-pass and both print the message, so the mislabel cannot become a false GO |
   | 3 | A GATE TERM MEASURED NOTHING - every term that had a sample passed, but at least one stood on a sample below its own denominator floor, so its verdict rests on nothing; the terms are listed in `underfloor_gate_terms`. Today `inpaint_scoped` is the only term that can reach this branch, so in practice this still means INPAINTNET UNEXERCISED: the gate would otherwise have been a clean PASS on all three terms, but both models were swapped (the default) and **neither side's InpaintNet pass filled a single frame** on this video, so the InpaintNet-scoped term was computed over an empty sample and the run holds no evidence at all about InpaintNet's ONNX output despite the label. Covers both ways that happens: the model never being called (`inference.py:363`, `:388-390`) and the model being called on both sides with every candidate rejected by production's own bounds and continuity checks (`:424-446`) - `inpaintnet_shim_calls` in the JSON distinguishes them. `gate_pass` in the JSON is forced `false`; re-run against a clip with a real detection gap that InpaintNet can actually bridge |
   | 4 | CRASH - setup (importing `TrackNetInference`, building either tracker, opening either ONNX Runtime session), **either** side of `track_video`, or the gate's own term construction (a term declared without a denominator floor, see `_register_gate_term`), raised something other than the `RuntimeError` production uses to report an unusable input. Distinct from FAIL: no divergence was measured, the conversion could not even be run. Written to a separate `coverage-<name>-<config>-crashed.json`, never to the plain `coverage-<name>-<config>.json` path, so a crashed re-run cannot overwrite a prior successful run's report. The report's `crash_phase` says which of `setup`, `torch track_video`, `onnx track_video`, or `gate term construction` died |

   The report's `gate` object also records `tracknet_shim_calls` and
   `inpaintnet_shim_calls` (`null` under `--tracknet-only`). **Both are
   diagnostic only**: no gate term and no exit code keys on either. A call
   count answers "did the model's forward pass run", which is strictly
   weaker than "did this model's output reach the trajectory", and keying
   the InpaintNet check on it was the round-4 review finding. Neither has a
   reachable false-PASS path - a zero-call TrackNet shim collapses ONNX-side
   visibility and fails the agreement term, and a zero-call InpaintNet shim
   leaves the ONNX inpainted set empty, which is either a set mismatch
   (exit 1) or an empty union (exit 3). `inpaintnet_shim_calls` stays useful
   when reading a report after the fact: `0` with an empty union means "no
   gaps to fill, pick a better clip", while `> 0` with an empty union means
   "gaps existed and every candidate was rejected", a different clip
   problem. The `gate` object also records `inpainted_frames_torch`,
   `inpainted_frames_onnx`, `inpaint_union_size`, `inpaint_sets_match`,
   `max_inpaint_delta_px_at_512x288`, and the per-term `gate_terms` block
   described above. `underfloor_gate_terms` and `inpaintnet_unexercised`
   (the latter derived from the former, so they cannot disagree) are
   recorded at the top level
   regardless of which exit code actually decided the run (1 or 3), so a
   reader does not have to infer any of this from anything but those
   fields.

   A video that will not open at all (bad path, unreadable file) is caught
   by `track_video`'s own `cap.isOpened()` check (`inference.py:145-146`)
   rather than by this script reopening it - this script no longer decodes
   video itself, so that loud-failure responsibility now belongs to
   production code, not a reimplementation of it. It surfaces the same way:
   printed to stderr, exit 2.

   Section (b) is wrapped so that a typo'd `--corpus`, a malformed
   `results.json`, or any other failure in it is printed to stderr and
   skipped, never reaching the exit code or overwriting the already-written
   gate-only report - (b) is advisory and must never be able to look like a
   gate failure.

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
