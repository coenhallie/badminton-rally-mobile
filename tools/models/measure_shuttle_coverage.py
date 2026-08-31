#!/usr/bin/env python3
"""Two independent measurements against the on-device TrackNet/InpaintNet conversion.

(a) THE GATE - conversion fidelity. Runs the SAME source video through
    production's own pipeline - TrackNetInference.track_video in
    ../badminton-tracker/backend/tracknet/inference.py - twice: once with the
    real PyTorch TrackNet (and, unless --tracknet-only is given, the real
    PyTorch InpaintNet), and once with those same attributes replaced by a
    thin ONNX Runtime shim (_OnnxModelShim below). track_video calls its
    models as plain attributes (`self.tracknet(batch_tensor)` at
    inference.py:298, `self.inpaintnet(inp_tensor)` at inference.py:416), so
    swapping the attribute is all that is needed; nothing here reimplements
    background computation, blob detection, gap-filling, or coordinate
    scaling - it is the same production code on both sides. Any divergence
    can only come from the ONNX conversion (or, with --tracknet-only, from
    TrackNet's conversion alone). This needs no cloud data at all, and it
    alone drives GATE PASS/FAIL and the exit code.

    The gate has THREE terms, not two: whole-video visibility agreement,
    whole-video p95 position delta, and a third term scoped to exactly the
    frames either side's InpaintNet pass filled (see _InpaintCapture and the
    "InpaintNet gate term" comment in main()). The first two are both
    frame-denominated aggregates over the entire video, and InpaintNet by
    construction only ever touches a small minority of frames (the gaps),
    so a broken ONNX InpaintNet that fills gaps at wrong-but-in-range
    coordinates can be structurally invisible to both of them - p95
    discards the largest 5% of frames outright, and a fixed handful of
    divergent frames sits near the 0.99 agreement boundary at one video
    length and clears it at another. The third term exists specifically
    because the first two cannot be trusted to catch an InpaintNet-only
    defect.

    HONEST CAVEAT ON WHAT THIS DOES NOT PROVE: this script forces
    device="cpu" so it can run anywhere, which means the PyTorch reference
    side runs fp32. TrackNetInference.use_half is only True when
    torch.cuda.is_available(), and production always runs on CUDA, so
    production's real reference is fp16-on-GPU, compared against a fp16
    ONNX model. This gate instead compares fp32-on-CPU torch against that
    same fp16 ONNX model, which is a STRICTER comparison than the
    fp16-to-fp16 one it stands in for (fp32 has less rounding error to
    begin with, so any divergence measured here is not smaller than what
    fp16-to-fp16 would show, and is plausibly larger). Stricter is the safe
    direction for a gate, but it is not the identical comparison production
    runs, and a reader should not read these numbers as what happens on the
    GPU.

(b) INFORMATIONAL - reimplementation fidelity, NOT a gate. Compares the
    local PyTorch track_video output (which, as of this rewrite, already
    runs the full production pipeline: median background, blob detection,
    InpaintNet gap-fill, scaling to the video's native resolution) against
    the cloud's results.json["shuttle_positions"]. That field is not raw
    model output either: it is the result of _build_shuttle_positions_dict
    (backend/modal_supabase_processor.py:4073, assigned into the payload at
    :4193), which applies court-ROI polygon rejection, static-cluster
    suppression, and minimum-movement suppression on top of
    TrackNetInference.track_video's own output. The expected-divergence
    caveat here is now much weaker than it used to be: both sides finally
    run the same background/blob-detection/inpainting/scaling, so what
    remains is the cloud's three extra suppression passes plus the ONNX
    conversion and any decode differences - not a whole reimplemented
    pipeline. A divergence in this section still must never be used as a
    pass/fail signal, and no coverage ratio is computed for it. Only
    suppression-invariant statistics are reported: how many frames both
    consider the shuttle visible, and the pixel delta on those frames.

The shot-gap detector rejects a candidate rally outright when fewer than 25%
of frames in its window are visible, so a coverage collapse costs whole
rallies rather than precision - which is why (a) exists as a hard gate at all.

PERFORMANCE NOTE: this runs track_video (full median-background sampling
plus per-frame inference) twice, once per side, entirely on CPU. Use a short
clip - but "short" is not sufficient on its own. _run_inpaintnet skips
itself entirely when a trajectory is nearly-fully visible or nearly-fully
missing (inference.py:363), which a short, easy, fully-tracked clip is
likely to be. Pick (or trim to) a clip that actually contains a real gap in
shuttle detection - a lost-behind-the-body moment, a fast smash, a frame the
shuttle leaves frame - so the InpaintNet half of the deployed configuration
is actually exercised. Note that "exercised" means a gap that InpaintNet
actually bridges, not merely one that makes _run_inpaintnet call the model:
production applies its own bounds and continuity checks to every candidate
(inference.py:424-446) and rejects them on the torch side too, so a gap the
model cannot plausibly fill leaves the same no-evidence result as no gap at
all. See EXIT CODES below for how this script tells you when that happened.

EXIT CODES, checked worst-first so a real failure can never be masked by a
weaker signal from later in the list:
  0 - GATE PASS. Measured clean on all three gate terms (visibility
      agreement, whole-video p95 delta, and the InpaintNet-scoped term -
      see the "THREE terms, not two" note above), each computed over a
      NON-EMPTY sample. Under the deployed configuration (both models
      swapped) that last part is the substantive claim: at least one frame
      was actually filled by one side's InpaintNet pass, torch and ONNX
      filled exactly the same frames, and their coordinates matched. A
      nonzero InpaintNet call count is NOT what exit 0 asserts and never
      was sufficient to assert it: production rejects inpaint candidates
      on the torch side too (the pred > 0.01 bound at inference.py:424,
      the 0 < pred < 1 bound at :425, and the forward/backward continuity
      checks at :429-446), so InpaintNet can run on both sides and change
      nothing on either, which is evidence of nothing at all. That case is
      exit 3, not exit 0.
  1 - GATE FAIL: visibility agreement, whole-video p95 delta, or the
      InpaintNet-scoped term missed threshold, on the gate's own measured
      terms. Takes priority over 3 below: a catastrophic ONNX collapse can
      itself be severe enough to also trip production's near-missing skip
      (inference.py:363) and leave the InpaintNet-scoped term with an empty
      sample, and that run must be reported as FAIL, not steered toward
      "pick a better clip" - inpaintnet_unexercised is still recorded in
      the JSON either way.
  2 - UNMEASURABLE: the video would not open, decoded no frames, the
      shuttle was visible too rarely in the torch reference to measure
      anything (GATE_MIN_VISIBLE_FRACTION), or argparse itself rejected the
      command line (its own usage-error exit code, unchanged by this
      script). This is also where a torch-internal RuntimeError from the
      reference track_video lands: production raises RuntimeError only for
      an unusable input (inference.py:134, :146, :244), so RuntimeError is
      read as "unusable input" here, and anything torch itself raises as a
      RuntimeError is reported as UNMEASURABLE with its message on stderr
      rather than as a crash report. Both are non-pass and both are loud,
      so the mislabel cannot turn into a false GO.
  3 - INPAINTNET UNEXERCISED: the gate would otherwise have been a clean
      PASS on all three terms, but both models were swapped (the default,
      deployed configuration) and NEITHER SIDE's InpaintNet pass filled a
      single frame on this video. The InpaintNet-scoped term was therefore
      computed over an EMPTY sample, and this run holds no evidence
      whatsoever about InpaintNet's ONNX output despite the "swapped"
      label - see the PERFORMANCE NOTE above. This covers both ways that
      happens: the model never being called at all (the near-fully-visible
      / near-fully-missing skip at inference.py:363, or the
      all-visible/all-invisible chunk skip at :388-390) and the model being
      called on both sides with every candidate it emitted rejected by
      production's own bounds and continuity checks (:424-446). Those look
      identical to a reader of the trajectory, and neither one measures
      anything, which is why this exit code keys on the empty sample rather
      than on the call count. gate_pass in the JSON report is forced false
      for this case, so neither this exit code nor the JSON can be mistaken
      for a validated deployed-config pass.
  4 - CRASH: setup (importing TrackNetInference, building either tracker,
      opening either ONNX Runtime session - a missing/truncated .onnx file,
      missing weights, or a bad --tracker-repo), or either side of
      track_video itself, raised something other than the RuntimeError
      production uses to report an unusable input. Distinct from 1 (FAIL)
      because no divergence was actually measured - the conversion could
      not even be run, which is not the same claim as "ran and diverged".
      A report is written recording the crash and which phase raised,
      under its own "-crashed" filename so it can never overwrite a prior
      successful run's report for the same video and configuration.
"""
import argparse, json, subprocess, sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import torch

TRACKNET_ONNX_DEFAULT = "tools/models/onnx/tracknet.fp16.onnx"
INPAINTNET_ONNX_DEFAULT = "tools/models/onnx/inpaintnet.fp16.onnx"
TRACKNET_WEIGHTS_DEFAULT = "tools/models/weights/tracknet.pt"
INPAINTNET_WEIGHTS_DEFAULT = "tools/models/weights/inpaintnet.pt"

# Same reasoning as check_tracknet_parity.py's 1px bound: a peak shift this
# small is inside the noise the static-cluster filter already tolerates. The
# delta threshold here is a little looser than that script's (2px instead of
# 1px) because this runs across a whole real video instead of one synthetic
# frame, so a handful of near-tie frames can nudge the median or p95 without
# meaning anything; the visibility-agreement bound is what actually catches
# an fp16 rounding error flipping whether the shuttle is seen at all.
VISIBILITY_AGREEMENT_MIN = 0.99
GATE_DELTA_PX_MAX = 2.0
# The delta half of the gate is only meaningful if the shuttle was actually
# seen often enough to measure a position drift on. Without this, a video
# with the wrong path, a black clip, or one where the shuttle is essentially
# never above the visibility threshold would score visibility_agreement ==
# 1.0 (both sides silently agree "not visible" every frame) - a falsely
# reassuring GATE PASS with nothing actually measured. This is keyed on the
# torch reference's own visible fraction alone, deliberately not on whether
# both sides agreed on any visible frame: if torch sees the shuttle often
# but ONNX never agrees, that is a real, measurable divergence
# (visibility_agreement collapses) and must reach GATE FAIL, not be
# reported as UNMEASURABLE.
GATE_MIN_VISIBLE_FRACTION = 0.05


class _OnnxModelShim:
    """Drop-in replacement for a torch model attribute on TrackNetInference.

    track_video's only contact with its models is two plain attribute
    calls - `self.tracknet(batch_tensor)` and `self.inpaintnet(inp_tensor)`.
    Assigning an instance of this class over one of those attributes routes
    exactly that call through ONNX Runtime while every other line of
    track_video (background, blob detection, gap-filling, scaling) keeps
    running unmodified.
    """

    def __init__(self, onnx_path):
        self.sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name
        # DIAGNOSTIC ONLY - no gate term and no exit code keys on this, for
        # either shim. A call count answers "did the model's forward pass
        # run", which is a strictly weaker question than "did this model's
        # output reach the trajectory": _run_inpaintnet can call the model
        # on every chunk and still have production reject every candidate
        # it emitted (inference.py:424-446), and _run_inpaintnet can also
        # skip the model outright (:363, :388-390). Both leave zero
        # evidence about the conversion, and only the second one shows up
        # in this counter, which is why main() keys exit 3 on the size of
        # the inpainted-frame union instead. Kept because it is genuinely
        # useful when reading a report after the fact: calls == 0 with an
        # empty union says "no gaps to fill, pick a better clip", while
        # calls > 0 with an empty union says "gaps existed and every
        # candidate was rejected", which is a different clip problem.
        self.calls = 0

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.calls += 1
        arr = x.detach().cpu().numpy().astype(np.float32)
        # track_video batches TrackNet calls at batch_size (default 16,
        # --batch-size here), but both ONNX exports use a static batch
        # dimension of 1 (export_tracknet.py's dummy input is
        # (1, in_dim, 288, 512); InpaintNet's is (1, 3, length)). Loop over
        # the batch here instead of forcing batch_size=1 on the torch side
        # too, so production's own batching is left untouched on both sides.
        outs = [self.sess.run(None, {self.input_name: arr[i:i + 1]})[0] for i in range(arr.shape[0])]
        out = np.concatenate(outs, axis=0)
        return torch.from_numpy(out).to(dtype=x.dtype, device=x.device)


class _InpaintCapture:
    """Wraps a TrackNetInference instance's bound _run_inpaintnet method to
    record exactly which frames it filled.

    _OnnxModelShim.calls proves InpaintNet's model forward pass ran - not
    that any of its output reached the final trajectory. Production's own
    continuity checks inside _run_inpaintnet (inference.py:424-446, the
    0 < pred < 1 bounds and the forward/backward gap-distance checks) can
    reject every candidate InpaintNet emits, in which case the model ran
    (calls > 0) but changed nothing. That is the round-1 bug one level
    too shallow: a broken ONNX InpaintNet whose garbage gets rejected looks
    identical, by call count alone, to a healthy one that simply had no
    gaps to fill. Diffing this wrapper's input coords against its return
    value is the only way to know which frames a side's InpaintNet pass
    actually changed, independent of whether the model was called.

    The SIZE of the set this records is also the InpaintNet-scoped gate
    term's denominator, and therefore its floor: see the DENOMINATOR
    FLOORS block in main().

    WHY `after_visible - before_visible` IS THE INPAINTED SET, checked
    against the production source: _run_inpaintnet starts from
    `inpainted_vis = vis.copy()` (inference.py:373), only ever writes
    `inpainted_vis[frame_idx] = 1.0` (:451) and never clears an entry, and
    only writes inpainted_xs/inpainted_ys under `vis[frame_idx] == 0`
    (:424), so it never rewrites an already-visible frame's coordinates.
    Its two early returns (`self.inpaintnet is None` at :346, and the
    near-fully-visible / near-fully-missing skip at :363) both return the
    input dict unchanged. The output is therefore a superset of the input's
    visible frames, and the difference is exactly the set of gaps filled.

    track_video calls `self._run_inpaintnet(raw_coords, total_frames,
    log_callback=log)` at inference.py:173 as a plain bound-method
    attribute access, exactly like the model calls _OnnxModelShim
    replaces, so the same instance-attribute-shadowing technique applies
    here without editing the sibling repo.
    """

    def __init__(self, tracker):
        self._original = tracker._run_inpaintnet
        self.inpainted_frames = None
        tracker._run_inpaintnet = self._wrapped

    def _wrapped(self, coords, total_frames, log_callback=None):
        before_visible = {f for f, c in coords.items() if c.get("visible")}
        result = self._original(coords, total_frames, log_callback=log_callback)
        after_visible = {f for f, c in result.items() if c.get("visible")}
        self.inpainted_frames = after_visible - before_visible
        return result


def _build_tracker(tracknet_weights, inpaintnet_weights):
    from tracknet.inference import TrackNetInference

    tracker = TrackNetInference(device="cpu")
    tracker.load_weights(str(tracknet_weights), str(inpaintnet_weights))
    return tracker


def _video_dims(video_path, fallback_w, fallback_h):
    """Read only the video's frame dimensions; no frame decoding.

    Needed to convert the position deltas track_video returns (original
    video pixel space, per inference.py's w_scale/h_scale) back into the
    512x288 model space that GATE_DELTA_PX_MAX and check_tracknet_parity.py's
    1px bound are both calibrated in, so "2px at 512x288" means the same
    thing regardless of the source video's resolution. track_video already
    proved this path opens (it raises RuntimeError otherwise, caught in
    main()), so this second, metadata-only open is not reintroducing that
    check - if it were ever to fail here despite that, dims fall back to the
    model's own size (scale 1.0) rather than crashing on a division by zero.
    """
    cap = cv2.VideoCapture(str(video_path))
    try:
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)) or fallback_w
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) or fallback_h
    finally:
        cap.release()
    return w, h


def _tracker_repo_commit(tracker_repo):
    """Best-effort git commit hash of the (read-only) tracker repo checkout,
    for provenance in the report JSON - so a report can be traced back to
    exactly which version of the production pipeline it ran. Returns None
    rather than raising if this cannot be determined (not a git checkout,
    git not on PATH, or any other lookup failure); provenance is a nicety,
    not something worth failing a multi-minute run over.
    """
    try:
        result = subprocess.run(
            ["git", "-C", str(tracker_repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception:
        pass
    return None


def _percentile_sorted(sorted_values: list, p: float):
    """Nearest-rank percentile over an already-sorted list, or None if empty."""
    if not sorted_values:
        return None
    idx = min(int(len(sorted_values) * p), len(sorted_values) - 1)
    return sorted_values[idx]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="path to the source mp4")
    ap.add_argument("--tracker-repo", required=True,
                     help="path to the badminton-tracker checkout (read-only); "
                          "needed to import TrackNetInference for the gate")
    ap.add_argument("--onnx", default=TRACKNET_ONNX_DEFAULT, help="path to the exported TrackNet fp16 ONNX model")
    ap.add_argument("--inpaintnet-onnx", default=INPAINTNET_ONNX_DEFAULT,
                     help="path to the exported InpaintNet fp16 ONNX model")
    ap.add_argument("--tracknet-weights", default=TRACKNET_WEIGHTS_DEFAULT)
    ap.add_argument("--inpaintnet-weights", default=INPAINTNET_WEIGHTS_DEFAULT)
    ap.add_argument("--tracknet-only", action="store_true",
                     help="swap only TrackNet's ONNX model, leaving the real PyTorch "
                          "InpaintNet on both sides. Isolates TrackNet's conversion when "
                          "the combined gate fails. Default swaps BOTH TrackNet and "
                          "InpaintNet ONNX models, which is the deployed on-device "
                          "configuration and is what this gate should normally run as.")
    ap.add_argument("--batch-size", type=int, default=16,
                     help="passed through to track_video's batch_size")
    ap.add_argument("--max-bg-samples", type=int, default=300,
                     help="passed through to track_video's max_bg_samples")
    ap.add_argument("--corpus", default=None,
                     help="optional corpus/<video_id> directory; enables the informational "
                          "(b) comparison against the cloud's results.json. Not required for the gate.")
    args = ap.parse_args()

    swapped = "TrackNet only" if args.tracknet_only else "TrackNet + InpaintNet (deployed config)"

    report_dir = Path("tools/models/reports")
    report_dir.mkdir(parents=True, exist_ok=True)
    name = Path(args.corpus).name if args.corpus else Path(args.video).stem
    # Disambiguated by configuration: a --tracknet-only isolation run must
    # not silently overwrite the deployed-config report for the same video,
    # or a later "which run produced this JSON" question has no answer.
    config_suffix = "tracknet-only" if args.tracknet_only else "full"
    report_path = report_dir / f"coverage-{name}-{config_suffix}.json"
    # Deliberately a distinct filename, not the same path with an
    # overwrite-if-crashed guard: a crash report is written unconditionally
    # on either crash path below, and must never be able to clobber a prior
    # successful multi-minute run's report for this same video and
    # configuration just because this run happened to fail.
    crash_report_path = report_dir / f"coverage-{name}-{config_suffix}-crashed.json"

    # Computed before setup, not after: a crash during setup itself (see the
    # try block immediately below) needs this to write a crash report too,
    # so it cannot depend on setup having already succeeded.
    provenance = {
        "video": str(args.video),
        "tracker_repo": str(args.tracker_repo),
        "tracker_repo_commit": _tracker_repo_commit(args.tracker_repo),
        "tracknet_weights": str(args.tracknet_weights),
        "inpaintnet_weights": str(args.inpaintnet_weights),
        "tracknet_onnx": str(args.onnx),
        "inpaintnet_onnx": str(args.inpaintnet_onnx) if not args.tracknet_only else None,
        "swapped": swapped,
        "batch_size": args.batch_size,
        "max_bg_samples": args.max_bg_samples,
    }

    def _write_crash(phase, error):
        print(f"{phase} crashed: {error}", file=sys.stderr)
        print(f"crash report written to {crash_report_path} (not {report_path}, "
              f"which is left untouched if it already holds a prior result)", file=sys.stderr)
        crash_report_path.write_text(json.dumps({
            "provenance": provenance,
            "crashed": True,
            "crash_phase": phase,
            "error": str(error),
        }, indent=2))

    try:
        # Setup, not measurement: importing TrackNetInference, building both
        # trackers (which loads and parses both .pt checkpoints), opening
        # both ONNX Runtime sessions, and wrapping _run_inpaintnet on both
        # instances. A missing or truncated .onnx file, missing weights, or
        # a bad --tracker-repo all raise here, uncaught, before any video is
        # even touched - previously this was outside every try in this
        # function, so it raised as a bare Python exception (exit 1),
        # indistinguishable from a measured GATE FAIL, with no crash report
        # written. Distinct from exit 1 for the same reason the ONNX-side
        # track_video crash below is: nothing was measured, the gate could
        # not even start.
        sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
        from tracknet.inference import WIDTH as MODEL_W, HEIGHT as MODEL_H

        torch_tracker = _build_tracker(args.tracknet_weights, args.inpaintnet_weights)
        onnx_tracker = _build_tracker(args.tracknet_weights, args.inpaintnet_weights)
        # Wraps _run_inpaintnet on BOTH trackers, unconditionally - both
        # always load a real torch InpaintNet in _build_tracker regardless
        # of --tracknet-only, so InpaintNet always runs on both sides, only
        # its model attribute (swapped below) differs.
        torch_inpaint = _InpaintCapture(torch_tracker)
        onnx_inpaint = _InpaintCapture(onnx_tracker)
        tracknet_shim = _OnnxModelShim(args.onnx)
        onnx_tracker.tracknet = tracknet_shim
        inpaintnet_shim = None
        if not args.tracknet_only:
            inpaintnet_shim = _OnnxModelShim(args.inpaintnet_onnx)
            onnx_tracker.inpaintnet = inpaintnet_shim
    except Exception as e:
        _write_crash("setup", e)
        return 4

    try:
        torch_positions = torch_tracker.track_video(
            args.video, batch_size=args.batch_size, max_bg_samples=args.max_bg_samples)
    except RuntimeError as e:
        # track_video's own cap.isOpened() check (inference.py:145-146) is
        # what catches a bad path or unreadable file now; this script no
        # longer opens the video itself for decoding, only for the metadata
        # read in _video_dims, so this is production's own loud failure,
        # not a reimplementation of it. RuntimeError is the right net for
        # it: production raises RuntimeError in exactly three places
        # (inference.py:134, :146, :244) and all three mean "the input you
        # gave me is unusable". A torch-internal RuntimeError would be
        # mislabelled UNMEASURABLE here rather than CRASH, which is a safe
        # direction (both are non-pass, both print the message) and is
        # noted in the exit-2 entry of the module docstring.
        print(e, file=sys.stderr)
        return 2
    except Exception as e:
        # Everything that is NOT production's unusable-input RuntimeError.
        # Previously unhandled, so it propagated out of main() as a bare
        # traceback and a Python exit status of 1 - indistinguishable to
        # any caller from a measured GATE FAIL, and with no crash report
        # written. Same defect round 3 fixed for setup and round 1 fixed
        # for the ONNX-side track_video, one phase later; the phase string
        # is what tells a report reader which of the two track_video calls
        # died.
        _write_crash("torch track_video", e)
        return 4

    if not torch_positions:
        print(f"no frames decoded from video: {args.video}", file=sys.stderr)
        return 2

    try:
        onnx_positions = onnx_tracker.track_video(
            args.video, batch_size=args.batch_size, max_bg_samples=args.max_bg_samples)
    except Exception as e:
        # Distinct from GATE FAIL (exit 1): nothing was actually measured
        # here, the ONNX side itself did not run to completion - a bad ONNX
        # file, a shape mismatch, an ONNX Runtime internal error. Treating
        # this as exit 1 would make "the conversion diverged" and "the
        # conversion could not even be evaluated" indistinguishable to a
        # caller checking the exit code, and previously would also have
        # lost the report entirely, since it was only ever written at the
        # very end of a multi-minute run.
        _write_crash("onnx track_video", e)
        return 4

    orig_w, orig_h = _video_dims(args.video, MODEL_W, MODEL_H)
    w_scale = orig_w / MODEL_W
    h_scale = orig_h / MODEL_H

    # --- (a) THE GATE ---------------------------------------------------
    frames_a = sorted(set(torch_positions) & set(onnx_positions))
    agree = sum(1 for f in frames_a if torch_positions[f]["visible"] == onnx_positions[f]["visible"])
    both_a = [f for f in frames_a if torch_positions[f]["visible"] and onnx_positions[f]["visible"]]

    def _delta_model_px(f):
        a, b = torch_positions[f], onnx_positions[f]
        dx = (a["x"] - b["x"]) / w_scale
        dy = (a["y"] - b["y"]) / h_scale
        return (dx * dx + dy * dy) ** 0.5

    deltas_a = sorted(_delta_model_px(f) for f in both_a)
    torch_visible_fraction = sum(1 for f in frames_a if torch_positions[f]["visible"]) / max(len(frames_a), 1)
    # Keyed on the torch reference's own visible fraction alone - NOT on
    # len(both_a) > 0. If torch sees the shuttle often but ONNX agrees on
    # none of those frames, that is a real, measurable divergence: agree
    # can be at most (1 - torch_visible_fraction) in that case, so
    # visibility_agreement necessarily falls below VISIBILITY_AGREEMENT_MIN
    # and the gate_ok check below fails on that term before it would ever
    # reach the (then-None) p95 comparison - see the GATE_MIN_VISIBLE_FRACTION
    # comment above. That is meant to reach GATE FAIL, not UNMEASURABLE.
    measurable = torch_visible_fraction >= GATE_MIN_VISIBLE_FRACTION

    # The whole-video terms above (visibility_agreement, p95) are both
    # frame-denominated aggregates over EVERY frame, but InpaintNet by
    # construction only ever touches gap frames - a small minority. p95 at
    # :95 of visible frames simply discards the largest 5% before the
    # threshold check, so a broken InpaintNet filling gaps at
    # wrong-but-in-range coordinates cannot move p95 at all, at any video
    # length. And a divergent handful of frames (say 30) sits exactly on
    # the 0.99 visibility_agreement boundary in a 3000-frame clip but
    # passes clean in a 4000-frame one - agreement alone is ratio-dependent
    # and usually loses too. Neither whole-video term can reliably catch an
    # InpaintNet-only defect. This term is the fix: it looks only at the
    # frames either side's InpaintNet pass actually filled (see
    # _InpaintCapture), where set equality plus a MAX (not p95 or median)
    # delta is the only statistic that cannot look past a single
    # wrong-but-in-range inpainted frame the way an aggregate over the
    # whole video can.
    torch_inpainted = sorted(torch_inpaint.inpainted_frames or set())
    onnx_inpainted = sorted(onnx_inpaint.inpainted_frames or set())
    inpaint_union = sorted(set(torch_inpainted) | set(onnx_inpainted))
    inpaint_sets_match = set(torch_inpainted) == set(onnx_inpainted)
    # A frame in inpainted_frames is, by _InpaintCapture's own construction,
    # always marked visible in _run_inpaintnet's return value, and track_video
    # only rescales x/y after that point without touching visible - so every
    # frame in inpaint_union should already be visible on both sides
    # whenever the sets match. The `if ... visible` filter below is
    # belt-and-braces against that invariant, not load-bearing for it.
    inpaint_deltas = [_delta_model_px(f) for f in inpaint_union
                       if torch_positions[f]["visible"] and onnx_positions[f]["visible"]]
    max_inpaint_delta_px = max(inpaint_deltas) if inpaint_deltas else None
    if not inpaint_union:
        # EMPTY SAMPLE. Nothing was inpainted on either side, so there is
        # nothing here to measure a defect on. `True` here is NOT a claim
        # that torch and ONNX agreed; it is the vacuous truth of "the empty
        # set equals the empty set, and a max over an empty union is
        # trivially within any bound". Absence of evidence, not evidence of
        # agreement. Nothing in this branch may be read as InpaintNet's
        # conversion having been validated.
        #
        # This term therefore cannot refuse a pass on its own, and must not
        # try to: under --tracknet-only an empty union is a perfectly fine
        # exit 0 (both sides run the real torch InpaintNet, so the term is
        # not applicable). The refusal for the deployed configuration lives
        # in inpaintnet_unexercised below, which is exactly this branch's
        # denominator floor - see the DENOMINATOR FLOORS block there.
        inpaint_gate_ok = True
    elif not inpaint_sets_match:
        # One side filled frames the other did not - for example ONNX's
        # garbage rejected by production's own 0 < pred < 1 and continuity
        # checks (inference.py:424-446) while torch's real fill was
        # accepted, or vice versa.
        inpaint_gate_ok = False
    elif max_inpaint_delta_px is None:
        # Unreachable if the invariant above holds; treated as a failure,
        # not a silent pass, if it is ever violated.
        inpaint_gate_ok = False
    else:
        inpaint_gate_ok = max_inpaint_delta_px <= GATE_DELTA_PX_MAX

    visibility_agreement = agree / max(len(frames_a), 1)
    p95_delta_px = _percentile_sorted(deltas_a, 0.95)

    gate = {
        "frames": len(frames_a),
        "visibility_agreement": visibility_agreement,
        "both_visible": len(both_a),
        "torch_visible_fraction": torch_visible_fraction,
        "median_delta_px_at_512x288": _percentile_sorted(deltas_a, 0.5),
        "p95_delta_px_at_512x288": p95_delta_px,
        # DIAGNOSTIC ONLY, both of them. Neither shim's call count is a gate
        # term or an input to any exit code (see _OnnxModelShim.__init__ for
        # why a call count is the wrong quantity to gate on, and the
        # DENOMINATOR FLOORS block below for what replaced it). A TrackNet
        # shim that is never called produces all-invisible output on the
        # ONNX side, which drags visibility_agreement toward 0 whenever
        # torch sees anything at all and reaches exit 1 (or exit 2 if torch
        # itself saw nothing); an InpaintNet shim that is never called
        # leaves the inpainted-frame union empty on the ONNX side, which
        # reaches exit 1 (sets mismatch) or exit 3 (union empty on both
        # sides). Neither has a reachable path to a false GATE PASS.
        "tracknet_shim_calls": tracknet_shim.calls,
        "inpaintnet_shim_calls": inpaintnet_shim.calls if inpaintnet_shim is not None else None,
        "inpainted_frames_torch": torch_inpainted,
        "inpainted_frames_onnx": onnx_inpainted,
        "inpaint_union_size": len(inpaint_union),
        "inpaint_sets_match": inpaint_sets_match,
        "max_inpaint_delta_px_at_512x288": max_inpaint_delta_px,
    }

    # --- DENOMINATOR FLOORS ---------------------------------------------
    # THE PRINCIPLE, stated once because forgetting it is the single root
    # cause of four separate review findings on this file: EVERY gate term
    # here is satisfiable by an empty or diluted sample unless it carries a
    # floor on its own denominator. An agreement ratio over zero frames is
    # 1.0; a p95 over zero deltas is undefined; set equality over two empty
    # sets holds; a max over an empty union is within any bound. So each
    # term below reports its verdict together with the size of the sample it
    # was computed over (n_measured) and the smallest sample that verdict is
    # allowed to rest on (n_required), and no term may be believed while its
    # n_measured is under its n_required. Add a fourth term and it needs its
    # own floor on day one, not after the review that finds it.
    #
    # Where each floor is actually enforced today, since these are checked
    # in three different places rather than one loop:
    #   visibility_agreement - `if not torch_positions: return 2` above
    #     rules out a zero-frame sample, and GATE_MIN_VISIBLE_FRACTION is a
    #     stronger, content-based floor on top of it (via `measurable`).
    #   p95_delta_px        - the `p95_delta_px is not None` guard below,
    #     which is load-bearing, not belt-and-braces: p95_ok is hoisted out
    #     of the `and` chain for reporting, so it no longer benefits from
    #     the short-circuit that GATE_MIN_VISIBLE_FRACTION used to provide.
    #   inpaint_scoped      - inpaintnet_unexercised below, which is this
    #     term's floor and nothing else. n_required is 1 under the deployed
    #     configuration and 0 under --tracknet-only, where both sides run
    #     the real torch InpaintNet and the term is genuinely inapplicable.
    agreement_ok = visibility_agreement >= VISIBILITY_AGREEMENT_MIN
    p95_ok = p95_delta_px is not None and p95_delta_px <= GATE_DELTA_PX_MAX

    gate["gate_terms"] = {
        "visibility_agreement": {
            "ok": agreement_ok,
            "value": visibility_agreement,
            "threshold_min": VISIBILITY_AGREEMENT_MIN,
            "n_measured": len(frames_a),
            "n_required": 1,
            "sample": "frames present in both the torch and the ONNX run",
        },
        "p95_delta_px_at_512x288": {
            "ok": p95_ok,
            "value": p95_delta_px,
            "threshold_max": GATE_DELTA_PX_MAX,
            "n_measured": len(deltas_a),
            "n_required": 1,
            "sample": "frames both sides call the shuttle visible",
        },
        "inpaint_scoped": {
            "ok": inpaint_gate_ok,
            "value": max_inpaint_delta_px,
            "threshold_max": GATE_DELTA_PX_MAX,
            "sets_match": inpaint_sets_match,
            "n_measured": len(inpaint_union),
            "n_required": 0 if inpaintnet_shim is None else 1,
            "sample": "frames either side's InpaintNet pass actually filled",
        },
    }

    # gate_ok_raw is what the gate measured, on its own terms, ignoring the
    # denominator floor that lives in inpaintnet_unexercised below. Built
    # from the same three locals the gate_terms block reports, so the
    # printed/recorded verdicts cannot drift from the decided one.
    gate_ok_raw = measurable and agreement_ok and p95_ok and inpaint_gate_ok

    # The inpaint_scoped term's denominator floor, and the only reason exit
    # 3 exists. An empty union means neither side filled a single frame, so
    # inpaint_gate_ok above is vacuously True (see its `not inpaint_union`
    # branch) and this run holds no evidence at all about InpaintNet's ONNX
    # output - it must not be allowed to print PASS under the deployed-config
    # label.
    #
    # Keyed on the union being empty, NOT on inpaintnet_shim.calls == 0,
    # which was a proxy for a different quantity and is why this bug
    # survived three rounds. calls == 0 is only one of the two ways the
    # sample comes out empty: _run_inpaintnet can skip the model outright
    # (inference.py:363, :388-390), and it can equally call the model on
    # every chunk and then reject every candidate it emitted through
    # production's own pred > 0.01 (:424), 0 < pred < 1 (:425) and
    # forward/backward continuity (:429-446) checks - which happens on the
    # torch side too, with no model unhealthy anywhere. The union subsumes
    # both: filling a frame on the ONNX side is impossible without the shim
    # having been called, so calls == 0 always implies an empty ONNX set,
    # hence either an empty union (exit 3) or a set mismatch (exit 1); there
    # is no path from calls == 0 to exit 0. Inert under --tracknet-only,
    # where inpaintnet_shim is None.
    inpaintnet_unexercised = inpaintnet_shim is not None and not inpaint_union

    # Priority, worst-first: a gate that already failed on its own measured
    # terms must be reported as FAIL, even when the InpaintNet-scoped term
    # also came out with an empty sample on the same run - a catastrophic
    # ONNX collapse that also trips production's own near-missing skip at
    # inference.py:363 must not be reported as "pick a better clip" when
    # the true story is "the conversion is broken". Note that this ordering
    # is what keeps the two signals independent: an empty union means
    # inpaint_gate_ok is vacuously True, so a run reaching the
    # inpaintnet_unexercised branch has genuinely passed the other two
    # terms. INPAINTNET_UNEXERCISED only applies to a run
    # that would otherwise have been a clean PASS: the one case where "the
    # measurement itself is not trustworthy as deployed-config coverage" is
    # the only thing wrong with it. Both signals are always kept in the
    # JSON regardless of which one decided the exit code.
    if not measurable:
        exit_code = 2
        reported_gate_pass = False
    elif not gate_ok_raw:
        exit_code = 1
        reported_gate_pass = False
    elif inpaintnet_unexercised:
        exit_code = 3
        reported_gate_pass = False
    else:
        exit_code = 0
        reported_gate_pass = True

    print(f"=== (a) GATE: conversion fidelity (PyTorch fp32/CPU vs ONNX fp16; swapped: {swapped}) ===")
    for k, v in gate.items():
        if k == "gate_terms":
            continue  # printed as its own block below, not as a raw dict
        print(f"  {k:30}: {v}")
    # Each term's verdict next to the size of the sample it was computed
    # over, so an operator reading a PASS can see at a glance what each
    # term actually had to look at. A verdict whose n is under its need is
    # a term that measured nothing - see the DENOMINATOR FLOORS block.
    print("  gate terms (verdict, and the sample size behind it):")
    for term, t in gate["gate_terms"].items():
        floor = "" if t["n_measured"] >= t["n_required"] else "  <-- UNDER ITS FLOOR, measures nothing"
        bound = (f"<= {t['threshold_max']}" if "threshold_max" in t
                 else f">= {t['threshold_min']}")
        print(f"    {term:26} ok={str(t['ok']):5} value={t['value']} (need {bound})  "
              f"n={t['n_measured']} (need >= {t['n_required']}) over {t['sample']}{floor}")
    if not measurable:
        print(f"  GATE UNMEASURABLE - the shuttle was visible in the torch reference on "
              f"fewer than {GATE_MIN_VISIBLE_FRACTION:.0%} of frames, so there is nothing "
              f"to measure position drift on. This is not evidence the conversion is "
              f"fine; check the video path and that it actually shows play.")
    elif not gate_ok_raw:
        print("  GATE FAIL")
        if not inpaint_gate_ok:
            print(f"  (InpaintNet gate term failed: inpaint_sets_match={inpaint_sets_match}, "
                  f"max_inpaint_delta_px_at_512x288={max_inpaint_delta_px} - torch inpainted "
                  f"{len(torch_inpainted)} frame(s), onnx inpainted {len(onnx_inpainted)} "
                  f"frame(s). See inpainted_frames_torch/inpainted_frames_onnx in the JSON.)")
        if inpaintnet_unexercised:
            print("  (The InpaintNet-scoped term also had an empty sample on this run - "
                  "neither side filled a single frame - so it measured nothing either. "
                  "The gate already failed on another term, and that is reported first; "
                  "see inpaint_union_size and gate_terms.inpaint_scoped in the JSON.)")
    elif inpaintnet_unexercised:
        print("  GATE INPAINTNET UNEXERCISED (exit 3) - visibility agreement and delta "
              "both passed, but neither side's InpaintNet pass filled a single frame on "
              "this video, so the InpaintNet-scoped term was computed over an empty "
              "sample and this run holds no evidence at all about InpaintNet's ONNX "
              "output despite the \"swapped\" label above. Either _run_inpaintnet never "
              "invoked the model (too few gaps, or too few detections) or every "
              "candidate it produced was rejected by production's own bounds and "
              "continuity checks - inpaintnet_shim_calls in the JSON tells you which. "
              "gate_pass is forced false. Re-run against a video with a real gap in "
              "shuttle detection that InpaintNet can actually bridge.")
    else:
        print("  GATE PASS")

    report = {"provenance": provenance, "gate": gate, "gate_measurable": measurable,
              "gate_pass": reported_gate_pass, "inpaintnet_unexercised": inpaintnet_unexercised,
              "informational": None}
    report_path.write_text(json.dumps(report, indent=2))

    # --- (b) INFORMATIONAL -----------------------------------------------
    # Wrapped end to end: a typo'd --corpus, a malformed results.json, or
    # any other failure in this section must never reach the exit code or
    # overwrite the gate-only report already written above. (b) is
    # advisory only, and a crash in it is not a gate failure.
    if args.corpus:
        try:
            results = json.loads((Path(args.corpus) / "results.json").read_text())
            cloud = {int(k): v for k, v in results.get("shuttle_positions", {}).items()}
            frames_b = sorted(set(torch_positions) & set(cloud))
            both_b = [f for f in frames_b if cloud[f].get("visible") and torch_positions[f]["visible"]]

            def _delta_orig_px(f):
                a, b = torch_positions[f], cloud[f]
                return ((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2) ** 0.5

            deltas_b = sorted(_delta_orig_px(f) for f in both_b)
            informational = {
                "note": ("NOT a gate: local now runs the same production pipeline as the "
                         "cloud (median background, blob detection, InpaintNet gap-fill, "
                         "scaling to native resolution) via TrackNetInference.track_video, "
                         "so this is no longer comparing raw model output to a filtered "
                         "track. What remains is the cloud's court-ROI polygon rejection, "
                         "static-cluster suppression, and minimum-movement suppression "
                         "(_build_shuttle_positions_dict, modal_supabase_processor.py:4073) "
                         "applied on top of its own track_video output, plus the ONNX "
                         "conversion and any decode differences. A divergence here is still "
                         "expected, for a narrower set of reasons than before, and must not "
                         "be used as a pass/fail signal."),
                "frames_compared": len(frames_b),
                "both_visible": len(both_b),
                "median_delta_px": _percentile_sorted(deltas_b, 0.5),
                "p95_delta_px": _percentile_sorted(deltas_b, 0.95),
            }
            print("=== (b) INFORMATIONAL: reimplementation fidelity vs cloud (NOT a gate) ===")
            for k, v in informational.items():
                print(f"  {k:30}: {v}")
            report["informational"] = informational
            report_path.write_text(json.dumps(report, indent=2))
        except Exception as e:
            print("=== (b) INFORMATIONAL: failed, skipped (does not affect the gate) ===",
                  file=sys.stderr)
            print(f"  {e}", file=sys.stderr)
    else:
        print("=== (b) INFORMATIONAL: skipped (no --corpus given) ===")

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
